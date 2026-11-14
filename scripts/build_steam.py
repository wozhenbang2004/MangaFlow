#!/usr/bin/env python3
"""Build the pinned Steam bridge for optional Android assets or Windows resources."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import struct
import subprocess
import sys
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
CRATE = ROOT / "native/steam"
TAPLINE = CRATE / "tapline"
PATCH = CRATE / "tapline-bounds.patch"
TAPLINE_SHA = "f54136611519bef50cf31336e0c28a78eb175657"
RUST = "1.88.0"
NDK = "27.2.12479018"
ANDROID_API = 26
VERSION = "0.1.0-f541366"
AAR_NAME = "mangaflow-steam-0.1.0.aar"
LIBRARY = "libmangaflow_steam.so"
LICENSE_NAMES = ("Tapline-MPL-2.0.txt", "Steam-Component-ThirdPartyNotices.txt")
BUILD = CRATE / "build"
LICENSES = ROOT / "app/src/main/assets/licenses"
MAX_LIBRARY = 128 * 1024 * 1024
MAX_LICENSES = 1024 * 1024
ANDROID_TARGETS = (
    ("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android", 2, 183),
    ("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi", 1, 40),
    ("x86", "i686-linux-android", "i686-linux-android", 1, 3),
    ("x86_64", "x86_64-linux-android", "x86_64-linux-android", 2, 62),
)


def run(arguments, *, cwd=ROOT, env=None, capture=False):
    print("+ " + " ".join(str(value) for value in arguments), flush=True)
    return subprocess.run(
        [str(value) for value in arguments], cwd=cwd, env=env,
        check=True, text=True, stdout=subprocess.PIPE if capture else None,
    ).stdout


def digest(data):
    return hashlib.sha256(data).hexdigest()


def prepare_source():
    if not (CRATE / "Cargo.toml").is_file() or not PATCH.is_file():
        raise RuntimeError("Steam bridge source or bounded-download patch is missing")
    head = run(("git", "-C", TAPLINE, "rev-parse", "HEAD"), capture=True).strip()
    if head != TAPLINE_SHA:
        raise RuntimeError("Steam dependency checkout is not the pinned commit")
    changes = run(("git", "-C", TAPLINE, "status", "--porcelain", "--untracked-files=all"), capture=True)
    if changes.strip():
        raise RuntimeError("Steam dependency must be a clean pinned checkout before patching")
    run(("git", "-C", TAPLINE, "apply", "--check", PATCH))
    run(("git", "-C", TAPLINE, "apply", PATCH))
    BUILD.mkdir(parents=True, exist_ok=True)
    # Keep upstream dependency selections; Cargo only adds/adjusts bridge dependencies.
    # generate-lockfile would instead re-resolve every compatible upstream package.
    shutil.copyfile(TAPLINE / "Cargo.lock", CRATE / "Cargo.lock")


def cargo(arguments, env=None, capture=False):
    return run(("cargo", "+" + RUST, *arguments, "--manifest-path", CRATE / "Cargo.toml"),
               env=env, capture=capture)


def build_target(target, env, locked):
    arguments = ["build", "--release", "--lib", "--target", target, "--jobs", "2"]
    if locked:
        arguments.append("--locked")
    cargo(arguments, env)


def library_bytes(path):
    if path.is_symlink() or not path.is_file() or not 20 <= path.stat().st_size <= MAX_LIBRARY:
        raise RuntimeError("Native bridge output is missing or too large: " + str(path))
    return path.read_bytes()


def android_library(path, elf_class, machine):
    data = library_bytes(path)
    if (data[:4] != b"\x7fELF" or data[4] != elf_class or data[5] != 1
            or struct.unpack_from("<H", data, 18)[0] != machine):
        raise RuntimeError("Native bridge ELF architecture does not match " + str(path))
    if elf_class == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        minimum, alignment_offset, alignment_format = 56, 48, "<Q"
    else:
        offset = struct.unpack_from("<I", data, 28)[0]
        entry_size, count = struct.unpack_from("<HH", data, 42)
        minimum, alignment_offset, alignment_format = 32, 28, "<I"
    if entry_size < minimum or count == 0 or offset + entry_size * count > len(data):
        raise RuntimeError("Invalid native ELF program headers")
    loads = 0
    for index in range(count):
        entry = offset + entry_size * index
        if struct.unpack_from("<I", data, entry)[0] == 1:
            loads += 1
            alignment = struct.unpack_from(alignment_format, data, entry + alignment_offset)[0]
            if alignment < 16384 or alignment & (alignment - 1):
                raise RuntimeError("Steam bridge is not aligned for Android 16 KiB pages")
    if loads == 0:
        raise RuntimeError("Native ELF contains no loadable segment")
    return data


def windows_library(path):
    data = library_bytes(path)
    if len(data) < 64 or data[:2] != b"MZ":
        raise RuntimeError("Steam bridge is not a Windows PE library")
    offset = struct.unpack_from("<I", data, 60)[0]
    if offset + 26 > len(data) or data[offset:offset + 4] != b"PE\0\0":
        raise RuntimeError("Invalid Steam bridge PE header")
    machine = struct.unpack_from("<H", data, offset + 4)[0]
    characteristics = struct.unpack_from("<H", data, offset + 22)[0]
    magic = struct.unpack_from("<H", data, offset + 24)[0]
    if machine != 0x8664 or magic != 0x20B or characteristics & 0x2000 == 0:
        raise RuntimeError("Steam bridge is not an x64 Windows DLL")
    return data


def write_member(archive, name, data):
    member = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    member.create_system = 3
    member.external_attr = (stat.S_IFREG | 0o444) << 16
    member.compress_type = ZIP_DEFLATED
    archive.writestr(member, data, compress_type=ZIP_DEFLATED, compresslevel=6)


def dependency_notices():
    metadata = json.loads(cargo(["metadata", "--locked", "--format-version", "1"], capture=True))
    (BUILD / "cargo-metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    shutil.copyfile(CRATE / "Cargo.lock", BUILD / "Cargo.lock")
    tapline_license = ROOT / "third_party/tapline/LICENSE-MPL-2.0.txt"
    if not tapline_license.is_file():
        raise RuntimeError("Pinned tapline license text is missing")
    texts = {}
    packages = []
    for package in sorted(metadata["packages"], key=lambda item: (item["name"], item["version"])):
        directory = Path(package["manifest_path"]).resolve().parent
        expression = package.get("license") or "See bundled license file"
        candidates = []
        if package.get("license_file"):
            declared = Path(package["license_file"])
            declared = declared if declared.is_absolute() else directory / declared
            declared = declared.resolve()
            if not (declared.is_relative_to(directory) or declared.is_relative_to(TAPLINE)):
                raise RuntimeError("Dependency license path leaves its source: " + package["name"])
            candidates.append(declared)
        for pattern in ("LICENSE*", "LICENCE*", "COPYING*", "NOTICE*", "COPYRIGHT*",
                        "license*", "licence*", "copying*", "notice*", "copyright*"):
            candidates.extend(directory.glob(pattern))
        # Some Rust crates, including crc-catalog, ship MIT/Apache texts
        # inside a LICENSES directory rather than a root LICENSE file.
        for folder_name in ("LICENSES", "licenses", "LICENCES"):
            folder = directory / folder_name
            if folder.is_dir() and not folder.is_symlink():
                candidates.extend(path for path in folder.iterdir() if path.is_file() and not path.is_symlink())
        if directory.is_relative_to(TAPLINE):
            candidates.append(TAPLINE / "LICENSE")
        if directory == CRATE:
            candidates.append(tapline_license)
        references = []
        for path in sorted(set(candidates)):
            if not path.is_file() or path.is_symlink():
                continue
            if not 0 < path.stat().st_size <= MAX_LICENSES:
                raise RuntimeError("Dependency license material is empty or too large")
            data = path.read_bytes()
            key = digest(data)
            texts.setdefault(key, data.decode("utf-8"))
            references.append(key)
        if not references:
            raise RuntimeError("Dependency package has no distributable license text: " + package["name"])
        packages.append({
            "name": package["name"], "version": package["version"], "license": expression,
            "source": package.get("source") or ("tapline@" + TAPLINE_SHA if directory.is_relative_to(TAPLINE)
                                               else "MangaFlow Steam bridge"),
            "repository": package.get("repository") or "",
            "licenseTexts": sorted(set(references)),
        })
    heading = ("MangaFlow optional Steam component " + VERSION + "\n"
               "Pinned tapline source: https://github.com/UnhingedSoftware/tapline/tree/" + TAPLINE_SHA + "\n"
               "The bounded-download modification is distributed as native/steam/tapline-bounds.patch.\n"
               "This file records the resolved Cargo dependency graph and includes its license texts.\n\n")
    listing = "\n".join(
        item["name"] + " " + item["version"] + "\nLicense: " + item["license"] +
        "\nSource: " + item["source"] + "\nRepository: " + item["repository"] +
        "\nLicense text SHA256: " + ", ".join(item["licenseTexts"]) + "\n"
        for item in packages)
    full_text = "".join("\n===== License text SHA256 " + key + " =====\n" + text + "\n"
                        for key, text in sorted(texts.items()))
    content = (heading + listing + full_text).encode("utf-8")
    mpl = tapline_license.read_bytes()
    if len(content) + len(mpl) > MAX_LICENSES:
        raise RuntimeError("Steam dependency notices exceed the component license bound")
    LICENSES.mkdir(parents=True, exist_ok=True)
    (LICENSES / LICENSE_NAMES[0]).write_bytes(mpl)
    (LICENSES / LICENSE_NAMES[1]).write_bytes(content)
    (BUILD / LICENSE_NAMES[0]).write_bytes(mpl)
    (BUILD / LICENSE_NAMES[1]).write_bytes(content)
    (BUILD / "dependency-licenses.json").write_text(
        json.dumps(packages, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return [(name, (LICENSES / name).read_bytes()) for name in LICENSE_NAMES]


def write_provenance(platform_name, target_names):
    value = {
        "schema": 1, "id": "STEAM", "version": VERSION, "platform": platform_name,
        "sourceCommit": os.environ.get("GITHUB_SHA", ""),
        "tapline": TAPLINE_SHA, "patchSha256": digest(PATCH.read_bytes()),
        "cargoLockSha256": digest((CRATE / "Cargo.lock").read_bytes()),
        "rustc": run(("rustc", "+" + RUST, "--version"), capture=True).strip(),
        "targets": list(target_names),
    }
    if platform_name == "android":
        value.update(ndk=NDK, api=ANDROID_API, elfPageAlignment=16384)
    (BUILD / "build-provenance.json").write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def build_android(aar_dir):
    if sys.platform != "linux":
        raise RuntimeError("The optional Android component is built on the Linux CI worker")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("ANDROID_HOME is required")
    ndk = Path(sdk) / "ndk" / NDK
    properties = (ndk / "source.properties").read_text(encoding="utf-8")
    if not any(line.strip() == "Pkg.Revision = " + NDK for line in properties.splitlines()):
        raise RuntimeError("Android NDK revision does not match the pinned compiler")
    binaries = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    outputs = []
    for index, (abi, target, clang_target, elf_class, machine) in enumerate(ANDROID_TARGETS):
        compiler = binaries / (clang_target + str(ANDROID_API) + "-clang")
        archiver = binaries / "llvm-ar"
        if not compiler.is_file() or not archiver.is_file():
            raise RuntimeError("Android cross compiler is missing for " + abi)
        env = os.environ.copy()
        env.pop("CARGO_ENCODED_RUSTFLAGS", None)
        key = target.replace("-", "_")
        env["CC_" + key] = str(compiler)
        env["AR_" + key] = str(archiver)
        env["CARGO_TARGET_" + key.upper() + "_LINKER"] = str(compiler)
        env["RUSTFLAGS"] = (env.get("RUSTFLAGS", "") +
                            " -C link-arg=-Wl,-z,max-page-size=16384").strip()
        build_target(target, env, locked=index > 0)
        output = CRATE / "target" / target / "release" / LIBRARY
        outputs.append((abi, android_library(output, elf_class, machine)))
    licenses = dependency_notices()
    aar_dir.mkdir(parents=True, exist_ok=True)
    destination = aar_dir / AAR_NAME
    temporary = destination.with_suffix(".aar.tmp")
    manifest = (b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                b'package="dev.mangaflow.steam"><uses-sdk android:minSdkVersion="26"/></manifest>')
    try:
        with ZipFile(temporary, "w") as archive:
            write_member(archive, "AndroidManifest.xml", manifest)
            for abi, data in outputs:
                write_member(archive, "jni/" + abi + "/" + LIBRARY, data)
            for name, data in licenses:
                write_member(archive, "LICENSES/" + name, data)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    write_provenance("android", (row[1] for row in ANDROID_TARGETS))
    print("Prepared optional JNI AAR: " + str(destination))


def build_windows():
    if sys.platform != "win32":
        raise RuntimeError("The Windows bridge is built on the Windows CI worker")
    target = "x86_64-pc-windows-msvc"
    env = os.environ.copy()
    env.pop("CARGO_ENCODED_RUSTFLAGS", None)
    env["RUSTFLAGS"] = (env.get("RUSTFLAGS", "") + " -C target-feature=+crt-static").strip()
    build_target(target, env, locked=False)
    output = CRATE / "target" / target / "release" / "mangaflow_steam.dll"
    data = windows_library(output)
    licenses = dependency_notices()
    destination = ROOT / "shared/src/desktopMain/resources/steam/windows-x64"
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "mangaflow_steam.dll").write_bytes(data)
    for name, content in licenses:
        (destination / name).write_bytes(content)
    write_provenance("windows-x64", (target,))
    print("Prepared Windows JNI resource: " + str(destination))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("android", "windows"), required=True)
    parser.add_argument("--aar-dir", type=Path, default=ROOT / "app/build/outputs/inference-aars")
    args = parser.parse_args()
    prepare_source()
    if args.platform == "android":
        build_android(args.aar_dir)
    else:
        build_windows()


if __name__ == "__main__":
    main()
