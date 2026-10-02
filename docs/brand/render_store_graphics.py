"""Render LedgerFlow's store graphics from the icon geometry.

Run from the repository root:  python docs/brand/render_store_graphics.py

Writes docs/brand/store-icon-512.png (Play and Galaxy Store listing icon:
512 x 512, full-bleed square; the stores apply their own mask) and
docs/brand/feature-graphic-1024x500.png (Play's feature graphic).

The geometry is ic_launcher_foreground.xml's, restated as rectangles: each page
is a rounded rectangle (radius 4) and each ruled line a capsule (height 3.5)
cut out of it. Everything is drawn at 4x and downsampled, so edges are
antialiased without any effect being added. Text is Inter, which the app
already ships under the SIL Open Font License
(core/designsystem/src/main/assets/licenses/Inter-OFL.txt).
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "brand"
FONT = ROOT / "core" / "designsystem" / "src" / "main" / "res" / "font" / "inter_variable.ttf"

BACKGROUND = "#15171C"  # LfColors dark surfaceBase
DEBIT = "#FF7A85"       # LfColors dark debit
CREDIT = "#5FD0A6"      # LfColors dark credit
TEXT = "#E8EAF0"        # LfColors dark textPrimary
TEXT_SECONDARY = "#9AA1B4"

# (x, y, width, height) in the 108-unit adaptive-icon canvas.
PAGES = [
    (DEBIT, (32, 34, 20, 42), [(37, 44, 10), (37, 52, 7), (37, 60, 10)]),
    (CREDIT, (56, 30, 20, 42), [(61, 40, 10), (61, 48, 7), (61, 56, 10)]),
]
SS = 4  # supersampling factor


def draw_pages(draw: ImageDraw.ImageDraw, scale: float, dx: float, dy: float, hole: str) -> None:
    """Draw both pages; `scale` maps icon units to pixels at the supersampled size."""
    def box(x, y, w, h):
        return [dx + x * scale, dy + y * scale, dx + (x + w) * scale, dy + (y + h) * scale]

    for colour, (x, y, w, h), rules in PAGES:
        draw.rounded_rectangle(box(x, y, w, h), radius=4 * scale, fill=colour)
        for rx, cy, length in rules:
            draw.rounded_rectangle(box(rx, cy - 1.75, length, 3.5), radius=1.75 * scale, fill=hole)


def store_icon(size: int = 512) -> Image.Image:
    # A launcher shows the central 72 of the 108 units (the outer 18 on each
    # side are for parallax and masks), so the store icon is that same crop --
    # otherwise the pages look a third smaller than they do on the phone.
    scale = size * SS / 72
    big = Image.new("RGB", (size * SS, size * SS), BACKGROUND)
    draw_pages(ImageDraw.Draw(big), scale=scale, dx=-18 * scale, dy=-18 * scale, hole=BACKGROUND)
    return big.resize((size, size), Image.LANCZOS)


def inter(weight: int, px: int) -> ImageFont.FreeTypeFont:
    font = ImageFont.truetype(str(FONT), px)
    # Inter's axes are [optical size, weight]: a lone value would set the first.
    font.set_variation_by_axes([min(32, max(14, px / SS)), weight])
    return font


def feature_graphic(width: int = 1024, height: int = 500) -> Image.Image:
    big = Image.new("RGB", (width * SS, height * SS), BACKGROUND)
    draw = ImageDraw.Draw(big)
    # The pages, ~300 px tall, centred vertically on the left.
    scale = 7.0 * SS
    draw_pages(draw, scale=scale, dx=(40 - 32 * 7.0 + 56) * SS, dy=(height / 2 - 53 * 7.0) * SS, hole=BACKGROUND)
    x = 470 * SS
    draw.text((x, 170 * SS), "LedgerFlow", font=inter(600, 76 * SS), fill=TEXT)
    draw.text((x, 270 * SS), "Private, offline expense tracking.", font=inter(400, 30 * SS), fill=TEXT_SECONDARY)
    draw.text((x, 312 * SS), "Nothing is booked until you approve it.", font=inter(400, 30 * SS), fill=TEXT_SECONDARY)
    return big.resize((width, height), Image.LANCZOS)


if __name__ == "__main__":
    store_icon().save(OUT / "store-icon-512.png", optimize=True)
    feature_graphic().save(OUT / "feature-graphic-1024x500.png", optimize=True)
    print("wrote", OUT / "store-icon-512.png", "and", OUT / "feature-graphic-1024x500.png")
