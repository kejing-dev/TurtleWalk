"""Turtle Walk launcher icon export: the 512 PNG of the adaptive icon (res/drawable/ic_launcher_foreground.xml on
@color/ic_launcher_background), drawn from the same shapes, plus the xxxhdpi legacy PNG (192).

    python tools/make_icon.py <export-512.png>
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
K = S / 108.0
TEAL = (0x1A, 0x7F, 0x9E, 255)
WHITE = (255, 255, 255, 255)


def line(d, pts, width):
    d.line([(x * K, y * K) for x, y in pts], fill=WHITE, width=int(width * K), joint="curve")
    r = width * K / 2
    for x, y in (pts[0], pts[-1]):
        d.ellipse((x * K - r, y * K - r, x * K + r, y * K + r), fill=WHITE)


def draw():
    im = Image.new("RGBA", (S, S), TEAL)
    d = ImageDraw.Draw(im)
    # three solid sides with rounded corners (right side up from the start, the top, the left side down)
    line(d, [(74, 72), (74, 38)], 4)
    d.arc(((66) * K, 34 * K, 74 * K, 42 * K), 270, 360, fill=WHITE, width=int(4 * K))
    line(d, [(70, 34), (38, 34)], 4)
    d.arc((34 * K, 34 * K, 42 * K, 42 * K), 180, 270, fill=WHITE, width=int(4 * K))
    line(d, [(34, 38), (34, 62)], 4)
    # the dashed bottom side
    for x in (46, 56, 66):
        line(d, [(x, 74), (x + 4, 74)], 4)
    # the turn arc in the upper right corner
    d.arc((52 * K, 44 * K, 68 * K, 60 * K), 270, 360, fill=WHITE, width=int(3 * K))
    # the triangle turtle pointing up
    d.polygon([(34 * K, 62.5 * K), (41.8 * K, 78 * K), (26.2 * K, 78 * K)], fill=WHITE)
    return im


def main():
    full = draw()
    os.makedirs(os.path.join(RES, "mipmap-xxxhdpi"), exist_ok=True)
    full.resize((192, 192), Image.LANCZOS).save(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png"))
    if len(sys.argv) > 1:
        full.resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("icon written")


if __name__ == "__main__":
    main()
