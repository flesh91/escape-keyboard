# Reads Hacker's Keyboard keymaps with Android resource fallback (xx-rYY -> xx -> base).
import re
import xml.etree.ElementTree as ET

ROWS = {
    "ae": [f"ae{i:02d}" for i in range(1, 13)],
    "ad": [f"ad{i:02d}" for i in range(1, 13)],
    "ac": [f"ac{i:02d}" for i in range(1, 12)],
    "ab": [f"ab{i:02d}" for i in range(1, 11)],
}
GRID = ROWS["ae"][:10] + ROWS["ad"] + ROWS["ac"] + ROWS["ab"] + ["tlde"]
EXTRA = ["ae11", "ae12", "bksl", "lsgt"]
ALL = GRID + EXTRA


def strings(path):
    """name -> unescaped text of the key_* strings in one keymap file"""
    out = {}
    if not path.exists():
        return out
    for el in ET.parse(path).getroot().iter("string"):
        name = el.get("name") or ""
        if not name.startswith("key_"):
            continue
        text = "".join(el.itertext())
        if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
            text = text[1:-1]
        text = re.sub(r"\\(.)", r"\1", text)  # Android string escapes
        out[name] = text
    return out


def all_strings(path):
    """name -> unescaped text of every string in one values file"""
    out = {}
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return out
    for el in root.iter("string"):
        text = "".join(el.itertext())
        if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
            text = text[1:-1]
        out[el.get("name") or ""] = re.sub(r"\\(.)", r"\1", text)
    return out


def keymap(res, qual):
    """Merged keymap for a values qualifier like 'de-rNE' (falls back to 'de', then base).
    Values that point at another string (@string/alternates_for_y) are resolved."""
    chain = [""]
    if qual:
        if "-r" in qual:
            chain.append(qual.split("-r")[0])
        chain.append(qual)
    merged, every = {}, {}
    for q in chain:
        d = res / ("values" if not q else f"values-{q}")
        merged.update(strings(d / "donottranslate-keymap.xml"))
        for f in sorted(d.glob("*.xml")):
            every.update(all_strings(f))
    for name, text in merged.items():
        seen = 0
        while text.startswith("@string/") and seen < 5:
            text = every.get(text[len("@string/"):], "")
            seen += 1
        merged[name] = text
    return merged


def val(m, pos, kind):
    return m.get(f"key_{pos}_{kind}", "")


def is_letter(s):
    return len(s) == 1 and s.isalpha()
