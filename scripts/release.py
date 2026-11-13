#!/usr/bin/env python3
"""Build release metadata and publish an immutable signed APK from GitHub Actions."""

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from package_runtimes import require_small_apk, runtime_assets

REPOSITORY = "wozhenbang2004/MangaFlow"
API = f"https://api.github.com/repos/{REPOSITORY}"
ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
PLAN = ROOT / "release-plan.json"


def request(url, method="GET", data=None, binary=False, content_type=None):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in {"api.github.com", "uploads.github.com"}:
        raise RuntimeError("Release requests must stay on GitHub's API hosts")
    headers = {
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "MangaFlow-Release",
    }
    if data is not None and not binary:
        data = json.dumps(data).encode()
        content_type = "application/json"
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, method=method, headers=headers, data=data)
    with urllib.request.urlopen(req, timeout=180) as response:
        body = response.read()
        return json.loads(body) if body else None


def optional(url):
    try:
        return request(url)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def environment(values):
    target = os.environ.get("GITHUB_ENV")
    if target:
        with open(target, "a", encoding="utf-8") as file:
            for key, value in values.items():
                if "\n" in str(value):
                    raise RuntimeError("Invalid environment value")
                file.write(f"{key}={value}\n")


def require_current_main(sha):
    current = request(API + "/git/ref/heads/main")
    if current["object"]["sha"] != sha:
        raise RuntimeError("A newer main revision is available; its workflow owns the next release.")


def ref_commit(tag):
    ref = optional(API + "/git/ref/tags/" + urllib.parse.quote(tag, safe=""))
    if ref is None:
        return None
    item = ref["object"]
    for _ in range(4):
        if item["type"] == "commit":
            return item["sha"]
        if item["type"] != "tag":
            raise RuntimeError("Unexpected release tag object")
        item = request(API + "/git/tags/" + item["sha"])["object"]
    raise RuntimeError("Nested release tag depth exceeded")


def require_tag_commit(tag, sha, missing_error=None):
    # A successful ref creation must still be confirmed by a read. Retry only absence.
    for delay in (0, 1, 2, 4, 8):
        if delay:
            time.sleep(delay)
        tagged = ref_commit(tag)
        if tagged == sha:
            return
        if tagged is not None:
            raise RuntimeError(f"Release tag {tag} points to {tagged}; expected {sha}")
    if missing_error is not None:
        raise missing_error
    raise RuntimeError(f"Release tag {tag} is still unavailable after confirmation retries; expected {sha}")


def reserve_tag(tag, sha):
    tagged = ref_commit(tag)
    if tagged is None:
        creation_error = None
        try:
            created = request(API + "/git/refs", "POST", {"ref": "refs/tags/" + tag, "sha": sha})
        except urllib.error.HTTPError as error:
            if error.code not in (409, 422):
                raise
            # A retry may observe a ref created by the first accepted request.
            creation_error = error
        else:
            item = created.get("object") or {}
            if created.get("ref") != "refs/tags/" + tag or item.get("type") != "commit" or item.get("sha") != sha:
                raise RuntimeError("GitHub did not confirm creation of the requested release tag")
        require_tag_commit(tag, sha, missing_error=creation_error)
    elif tagged != sha:
        raise RuntimeError(f"Release tag {tag} points to {tagged}; expected {sha}")


def plan():
    if os.environ["GITHUB_REPOSITORY"] != REPOSITORY or os.environ["GITHUB_REF"] != "refs/heads/main":
        raise RuntimeError("Only this repository's main branch can publish")
    number = int(os.environ["GITHUB_RUN_NUMBER"])
    code = 10000 + number
    if not 10000 < code <= 2_100_000_000:
        raise RuntimeError("Version code exceeds Android's supported range")
    name = f"0.1.{number}"
    tag = "v" + name
    sha = os.environ["GITHUB_SHA"]
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise RuntimeError("Invalid source revision")
    existing = optional(API + "/releases/tags/" + tag)
    tagged = ref_commit(tag)
    if tagged is not None and tagged != sha:
        raise RuntimeError("This release tag is already reserved for a different revision")
    if existing and not existing["draft"]:
        if tagged != sha:
            raise RuntimeError("Published release does not point to the selected revision")
        environment({"RELEASE_ALREADY_PUBLISHED": "true", "RELEASE_TAG": tag})
        print(f"{tag} was already published from this commit; keeping its assets unchanged.")
        return
    if existing and existing["target_commitish"] != sha:
        raise RuntimeError("Existing draft belongs to a different source revision")
    require_current_main(sha)
    # A recreated workflow must never silently restart version numbering.
    latest = optional(API + "/releases/latest")
    if latest:
        match = re.fullmatch(r"v0\.1\.(\d+)", latest["tag_name"])
        if match is None or int(match.group(1)) >= number:
            raise RuntimeError("Release numbering would not advance; preserve the workflow's run counter.")
    message = subprocess.check_output(["git", "show", "-s", "--format=%s", sha], cwd=ROOT, text=True).strip()
    timestamp = int(subprocess.check_output(["git", "show", "-s", "--format=%ct", sha], cwd=ROOT, text=True).strip())
    notes = message[:2000] if message else "阅读体验与稳定性改进"
    value = {
        "tag": tag, "versionCode": code, "versionName": name,
        "sourceCommit": sha, "notes": notes,
        "publishedAt": datetime.fromtimestamp(timestamp, timezone.utc).isoformat(),
    }
    PLAN.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    environment({
        "RELEASE_ALREADY_PUBLISHED": "false", "RELEASE_TAG": tag,
        "RELEASE_VERSION_CODE": code, "RELEASE_VERSION_NAME": name,
    })
    print(f"Preparing {tag} ({code}) from {sha[:12]}")


def signed_envelope(payload, private_key):
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()
    signature = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", str(private_key)],
        input=encoded, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
    ).stdout
    return {
        "algorithm": "SHA256withRSA",
        "payload": base64.b64encode(encoded).decode(),
        "signature": base64.b64encode(signature).decode(),
    }


def runtime_payload(value, packages):
    return {
        "schema": 1, "packageName": "dev.mangaflow", "versionCode": value["versionCode"],
        "versionName": value["versionName"], "sourceCommit": value["sourceCommit"],
        "runtimes": [{**item, "archive": {
            **item["archive"],
            "url": f"https://github.com/{REPOSITORY}/releases/download/{value['tag']}/{item['archive']['name']}",
        }} for item in packages],
    }


def manifest(apk, private_key, runtime_dir):
    value = json.loads(PLAN.read_text(encoding="utf-8"))
    DIST.mkdir(exist_ok=True)
    target = DIST / f"MangaFlow-{value['versionName']}.apk"
    if apk.resolve() != target.resolve():
        target.write_bytes(apk.read_bytes())
    require_small_apk(target)
    packages, runtime_paths = runtime_assets(runtime_dir)
    catalog = DIST / "runtime-catalog.json"
    catalog.write_text(json.dumps(signed_envelope(runtime_payload(value, packages), private_key),
                                  separators=(",", ":")) + "\n", encoding="utf-8")
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    payload = {
        "schema": 1, "packageName": "dev.mangaflow", "versionCode": value["versionCode"],
        "versionName": value["versionName"], "minSdk": 26, "notes": value["notes"],
        "sourceCommit": value["sourceCommit"], "publishedAt": value["publishedAt"],
        "apk": {
            "url": f"https://github.com/{REPOSITORY}/releases/download/{value['tag']}/{target.name}",
            "bytes": target.stat().st_size, "sha256": digest,
        },
    }
    # The same private key signs the APK and these exact payload bytes.
    envelope = signed_envelope(payload, private_key)
    (DIST / "latest.json").write_text(json.dumps(envelope, separators=(",", ":")) + "\n", encoding="utf-8")
    sums = []
    for item in (target, DIST / "latest.json", catalog, *runtime_paths):
        sums.append(hashlib.sha256(item.read_bytes()).hexdigest() + "  " + item.name)
    (DIST / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")
    print(f"Prepared {target.name}, signed update/runtime catalogs, {len(runtime_paths)} optional components and SHA256SUMS")


def publish():
    value = json.loads(PLAN.read_text(encoding="utf-8"))
    require_current_main(value["sourceCommit"])
    tag = value["tag"]
    release = optional(API + "/releases/tags/" + tag)
    tagged = ref_commit(tag)
    if tagged is not None and tagged != value["sourceCommit"]:
        raise RuntimeError("Release tag belongs to a different source revision")
    if release and not release["draft"]:
        if tagged != value["sourceCommit"]:
            raise RuntimeError("Published release tag changed")
        print(f"{tag} is already published; assets remain unchanged.")
        return
    if release and release["target_commitish"] != value["sourceCommit"]:
        raise RuntimeError("Existing draft belongs to a different source revision")
    body = (
        f"{value['notes']}\n\n"
        f"Android 8.0+ · versionCode {value['versionCode']}\n\n"
        "下载 APK 安装；已安装漫流的用户可以在应用中检查更新并覆盖升级。\n\n"
        "AI 识别组件在应用中按需下载，仅获取当前设备架构所需文件。\n\n"
        f"Source: `{value['sourceCommit']}`\n\n"
        "本流水线只编译、签名和发布安装包，未运行测试。"
    )
    if release is None:
        # Keep the release hidden until every public asset is uploaded.
        release = request(API + "/releases", "POST", {
            "tag_name": tag, "target_commitish": value["sourceCommit"],
            "name": "漫流 " + value["versionName"], "body": body,
            "draft": True, "prerelease": False,
        })
    packages, runtime_paths = runtime_assets(DIST / "runtime-assets")
    catalog = DIST / "runtime-catalog.json"
    wrapper = json.loads(catalog.read_text(encoding="utf-8"))
    if wrapper.get("algorithm") != "SHA256withRSA" or not 256 <= len(base64.b64decode(wrapper["signature"], validate=True)) <= 1024:
        raise RuntimeError("Runtime catalog signing envelope is invalid")
    if json.loads(base64.b64decode(wrapper["payload"], validate=True)) != runtime_payload(value, packages):
        raise RuntimeError("Runtime catalog does not match this release's verified component assets")
    existing = {item["name"]: item for item in release.get("assets", [])}
    files = [DIST / f"MangaFlow-{value['versionName']}.apk", DIST / "latest.json", catalog,
             DIST / "SHA256SUMS", *runtime_paths]
    for file in files:
        data = file.read_bytes()
        expected = "sha256:" + hashlib.sha256(data).hexdigest()
        previous = existing.get(file.name)
        if previous:
            if previous.get("digest") != expected or previous["size"] != len(data):
                raise RuntimeError("An existing draft asset has different bytes; it will not be replaced: " + file.name)
            continue
        upload = release["upload_url"].split("{", 1)[0] + "?name=" + urllib.parse.quote(file.name)
        uploaded = request(upload, "POST", data, binary=True, content_type="application/octet-stream")
        if uploaded.get("digest") is None:
            uploaded = request(API + "/releases/assets/" + str(uploaded["id"]))
        if uploaded.get("state") != "uploaded" or uploaded.get("size") != len(data) or uploaded.get("digest") != expected:
            raise RuntimeError("GitHub did not confirm the complete release asset: " + file.name)
    require_current_main(value["sourceCommit"])
    reserve_tag(tag, value["sourceCommit"])
    require_current_main(value["sourceCommit"])
    result = request(API + "/releases/" + str(release["id"]), "PATCH", {"draft": False, "make_latest": "true"})
    if result["draft"] or result["tag_name"] != tag:
        raise RuntimeError("GitHub did not confirm publication")
    require_tag_commit(tag, value["sourceCommit"])
    print(result["html_url"])
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as file:
            file.write(f"## 漫流 {value['versionName']}\n\n[下载 APK]({payload_url(value)}) · [Release]({result['html_url']})\n\n已编译、签名并发布。未运行测试。\n")


def payload_url(value):
    return f"https://github.com/{REPOSITORY}/releases/download/{value['tag']}/MangaFlow-{value['versionName']}.apk"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    modes.add_parser("plan")
    metadata = modes.add_parser("manifest")
    metadata.add_argument("--apk", type=Path, required=True)
    metadata.add_argument("--private-key", type=Path, required=True)
    metadata.add_argument("--runtime-dir", type=Path, required=True)
    modes.add_parser("publish")
    args = parser.parse_args()
    if args.mode == "plan":
        plan()
    elif args.mode == "manifest":
        manifest(args.apk, args.private_key, args.runtime_dir)
    else:
        publish()


if __name__ == "__main__":
    main()
