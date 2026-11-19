#!/usr/bin/env python3
"""Build the signed-catalog Real-CUGAN Android runtime input. This does not modify the base APK."""

import os
from pathlib import Path
import subprocess
from zipfile import ZIP_DEFLATED, ZipFile

from package_runtimes import ABIS, require_elf

ROOT = Path(__file__).resolve().parents[1]
# The pinned upstream ncnn/glslang archive uses the r30 libc++ ABI.
NDK_VERSION = "30.0.16248370"
CMAKE_VERSION = "3.22.1"
LIBRARY = "libmangaflow_cugan.so"


def build():
    sdk = Path(os.environ["ANDROID_HOME"]).resolve()
    ndk = sdk / "ndk" / NDK_VERSION
    tools = sdk / "cmake" / CMAKE_VERSION / "bin"
    cmake = tools / "cmake"
    strip = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    source = ROOT / "native/enhancement"
    scratch = source / "build"
    destination = ROOT / "app/build/outputs/inference-aars/mangaflow-cugan-1.2.0.aar"
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".tmp")
    try:
        with ZipFile(temporary, "w", ZIP_DEFLATED) as artifact:
            for abi in ABIS:
                output = scratch / abi
                subprocess.run([
                    cmake, "-S", source, "-B", output, "-G", "Ninja",
                    f"-DCMAKE_MAKE_PROGRAM={tools / 'ninja'}",
                    f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
                    f"-DFETCHCONTENT_BASE_DIR={scratch / 'dependencies'}",
                    f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-26",
                    "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=Release",
                ], check=True)
                subprocess.run([cmake, "--build", output, "--parallel", "2", "--target", "mangaflow_cugan"], check=True)
                library = output / LIBRARY
                staged = output / "stripped" / LIBRARY
                staged.parent.mkdir(exist_ok=True)
                subprocess.run([strip, "--strip-unneeded", "-o", staged, library], check=True)
                data = staged.read_bytes()
                require_elf(data, abi, LIBRARY)
                artifact.writestr(f"jni/{abi}/{LIBRARY}", data)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    build()
