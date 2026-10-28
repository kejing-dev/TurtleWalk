"""Subset Noto Sans SC (variable, OFL 1.1) to the characters the Chinese copy uses.

Re-run whenever the zh strings change:

    python tools/subset_cjk_font.py [--source <NotoSansSC-VF.ttf>]

Default source: ../fire-plan/app/src/main/res/font/noto_sans_sc.ttf (the full variable font that project ships).
Output: app/src/main/res/font/noto_sans_sc_subset.ttf (the wght axis is kept) and third_party/noto-sans-sc/SUBSET.txt.

Latin letters and digits come from Noto Sans (res/font/noto_sans_*.ttf); this subset is the fallback after it.
"""
import argparse
import os
import re

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
STRING_FILES = [
    os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml"),
    os.path.join(ROOT, "app", "src", "main", "res", "values-zh", "strings.xml"),
]
KOTLIN_DIRS = [os.path.join(ROOT, "app", "src", "main", "java")]
EXTRA = (
    "，。、；：？！…—·（）《》「」【】　～"
    "●○▶❚✓×°±←→↑↓"
    "一二三四五六七八九十百千万零两个米厘慢中快原速领舞跟步"
)


def collect():
    chars = set(EXTRA)
    for path in STRING_FILES:
        with open(path, encoding="utf-8") as f:
            chars.update(re.sub(r"<[^>]+>", "", f.read()))
    for d in KOTLIN_DIRS:
        for dirpath, _, files in os.walk(d):
            for name in files:
                if name.endswith(".kt"):
                    with open(os.path.join(dirpath, name), encoding="utf-8") as f:
                        for ch in f.read():
                            if ord(ch) > 0x2E7F:
                                chars.add(ch)
    return "".join(sorted(c for c in chars if not c.isspace() and ord(c) > 0x7F))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=os.path.join(ROOT, "..", "fire-plan", "app", "src", "main", "res", "font", "noto_sans_sc.ttf"))
    ap.add_argument("--out", default=os.path.join(ROOT, "app", "src", "main", "res", "font", "noto_sans_sc_subset.ttf"))
    args = ap.parse_args()
    text = collect()
    font = TTFont(args.source)
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.recalc_bounds = True
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(text=text)
    subsetter.subset(font)
    font.save(args.out)
    os.makedirs(os.path.join(ROOT, "third_party", "noto-sans-sc"), exist_ok=True)
    with open(os.path.join(ROOT, "third_party", "noto-sans-sc", "SUBSET.txt"), "w", encoding="utf-8") as f:
        f.write("Noto Sans SC (variable) subset to these %d characters:\n%s\n" % (len(text), text))
    print("subset %d chars -> %s (%d bytes)" % (len(text), args.out, os.path.getsize(args.out)))


if __name__ == "__main__":
    main()
