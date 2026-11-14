//! JNI media-only adapter around the pinned MPL-2.0 tapline core.
//! Credentials stay in memory; platform code owns encrypted persistence.
use jni_sys::{JNIEnv, jboolean, jclass, jint, jlong, jstring};
use serde_json::{Value, json};
use std::collections::{HashMap, VecDeque};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use tapline::{
    AppId, BrowseQuery, BrowseSort, Event, FileModes, InstallError, InstallOptions,
    PublishedFileId, Session, Shared, StoredToken, WorkshopContent, WorkshopLayout,
    GuardType, LoginError, PollOutcome,
};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;
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

async fn authenticated(request: &mut Value) -> Result<(Session, StoredToken), &'static str> {
    let token = credentials(request)?;
    let session = Session::with_token_shared(&token, shared()).await
        .map_err(|_| "session_unavailable")?;
    Ok((session, token))
}

fn label(value: &str, limit: usize) -> String {
    value.chars().filter(|c| !c.is_control()).take(limit).collect()
}

fn preview(value: Option<&str>) -> String {
    let Some(value) = value.filter(|url| url.len() <= 4096) else { return String::new(); };
    let Some(rest) = value.strip_prefix("https://") else { return String::new(); };
    let host = rest.split('/').next().unwrap_or_default().to_ascii_lowercase();
    if host.contains('@') || host.contains(':') { return String::new(); }
    if ["steamusercontent.com", "steamstatic.com", "steamuserimages-a.akamaihd.net"]
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

async fn password_login(request: &mut Value, job: &Arc<Job>) -> Result<StoredToken, &'static str> {
    let account = Zeroizing::new(take_text(request, "account").ok_or("invalid_request")?);
    let mut password = Zeroizing::new(take_text(request, "password").ok_or("invalid_request")?);
    if account.is_empty() || account.len() > 128 || account.chars().any(char::is_control) ||
        password.is_empty() || password.len() > 4096 {
        return Err("invalid_request");
    }
    let mut session = Session::anonymous_shared(shared()).await.map_err(|_| "steam_unavailable")?;
    let key = session.password_key(&account).await.map_err(login_error)?;
    // The pinned implementation RSA-encrypts and zeroizes this transferred password.
    let mut pending = session.begin_password_login(&account, std::mem::take(&mut *password), &key)
        .await.map_err(login_error)?;
    if pending.client_id == 0 || pending.request_id.is_empty() || pending.steam_id == 0 {
        return Err("login_failed");
    }
    let methods: Vec<i32> = pending.confirmations.iter().map(|kind| kind.as_i32())
        .filter(|kind| matches!(*kind, 2 | 3 | 4 | 5)).collect();
    if methods.is_empty() && pending.confirmations.iter().any(|kind|
        !matches!(kind, GuardType::None)) {
        return Err("unsupported_guard");
    }
    let (sender, mut codes) = mpsc::channel::<GuardInput>(1);
    if let Ok(mut slot) = job.guard.lock() { *slot = Some(sender); }
    if !methods.is_empty() { job.emit(json!({"kind":"guard","methods":methods})); }
    let interval = if pending.interval.is_finite() {
        pending.interval.clamp(1.0, 10.0)
    } else { 5.0 };
    loop {
        tokio::select! {
            code = codes.recv() => {
                let Some((code, kind)) = code else { return Err("cancelled"); };
                if !pending.confirmations.contains(&kind) || !kind.needs_a_code() {
                    job.emit(json!({"kind":"guardRejected","code":"invalid_guard_code"}));
                    continue;
                }
                match session.submit_guard_code(&pending, &code, kind).await {
                    Ok(()) => job.emit(json!({"kind":"guardAccepted"})),
                    Err(error) => {
                        let error = login_error(error);
                        if matches!(error, "invalid_guard_code" | "expired_guard_code") {
                            job.emit(json!({"kind":"guardRejected","code":error}));
                        } else { return Err(error); }
                    }
                }
            }
            _ = tokio::time::sleep(Duration::from_secs_f32(interval)) => {
                match session.poll_login(&pending).await.map_err(login_error)? {
                    PollOutcome::Pending { .. } => {}
                    PollOutcome::Moved { client_id, .. } => pending.client_id = client_id,
                    PollOutcome::Complete { account, refresh_token, mut access_token } => {
                        access_token.zeroize();
                        return Ok(StoredToken { account, refresh_token });
                    }
                }
            }
        }
    }
}

async fn run(mut request: SecretValue, job: Arc<Job>) -> Outcome {
    let op = text(&request.0, "op").ok_or("invalid_request")?;
    match op.as_str() {
        "login" | "passwordLogin" => {
            let token = if op == "passwordLogin" {
                password_login(&mut request.0, &job).await?
            } else {
            let mut session = Session::anonymous_shared(shared()).await
                .map_err(|_| "steam_unavailable")?;
            let codes = Arc::clone(&job);
            let token = session.qr_login(Duration::from_secs(300), &mut |url| {
                if url.len() <= 8192 && url.starts_with("https://") {
                    codes.emit(json!({"kind":"qr","url":url}));
                }
            }).await.map_err(login_error)?;
            token
            };
            let steam_id = token.steam_id().filter(|id| *id != 0).ok_or("login_failed")?;
            // Validate the content session before the caller commits a new credential.
            let verified = Session::with_token_shared(&token, shared()).await
                .map_err(|_| "session_unavailable")?;
            if verified.account().is_none() { return Err("login_failed"); }
            job.emit(json!({
                "kind":"loggedIn","account":&token.account,"steamId":steam_id.to_string(),
                "refreshToken":&token.refresh_token
            }));
            Ok(json!({"kind":"complete"}))
        }
        "account" => {
            let (session, token) = authenticated(&mut request.0).await?;
            job.emit(json!({
                "kind":"account","account":session.account().unwrap_or(&token.account),
                "steamId":token.steam_id().ok_or("auth_required")?.to_string()
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
            let mut session = if request.0.get("refreshToken").and_then(Value::as_str)
                .is_some_and(|value| !value.is_empty()) {
                authenticated(&mut request.0).await?.0
            } else {
                Session::anonymous_shared(shared()).await.map_err(|_| "steam_unavailable")?
            };
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
            let results = session.browse_workshop(&query).await.map_err(|_| "steam_unavailable")?;
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
            let (mut session, _token) = authenticated(&mut request.0).await?;
            let item = session.workshop_details(&[PublishedFileId(item_id)]).await
                .map_err(|_| "steam_unavailable")?.into_iter().next()
                .ok_or("download_failed")?.map_err(|_| "download_failed")?;
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
            job.emit(json!({"kind":"progress","completed":0,"total":item.size}));
            let forward = Arc::clone(&job);
            let report = session.download_workshop_item_bounded(
                &item, &options, limit, MAX_ENTRIES, &mut |event| {
                    match event {
                        Event::Planned { plan } => forward.emit(json!({
                            "kind":"progress","completed":0,"total":plan.total_bytes
                        })),
                        Event::Progress { bytes_done, bytes_total } => forward.emit(json!({
                            "kind":"progress","completed":bytes_done,"total":bytes_total
                        })),
                        _ => {}
                    }
                }
            ).await.map_err(install_error)?;
            if report.bytes_written == 0 || report.bytes_written > limit || report.files > MAX_ENTRIES as u64 {
                return Err("size_limit");
            }
            job.emit(json!({
                "kind":"progress","completed":report.bytes_written,"total":report.bytes_written
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
