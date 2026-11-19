# Enhancement engines: implementation and source evidence

Updated: 2026-11-19 UTC. This record covers the engine adapters delivered with the
Enhancement Center work. It is source-level evidence, **not a Windows/Android
device qualification or a claim that the complete V1.0 acceptance has passed**.
No local build, benchmark, test, executable installation, media conversion or
device probe was run during this work, following the current project workflow.

## Implemented platform contracts

`shared/src/commonMain/kotlin/dev/mangaflow/enhancement/engine/EnhancementEngine.kt`
defines one application-owned `EnhancementEngine`: `inspect`, `install`,
`importModel`, `describeImage`, `processImage`, `processVideo` and `close`.
It does not create another GPU priority scheduler. The Enhancement Center owns
task admission through the existing experience policy and shared scheduler.

An engine's installation state, a user's requested quality, and successful
qualification on the current device are separate facts. Installed external
engines report `READY_FOR_TRIAL`, never a fabricated device-verified state.
Model identity is the SHA-256 of the installed weight bytes. User imports retain
their source name and declared scale/content; the first actual processing call
must validate the real input/output shape and output geometry. Importing a file
alone does not prove model compatibility or quality.

| Path | Implemented operation | Qualification still required |
| --- | --- | --- |
| Android / Windows local detail | Original 1× conservative edge-preserving denoise; streamed PNG output | Text, screentone and contour appearance on actual pages |
| Android ONNX | Existing signed ONNX Runtime component, CPU FP32 NCHW image inference, overlapping tiles and streamed output | Actual model compatibility, memory/thermal behavior, seam and text review |
| Windows MangaJaNaiCLI | Pinned optional executable, official component setup, explicit content-specific model, real 1× repair / native 2× or 4× output | GPU backend execution and visual quality on Windows hardware |
| Windows VideoJaNai | Pinned optional executable, actual super-resolution and/or RIFE, segment checkpoints, original stream remux and output validation | Hardware encoder/GPU execution, A/V sync, representative media and long task recovery |
| Existing Anime4K S/S and M/M | Catalog entries for the actual built-in 1× restore / 2× restore-and-upscale paths | Platform shader execution and sustained device measurements |
| Windows high-quality realtime | Explicitly unavailable in this integration | Bundled player/native filter integration and device qualification |
| Android Vulkan/ncnn RIFE | Explicitly unavailable in this integration | Native library/model packaging, Media3 frame ownership, timestamps and device qualification |
| Online prepared video switching | No engine backend supplied by this batch | Legal/cache capability checks, keyframes, safe timeline splice and player integration |

The local algorithm is not neural super-resolution. Its version is `1.0.0` and
its algorithm-identity digest is
`a9241c52aac7eecfb6b4ced9fdd587004da45864954d528047406ce3aa09b620`.
The same constants are used by the cache key and `EngineOutput`. This is an
algorithm descriptor digest, not an invented model-weight digest.

## Fixed distributions and licenses

Archive sizes and digests were read from the official release asset records.
The installer checks both the exact byte count and SHA-256 before extraction;
it preserves the whole upstream archive, including its license notices.
The application does not bundle or rehost these executables or model weights.

| Component | Fixed upstream identity | Archive bytes | Archive SHA-256 |
| --- | --- | ---: | --- |
| MangaJaNaiCLI Windows x64 | `v0.9.3`, source `901322ae449c6fea82427293ab702aaa7a762dd1` | 33,610,970 | `c1c5396945557fdea0263510a693ed3e976602a7c93ea66a33491463dc747ae5` |
| VideoJaNai Windows x64 CLI | `2.14.1`, source `d57f2575a7e53e51a1b0778f307c89f0c4ebcd43` | 170,908,359 | `a362daaaaf380cd70b65cfcbd86449f03fde8e606bc251513eaae699747389ec` |
| Gyan FFmpeg Windows shared build | `9.0.2`, `ffmpeg-9.0.2-full_build-shared.zip` | 99,899,672 | `8d31e162f1616e37aab3fa2db991b97e1b8dbeb1c8465fd81a81be16ff91b328` |

Release sources:

- [MangaJaNaiCLI v0.9.3](https://github.com/the-database/MangaJaNaiCLI/releases/tag/v0.9.3), [fixed README and source](https://github.com/the-database/MangaJaNaiCLI/tree/901322ae449c6fea82427293ab702aaa7a762dd1).
- [VideoJaNai 2.14.1](https://github.com/the-database/VideoJaNai/releases/tag/2.14.1), [fixed source](https://github.com/the-database/VideoJaNai/tree/d57f2575a7e53e51a1b0778f307c89f0c4ebcd43).
- [Gyan FFmpeg 9.0.2 release](https://github.com/GyanD/codexffmpeg/releases/tag/9.0.2), [FFmpeg licensing](https://ffmpeg.org/legal.html). This is an optional external full build; it is not described as an exclusively LGPL package.
- [MangaJaNai model license](https://github.com/the-database/MangaJaNai/blob/main/LICENSE): CC BY-NC 4.0, distinct from GPL-3.0 CLI code. Individual model packages, native runtimes and GPU vendor components retain their own terms.
- [RIFE upstream MIT license](https://github.com/hzwer/ECCV2022-RIFE/blob/main/LICENSE) and [rife-ncnn-vulkan MIT license](https://github.com/nihui/rife-ncnn-vulkan/blob/master/LICENSE) apply to those projects. They are not a blanket statement about every converted model or bundled vendor component.

The pinned CLIs' own `setup --progress lines` downloads GPU-dependent native
runtimes and model packages using their official setup manifests. MangaFlow
records the actual installed weight hashes rather than assigning a hash from a
model name. Setup can download substantially more than the small initial CLI
archives; the UI does not present those archive sizes as total installation size.
Installation remains an explicit user action.

The official RIFE package referenced by VideoJaNai is
[models-rife-fp16-2](https://github.com/the-database/animejanai-inference/releases/tag/models-rife-fp16-2),
asset `rife-fp16-2.7z`, 322,128,549 bytes, SHA-256
`85fa8aa7408f82636f53a3179998e021708746ff85c7468ae11a0d3157af1451`.
This is evidence for the available model family, not a substitute for checking
the selected installed `.onnx` file before a task starts.

## Actual JaNai interfaces and choices

The official [MangaJaNaiCLI README](https://github.com/the-database/MangaJaNaiCLI/blob/901322ae449c6fea82427293ab702aaa7a762dd1/README.md),
its `cli.rs`, `run.rs`, `progress.rs` and `setup.rs` were inspected. The adapter
uses argument arrays, not a shell:

```text
mangajanai --no-config <actual-image-file> -o <private-output-directory>
  --model <selected-weights> --format png --filename result --scale <1|2|4>
  --tile <bounded-size> --overlap <overlap> --workers 1 --streams 1 --progress lines
```

The source is copied unchanged into the task's private folder with an extension
derived from the actual decoder format. This is necessary because reader caches
legitimately use opaque filenames while the CLI classifies input by extension.
The original file is never handed to the CLI as an output destination.
Black-and-white pages select MangaJaNai models; color/webtoon pages select
IllustrationJaNai models or an explicitly imported matching model. Sampled color
and dimensions provide a reversible initial content classification, not a claim
that one model suits all art. Windows WebP uses the installed provider's real
crop/scaling options, since that provider ignores ordinary ImageIO region hints.

The upstream CLI supports TensorRT, TensorRT-RTX and Windows DirectML. Its
README states that supported models can use native GPU kernels on RTX 30 series
and newer. The application only reports the backend messages emitted by the
actual running CLI. Upstream RTX 5090 examples are not RTX 5060 measurements and
are not reused as local throughput estimates.

The official [VideoJaNai CLI source](https://github.com/the-database/VideoJaNai/tree/d57f2575a7e53e51a1b0778f307c89f0c4ebcd43/VideoJaNai.Cli)
and core command/progress/encode code were inspected. The adapter uses
`--no-config`, `--stop-on-damage`, the selected hardware encoder, one or two
explicit `--model` arguments, or `--no-upscale` for pure interpolation. A native
2× model can execute twice for 4×; this is recorded as a composition, not called
a native 4× network. RIFE receives a rational `--rife-factor`, the actual model
version and ensemble choice, plus `--rife-after-upscale` when upscaling is used.

| Interpolation quality intent | Required installed file |
| --- | --- |
| Light | `rife_v4.25_lite.onnx` |
| Balanced | `rife_v4.22.onnx` |
| Fine | `rife_v4.22_ensemble.onnx` |
| Ultra | `rife_v4.26_ensemble.onnx` |

Missing exact weights produce an explicit unavailable result; no cheaper RIFE
model is relabeled. `frameMultiplier=2` is resolved from actual ffprobe source
FPS, including fractional rates. `scale=1, model=null` is a valid RIFE-only
task. The pipeline digest binds both the selected super-resolution and RIFE
weight digests. Availability of only RIFE weights does not require an unrelated
super-resolution model.

Scene-cut protection is mandatory. The upstream `aji_trt.cpp` compares measured
inter-frame luma difference with the configured threshold: threshold zero does
not mean detection off. The integration passes `0.15`. This source inspection
does not establish that all difficult cuts, subtitles or animation artifacts
are handled perfectly; actual visual acceptance is still required.

## Offline publication, timestamps and recovery

Only completed, leased original downloads are admitted by the owner. The engine
checks source/model identity and produces a separate Matroska asset. Optional
keyframe-based segments have identity-bound directories and completed-output
SHA-256 receipts; cancellation preserves completed segments. Restart reruns a
partial segment instead of trusting an incomplete file. Native process
cancellation terminates the task-owned process tree before returning.

After enhancement, FFmpeg explicitly remuxes audio, subtitles, supported
attachments, flat chapters and metadata from the unchanged original using stream
copy. The final output is checked for dimensions, actual FPS, video duration and
start time, per-audio/subtitle identity/metadata/start time, attachments, chapter
times and tags. Every output video frame is decoded by FFmpeg before publication
to reject truncated or corrupt output. Video track tags are mapped explicitly.
The source stamp/hash and selected weights are rechecked before publication.

The current boundary is one ordinary SDR video track with readable timestamps.
Multiple video tracks, data/unknown streams, cover video tracks, HDR/Dolby/rotated
features and unpreserved metadata fail explicitly. Complex edition/interactive
metadata, arbitrary variable-frame-rate preservation and unusual container
features have not been qualified and must not be advertised as supported.
Stream identity checks are not claims of independent perceptual A/V-sync
verification; that remains a real-media/device acceptance item.

Engine outputs are caller-owned temporary files. A new destination is created
exclusively, never replacing an existing original or enhanced asset. The cache
owner performs the final manifest publication after its task/session/source
guards and retains file leases. Original and adult-content partition identities
come from that owner; the platform adapter does not merge shelves, histories or
cache namespaces.

## Android implementation and native research boundary

Android reuses `PanelRuntimeStore`'s signed per-ABI ONNX Runtime 1.31.1 component
and existing [upstream Java API](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OnnxTensor.html).
The implemented path is explicitly CPU inference: one retained FP32 NCHW
single-input/single-output image session, true output-shape validation, bounded
overlap tiles, color-aware model selection, sRGB decode and row-streamed PNG.
There is no claimed NNAPI/Vulkan speedup. Android picker `content:` URIs are read
through the granted ContentResolver access into private model storage; no guessed
filesystem path or broad storage permission is required.

The [official libaji repository](https://github.com/the-database/animejanai-inference)
documents a C image ABI (`aji_img_create`, `aji_img_load`, image processing and
model information), retained model resources and separate video/RIFE integration.
Its Vulkan backend was marked in development in the inspected header. No stable
Android release/model compatibility/device evidence was established. Its source
is not copied into MangaFlow or described as licensed solely by another JaNai
repository's license.

[rife-ncnn-vulkan](https://github.com/nihui/rife-ncnn-vulkan) is a genuine native
candidate, not an Android Media3 drop-in. Packaging a library alone does not
implement MediaCodec texture import, frame-pair lifetime, scene boundaries,
timestamp interpolation, audio scheduling, flush/seek invalidation, memory/heat
admission or a real presentation counter. Those missing pieces remain OPEN.

The current desktop MediaMP 0.5.1 libmpv package does not include the custom
`vf_animejanai` native filter supplied by the JaNai mpv integration. Therefore
setting a filter string cannot make high-quality realtime enhancement/RIFE
available. A separately built, licensed and device-qualified player integration
is still required. Offline processing is the implemented path for those models.

## Measurement semantics and remaining acceptance

- `EngineOutput.elapsedMillis` is whole-task wall time. It is never GPU execution
  time and never a frame-presentation timestamp. CLI frame deltas provide actual
  completed-frame throughput only. Missing GPU timing remains null.
- Android Anime4K timer-query observations, CPU submission observations and final
  presentation observations are owned by the player telemetry path, not inferred
  from these engine calls. A query completion-observed timestamp is not an exact
  GPU hardware timestamp.
- No successful hardware/model inference, sustained thermal stability, seam-free
  output, RIFE text/motion quality, 4K/60/120 FPS performance or player transition
  has been observed in this environment. CI compilation cannot close those items.
- Explicit remaining engine deliverables: Windows realtime JaNai/RIFE, Android
  native Vulkan/ncnn RIFE and high-quality video inference, online enhanced-segment
  switching, and hardware-specific long-running qualification.

The integration supplies real executable paths for the implemented operations
and explicit unavailable states elsewhere. It does not relabel Anime4K M as
Ultra, manufacture weights, substitute estimated throughput for presentation FPS,
or claim the entire V1.0 device acceptance is complete.
