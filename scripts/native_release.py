"""Attach Windows installers to the exact Android release for this source commit."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import time
import zlib
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen


REPOSITORY = os.environ.get("GITHUB_REPOSITORY", "wozhenbang2004/MangaFlow")
API = f"https://api.github.com/repos/{REPOSITORY}"


def request_json(url: str, *, data: bytes | None = None, content_type: str = "application/json", missing: bool = False):
    token = os.environ["GH_TOKEN"]
    request = Request(url, data=data, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": content_type,
        "User-Agent": "MangaFlow-release",
    })
    try:
        with urlopen(request, timeout=45) as response:
            return json.load(response)
    except HTTPError as error:
        if missing and error.code == 404:
            return None
        raise RuntimeError(f"GitHub release operation failed (HTTP {error.code})") from None


def plan() -> None:
    sha = os.environ["GITHUB_SHA"]
    version = None
    for attempt in range(20):
        runs = request_json(f"{API}/actions/workflows/android-release.yml/runs?head_sha={sha}&branch=main&per_page=20")
        matching = [run for run in runs["workflow_runs"] if run["head_sha"] == sha and run["event"] in {"push", "workflow_dispatch"}]
        if matching:
            number = max(matching, key=lambda run: run["id"])["run_number"]
            version = f"0.1.{number}"
            break
        if attempt != 19:
            time.sleep(3)
    if version is None:
        raise RuntimeError("No matching main Android release run exists for this exact source commit")
    with Path(os.environ["GITHUB_ENV"]).open("a", encoding="utf-8") as output:
        output.write(f"MANGA_VERSION={version}\nMANGA_TAG=v{version}\nMANGA_BUILD_NUMBER={number}\n")
    print(f"Native application version: {version}; source: {sha}")



def file_sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def runtime_fingerprint(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted((item for item in root.rglob("*") if item.is_file()),
                       key=lambda item: item.relative_to(root).as_posix()):
        digest.update((path.relative_to(root).as_posix() + "\0" + file_sha(path) + "\n").encode("utf-8"))
    return digest.hexdigest()


def previous_file_assets() -> dict[str, dict]:
    """A prior full file manifest carries stable URLs from any older release."""
    prefix = f"https://github.com/{REPOSITORY}/releases/download/"
    releases = request_json(f"{API}/releases?per_page=20")
    for release in releases:
        if release.get("draft") or release.get("prerelease") or release["tag_name"] == os.environ["MANGA_TAG"]:
            continue
        asset = next((item for item in release["assets"] if item["name"].endswith("-windows-x64.update.json")), None)
        if asset is None or not asset.get("digest", "").startswith("sha256:") or asset["size"] > 2 * 1024 * 1024:
            continue
        try:
            request = Request(asset["browser_download_url"], headers={"User-Agent": "MangaFlow-release"})
            with urlopen(request, timeout=45) as response:
                data = response.read(2 * 1024 * 1024 + 1)
            if len(data) != asset["size"] or "sha256:" + hashlib.sha256(data).hexdigest() != asset["digest"]:
                continue
            manifest = json.loads(data)
            if manifest.get("protocol") != 1:
                continue
            return {entry["sha256"]: entry for entry in manifest["files"]
                    if entry.get("url", "").startswith(prefix)}
        except (OSError, ValueError, KeyError):
            continue
    return {}


def stage_incremental(image: Path, destination: Path, version: str) -> None:
    app = image / "app"
    runtime = image / "runtime"
    if not (app / "MangaFlow.cfg").is_file() or not runtime.is_dir():
        raise RuntimeError(f"The jpackage application image is missing at {image}; "
                           "run :desktopApp:createReleaseDistributable before staging incremental files")
    prior = previous_file_assets()
    entries = []
    new_assets = set()
    for path in sorted(item for item in app.rglob("*") if item.is_file()):
        if path.is_symlink():
            raise RuntimeError("Packaged application files must not be symbolic links")
        relative = path.relative_to(image).as_posix()
        digest = file_sha(path)
        checksum = 1
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                checksum = zlib.adler32(block, checksum)
        previous = prior.get(digest)
        if previous and previous.get("size") == path.stat().st_size:
            url = previous["url"]
        else:
            name = f"MangaFlow-file-{digest}.bin"
            url = f"https://github.com/{REPOSITORY}/releases/download/{os.environ['MANGA_TAG']}/{name}"
            if name not in new_assets:
                shutil.copy2(path, destination / name)
                new_assets.add(name)
        entries.append({"path": relative, "size": path.stat().st_size, "sha256": digest,
                        "checksum": format(checksum & 0xffffffff, "x"), "url": url})
    if not 1 <= len(entries) <= 1000:
        raise RuntimeError("Application file inventory exceeds the updater's supported bounds")
    manifest = {"protocol": 1, "version": version, "sourceCommit": os.environ["GITHUB_SHA"],
                "runtimeFingerprint": runtime_fingerprint(runtime), "files": entries}
    target = destination / f"MangaFlow-{version}-windows-x64.update.json"
    target.write_text(json.dumps(manifest, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Staged file manifest: {len(entries)} app files, {len(new_assets)} newly published file assets")

def stage() -> None:
    version = os.environ["MANGA_VERSION"]
    root = Path("desktopApp/build/compose/binaries/main-release")
    destination = Path("dist/native")
    destination.mkdir(parents=True, exist_ok=True)
    files = sorted([*(root / "exe").glob("*.exe"), *(root / "msi").glob("*.msi")])
    if not files:
        raise RuntimeError("The Windows package task produced no installer")
    for path in files:
        name = f"MangaFlow-{version}-windows-x64{path.suffix.lower()}"
        shutil.copy2(path, destination / name)
        print(f"Staged {name} ({path.stat().st_size} bytes)")
    stage_incremental(root / "app" / "MangaFlow", destination, version)


def release_handoff_blocker(sha: str, number: str) -> str | None:
    """A successful EXE build is not a failure when its Android release is superseded.

    Keep published-release integrity strict; only skip an unpublishable attachment.
    Workflow artifacts retain the exact Windows installers for that source SHA.
    """
    main = request_json(f"{API}/git/ref/heads/main")
    if main["object"]["sha"] != sha:
        return "newer main commit owns the next Android release"

    runs = request_json(
        f"{API}/actions/workflows/android-release.yml/runs?head_sha={sha}&branch=main&per_page=20"
    )
    matching = [run for run in runs["workflow_runs"]
                if run["head_sha"] == sha and str(run["run_number"]) == number]
    if matching and matching[0]["status"] == "completed" and matching[0]["conclusion"] != "success":
        return f"matching Android release finished as {matching[0]['conclusion']}"
    return None


def publish() -> None:
    tag = os.environ["MANGA_TAG"]
    sha = os.environ["GITHUB_SHA"]
    release = None
    # Android owns the signed release and its tag. Native packaging must never create a competing one.
    for attempt in range(80):
        release = request_json(f"{API}/releases/tags/{quote(tag, safe='')}", missing=True)
        if release is not None and not release.get("draft"):
            break
        # A new push must not turn an already built EXE into a failed workflow.
        # It cannot be published to another SHA's signed Android release either.
        blocker = release_handoff_blocker(sha, os.environ["MANGA_BUILD_NUMBER"])
        if blocker is not None:
            print(f"::notice::Windows installers built and retained as workflow artifacts. "
                  f"Skipping release attachment: {blocker}.")
            return
        if attempt != 79:
            time.sleep(15)
    if release is None or release.get("draft"):
        raise RuntimeError("The matching signed Android release was not published within "
                           "the wait window; the Windows installers remain in workflow artifacts")
    reference = request_json(f"{API}/git/ref/tags/{quote(tag, safe='')}")["object"]
    while reference["type"] == "tag":
        reference = request_json(f"{API}/git/tags/{reference['sha']}")["object"]
    if reference["type"] != "commit" or reference["sha"] != sha:
        raise RuntimeError("Release tag does not match the native application's exact source commit")
    files = sorted((path for path in Path("dist/native").rglob("*")
                    if path.is_file() and path.suffix.lower() in {".exe", ".msi", ".bin", ".json"}),
                   key=lambda path: (path.suffix == ".json", path.name))
    if not any(path.suffix in {".exe", ".msi"} for path in files):
        raise RuntimeError("No Windows installers were downloaded from this workflow")
    existing = {asset["name"]: asset for asset in release["assets"]}
    for path in files:
        if path.suffix == ".bin":
            if path.name != f"MangaFlow-file-{file_sha(path)}.bin":
                raise RuntimeError("Incremental file asset name does not match its SHA-256")
        elif not path.name.startswith(f"MangaFlow-{os.environ['MANGA_VERSION']}-windows-x64."):
            raise RuntimeError("Native asset name does not match this release version")
        digest = "sha256:" + file_sha(path)
        if path.name in existing:
            asset = existing[path.name]
            if asset["size"] != path.stat().st_size or (asset.get("digest") and asset["digest"] != digest):
                raise RuntimeError("An immutable native asset already exists with different bytes")
            print(f"Preserved immutable existing asset: {path.name}")
            continue
        upload_url = f"https://uploads.github.com/repos/{REPOSITORY}/releases/{release['id']}/assets?name={quote(path.name)}"
        content = path.read_bytes()
        uploaded = request_json(upload_url, data=content,
                                content_type="application/json" if path.suffix == ".json" else "application/octet-stream")
        if uploaded.get("size") != len(content) or uploaded.get("digest") != digest:
            raise RuntimeError("Uploaded native asset size or SHA-256 does not match the staged file")
        print(f"Published {uploaded['browser_download_url']}")



if __name__ == "__main__":
    actions = {"plan": plan, "stage": stage, "publish": publish}
    if len(sys.argv) != 2 or sys.argv[1] not in actions:
        raise SystemExit("Usage: native_release.py plan|stage|publish")
    actions[sys.argv[1]]()
