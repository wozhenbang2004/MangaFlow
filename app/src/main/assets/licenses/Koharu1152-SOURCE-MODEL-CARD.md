---
license: other
library_name: rfdetr
pipeline_tag: image-segmentation
datasets:
  - mayocream/manga109-segmentation
language:
  - ja
tags:
  - manga
  - comics
  - rf-detr
  - instance-segmentation
  - object-detection
  - layout-analysis
  - text-detection
---

# KoharuLayout-RFDETR-Seg-2XL-1152

KoharuLayout is a high-resolution RF-DETR Seg 2XL model for manga page layout
analysis. It predicts bounding boxes and instance masks for four classes:

| ID | Class | Meaning |
|---:|---|---|
| 0 | `text` | Dialogue, captions, titles, credits, and other non-COO text |
| 1 | `onomatopoeia` | Comic onomatopoeia and sound effects (COO) |
| 2 | `bubble` | Speech and text balloons |
| 3 | `panel` | Manga panels and frames |

The model does **not** perform OCR or reading-order prediction.

## Files

- `model.safetensors` — RF-DETR inference weights (SafeTensors only)
- `load_model.py` — strict RF-DETR Seg 2XL loader
- `inference_config.json` — class mapping and recommended inference settings
- `validation_metrics.json` — final held-out validation results

The SafeTensors weights are the epoch-7 best checkpoint from the TextSeg-refined
Manga109 Segmentation v2.0.0 training run. They contain only the standard
RF-DETR Seg 2XL model state; no custom dense head is required.

## Usage

```bash
pip install "rfdetr==1.7.0" "safetensors>=0.5" huggingface_hub pillow
```

```python
from huggingface_hub import hf_hub_download
from PIL import Image
import importlib.util
import numpy as np

weights = hf_hub_download(
    repo_id="mayocream/koharu-layout-rfdetr-seg-2xl-1152",
    filename="model.safetensors",
)
loader_path = hf_hub_download(
    repo_id="mayocream/koharu-layout-rfdetr-seg-2xl-1152",
    filename="load_model.py",
)
spec = importlib.util.spec_from_file_location("koharu_layout_loader", loader_path)
loader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(loader)
model = loader.load_model(weights)

image = Image.open("page.jpg").convert("RGB")
detections = model.predict(
    image,
    threshold=0.20,
    shape=(1152, 1152),
    include_source_image=False,
)

class_thresholds = {0: 0.25, 1: 0.20, 2: 0.50, 3: 0.50}
keep = np.asarray([
    score >= class_thresholds[int(class_id)]
    for class_id, score in zip(detections.class_id, detections.confidence)
])
detections = detections[keep]

print(detections.xyxy)       # bounding boxes
print(detections.mask)       # instance masks
print(detections.class_id)   # 0=text, 1=COO, 2=bubble, 3=panel
print(detections.confidence)
```

CUDA is strongly recommended. The model was trained and evaluated at 1152 px.

## Recommended thresholds

Run prediction at the lowest threshold below, then apply class-specific
filtering:

| Class | Suggested threshold |
|---|---:|
| Text | 0.25 |
| COO / SFX | 0.20 |
| Bubble | 0.50 |
| Panel | 0.50 |

The lower SFX threshold is intentional. On a 291-page out-of-domain comparison,
changing only SFX from 0.40 to 0.20 raised typography-mask recall against
TextSeg from 72.98% to 79.51% and raw mask IoU from 58.37% to 61.75%. It is
particularly helpful for large stylized effects. For applications that favor
precision over pre-inpainting recall, raise the SFX threshold toward 0.40.

## Validation results

The final checkpoint was evaluated on the held-out 1,001-page TextSeg-refined
Manga109 validation split at 1152 px.

| Metric | Score |
|---|---:|
| Box mAP50–95 | 0.7969 |
| Box mAP50 | 0.8970 |
| Box mAP75 | 0.8425 |
| Mask mAP50–95 | 0.5713 |
| Mask mAP50 | 0.8256 |
| Detection precision | 0.8767 |
| Detection recall | 0.8177 |
| Detection F1 | 0.8392 |

Per-class box AP:

| Class | AP |
|---|---:|
| Text | 0.8779 |
| COO | 0.4431 |
| Bubble | 0.9092 |
| Panel | 0.9574 |

## Out-of-domain review

The model was also run qualitatively on 212 full-color Blue Archive comic pages
and 79 monochrome Marriage Toxin pages. These folders have no ground-truth
annotations, so the observations below are visual rather than accuracy claims.

- Dialogue text, bubbles, and conventional panel layouts generalized well.
- Marriage Toxin dialogue, bubbles, and panels were particularly consistent.
- SFX at threshold 0.20 recovered substantially more large stylized effects on
  the Blue Archive pages, with a small precision cost.
- Covers, logos, credits, and dense collage layouts remain difficult.
- Very decorative marks can be accepted as SFX at the recommended low threshold.

TextSeg was used as the comparison reference for this review, not human ground
truth. After 4 px reference-scale dilation and hole filling, RF-DETR/TextSeg
mask IoU increased from 79.18% at SFX 0.40 to 82.27% at SFX 0.20.

## Training

- Architecture: RF-DETR Seg 2XL (`rfdetr==1.7.0`)
- Resolution: 1152 × 1152
- Main supervision: Manga109 Segmentation v2.0.0, with PP-DocLayoutV3 boxes and
  TextSeg-refined typography masks
- Classes: text, onomatopoeia, bubble, panel
- Final continuation stage: 8 epochs; best checkpoint at epoch 7
- Final-stage global batch: 32 (8 GPUs × 2 images × 2 accumulation steps)
- Optimizer learning rate: `3e-5`; encoder learning rate: `1e-5`
- EMA enabled; seed 42
- Training hardware: 8 × NVIDIA H100 80 GB
- No custom dense head or typography-distillation branch

## Limitations

- COO remains weaker than text, bubble, and panel detection.
- Large display titles and credits may be missed or assigned low confidence.
- Character nameplates and small icons can be confused with bubbles.
- Dense collages can create many duplicate or low-confidence instances.
- The model has no OCR, semantic reading order, or text-to-bubble relationship
  output.
- The training distribution is predominantly Japanese manga. Results on other
  comic styles and languages may differ.

## License and training-data terms

The RF-DETR software is distributed under Apache-2.0. The model was fine-tuned
using Manga109 images, which are distributed separately under Manga109's
academic-use terms. This repository does not redistribute Manga109 images.

The model repository therefore uses a custom/`other` license designation.
Users are responsible for obtaining Manga109 and complying with its terms and
all applicable rights. This model card does not grant rights to Manga109 source
images or to third-party comic content.

## Weight integrity

SHA-256:

```text
9bf6d2cbd7793c956d8c857bb1672a396eb7f100eb0682f86830d05e31168efb
```
