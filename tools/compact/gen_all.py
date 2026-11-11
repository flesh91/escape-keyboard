# Generates res/xml-<qual>/kbd_compact.xml (5-row compact, 12 columns) for every keymap
# locale, from the locale's keymap strings. Letters that live outside the compact grid
# (ae11, ae12, bksl, lsgt) are added to the long-press popup of the nearest grid key.
# Usage: python3 tools/compact/gen_all.py app/src/main/res [--check]
import re, sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import keymaps as K

res = pathlib.Path(sys.argv[1])
CHECK_ONLY = "--check" in sys.argv

# English QWERTY variants keep the hand-made English compact layout (res/xml/kbd_compact.xml);
# ru-rEN ("Russian, for passwords") is English letters on purpose.
SKIP = {"en", "en-rGB", "en-rRU", "ru-rEN"}
EXTRA_TARGET = {"ae11": "ad11", "ae12": "ad12", "bksl": "ac11", "lsgt": "ab01"}
NUM_POPUPS = ["`", "@~", "#-", "$_", "=", "^+", "&{", "}", "[", "]"]  # English symbols on 1..0
# Long-press popup of space: Persian has the zero-width non-joiner there (Shift+Space in the
# full layout, which the compact one doesn't have); it only opens with pref_space_zwnj on
SPACE_POPUP = {"fa": "@xml/popup_zwnj"}

english = (res / "xml/kbd_compact.xml").read_text()
header = english[:english.index("<Keyboard")]
rows = re.findall(r"    <Row.*?</Row>\n", english, re.S)
ext_row, num_row, bottom_row = rows[0], rows[1], rows[-1]
ext_row = ext_row.replace('<Key android:keyLabel=";" android:shiftLabel=":" />',
                          '<Key android:keyLabel="/" android:shiftLabel="\\?" />')
delete_key = num_row[num_row.index('        <Key\n            android:codes="@integer/key_delete"'):
                     num_row.index("    </Row>")]


def attr(text):
    """Literal text for a popupCharacters attribute (AAPT escapes + XML entities)."""
    out = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    if out[:1] in ("@", "?"):
        out = "\\" + out
    return out.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def popup(km, pos, extras):
    add = "".join(c for c in extras.get(pos, "") if c not in K.val(km, pos, "alt"))
    if not add:
        return f"@string/key_{pos}_alt"
    return attr(add + K.val(km, pos, "alt"))


def key(km, pos, extras, edge=None, popup_override=None, width=None):
    p = popup_override if popup_override is not None else popup(km, pos, extras)
    s = (f'        <Key\n            android:keyLabel="@string/key_{pos}_main"\n'
         f'            android:shiftLabel="@string/key_{pos}_shift"\n'
         f'            android:popupKeyboard="@xml/kbd_popup_template"\n'
         f'            android:popupCharacters="{p}"')
    if width:
        s += f'\n            android:keyWidth="{width}"'
    if edge:
        s += f'\n            android:keyEdgeFlags="{edge}"'
    return s + " />\n"


def reachable(km, extras):
    """Letters the compact layout can type (labels, shift labels, popups)."""
    got = set()
    for pos in K.GRID:
        got |= set(K.val(km, pos, "main")) | set(K.val(km, pos, "shift"))
        if not pos.startswith("ae"):
            got |= popup_letters(K.val(km, pos, "alt"))
    for t in extras.values():
        got |= popup_letters(t)
    return {c for c in got if c.isalpha()}


def popup_letters(text):
    """Popup characters, plus their upper case: the popup follows Shift (Keyboard.java)."""
    out = set(text)
    for c in text:
        if len(c.upper()) == 1:
            out.add(c.upper())
    return out


def needed(km):
    """Letters typed by the full layout's main and shift labels."""
    got = set()
    for pos in K.ALL:
        for kind in ("main", "shift"):
            v = K.val(km, pos, kind)
            if K.is_letter(v):
                got.add(v)
    return got


def build(qual):
    km = K.keymap(res, qual)
    bottom = bottom_row
    if qual in SPACE_POPUP:
        bottom = bottom.replace('android:iconPreview="@drawable/sym_keyboard_feedback_space"\n',
                                'android:iconPreview="@drawable/sym_keyboard_feedback_space"\n'
                                f'            android:popupKeyboard="{SPACE_POPUP[qual]}"\n', 1)
    extras = {}
    for src, dst in EXTRA_TARGET.items():
        for kind in ("main", "shift"):
            v = K.val(km, src, kind)
            if not K.is_letter(v):
                continue
            # already reachable from some grid key? then don't duplicate it
            if v in reachable(km, {}):
                continue
            if kind == "shift" and K.is_letter(K.val(km, src, "main")) \
                    and K.val(km, src, "main").upper() == v:
                continue  # popups follow Shift, the lower-case letter is enough
            extras[dst] = extras.get(dst, "") + v
    missing = needed(km) - reachable(km, extras)

    num = "".join(key(km, f"ae{i:02d}", {}, popup_override=attr(NUM_POPUPS[i - 1]),
                      edge="left" if i == 1 else None) for i in range(1, 11))
    r2 = "".join(key(km, f"ad{i:02d}", extras, edge="left" if i == 1 else ("right" if i == 12 else None))
                 for i in range(1, 13))
    r3 = "".join(key(km, f"ac{i:02d}", extras, edge="left" if i == 1 else None) for i in range(1, 12))
    r3 += ('        <Key\n            android:codes="@integer/key_return"\n'
           '            android:keyIcon="@drawable/sym_keyboard_return"\n'
           '            android:iconPreview="@drawable/sym_keyboard_feedback_return"\n'
           '            android:isModifier="true"\n            android:keyEdgeFlags="right" />\n')
    r4 = ('        <Key\n            android:codes="@integer/key_shift"\n'
          '            android:keyIcon="@drawable/sym_keyboard_shift"\n'
          '            android:iconPreview="@drawable/sym_keyboard_feedback_shift"\n'
          '            android:isModifier="true"\n            android:isSticky="true"\n'
          '            android:keyEdgeFlags="left" />\n')
    r4 += "".join(key(km, f"ab{i:02d}", extras) for i in range(1, 10))
    r4 += key(km, "ab10", extras, popup_override=attr(",;:/?!'\"<>"))
    r4 += key(km, "tlde", extras, edge="right")

    xml = header + f'''<!--
    5-row compact layout generated from this locale's keymap (donottranslate-keymap.xml).
    12 columns: the letter rows need 12/11/9 keys. Letters the compact grid has no key for
    are in the long-press popup of the nearest key. Generated by tools/compact/gen_all.py, do not edit.
-->
<Keyboard
    xmlns:android="http://schemas.android.com/apk/res-auto"
    android:keyWidth="8.33333333%p"
    android:horizontalGap="0px"
    android:verticalGap="0px"
    android:verticalPad="@dimen/key_vertical_pad_compact"
    android:layoutRows="5"
    android:layoutColumns="12"
 >
''' + ext_row + '\n    <Row android:keyWidth="8.5%p">\n' + num + delete_key + "    </Row>\n" \
        + "    <Row>\n" + r2 + "    </Row>\n" + "    <Row>\n" + r3 + "    </Row>\n" \
        + "    <Row>\n" + r4 + "    </Row>\n" + bottom + "</Keyboard>\n"
    return xml, extras, missing


quals = sorted(p.parent.name[len("values-"):] for p in res.glob("values-*/donottranslate-keymap.xml"))
for q in quals:
    if q in SKIP:
        continue
    xml, extras, missing = build(q)
    note = " ".join(f"{k}+{v}" for k, v in extras.items()) or "-"
    print(f"{q:8s} popups: {note:22s} missing: {''.join(sorted(missing)) or '-'}")
    if not CHECK_ONLY:
        d = res / f"xml-{q}"
        d.mkdir(exist_ok=True)
        (d / "kbd_compact.xml").write_text(xml)
