---
license: other
library_name: onnxruntime
pipeline_tag: image-segmentation
base_model: mayocream/koharu-layout-rfdetr-seg-2xl-1152
datasets:
  - mayocream/manga109-segmentation
language:
  - ja
tags:
  - onnx
  - rfdetr
  - manga
  - comics
  - instance-segmentation
  - object-detection
  - layout-analysis
  - text-detection
---

# KoharuLayout RF-DETR Seg 2XL 1152 — ONNX

Validated FP32 ONNX export of
[`mayocream/koharu-layout-rfdetr-seg-2xl-1152`](https://huggingface.co/mayocream/koharu-layout-rfdetr-seg-2xl-1152).
It predicts bounding boxes and instance masks for manga page layout analysis.

| ID | Class |
|---:|---|
| 0 | `text` |
| 1 | `onomatopoeia` |
| 2 | `bubble` |
| 3 | `panel` |

The model does not perform OCR or reading-order prediction.

## Files

- `rfdetr-seg-2xlarge.onnx` — static-batch FP32 ONNX graph, opset 17
- `onnx_config.json` — tensor, preprocessing, class, and threshold contract
- `onnx_validation.json` — PyTorch/ONNX Runtime parity report

## ONNX Runtime

```bash
pip install huggingface_hub numpy onnxruntime pillow
```

```python
from huggingface_hub import hf_hub_download
from PIL import Image
import numpy as np
import onnxruntime as ort

repo_id = "ShiniShiho/koharu-layout-rfdetr-seg-2xl-1152-onnx"
model_path = hf_hub_download(repo_id=repo_id, filename="rfdetr-seg-2xlarge.onnx")
session = ort.InferenceSession(model_path)

image = Image.open("page.jpg").convert("RGB")
array = np.asarray(
    image.resize((1152, 1152), Image.Resampling.BILINEAR),
    dtype=np.float32,
) / 255.0
array = (array - np.asarray([0.485, 0.456, 0.406], dtype=np.float32)) / np.asarray(
    [0.229, 0.224, 0.225], dtype=np.float32
)
input_tensor = array.transpose(2, 0, 1)[None].astype(np.float32)

boxes_cxcywh, class_logits, mask_logits = session.run(
    ["dets", "labels", "masks"],
    {"input": input_tensor},
)
class_scores = 1.0 / (1.0 + np.exp(-class_logits[..., :4]))
class_ids = class_scores.argmax(axis=-1)
confidences = class_scores.max(axis=-1)
```

The graph has a fixed `float32` NCHW input shape of `[1, 3, 1152, 1152]`.
`dets` contains 300 normalized `cx, cy, width, height` boxes. `labels`
contains four class logits plus a final no-object channel; apply sigmoid to the
first four channels. `masks` contains 300 masks at 288 × 288; resize mask logits
bilinearly to the target image size and threshold at zero.

Recommended confidence thresholds are 0.25 for text, 0.20 for onomatopoeia,
and 0.50 for bubbles and panels.

## Validation

The graph passed ONNX's full model checker and was compared with the source
PyTorch graph on a deterministic input:

- top-class agreement: 100%
- maximum confidence drift: `2.25e-5`
- global thresholded-mask IoU: `0.99979`
- source weights SHA-256: `9bf6d2cbd7793c956d8c857bb1672a396eb7f100eb0682f86830d05e31168efb`
- ONNX SHA-256: `7cc10d4316371946b8441da3512261a8e148b129abcdb0ea6235ed1d1d06d351`

The export uses `rfdetr==1.7.0`, ONNX opset 17, and a static batch size of 1.
See `onnx_validation.json` for environment and raw-output error statistics.

## License and data terms

RF-DETR software is Apache-2.0. The source model was fine-tuned using Manga109
images, which are distributed separately under Manga109's academic-use terms.
This repository does not redistribute Manga109 images. The model therefore
retains the source repository's custom/`other` license designation; users are
responsible for complying with Manga109 and third-party content terms.
