"""Export the launcher assets with Pillow. Optionally import the original 1254px portrait."""

from io import BytesIO
from pathlib import Path
import struct
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "app/src/main/res"
ANDROID_PORTRAIT = ANDROID / "drawable-nodpi/kayoko_launcher_portrait.webp"
DESKTOP_PNG = ROOT / "desktopApp/src/main/resources/mangaflow.png"
DESKTOP_ICO = ROOT / "desktopApp/icons/mangaflow.ico"
IOS = ROOT / "iosApp/Assets.xcassets/AppIcon.appiconset/mangaflow-1024.png"
RESAMPLE = Image.Resampling.LANCZOS
SOURCE_CROP = (78, 0, 1178, 1100)
# These rectangles are in the 1024px universal icon's coordinates.
SMALL_CROPS = {
    16: (160, 114, 879, 833),
    20: (126, 74, 936, 884),
    24: (99, 51, 946, 898),
    32: (59, 32, 962, 935),
    40: (32, 11, 991, 970),
}


def windows_icon(portrait, size):
    crop = SMALL_CROPS.get(size)
    image = (portrait.crop(crop) if crop else portrait).resize((size, size), RESAMPLE)
    if crop:
        image = image.filter(ImageFilter.UnsharpMask(radius=.52, percent=95, threshold=3))
    side = size * 4
    mask = Image.new("L", (side, side))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, side - 1, side - 1), radius=round(side * .20), fill=255)
    image.putalpha(mask.resize(image.size, RESAMPLE))
    return image


def adaptive_foreground(portrait):
    # Only the near-white region connected to the outside is background. The whites
    # inside the hair and face stay in the foreground when a launcher applies parallax.
    red, green, blue = portrait.split()
    brightness = ImageChops.darker(ImageChops.darker(red, green), blue)
    exterior = ImageOps.expand(brightness.point(lambda level: 255 if level >= 243 else 0), 1, fill=255)
    ImageDraw.floodfill(exterior, (0, 0), 128)
    alpha = exterior.crop((1, 1, portrait.width + 1, portrait.height + 1)).point(
        lambda value: 0 if value == 128 else 255)
    character = portrait.copy()
    character.putalpha(alpha)
    # In the launcher's 108-unit layer the portrait occupies x=15..92, y=15..92.
    # Extend obscured edge pixels under the moving mask so the shoulders have no cut edge.
    side, offset, scaled = 648, 90, 462
    character = character.resize((scaled, scaled), RESAMPLE)
    band = Image.new("RGBA", (side, scaled))
    band.paste(character.crop((0, 0, 1, scaled)).resize((offset, scaled)), (0, 0))
    band.paste(character, (offset, 0))
    band.paste(character.crop((scaled - 1, 0, scaled, scaled)).resize((side - offset - scaled, scaled)),
               (offset + scaled, 0))
    foreground = Image.new("RGBA", (side, side))
    foreground.paste(band, (0, offset))
    foreground.paste(band.crop((0, scaled - 1, side, scaled)).resize((side, side - offset - scaled)),
                     (0, offset + scaled))
    return foreground


def png_bytes(image):
    data = BytesIO()
    image.save(data, format="PNG", optimize=True)
    return data.getvalue()


def icon_dib(image):
    """A BGRA DIB plus its padded one-bit AND mask for small native Windows icons."""
    width, height = image.size
    bottom_up = image.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    pixels = bottom_up.tobytes("raw", "BGRA")
    stride = (width + 31) // 32 * 4
    mask = bytearray(stride * height)
    for index, alpha in enumerate(bottom_up.getchannel("A").tobytes()):
        if alpha == 0:
            y, x = divmod(index, width)
            mask[y * stride + x // 8] |= 128 >> (x % 8)
    header = struct.pack("<IiiHHIIiiII", 40, width, height * 2, 1, 32, 0, len(pixels), 0, 0, 0, 0)
    return header + pixels + mask


def write_ico(frames):
    entries = bytearray()
    payload = bytearray()
    offset = 6 + 16 * len(frames)
    for size, image in frames:
        frame = icon_dib(image) if size <= 48 else png_bytes(image)
        side = size % 256
        entries.extend(struct.pack("<BBBBHHII", side, side, 0, 0, 1, 32, len(frame), offset + len(payload)))
        payload.extend(frame)
    DESKTOP_ICO.write_bytes(struct.pack("<HHH", 0, 1, len(frames)) + entries + payload)


def main():
    if len(sys.argv) > 2:
        raise SystemExit("Usage: python scripts/export_brand.py [original-1254px-portrait]")
    if len(sys.argv) == 2:
        with Image.open(sys.argv[1]) as original:
            if original.size != (1254, 1254):
                raise ValueError("The selected original artwork is 1254 × 1254.")
            portrait = original.convert("RGB").crop(SOURCE_CROP).resize((1024, 1024), RESAMPLE)
            portrait.info.clear()
            IOS.write_bytes(png_bytes(portrait))
    with Image.open(IOS) as master:
        portrait = master.convert("RGB")
    DESKTOP_PNG.write_bytes(png_bytes(windows_icon(portrait, 512)))
    frames = {size: windows_icon(portrait, size) for size in (16, 20, 24, 32, 40, 48, 64, 96, 128, 256)}
    DESKTOP_PNG.with_name("mangaflow-32.png").write_bytes(png_bytes(frames[32]))
    write_ico(frames.items())
    adaptive_foreground(portrait).save(ANDROID_PORTRAIT, "WEBP", quality=96, alpha_quality=100, method=6)


if __name__ == "__main__":
    main()
