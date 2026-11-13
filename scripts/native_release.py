"""Attach Windows installers to the exact Android release for this source commit."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import time
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


def stage() -> None:
    version = os.environ["MANGA_VERSION"]
    root = Path("desktopApp/build/compose/binaries/main")
    destination = Path("dist/native")
    destination.mkdir(parents=True, exist_ok=True)
    files = sorted([*(root / "exe").glob("*.exe"), *(root / "msi").glob("*.msi")])
    if not files:
        raise RuntimeError("The Windows package task produced no installer")
    for path in files:
        name = f"MangaFlow-{version}-windows-x64{path.suffix.lower()}"
        shutil.copy2(path, destination / name)
        print(f"Staged {name} ({path.stat().st_size} bytes)")


def publish() -> None:
    tag = os.environ["MANGA_TAG"]
    sha = os.environ["GITHUB_SHA"]
    release = None
    # Android owns the signed release and its tag. Native packaging must never create a competing one.
    for attempt in range(80):
        release = request_json(f"{API}/releases/tags/{quote(tag, safe='')}", missing=True)
        if release is not None and not release.get("draft"):
            break
        if attempt != 79:
            time.sleep(15)
    if release is None or release.get("draft"):
        raise RuntimeError("The matching signed Android release has not been published")
    reference = request_json(f"{API}/git/ref/tags/{quote(tag, safe='')}")["object"]
    while reference["type"] == "tag":
        reference = request_json(f"{API}/git/tags/{reference['sha']}")["object"]
    if reference["type"] != "commit" or reference["sha"] != sha:
        raise RuntimeError("Release tag does not match the native application's exact source commit")
    installers = sorted(path for path in Path("dist/native").rglob("*") if path.is_file() and path.suffix.lower() in {".exe", ".msi"})
    if not installers:
        raise RuntimeError("No Windows installers were downloaded from this workflow")
    existing = {asset["name"] for asset in release["assets"]}
    for path in installers:
        if not path.name.startswith(f"MangaFlow-{os.environ['MANGA_VERSION']}-windows-x64."):
            raise RuntimeError("Installer name does not match this release version")
        if path.name in existing:
            print(f"Preserved immutable existing asset: {path.name}")
            continue
        upload_url = f"https://uploads.github.com/repos/{REPOSITORY}/releases/{release['id']}/assets?name={quote(path.name)}"
        content = path.read_bytes()
        uploaded = request_json(upload_url, data=content, content_type="application/octet-stream")
        if uploaded.get("size") != len(content):
            raise RuntimeError("Uploaded installer size does not match the staged file")
        digest = uploaded.get("digest")
        if digest and digest != "sha256:" + hashlib.sha256(content).hexdigest():
            raise RuntimeError("Uploaded installer digest does not match the staged file")
        print(f"Published {uploaded['browser_download_url']}")


if __name__ == "__main__":
    actions = {"plan": plan, "stage": stage, "publish": publish}
    if len(sys.argv) != 2 or sys.argv[1] not in actions:
        raise SystemExit("Usage: native_release.py plan|stage|publish")
    actions[sys.argv[1]]()
