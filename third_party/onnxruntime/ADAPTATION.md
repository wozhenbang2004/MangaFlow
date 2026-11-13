# Optional Android ONNX Runtime loader

`app/src/main/java/ai/onnxruntime/OnnxRuntime.java` adapts the actual upstream
Java loader from `microsoft/onnxruntime` **v1.31.1**:

- Source: https://github.com/microsoft/onnxruntime/blob/v1.31.1/java/src/main/java/ai/onnxruntime/OnnxRuntime.java
- Original Git blob: `ccbf89b592567ce1cbbae0b984192a55876387b8`.
- Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
- MIT; the original header remains in the adapted source. The complete license
  remains in `app/src/main/assets/licenses/ONNX-Runtime-MIT.txt`, with upstream
  dependency notices in `ONNX-Runtime-ThirdPartyNotices.txt`.

The original Java API and ABI remain pinned to ONNX Runtime 1.31.1. The build
must exclude the original dependency's `ai/onnxruntime/OnnxRuntime*.class` entries
and compile this source into the APK once. Other original API classes stay in
the APK. There is no downloaded DEX/JAR and no custom or reflective ClassLoader.

## Exact adaptation

Upstream `load(String)` returns immediately on Android after
`System.loadLibrary(library)`. Its `onnxruntime.native.path`, per-library `.path`
and `.skip` properties are checked only after that Android return, so those
desktop controls do not support app-private Android downloads. Upstream Android
initialization also skips an explicit core-library load before loading JNI.

Only two loading decisions change:

1. Initialization loads `onnxruntime` before `onnxruntime4j_jni` on Android as
   well as on the existing desktop path.
2. The Android branch reads the single application-owned property
   **`mangaflow.onnx.native.dir`**. This must be an absolute directory. Each
   mapped library path is canonicalized and must be a readable regular file,
   non-writable, with its canonical parent equal to that directory. The branch
   calls `System.load` on that absolute path. Missing or invalid configuration
   fails; there is no Android fallback to arbitrary library search paths.

Every other upstream API version, provider, native method, error and desktop
loading path is retained. The source preserves the ORT API-31 initialization
required by the matching 1.31.1 native binaries.

## Loading contract with PanelRuntimeStore

The property is a handoff, not an integrity mechanism. Before publishing it,
`PanelRuntimeStore` owns APK-certificate signature validation of the immutable
runtime catalog, application/runtime version binding, current ABI selection,
complete file-length and SHA-256 checks, bounded extraction into private storage,
atomic installation and read-only file permissions. No unverified bytes may reach
`System.load` through this loader.

`ensureLoaded(ONNX)` loads the verified canonical `libonnxruntime.so`, then
`libonnxruntime4j_jni.so`, from the normal application ClassLoader. It then sets
`System.setProperty("mangaflow.onnx.native.dir", verifiedDirectory.canonicalPath)`.
Subsequent ORT Java initialization uses this adapted loader and the identical
canonical paths in that same application ClassLoader. It does not depend on
Android's version-specific bare-SONAME lookup behavior.

All ORT API initialization must be gated behind that successful store operation.
An attempted partial native load cannot be unloaded safely by Java. The store
retains immutable files and blocks replacement/deletion after any loading attempt
until process restart. Existing session closure and cancellation wait for native
inference to finish; they do not unload the process's shared libraries.

## LiteRT 1.4.2 comparison

LiteRT uses its original Java loader. The exact official
`litert-api-1.4.2.aar` `classes.jar` was inspected statically. Its
`TensorFlowLite.<clinit>` catches missing default/stable/GMS library-name loads
and records the exception. `TensorFlowLite.init()` calls `nativeDoNothing()` and
sets `isInit` on success; a recorded name-load error alone does not reject an
already loaded JNI implementation. There is no explicit private-directory
property in that loader.

`ensureLoaded(LITERT)` therefore loads the verified, read-only canonical
`libtensorflowlite_jni.so` with `System.load` from the same application
ClassLoader, then calls `TensorFlowLite.init()`. Application-only Interpreter
selection remains unchanged. Current source with the same loader mechanism:
https://github.com/google-ai-edge/LiteRT/blob/19c67cbdb91f1fe49d0edb4fc85e5da59ef03b29/tflite/java/src/main/java/org/tensorflow/lite/TensorFlowLite.java

The published LiteRT 1.4.2 source JAR is a placeholder containing a README; it
was not used as evidence that it includes those Java sources. The exact AAR
bytecode and the above actual upstream source are separate evidence.

## Evidence boundary

The source/API and exact LiteRT class structure were read without executing
native libraries, models, Gradle, device operations or tests. Android 8/API 26's
`Runtime.loadLibrary0` source was also read: a missing `findLibrary` result throws
before attempting the native load, which makes private absolute loading necessary
for the supported minimum SDK. Static integration is not device acceptance.

Platform source:
https://android.googlesource.com/platform/libcore/+/android-8.0.0_r1/ojluni/src/main/java/java/lang/Runtime.java
