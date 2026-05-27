"""Generate the Mist Lantern and Mist Crystal textures for v0.21."""
from PIL import Image, ImageDraw
import shutil
import os

BASE = r"C:\Users\ncerd\Mists\src\main\resources\assets\mists\textures"
BLOCK = os.path.join(BASE, "block")
ITEM = os.path.join(BASE, "item")
os.makedirs(BLOCK, exist_ok=True)
os.makedirs(ITEM, exist_ok=True)


def gen_mist_lantern():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Iron cage palette (slightly cool to read as "mist" iron)
    cage = (160, 172, 192, 255)
    cage_shade = (118, 130, 148, 255)
    cage_hi = (200, 212, 232, 255)

    # Top cap
    d.rectangle([6, 1, 9, 1], fill=cage_shade)
    d.rectangle([5, 2, 10, 2], fill=cage)
    d.rectangle([4, 3, 11, 3], fill=cage)
    d.rectangle([5, 2, 5, 2], fill=cage_hi)

    # Cage vertical bars
    for y in range(4, 13):
        img.putpixel((4, y), cage_shade)
        img.putpixel((11, y), cage_shade)
        img.putpixel((5, y), cage)
        img.putpixel((10, y), cage)

    # Inner mist glow before the bottom cap so cage overlaps it
    # Soft blue-white radial gradient centred at (7, 8)
    cx, cy = 7, 8
    for y in range(3, 13):
        for x in range(4, 12):
            # skip the cage rim
            if x in (4, 5, 10, 11):
                continue
            dx = x - cx
            dy = y - cy
            dist = (dx * dx + dy * dy) ** 0.5
            if dist < 4.5:
                t = max(0.0, 1.0 - dist / 4.5)
                alpha = int(255 * (t ** 1.4))
                # White-blue glow that fades to translucent
                r = int(220 + 35 * t)
                g = int(232 + 23 * t)
                b = 255
                # mix on top of any existing (cage already covers some pixels but they are skipped)
                img.putpixel((x, y), (min(r, 255), min(g, 255), b, alpha))

    # Bright core
    for y in range(7, 10):
        for x in range(6, 9):
            img.putpixel((x, y), (245, 250, 255, 255))
    img.putpixel((7, 8), (255, 255, 255, 255))

    # Bottom cap
    d.rectangle([4, 13, 11, 13], fill=cage)
    d.rectangle([5, 14, 10, 14], fill=cage_shade)
    d.rectangle([6, 15, 9, 15], fill=cage_shade)

    # A faint horizontal cage crossbar at mid-height (subtle)
    for x in range(5, 11):
        img.putpixel((x, 8), (cage_shade[0], cage_shade[1], cage_shade[2], 180))

    out = os.path.join(BLOCK, "mist_lantern.png")
    img.save(out)
    print(f"Wrote {out} ({os.path.getsize(out)} bytes)")
    return img


def gen_mist_crystal():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    outline = (210, 232, 252, 255)
    core = (140, 195, 240, 255)
    mid = (180, 220, 250, 255)
    hi = (240, 252, 255, 255)
    shadow = (90, 140, 200, 255)

    # Diamond/crystal vertices: top (8,1), left (3,8), bottom (8,14), right (13,8)
    # Fill: row by row
    for y in range(1, 15):
        # Determine the row's half-width based on triangular shape
        if y == 1 or y == 14:
            hw = 0
        elif y <= 8:
            # top half: width grows from 0 to 5 as y goes 1..8
            hw = (y - 1) * 5 // 7
        else:
            # bottom half: width shrinks from 5 to 0 as y goes 8..14
            hw = (14 - y) * 5 // 6
        if hw <= 0:
            # single-pixel tip
            img.putpixel((8, y), outline)
            continue
        for x in range(8 - hw, 8 + hw + 1):
            # outline pixels at edges
            if x == 8 - hw or x == 8 + hw:
                img.putpixel((x, y), outline)
            else:
                # facet: right-side darker (shadow), left-side lighter (lit)
                if x < 8:
                    img.putpixel((x, y), mid)
                elif x == 8:
                    img.putpixel((x, y), core)
                else:
                    img.putpixel((x, y), shadow if y >= 8 else core)

    # Inner sparkle
    img.putpixel((7, 5), hi)
    img.putpixel((6, 6), hi)
    img.putpixel((7, 6), hi)
    img.putpixel((8, 4), (255, 255, 255, 255))
    img.putpixel((9, 9), (255, 255, 255, 255))

    # Make the top and bottom tips slightly more visible
    img.putpixel((8, 1), hi)
    img.putpixel((8, 14), outline)

    out = os.path.join(ITEM, "mist_crystal.png")
    img.save(out)
    print(f"Wrote {out} ({os.path.getsize(out)} bytes)")
    return img


def copy_lantern_as_item():
    src = os.path.join(BLOCK, "mist_lantern.png")
    dst = os.path.join(ITEM, "mist_lantern.png")
    shutil.copy(src, dst)
    print(f"Wrote {dst} ({os.path.getsize(dst)} bytes)")


if __name__ == "__main__":
    gen_mist_lantern()
    gen_mist_crystal()
    copy_lantern_as_item()
