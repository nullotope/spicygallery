#!/usr/bin/env python3
"""Regenerates the committed test fixtures for spicygallery-core (spec §11).

Run from the repo root:  python3 scripts/dev/make-fixtures.py
Requires: python3-Pillow, ffmpeg on PATH.

Fixtures produced (all tiny):
  plain.png   8x4 RGB PNG                     plain.jpg   8x4 JPEG, no EXIF
  rot90.jpg   8x4 JPEG, EXIF Orientation=6    (displays as 4x8 after orientation)
  tiny.webp   8x4 lossless WebP               tiny.bmp    8x4 BMP
  tiny.gif    8x4 GIF                         tiny.mp4    0.6 s, 64x32 h264
  wide.jpg    1024x512 JPEG                   tall.png    300x900 PNG
"""
from pathlib import Path
from subprocess import run

from PIL import Image

OUT = Path("spicygallery-core/src/test/resources/fixtures")
OUT.mkdir(parents=True, exist_ok=True)


def rgb(w, h, color):
    return Image.new("RGB", (w, h), color)


rgb(8, 4, (200, 30, 30)).save(OUT / "plain.png")
rgb(8, 4, (30, 200, 30)).save(OUT / "plain.jpg", quality=90)

im = rgb(8, 4, (30, 30, 200))
exif = im.getexif()
exif[0x0112] = 6  # EXIF Orientation: rotate 90° CW to display → 4x8
im.save(OUT / "rot90.jpg", exif=exif.tobytes(), quality=90)

rgb(8, 4, (10, 20, 30)).save(OUT / "tiny.webp", lossless=True)
rgb(8, 4, (40, 50, 60)).save(OUT / "tiny.bmp")
rgb(8, 4, (70, 80, 90)).save(OUT / "tiny.gif")
rgb(1024, 512, (5, 5, 5)).save(OUT / "wide.jpg", quality=85)
rgb(300, 900, (6, 6, 6)).save(OUT / "tall.png")

run([
    "ffmpeg", "-y", "-loglevel", "error",
    "-f", "lavfi", "-i", "color=c=blue:s=64x32:d=0.6:r=10",
    "-pix_fmt", "yuv420p", "-vcodec", "libx264",
    str(OUT / "tiny.mp4"),
], check=True)

# verify the EXIF fixture really carries Orientation=6 at its stored size
check = Image.open(OUT / "rot90.jpg")
assert check.size == (8, 4), check.size
assert check.getexif()[0x0112] == 6
print("fixtures written to", OUT)
