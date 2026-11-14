#!/usr/bin/env python3
"""Package pinned native engines as optional, architecture-specific release assets."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import stat
import struct
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
LICENSES = ROOT / "app/src/main/assets/licenses"
INDEX_NAME = ".runtime-index.json"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
SPECS = (
    {
        "id": "LITERT", "version": "1.4.2", "aar": "litert-1.4.2.aar",
        "libraries": ("libtensorflowlite_jni.so",),
        "licenses": ("LiteRT-APACHE-2.0.txt",),
    },
    {
        "id": "ONNX", "version": "1.31.1", "aar": "onnxruntime-android-1.31.1.aar",
        "libraries": ("libonnxruntime.so", "libonnxruntime4j_jni.so"),
        "licenses": ("ONNX-Runtime-MIT.txt", "ONNX-Runtime-ThirdPartyNotices.txt"),
    },
    {
        "id": "QUICKJS", "version": "1.0.18", "aar": "quickjs-kt-android-1.0.18.aar",
        "libraries": ("libquickjs.so",),
        "licenses": ("QuickJS-kt-APACHE-2.0.txt", "QuickJS-MIT.txt", "c-vector-MIT.txt"),
    },
    {
        "id": "STEAM", "version": "0.1.1-f541366", "aar": "mangaflow-steam-0.1.1.aar",
        "libraries": ("libmangaflow_steam.so",),
        "licenses": ("Tapline-MPL-2.0.txt", "Steam-Component-ThirdPartyNotices.txt"),
    },
)
LIBRARY_NAMES = {name for spec in SPECS for name in spec["libraries"]}
MAX_LIBRARY_BYTES = 128 * 1024 * 1024
MAX_LICENSE_BYTES = 1024 * 1024
MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
ELF_IDENTITIES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def archive_name(spec, abi):
    return f"runtime-{spec['id'].lower()}-{spec['version']}-{abi}.zip"


def require_small_apk(apk):
    """Inspect packaging only; never install or execute the APK."""
    with ZipFile(apk) as archive:
        bundled = [entry.filename for entry in archive.infolist()
                   if entry.filename.rsplit("/", 1)[-1] in LIBRARY_NAMES]
        models = [entry.filename for entry in archive.infolist()
                  if entry.filename.lower().endswith((".onnx", ".tflite", ".safetensors", ".pt"))]
    if bundled:
        raise RuntimeError("Optional native libraries are still bundled in the APK: " + ", ".join(bundled))
    if models:
        raise RuntimeError("Optional model weights are still bundled in the APK: " + ", ".join(models))
    print(f"Confirmed optional native libraries and model weights are absent from {apk.name}")


def require_elf(data, abi, name):
    expected_class, expected_machine = ELF_IDENTITIES[abi]
    if (not 20 <= len(data) <= MAX_LIBRARY_BYTES or data[:4] != b"\x7fELF" or
            data[4] != expected_class or data[5] != 1 or struct.unpack_from("<H", data, 18)[0] != expected_machine):
        raise RuntimeError(f"Invalid {abi} native library: {name}")


def write_member(archive, name, data):
    member = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    member.create_system = 3
    member.external_attr = (stat.S_IFREG | 0o444) << 16
    member.compress_type = ZIP_DEFLATED
    archive.writestr(member, data, compress_type=ZIP_DEFLATED, compresslevel=6)


def package(aar_dir, output, apks=()):
    for apk in apks:
        require_small_apk(apk)
    output.mkdir(parents=True, exist_ok=True)
    packages = []
    for spec in SPECS:
        aar = aar_dir / spec["aar"]
        if not aar.is_file():
            raise RuntimeError("Missing pinned runtime AAR: " + str(aar))
        license_members = [("LICENSES/" + name, (LICENSES / name).read_bytes()) for name in spec["licenses"]]
        if sum(len(data) for _, data in license_members) > MAX_LICENSE_BYTES or any(not data for _, data in license_members):
            raise RuntimeError("Runtime license materials are missing or too large")
        with ZipFile(aar) as source:
            source_entries = source.infolist()
            for abi in ABIS:
                name = archive_name(spec, abi)
                destination = output / name
                temporary = output / (name + ".tmp")
                libraries = []
                try:
                    with ZipFile(temporary, "w") as target:
                        for library in spec["libraries"]:
                            path = f"jni/{abi}/{library}"
                            matches = [entry for entry in source_entries if entry.filename == path]
                            if len(matches) != 1 or matches[0].is_dir() or stat.S_ISLNK(matches[0].external_attr >> 16):
                                raise RuntimeError("Missing or ambiguous native library in pinned AAR: " + path)
                            if not 20 <= matches[0].file_size <= MAX_LIBRARY_BYTES:
                                raise RuntimeError("Native library size is invalid: " + path)
                            data = source.read(matches[0])
                            require_elf(data, abi, library)
                            write_member(target, library, data)
                            libraries.append({"name": library, "bytes": len(data), "sha256": digest(data)})
                        for path, data in license_members:
                            write_member(target, path, data)
                    temporary.replace(destination)
                finally:
                    temporary.unlink(missing_ok=True)
                size = destination.stat().st_size
                if not 0 < size <= MAX_ARCHIVE_BYTES:
                    raise RuntimeError("Runtime archive size is invalid: " + name)
                packages.append({
                    "id": spec["id"], "version": spec["version"], "abi": abi,
                    "archive": {"name": name, "bytes": size, "sha256": digest(destination.read_bytes())},
                    "libraries": libraries,
                })
    index = {"schema": 1, "runtimes": packages}
    (output / INDEX_NAME).write_text(json.dumps(index, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Prepared {len(packages)} optional runtime packages in {output}")


def runtime_assets(directory):
    """Read the exact packaging output before signing or uploading its immutable assets."""
    index = json.loads((directory / INDEX_NAME).read_text(encoding="utf-8"))
    if index.get("schema") != 1 or not isinstance(index.get("runtimes"), list):
        raise RuntimeError("Invalid runtime package index")
    expected = {(spec["id"], spec["version"], abi): spec for spec in SPECS for abi in ABIS}
    packages = index["runtimes"]
    if len(packages) != len(expected):
        raise RuntimeError("Runtime index must include every pinned runtime and supported ABI")
    paths = []
    seen = set()
    for item in packages:
        identity = (item.get("id"), item.get("version"), item.get("abi"))
        spec = expected.get(identity)
        if spec is None or identity in seen:
            raise RuntimeError("Unexpected or duplicate runtime identity")
        seen.add(identity)
        archive = item.get("archive") or {}
        name = archive_name(spec, item["abi"])
        if archive.get("name") != name or not re.fullmatch(r"[a-f0-9]{64}", archive.get("sha256", "")):
            raise RuntimeError("Invalid runtime archive identity")
        path = directory / name
        data = path.read_bytes()
        if not 0 < len(data) <= MAX_ARCHIVE_BYTES or archive.get("bytes") != len(data) or archive["sha256"] != digest(data):
            raise RuntimeError("Runtime archive does not match its declared digest: " + name)
        libraries = item.get("libraries")
        if not isinstance(libraries, list) or [entry.get("name") for entry in libraries] != list(spec["libraries"]):
            raise RuntimeError("Runtime native member list does not match the pinned SDK")
        with ZipFile(path) as bundle:
            entries = bundle.infolist()
            expected_names = set(spec["libraries"]) | {"LICENSES/" + name for name in spec["licenses"]}
            if len(entries) != len(expected_names) or {entry.filename for entry in entries} != expected_names:
                raise RuntimeError("Runtime archive contains unexpected or duplicate members")
            for library in libraries:
                member = bundle.getinfo(library["name"])
                if not 20 <= member.file_size <= MAX_LIBRARY_BYTES or member.is_dir() or stat.S_ISLNK(member.external_attr >> 16):
                    raise RuntimeError("Invalid runtime library member")
                content = bundle.read(member)
                require_elf(content, item["abi"], member.filename)
                if library.get("bytes") != len(content) or library.get("sha256") != digest(content):
                    raise RuntimeError("Runtime native member digest changed")
            for license_name in spec["licenses"]:
                member = bundle.getinfo("LICENSES/" + license_name)
                original = (LICENSES / license_name).read_bytes()
                if (not 0 < member.file_size <= MAX_LICENSE_BYTES or member.is_dir() or
                        stat.S_ISLNK(member.external_attr >> 16) or bundle.read(member) != original):
                    raise RuntimeError("Runtime license material changed")
        paths.append(path)
    return packages, paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apk", type=Path, action="append", default=[])
    args = parser.parse_args()
    package(args.aar_dir, args.output, args.apk)


if __name__ == "__main__":
    main()
