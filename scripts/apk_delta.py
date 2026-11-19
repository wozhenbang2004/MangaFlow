"""Build byte-exact APK deltas from unchanged compressed ZIP entries."""

import gzip
import hashlib
import io
import re
import struct
import urllib.request
from zipfile import BadZipFile, ZipFile


MAX_APK_BYTES = 32 * 1024 * 1024
BASE_COUNT = 8


def compressed_entries(apk):
    with ZipFile(io.BytesIO(apk)) as archive:
        for entry in archive.infolist():
            if entry.compress_size < 64:
                continue
            header = entry.header_offset
            if apk[header:header + 4] != b"PK\x03\x04":
                raise ValueError("APK entry has no local ZIP header")
            name_size, extra_size = struct.unpack_from("<HH", apk, header + 26)
            offset = header + 30 + name_size + extra_size
            end = offset + entry.compress_size
            if end > len(apk):
                raise ValueError("APK entry extends outside the archive")
            yield entry.filename, offset, end


def delta_bytes(base, target, target_entries):
    base_view, target_view = memoryview(base), memoryview(target)
    entries = {name: (start, end) for name, start, end in compressed_entries(base)}
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb", mtime=0) as patch:
        patch.write(b"MFD1")
        position = 0
        # The literal ranges also carry ZIP headers, the signing block and the central directory.
        for name, start, end in target_entries:
            previous = entries.get(name)
            if previous is None or start < position:
                continue
            old_start, old_end = previous
            if base_view[old_start:old_end] != target_view[start:end]:
                continue
            if start > position:
                patch.write(struct.pack(">BI", 0, start - position))
                patch.write(target_view[position:start])
            patch.write(struct.pack(">BII", 1, end - start, old_start))
            position = end
        if position < len(target):
            patch.write(struct.pack(">BI", 0, len(target) - position))
            patch.write(target_view[position:])
    return output.getvalue()


def build_deltas(releases, target, destination, repository, tag):
    if target.stat().st_size > MAX_APK_BYTES:
        return []
    target_data = target.read_bytes()
    target_entries = sorted(compressed_entries(target_data), key=lambda item: item[1])
    release_root = f"https://github.com/{repository}/releases/download/"
    version = int(tag.rsplit(".", 1)[1])
    candidates = []
    for release in releases:
        base_tag = release["tag_name"]
        if release["draft"] or release["prerelease"] or not re.fullmatch(r"v0\.1\.[0-9]+", base_tag):
            continue
        base = int(base_tag.rsplit(".", 1)[1])
        if base < version:
            candidates.append((base, base_tag, release["assets"]))

    deltas = []
    for base, base_tag, assets in sorted(candidates, key=lambda item: item[0], reverse=True)[:BASE_COUNT]:
        name = f"MangaFlow-{base_tag[1:]}.apk"
        asset = next((item for item in assets if item["name"] == name), None)
        if not asset or not re.fullmatch(r"sha256:[a-f0-9]{64}", asset.get("digest") or ""):
            continue
        size = asset["size"]
        if not 0 < size <= MAX_APK_BYTES:
            continue
        request = urllib.request.Request(release_root + base_tag + "/" + name,
                                         headers={"User-Agent": "MangaFlow-Release"})
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                base_data = response.read(size + 1)
        except OSError as failure:
            print(f"::warning::Could not retrieve Android delta base {base_tag}: {failure}")
            continue
        base_digest = hashlib.sha256(base_data).hexdigest()
        if len(base_data) != size or "sha256:" + base_digest != asset["digest"]:
            print(f"::warning::Android delta base {base_tag} did not match its published digest")
            continue
        try:
            patch = delta_bytes(base_data, target_data, target_entries)
        except (BadZipFile, ValueError, struct.error) as failure:
            print(f"::warning::Could not build the Android delta from {base_tag}: {failure}")
            continue
        if len(patch) * 5 >= len(target_data) * 4:
            continue
        patch_name = f"MangaFlow-{base_tag[1:]}-to-{tag[1:]}.apk.delta"
        (destination / patch_name).write_bytes(patch)
        deltas.append({"baseVersionCode": 10_000 + base, "baseSha256": base_digest,
                       "format": "mfd1", "url": release_root + tag + "/" + patch_name,
                       "bytes": len(patch), "sha256": hashlib.sha256(patch).hexdigest()})
        print(f"Prepared Android delta from {base_tag}: {len(patch):,} of {len(target_data):,} APK bytes")
    return deltas
