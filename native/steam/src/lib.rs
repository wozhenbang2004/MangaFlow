//! JNI media-only adapter around the pinned MPL-2.0 tapline core.
//! Credentials stay in memory; platform code owns encrypted persistence.
use jni_sys::{JNIEnv, jboolean, jclass, jint, jlong, jstring};
use serde_json::{Value, json};
use std::collections::{HashMap, VecDeque};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tapline::{
    AppId, BrowseQuery, BrowseSort, Event, FileModes, InstallError, InstallOptions,
    PublishedFileId, Session, Shared, StoredToken, WorkshopContent, WorkshopLayout,
    GuardType, LoginError, NetworkRoute, PendingLogin, PollOutcome,
};
use tokio::runtime::Runtime;
use tokio::sync::{Mutex as AsyncMutex, MutexGuard, mpsc};
use tapline_io::{Fetch, Request};
use tapline_proto::steammessages_publishedfile_steamclient::CPublishedFile_GetUserFiles_Request;
use tapline_rt_tokio::HttpClient;
use tokio::task::{AbortHandle, JoinHandle};
use zeroize::{Zeroize, Zeroizing};

const APP: AppId = AppId(431960);
const MAX_BYTES: u64 = 512 * 1024 * 1024;
const MAX_ENTRIES: usize = 512;
const MAX_JOBS: usize = 8;
const PAGE_SIZE: u32 = 20;
type Outcome = Result<Value, &'static str>;

struct SecretValue(Value);
impl Drop for SecretValue {
    fn drop(&mut self) { wipe(&mut self.0); }
}

fn wipe(value: &mut Value) {
    match value {
        Value::String(text) => text.zeroize(),
        Value::Array(values) => values.iter_mut().for_each(wipe),
        Value::Object(values) => values.values_mut().for_each(wipe),
        _ => {}
    }
}

struct Queued {
    text: String,
    progress: bool,
}
impl Drop for Queued {
    fn drop(&mut self) { self.text.zeroize(); }
}

type GuardInput = (Zeroizing<String>, GuardType);

struct Job {
    guard: Mutex<Option<mpsc::Sender<GuardInput>>>,
    events: Mutex<VecDeque<Queued>>,
    abort: Mutex<Option<AbortHandle>>,
    supervisor: Mutex<Option<JoinHandle<()>>>,
    cancelled: AtomicBool,
    finished: AtomicBool,
}

impl Job {
    fn new() -> Self {
        Self {
            guard: Mutex::new(None),
            events: Mutex::new(VecDeque::new()),
            abort: Mutex::new(None),
            supervisor: Mutex::new(None),
            cancelled: AtomicBool::new(false),
            finished: AtomicBool::new(false),
        }
    }

    fn emit(&self, mut value: Value) {
        if self.finished.load(Ordering::Acquire) {
            wipe(&mut value);
            return;
        }
        let progress = value.get("kind").and_then(Value::as_str) == Some("progress");
        let text = value.to_string();
        wipe(&mut value);
        if let Ok(mut events) = self.events.lock() {
            if progress { events.retain(|event| !event.progress); }
            while events.len() >= 32 { events.pop_front(); }
            events.push_back(Queued { text, progress });
        }
    }

    fn finish(&self, result: Outcome) {
        if let Ok(mut sender) = self.guard.lock() { sender.take(); }
        match result {
            Ok(value) => self.emit(value),
            Err(code) => self.emit(json!({"kind":"error","code":code})),
        }
        self.finished.store(true, Ordering::Release);
    }

    fn cancel(&self) {
        if let Ok(mut sender) = self.guard.lock() { sender.take(); }
        if !self.finished.load(Ordering::Acquire) {
            self.cancelled.store(true, Ordering::Release);
            if let Ok(handle) = self.abort.lock() {
                if let Some(handle) = handle.as_ref() { handle.abort(); }
            }
        }
    }

    fn next(&self) -> Option<String> {
        if let Ok(mut events) = self.events.lock() {
            if let Some(mut event) = events.pop_front() {
                return Some(std::mem::take(&mut event.text));
            }
        }
        self.finished.load(Ordering::Acquire)
            .then(|| String::from("{\"kind\":\"complete\"}"))
    }
}

fn runtime() -> Option<&'static Runtime> {
    static RUNTIME: OnceLock<Option<Runtime>> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .max_blocking_threads(4)
            .enable_all()
            .thread_name("mangaflow-steam")
            .build().ok()
    }).as_ref()
}

fn shared() -> Arc<Shared> {
    static SHARED: OnceLock<Arc<Shared>> = OnceLock::new();
    Arc::clone(SHARED.get_or_init(|| Shared::new(4)))
}

fn routed_shared(request: &mut Value) -> Result<(Arc<Shared>, NetworkRoute), &'static str> {
    let network = request.get_mut("network").map(Value::take).ok_or("network_configuration")?;
    let kind = network.get("kind").and_then(Value::as_str).ok_or("network_configuration")?;
    let route = match kind {
        "SYSTEM" => NetworkRoute::System,
        "DIRECT" => NetworkRoute::Direct,
        "HTTP" | "SOCKS5" => {
            let host = text(&network, "host").ok_or("network_configuration")?;
            let port = network.get("port").and_then(Value::as_u64)
                .filter(|port| (1..=65535).contains(port)).ok_or("network_configuration")? as u16;
            if host.is_empty() || host.len() > 253 || host.chars().any(|c|
                c.is_control() || c.is_whitespace() || matches!(c, '/' | '\\' | '@' | '?' | '#' | '[' | ']')) {
                return Err("network_configuration");
            }
            if kind == "HTTP" { NetworkRoute::Http { host, port } }
            else { NetworkRoute::Socks5 { host, port } }
        }
        _ => return Err("network_configuration"),
    };
    Ok((shared().with_route(route.clone()), route))
}

fn jobs() -> &'static Mutex<HashMap<i64, Arc<Job>>> {
    static JOBS: OnceLock<Mutex<HashMap<i64, Arc<Job>>>> = OnceLock::new();
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn lookup(id: i64) -> Option<Arc<Job>> {
    jobs().lock().ok()?.get(&id).cloned()
}

fn text(value: &Value, name: &str) -> Option<String> {
    value.get(name).and_then(Value::as_str).map(str::to_owned)
}

fn take_text(value: &mut Value, name: &str) -> Option<String> {
    match value.get_mut(name)?.take() {
        Value::String(text) => Some(text),
        _ => None,
    }
}

fn credentials(request: &mut Value) -> Result<StoredToken, &'static str> {
    let account = take_text(request, "account").ok_or("auth_required")?;
    let refresh_token = take_text(request, "refreshToken").ok_or("auth_required")?;
    let token = StoredToken { account, refresh_token };
    if token.account.trim().is_empty() || token.account.len() > 256 ||
        token.refresh_token.len() > 16_384 {
        return Err("auth_required");
    }
    let steam_id = token.steam_id().filter(|id| *id != 0).ok_or("auth_required")?;
    if let Some(expected) = request.get("steamId").and_then(Value::as_str) {
        if expected != steam_id.to_string() { return Err("auth_required"); }
    }
    Ok(token)
}

fn phase(job: &Job, value: &str) {
    job.emit(json!({"kind":"phase","phase":value}));
}

// The pinned client does not establish distinct logon session identities.
// Hold this guard for the entire authenticated operation so another lane cannot
// replace its CM session while a manifest or download still depends on it.
async fn authenticated_slot(job: &Job) -> Result<MutexGuard<'static, ()>, &'static str> {
    static ACTIVE: OnceLock<AsyncMutex<()>> = OnceLock::new();
    let gate = ACTIVE.get_or_init(|| AsyncMutex::new(()));
    if let Ok(permit) = gate.try_lock() { return Ok(permit); }
    phase(job, "waiting");
    tokio::time::timeout(Duration::from_secs(20), gate.lock()).await.map_err(|_| "busy")
}

async fn authenticated(request: &mut Value, job: &Job, transport: Arc<Shared>) -> Result<(Session, StoredToken), &'static str> {
    let token = credentials(request)?;
    phase(job, "connecting");
    let session = tokio::time::timeout(
        Duration::from_secs(40), Session::with_token_shared(&token, transport),
    ).await.map_err(|_| "connection_timeout")?.map_err(|_| "session_unavailable")?;
    Ok((session, token))
}

async fn profile(token: &StoredToken, route: NetworkRoute) -> Outcome {
    let steam_id = token.steam_id().filter(|id| *id != 0).ok_or("auth_required")?;
    // Public profile metadata carries no token, password or account name.
    let request = Request::get(format!("https://steamcommunity.com/profiles/{steam_id}/?xml=1"))
        .header("Accept", "application/xml,text/xml")
        .header("Accept-Encoding", "identity");
    let response = tokio::time::timeout(
        Duration::from_secs(15), HttpClient::with_route(route).get(request, 256 * 1024),
    ).await.map_err(|_| "profile_timeout")?.map_err(|_| "profile_unavailable")?;
    if !response.is_success() { return Err("profile_unavailable"); }
    let xml = std::str::from_utf8(&response.body).map_err(|_| "profile_unavailable")?;
    let document = roxmltree::Document::parse_with_options(xml, roxmltree::ParsingOptions {
        allow_dtd: false,
        nodes_limit: 4096,
    }).map_err(|_| "profile_unavailable")?;
    let root = document.root_element();
    if !root.has_tag_name("profile") { return Err("profile_unavailable"); }
    let field = |name: &str| root.children()
        .find(|node| node.has_tag_name(name)).and_then(|node| node.text());
    if field("steamID64").and_then(|id| id.trim().parse::<u64>().ok()) != Some(steam_id) {
        return Err("profile_unavailable");
    }
    let name = field("steamID").map(|name| label(name, 128)).filter(|name| !name.trim().is_empty());
    let avatar = preview(field("avatarFull"));
    if name.is_none() && avatar.is_empty() { return Err("profile_unavailable"); }
    Ok(json!({
        "kind":"profile","steamId":steam_id.to_string(),"personaName":name,
        "avatarUrl":(!avatar.is_empty()).then_some(avatar)
    }))
}

fn label(value: &str, limit: usize) -> String {
    value.chars().filter(|c| !c.is_control()).take(limit).collect()
}

fn preview(value: Option<&str>) -> String {
    let Some(value) = value.filter(|url| url.len() <= 4096) else { return String::new(); };
    let Some(rest) = value.strip_prefix("https://") else { return String::new(); };
    let host = rest.split('/').next().unwrap_or_default().to_ascii_lowercase();
    if host.contains('@') || host.contains(':') { return String::new(); }
    if ["steamusercontent.com", "steamstatic.com", "steamuserimages-a.akamaihd.net",
        "steamcdn-a.akamaihd.net"]
        .iter().any(|suffix| host == *suffix || host.ends_with(&format!(".{suffix}"))) {
        value.to_owned()
    } else {
        String::new()
    }
}

fn extension_media(path: &str) -> Option<&'static str> {
    let path = path.split(['?', '#']).next()?.to_ascii_lowercase();
    match path.rsplit('.').next()? {
        "png" | "jpg" | "jpeg" | "webp" | "bmp" | "gif" => Some("image"),
        "mp4" | "webm" | "mkv" | "mov" | "m4v" => Some("video"),
        _ => None,
    }
}

fn legacy_primary(content: &WorkshopContent) -> Option<(String, &'static str)> {
    let WorkshopContent::Legacy { url, filename } = content else { return None; };
    let media = match filename.as_deref() {
        Some(name) => extension_media(name),
        None => extension_media(url),
    }?;
    let primary = filename.as_deref().unwrap_or("contents.bin").replace('\\', "/");
    Some((primary, media))
}

fn owned_directory(value: &str) -> Result<PathBuf, &'static str> {
    if value.is_empty() || value.len() > 4096 || !Path::new(value).is_absolute() {
        return Err("unsafe_path");
    }
    let requested = Path::new(value);
    let metadata = std::fs::symlink_metadata(requested).map_err(|_| "unsafe_path")?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() { return Err("unsafe_path"); }
    let directory = std::fs::canonicalize(requested).map_err(|_| "unsafe_path")?;
    let mut files = std::fs::read_dir(&directory).map_err(|_| "unsafe_path")?;
    if files.next().is_some() { return Err("unsafe_path"); }
    Ok(directory)
}

fn install_error(error: InstallError) -> &'static str {
    match error {
        InstallError::NoDepotKey { eresult: 15, .. } => "not_owned",
        InstallError::UnsafePath { .. } => "unsafe_path",
        InstallError::Io(message) if message == "mangaflow-size-limit" => "size_limit",
        InstallError::Io(message) if message == "mangaflow-unsupported-entry" => "unsupported_media",
        InstallError::Io(message) if message == "mangaflow-invalid-chunk" => "download_failed",
        InstallError::Io(message) if message == "mangaflow-download-stalled" => "download_stalled",
        InstallError::Io(message) if message == "mangaflow-range-unsupported" => "range_unsupported",
        InstallError::Io(message) if message == "mangaflow-content-changed" => "content_changed",
        _ => "download_failed",
    }
}


fn login_error(error: LoginError) -> &'static str {
    match error {
        LoginError::Refused { eresult: 5, .. } => "invalid_credentials",
        LoginError::Refused { eresult: 65, .. } => "invalid_guard_code",
        LoginError::Refused { eresult: 88, .. } => "expired_guard_code",
        LoginError::Refused { eresult: 84, .. } => "login_rate_limited",
        LoginError::Refused { eresult: 15, .. } => "login_refused",
        LoginError::Session(_) => "steam_unavailable",
        _ => "login_failed",
    }
}

async fn reconnect_pending(
    session: &mut Session, attempts: &mut u8, job: &Job, transport: &Arc<Shared>,
) -> Result<(), &'static str> {
    job.emit(json!({"kind":"guardReconnecting"}));
    while *attempts < 3 {
        *attempts += 1;
        tokio::time::sleep(Duration::from_secs(u64::from(*attempts))).await;
        if let Ok(Ok(reconnected)) = tokio::time::timeout(
            Duration::from_secs(20), Session::anonymous_shared(Arc::clone(transport)),
        ).await {
            // Keep the original client_id, request_id and confirmation methods.
            // A reconnect must never begin a new password or QR auth session.
            *session = reconnected;
            return Ok(());
        }
    }
    Err("auth_connection_lost")
}

fn challenge(job: &Job, pending: &PendingLogin) {
    if let Some(url) = pending.challenge_url.as_deref()
        .filter(|url| url.len() <= 8192 && url.starts_with("https://")) {
        job.emit(json!({"kind":"qr","url":url}));
    }
}

async fn finish_pending_login(
    mut session: Session, mut pending: PendingLogin, job: &Arc<Job>, transport: Arc<Shared>,
) -> Result<StoredToken, &'static str> {
    if pending.client_id == 0 || pending.request_id.is_empty() { return Err("login_failed"); }
    let methods: Vec<i32> = pending.confirmations.iter().map(|kind| kind.as_i32())
        .filter(|kind| matches!(*kind, 2 | 3 | 4 | 5))
        .filter(|kind| pending.steam_id != 0 || !matches!(*kind, 2 | 3)).collect();
    if methods.is_empty() && pending.challenge_url.is_none() &&
        pending.confirmations.iter().any(|kind| !matches!(kind, GuardType::None)) {
        return Err("unsupported_guard");
    }
    let (sender, mut codes) = mpsc::channel::<GuardInput>(1);
    if let Ok(mut slot) = job.guard.lock() { *slot = Some(sender); }
    challenge(job, &pending);
    if !methods.is_empty() { job.emit(json!({"kind":"guard","methods":methods})); }
    let interval = Duration::from_secs_f32(if pending.interval.is_finite() {
        pending.interval.clamp(1.0, 30.0)
    } else { 5.0 });
    let deadline = tokio::time::Instant::now() + Duration::from_secs(300);
    let mut poll_at = tokio::time::Instant::now() + interval;
    let mut heartbeat_at = tokio::time::Instant::now() + Duration::from_secs(10);
    let mut reconnects = 0_u8;
    let mut needs_reconnect = false;
    let mut recovering = false;
    let mut ambiguous_submission = false;
    loop {
        if tokio::time::Instant::now() >= deadline { return Err("auth_session_expired"); }
        if needs_reconnect {
            reconnect_pending(&mut session, &mut reconnects, job, &transport).await?;
            needs_reconnect = false;
            recovering = true;
            poll_at = tokio::time::Instant::now();
            heartbeat_at = tokio::time::Instant::now() + Duration::from_secs(10);
        }
        tokio::select! {
            _ = tokio::time::sleep_until(deadline) => return Err("auth_session_expired"),
            _ = tokio::time::sleep_until(heartbeat_at) => {
                // poll_login itself does not send a CM heartbeat in this pin.
                if !matches!(tokio::time::timeout(
                    Duration::from_secs(15), session.keep_alive(),
                ).await, Ok(Ok(()))) {
                    needs_reconnect = true;
                }
                heartbeat_at = tokio::time::Instant::now() + Duration::from_secs(10);
            }
            code = codes.recv() => {
                let Some((code, kind)) = code else { return Err("cancelled"); };
                if pending.steam_id == 0 || !pending.confirmations.contains(&kind) ||
                    !kind.needs_a_code() {
                    job.emit(json!({"kind":"guardRejected","code":"invalid_guard_code"}));
                    continue;
                }
                match tokio::time::timeout(
                    Duration::from_secs(20), session.submit_guard_code(&pending, &code, kind),
                ).await {
                    Ok(Ok(())) => {
                        job.emit(json!({"kind":"guardAccepted"}));
                        poll_at = tokio::time::Instant::now();
                    }
                    Ok(Err(LoginError::Session(_))) | Err(_) => {
                        // Steam may have received the code. Re-poll the same challenge;
                        // never automatically submit it a second time.
                        ambiguous_submission = true;
                        needs_reconnect = true;
                    }
                    Ok(Err(error)) => {
                        let error = login_error(error);
                        if matches!(error, "invalid_guard_code" | "expired_guard_code") {
                            job.emit(json!({"kind":"guardRejected","code":error}));
                        } else { return Err(error); }
                    }
                }
            }
            _ = tokio::time::sleep_until(poll_at) => {
                let outcome = match tokio::time::timeout(
                    Duration::from_secs(20), session.poll_login(&pending),
                ).await {
                    Ok(Ok(outcome)) => outcome,
                    Ok(Err(LoginError::Session(_))) | Err(_) => {
                        needs_reconnect = true;
                        continue;
                    }
                    Ok(Err(error)) => return Err(login_error(error)),
                };
                poll_at = tokio::time::Instant::now() + interval;
                if recovering {
                    job.emit(json!({"kind":"guardResumed"}));
                    recovering = false;
                }
                match outcome {
                    PollOutcome::Pending { .. } => {
                        if ambiguous_submission {
                            job.emit(json!({"kind":"guardRejected","code":"guard_submission_unknown"}));
                            ambiguous_submission = false;
                        }
                    }
                    PollOutcome::Moved { client_id, challenge_url } => {
                        pending.client_id = client_id;
                        pending.challenge_url = challenge_url;
                        challenge(job, &pending);
                        if ambiguous_submission {
                            job.emit(json!({"kind":"guardRejected","code":"guard_submission_unknown"}));
                            ambiguous_submission = false;
                        }
                    }
                    PollOutcome::Complete { account, refresh_token, mut access_token } => {
                        access_token.zeroize();
                        if let Ok(mut sender) = job.guard.lock() { sender.take(); }
                        job.emit(json!({"kind":"authVerifying"}));
                        return Ok(StoredToken { account, refresh_token });
                    }
                }
            }
        }
    }
}

async fn password_login(request: &mut Value, job: &Arc<Job>, transport: Arc<Shared>) -> Result<StoredToken, &'static str> {
    let account = Zeroizing::new(take_text(request, "account").ok_or("invalid_request")?);
    let mut password = Zeroizing::new(take_text(request, "password").ok_or("invalid_request")?);
    if account.is_empty() || account.len() > 128 || account.chars().any(char::is_control) ||
        password.is_empty() || password.len() > 4096 {
        return Err("invalid_request");
    }
    phase(job, "connecting");
    let mut session = tokio::time::timeout(
        Duration::from_secs(30), Session::anonymous_shared(Arc::clone(&transport)),
    ).await.map_err(|_| "connection_timeout")?.map_err(|_| "steam_unavailable")?;
    let key = tokio::time::timeout(Duration::from_secs(20), session.password_key(&account))
        .await.map_err(|_| "connection_timeout")?.map_err(login_error)?;
    // The pinned implementation RSA-encrypts and zeroizes this transferred password.
    let pending = tokio::time::timeout(Duration::from_secs(20),
        session.begin_password_login(&account, std::mem::take(&mut *password), &key),
    ).await.map_err(|_| "connection_timeout")?.map_err(login_error)?;
    if pending.steam_id == 0 { return Err("login_failed"); }
    finish_pending_login(session, pending, job, transport).await
}

async fn run(mut request: SecretValue, job: Arc<Job>) -> Outcome {
    let op = text(&request.0, "op").ok_or("invalid_request")?;
    let (transport, route) = routed_shared(&mut request.0)?;
    match op.as_str() {
        "login" | "passwordLogin" => {
            let token = if op == "passwordLogin" {
                password_login(&mut request.0, &job, Arc::clone(&transport)).await?
            } else {
                phase(&job, "connecting");
                let mut session = tokio::time::timeout(
                    Duration::from_secs(30), Session::anonymous_shared(Arc::clone(&transport)),
                ).await.map_err(|_| "connection_timeout")?.map_err(|_| "steam_unavailable")?;
                let pending = tokio::time::timeout(Duration::from_secs(20), session.begin_qr_login())
                    .await.map_err(|_| "connection_timeout")?.map_err(login_error)?;
                finish_pending_login(session, pending, &job, Arc::clone(&transport)).await?
            };
            let steam_id = token.steam_id().filter(|id| *id != 0).ok_or("login_failed")?;
            let _permit = authenticated_slot(&job).await?;
            // Validate the content session before the caller commits a new credential.
            phase(&job, "connecting");
            let verified = tokio::time::timeout(
                Duration::from_secs(40), Session::with_token_shared(&token, Arc::clone(&transport)),
            ).await.map_err(|_| "connection_timeout")?.map_err(|_| "session_unavailable")?;
            if verified.account().is_none() { return Err("login_failed"); }
            job.emit(json!({
                "kind":"loggedIn","account":&token.account,"steamId":steam_id.to_string(),
                "refreshToken":&token.refresh_token
            }));
            Ok(json!({"kind":"complete"}))
        }
        "account" => {
            let _permit = authenticated_slot(&job).await?;
            let (session, token) = authenticated(&mut request.0, &job, Arc::clone(&transport)).await?;
            job.emit(json!({
                "kind":"account","account":session.account().unwrap_or(&token.account),
                "steamId":token.steam_id().ok_or("auth_required")?.to_string()
            }));
            Ok(json!({"kind":"complete"}))
        }
        "profile" => {
            // JVM only schedules this for its verified current account; metadata
            // failure must not invalidate the independently verified credential.
            let token = credentials(&mut request.0)?;
            let result = profile(&token, route).await?;
            job.emit(result);
            Ok(json!({"kind":"complete"}))
        }
        "library" => {
            let collection = text(&request.0, "collection").ok_or("invalid_request")?;
            let kind = match collection.as_str() {
                "favorites" => "myfavorites",
                "subscriptions" => "mysubscriptions",
                _ => return Err("invalid_request"),
            };
            let page = request.0.get("page").and_then(Value::as_u64).unwrap_or(1);
            if !(1..=10_000).contains(&page) { return Err("invalid_request"); }
            let media = text(&request.0, "media").ok_or("invalid_request")?;
            if media != "image" && media != "video" { return Err("invalid_request"); }
            let _permit = authenticated_slot(&job).await?;
            let (mut session, token) = authenticated(&mut request.0, &job, Arc::clone(&transport)).await?;
            phase(&job, "resolving");
            let query = CPublishedFile_GetUserFiles_Request {
                steamid: Some(token.steam_id().ok_or("auth_required")?),
                appid: Some(APP.get()),
                page: Some(page as u32),
                numperpage: Some(PAGE_SIZE),
                r#type: Some(kind.to_owned()),
                // Use the schema's documented default order for both collections.
                sortmethod: Some("lastupdated".to_owned()),
                requiredtags: if media == "video" { vec!["Video".to_owned()] } else { Vec::new() },
                excludedtags: if media == "image" {
                    vec!["Video".to_owned(), "Web".to_owned(), "Application".to_owned()]
                } else { Vec::new() },
                return_tags: Some(true),
                return_previews: Some(true),
                ..Default::default()
            };
            let results = tokio::time::timeout(Duration::from_secs(25), session.call_raw(&query))
                .await.map_err(|_| "metadata_timeout")?.map_err(|_| "steam_unavailable")?;
            let depot = tokio::time::timeout(Duration::from_secs(15), session.app_info(APP))
                .await.ok().and_then(Result::ok).and_then(|info| info.workshop_depot());
            let mut items = Vec::new();
            let mut unsupported = 0_u64;
            for details in &results.publishedfiledetails {
                let Ok(item) = tapline::classify(details, depot) else { unsupported += 1; continue; };
                if item.app != APP || item.id.get() == 0 { unsupported += 1; continue; }
                let actual = legacy_primary(&item.content).map(|(_, kind)| kind)
                    .or_else(|| details.tags.iter().any(|tag| tag.tag.as_deref()
                        .is_some_and(|tag| tag.eq_ignore_ascii_case("Video"))).then_some("video"));
                if actual != Some(media.as_str()) { unsupported += 1; continue; }
                items.push(json!({
                    "id":item.id.get().to_string(),"title":label(&item.title,300),
                    "previewUrl":preview(details.preview_url.as_deref()),"media":media,
                    "sizeBytes":item.size
                }));
            }
            let total = results.total.unwrap_or(0);
            // Paging follows the upstream collection, including unsupported rows.
            let has_more = page.saturating_mul(u64::from(PAGE_SIZE)) < u64::from(total);
            job.emit(json!({
                "kind":"items","items":items,"page":page,"hasMore":has_more,
                "unsupportedCount":unsupported
            }));
            Ok(json!({"kind":"complete"}))
        }
        "search" => {
            let query_text = text(&request.0, "query").unwrap_or_default().trim().to_owned();
            if query_text.chars().count() > 120 { return Err("invalid_request"); }
            let page = request.0.get("page").and_then(Value::as_u64).unwrap_or(1);
            if !(1..=10_000).contains(&page) { return Err("invalid_request"); }
            let media = text(&request.0, "media").ok_or("invalid_request")?;
            if media != "image" && media != "video" { return Err("invalid_request"); }
            // Public discovery never replaces an authenticated transfer's CM session.
            phase(&job, "connecting");
            let mut session = tokio::time::timeout(
                Duration::from_secs(30), Session::anonymous_shared(Arc::clone(&transport)),
            ).await.map_err(|_| "connection_timeout")?.map_err(|_| "steam_unavailable")?;
            phase(&job, "resolving");
            let query = BrowseQuery {
                app: APP,
                text: (!query_text.is_empty()).then_some(query_text),
                required_tags: if media == "video" { vec!["Video".to_owned()] } else { Vec::new() },
                excluded_tags: if media == "image" {
                    vec!["Video".to_owned(), "Web".to_owned(), "Application".to_owned()]
                } else { Vec::new() },
                sort: BrowseSort::Vote,
                per_page: PAGE_SIZE,
                page: Some(page as u32),
                ..BrowseQuery::default()
            };
            let results = tokio::time::timeout(Duration::from_secs(45), session.browse_workshop(&query))
                .await.map_err(|_| "metadata_timeout")?.map_err(|_| "steam_unavailable")?;
            let mut items = Vec::new();
            let mut unsupported = results.skipped.len() as u64;
            for found in &results.items {
                if found.item.app != APP { unsupported += 1; continue; }
                let actual = legacy_primary(&found.item.content).map(|(_, kind)| kind)
                    .or_else(|| found.tags.iter().any(|tag| tag.eq_ignore_ascii_case("Video"))
                        .then_some("video"));
                if actual != Some(media.as_str()) { unsupported += 1; continue; }
                items.push(json!({
                    "id":found.item.id.get().to_string(),"title":label(&found.item.title,300),
                    "previewUrl":preview(found.preview_url.as_deref()),"media":media,
                    "sizeBytes":found.item.size
                }));
            }
            let has_more = page.saturating_mul(u64::from(PAGE_SIZE)) < u64::from(results.total);
            job.emit(json!({
                "kind":"items","items":items,"page":page,"hasMore":has_more,
                "unsupportedCount":unsupported
            }));
            Ok(json!({"kind":"complete"}))
        }
        "download" => {
            let item_id = text(&request.0, "itemId").and_then(|id| id.parse::<u64>().ok())
                .filter(|id| *id != 0).ok_or("invalid_request")?;
            let media = text(&request.0, "media").ok_or("invalid_request")?;
            if media != "image" && media != "video" { return Err("invalid_request"); }
            let limit = request.0.get("maxBytes").and_then(Value::as_u64).unwrap_or(MAX_BYTES);
            if limit == 0 || limit > MAX_BYTES { return Err("invalid_request"); }
            let path = text(&request.0, "directory").ok_or("unsafe_path")?;
            let directory = owned_directory(&path)?;
            job.emit(json!({"kind":"progress","completed":0,"total":0,"known":false}));
            let _permit = authenticated_slot(&job).await?;
            let (mut session, _token) = authenticated(&mut request.0, &job, Arc::clone(&transport)).await?;
            phase(&job, "resolving");
            let item = tokio::time::timeout(
                Duration::from_secs(35), session.workshop_details(&[PublishedFileId(item_id)]),
            ).await.map_err(|_| "metadata_timeout")?.map_err(|_| "steam_unavailable")?
                .into_iter().next().ok_or("download_failed")?.map_err(|_| "download_failed")?;
            if item.app != APP || item.id.get() != item_id { return Err("invalid_request"); }
            if item.size == 0 || item.size > limit { return Err("size_limit"); }
            let primary = legacy_primary(&item.content);
            if matches!(&item.content, WorkshopContent::Legacy { .. }) &&
                primary.as_ref().map(|(_, kind)| *kind) != Some(media.as_str()) {
                return Err("unsupported_media");
            }
            if media == "image" && primary.is_none() { return Err("unsupported_media"); }
            let options = InstallOptions {
                install_dir: directory.clone(),
                concurrency: 4,
                include_dlc: false,
                resume: false,
                force: false,
                file_modes: FileModes::Manifest,
                workshop_layout: WorkshopLayout::Flat,
                ..InstallOptions::default()
            };
            let started = Instant::now();
            let last_activity = Arc::new(AtomicU64::new(0));
            let most_bytes = Arc::new(AtomicU64::new(0));
            let planned = Arc::new(AtomicBool::new(false));
            let forward = Arc::clone(&job);
            let activity = Arc::clone(&last_activity);
            let maximum = Arc::clone(&most_bytes);
            let plan_seen = Arc::clone(&planned);
            let mut reported_at = 0_u64;
            let mut reported_bytes = 0_u64;
            let mut observe = move |event| {
                let elapsed = started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64;
                match event {
                    Event::Planned { plan } => {
                        if !plan_seen.swap(true, Ordering::AcqRel) {
                            activity.store(elapsed, Ordering::Release);
                            phase(&forward, "downloading");
                            forward.emit(json!({
                                "kind":"progress","completed":0,"total":plan.total_bytes,"known":false
                            }));
                        }
                    }
                    Event::Progress { bytes_done, bytes_total } => {
                        if bytes_done > maximum.fetch_max(bytes_done, Ordering::AcqRel) {
                            activity.store(elapsed, Ordering::Release);
                            // Receive callbacks can fire for every socket read. Keep the
                            // first byte visible, without flooding the JNI event queue.
                            if reported_bytes == 0 || elapsed.saturating_sub(reported_at) >= 200 ||
                                bytes_done == bytes_total {
                                forward.emit(json!({
                                    "kind":"progress","completed":bytes_done,"total":bytes_total,"known":true
                                }));
                                reported_at = elapsed;
                                reported_bytes = bytes_done;
                            }
                        }
                    }
                    _ => {}
                }
            };
            let transfer = session.download_workshop_item_bounded(
                &item, &options, limit, MAX_ENTRIES, &mut observe,
            );
            tokio::pin!(transfer);
            let mut watch = tokio::time::interval(Duration::from_secs(5));
            watch.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            let report = loop {
                tokio::select! {
                    result = &mut transfer => break result.map_err(install_error)?,
                    _ = watch.tick() => {
                        let elapsed = started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64;
                        if elapsed.saturating_sub(last_activity.load(Ordering::Acquire)) >= 45_000 {
                            return Err("download_stalled");
                        }
                    }
                }
            };
            phase(&job, "verifying");
            if report.bytes_written == 0 || report.bytes_written > limit || report.files > MAX_ENTRIES as u64 {
                return Err("size_limit");
            }
            job.emit(json!({
                "kind":"progress","completed":report.bytes_written,"total":report.bytes_written,"known":true
            }));
            let mut complete = json!({
                "kind":"complete","directory":directory.to_string_lossy(),
                "bytes":report.bytes_written,"files":report.files
            });
            if let Some((filename, actual_media)) = primary {
                complete["primaryFile"] = Value::String(filename);
                complete["media"] = Value::String(actual_media.to_owned());
            }
            Ok(complete)
        }
        _ => Err("invalid_request"),
    }
}

fn start(request: Result<SecretValue, serde_json::Error>) -> i64 {
    let Some(runtime) = runtime() else { return 0; };
    let job = Arc::new(Job::new());
    static NEXT: AtomicI64 = AtomicI64::new(1);
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    if id <= 0 { return 0; }
    if let Ok(mut registry) = jobs().lock() {
        if registry.len() >= MAX_JOBS { return 0; }
        registry.insert(id, Arc::clone(&job));
    } else { return 0; }
    let Ok(request) = request else {
        job.finish(Err("invalid_request"));
        return id;
    };
    let seconds = match request.0.get("op").and_then(Value::as_str) {
        Some("login" | "passwordLogin") => 360,
        Some("download") => 1800,
        _ => 90,
    };
    let working = Arc::clone(&job);
    let worker = runtime.spawn(async move {
        tokio::time::timeout(Duration::from_secs(seconds), run(request, working)).await
            .unwrap_or(Err("timeout"))
    });
    let abort = worker.abort_handle();
    if let Ok(mut slot) = job.abort.lock() { *slot = Some(abort.clone()); }
    if job.cancelled.load(Ordering::Acquire) { abort.abort(); }
    let ending = Arc::clone(&job);
    let supervisor = runtime.spawn(async move {
        let result = match worker.await {
            Ok(result) => result,
            Err(_) if ending.cancelled.load(Ordering::Acquire) => Err("cancelled"),
            Err(_) => Err("native_failed"),
        };
        ending.finish(result);
    });
    if let Ok(mut slot) = job.supervisor.lock() { *slot = Some(supervisor); }
    id
}

fn free(id: i64) {
    let job = jobs().lock().ok().and_then(|mut registry| registry.remove(&id));
    let Some(job) = job else { return; };
    job.cancel();
    let handle = job.supervisor.lock().ok().and_then(|mut value| value.take());
    if let (Some(runtime), Some(handle)) = (runtime(), handle) {
        // Kotlin invokes free on its IO dispatcher after the polling loop.
        let _ = runtime.block_on(handle);
    }
}

unsafe fn read_string(env: *mut JNIEnv, input: jstring) -> Option<String> {
    if env.is_null() || input.is_null() || unsafe { (*env).is_null() } { return None; }
    let interface = unsafe { &**env };
    let length = unsafe { interface.GetStringLength?(env, input) };
    if !(0..=32_768).contains(&length) { return None; }
    let chars = unsafe { interface.GetStringChars?(env, input, std::ptr::null_mut()) };
    if chars.is_null() { return None; }
    let decoded = String::from_utf16(unsafe {
        std::slice::from_raw_parts(chars, length as usize)
    }).ok();
    if let Some(release) = interface.ReleaseStringChars {
        unsafe { release(env, input, chars); }
    }
    decoded
}

unsafe fn write_string(env: *mut JNIEnv, output: String) -> jstring {
    let output = Zeroizing::new(output);
    if env.is_null() || unsafe { (*env).is_null() } { return std::ptr::null_mut(); }
    let interface = unsafe { &**env };
    let Some(create) = interface.NewString else { return std::ptr::null_mut(); };
    let units = Zeroizing::new(output.encode_utf16().collect::<Vec<u16>>());
    let Ok(length) = i32::try_from(units.len()) else { return std::ptr::null_mut(); };
    unsafe { create(env, units.as_ptr(), length) }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_mangaflow_storage_SteamNative_nativeStart(
    env: *mut JNIEnv, _class: jclass, input: jstring,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(raw) = (unsafe { read_string(env, input) }) else { return 0; };
        let raw = Zeroizing::new(raw);
        let request = serde_json::from_str::<Value>(&raw).map(SecretValue);
        start(request)
    })).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_mangaflow_storage_SteamNative_nativePoll(
    env: *mut JNIEnv, _class: jclass, id: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let output = match lookup(id) {
            Some(job) => job.next(),
            None => Some(String::from("{\"kind\":\"error\",\"code\":\"invalid_job\"}")),
        };
        output.map_or(std::ptr::null_mut(), |value| unsafe { write_string(env, value) })
    })).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mangaflow_storage_SteamNative_nativeCancel(
    _env: *mut JNIEnv, _class: jclass, id: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(job) = lookup(id) { job.cancel(); }
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mangaflow_storage_SteamNative_nativeFree(
    _env: *mut JNIEnv, _class: jclass, id: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| free(id)));
}


#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_dev_mangaflow_storage_SteamNative_nativeSubmitGuard(
    env: *mut JNIEnv, _class: jclass, id: jlong, input: jstring, kind: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(code) = (unsafe { read_string(env, input) }) else { return 0; };
        let code = Zeroizing::new(code);
        if !(4..=10).contains(&code.len()) || !code.bytes().all(|c| c.is_ascii_alphanumeric()) {
            return 0;
        }
        let kind = match kind {
            2 => GuardType::EmailCode,
            3 => GuardType::DeviceCode,
            _ => return 0,
        };
        let Some(job) = lookup(id) else { return 0; };
        if job.finished.load(Ordering::Acquire) || job.cancelled.load(Ordering::Acquire) { return 0; }
        let Ok(sender) = job.guard.lock() else { return 0; };
        let Some(sender) = sender.as_ref() else { return 0; };
        if sender.try_send((code, kind)).is_ok() { 1 } else { 0 }
    })).unwrap_or(0)
}
