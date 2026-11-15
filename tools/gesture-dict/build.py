# Builds the word lists for gesture (swipe) typing: app/src/main/assets/gesture/<lang>.txt
# for Ukrainian, Russian and English, one "word<TAB>freq" line per word, most frequent first,
# freq 1..255 (log scale).
#
# Source: the "large" word frequency lists of wordfreq 3.1.1 by Robyn Speer
# (https://github.com/rspeer/wordfreq), the same data the built-in main dictionaries come
# from (tools/dict). wordfreq code is Apache-2.0; its data files are CC BY-SA 4.0, and so are
# the generated lists (attribution: NOTICE, "Gesture typing word lists").
#
# Python 3 standard library only. Run from anywhere:
#   python3 -I tools/gesture-dict/build.py
# The wordfreq wheel is downloaded once from PyPI into tools/gesture-dict/.cache/wordfreq-3.1.1/
# (git-ignored) and checked against its SHA-256; later runs read it from there.
import gzip, hashlib, io, math, pathlib, re, struct, sys, unicodedata, urllib.request, zipfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "gesture"
CACHE = HERE / ".cache" / "wordfreq-3.1.1"

WHEEL = "wordfreq-3.1.1-py3-none-any.whl"
WHEEL_URL = ("https://files.pythonhosted.org/packages/24/61/"
             "62835c475d69872d30689f284497853fe33fe1d6dd18f57346d13305861d/" + WHEEL)
WHEEL_SHA256 = "4b1c6ecffc6198be3396d5cf871c4423ca71c907c231348d352dd54d62b97473"

TOP = 60000      # words per language
MAX_LEN = 24     # longer words are left out

LANGS = {
    # language: (letters, one-letter words that exist, apostrophe allowed)
    "uk": ("абвгґдеєжзиіїйклмнопрстуфхцчшщьюя", "авзійоуя", True),
    "ru": ("абвгдеёжзийклмнопрстуфхцчшщъыьэюя", "авиксуоя", False),
    "en": ("abcdefghijklmnopqrstuvwxyz", "ai", True),
}
# Letters only one of the two Cyrillic languages has: a word with them is surely not a
# loanword from the other one
OWN = {"uk": set("ґєії'"), "ru": set("ёъыэ")}
# A word this much more frequent (zipf) in the other language is mixed in from it (Russian
# "что", "как" in Ukrainian tweets) and is left out
OTHER_LANG = {"uk": ("ru", 1.0), "ru": ("uk", 1.5)}
# Real words of the language that the check above would take for the other one
KEEP_SHARED = {"uk": {"дома", "пор"}, "ru": {"та"}}
# Apostrophe look-alikes, written as U+0027
APOSTROPHES = dict.fromkeys(map(ord, "ʼ’‘`´ʹ′"), "'")

# wordfreq splits words at hyphens, and its data has the solid spellings instead (чтото):
# they are written with the hyphen
HYPHENATE = {
    "ru": [r"^(кто|что|где|как|когда|куда|откуда|почему|зачем|чей|чья|чье|чьё|чьи|какой|какая|"
           r"какое|какие|какого|какому|каком|какую|каким|каких|сколько|кого|кому|кем|чем|чего|чему|"
           r"чём)(то|либо|нибудь)$",
           r"^(кое)(кто|что|где|как|когда|куда|какой|какая|какое|какие|кого|чего|чем|кому)$",
           r"^(по)(моему|твоему|своему|нашему|вашему|новому|другому|прежнему|русски|английски|"
           r"украински|немецки|французски|хорошему|настоящему|разному|честному|всякому|видимому)$",
           r"^(из)(за|под)$"],
    "uk": [r"^(хто|що|де|як|коли|куди|звідки|чому|навіщо|чий|чия|чиє|чиї|який|яка|яке|які|якого|"
           r"кого|кому|чого|чим|скільки)(небудь)$",
           r"^(будь)(хто|що|де|як|коли|куди|який|яка|яке|які|якого|чий|чия|чиє|чиї|кого|чого|кому|"
           r"чим)$",
           r"^(по)(моєму|твоєму|своєму|нашому|вашому|новому|іншому|різному|справжньому|людськи|"
           r"українськи|англійськи|німецьки|сучасному|доброму)$"],
}
HYPHENATE = {lang: [re.compile(r) for r in rs] for lang, rs in HYPHENATE.items()}
# The common hyphenated words (the same list as tools/dict/build_dicts.py), added with the
# frequency wordfreq estimates for a word of several tokens: 1 / sum(1 / token frequency)
_UK_PRON = ("хто що де як коли куди звідки який яка яке які якого якому якою яких кого кому "
            "чого чим чий чия чиє чиї").split()
_RU_PRON = ("кто что где как когда куда откуда почему зачем какой какая какое какие какого каком "
            "кого кому чего чем чей чья чьё чьи сколько").split()
HYPHENATED = {
    "uk": [f"{w}-небудь" for w in _UK_PRON + ["скільки"]] + [f"будь-{w}" for w in _UK_PRON]
          + [f"казна-{w}" for w in "що хто де куди який яка яке які".split()]
          + [f"по-{w}" for w in ("моєму твоєму своєму нашому вашому новому іншому різному "
                                 "справжньому людськи українськи англійськи").split()],
    "ru": [f"{w}-{s}" for w in _RU_PRON for s in ("то", "либо", "нибудь")]
          + [f"кое-{w}" for w in "кто что где как когда куда какой какая какое какие кого чего чем кому".split()]
          + [f"по-{w}" for w in ("моему твоему своему нашему вашему новому другому прежнему русски "
                                 "английски украински хорошему настоящему разному").split()]
          + "из-за из-под всё-таки все-таки так-то вот-вот чуть-чуть как-никак давным-давно".split(),
}
# Two words written as one: left out
DROP = {
    "uk": {"будьласка", "незнаю"},
    "ru": {"чтоли", "чтож", "тоесть", "вообщем", "вобщем", "всмысле", "какбы", "потомучто",
           "незнаю", "всетаки", "всётаки", "чутьчуть", "вотвот", "изпод", "какникак"},
}
# A spelling without the apostrophe (dont, мясо) is left out when the apostrophe word
# (don't, м'ясо) is at least as frequent; so "years" stays despite "year's". These real words
# are kept anyway (its / it's)
KEEP_BARE = {"en": {"its", "well", "ill", "id", "hell", "shell", "were", "wed", "shed", "lets"}}
# Swear words and slurs: the main dictionaries keep them valid but never suggest them
# (tools/dict/build_dicts.py, the same lists; that script needs wordfreq installed, so they
# are repeated here). A gesture types its best word by itself, so they are left out.
# Stems that are safe to look for anywhere in a word ("хуй" is not: рахуйте, "ёб": учёба)
BLOCK_SUBSTR = {
    "uk": ["пизд", "пізд", "залуп", "підор", "підар", "шльондр", "шлюх", "гандон", "гондон",
           "мудак", "мудил", "дроч", "йоба", "долбо"],
    "ru": ["пизд", "залуп", "пидор", "пидар", "пидр", "шлюх", "гандон", "гондон", "мудак",
           "мудил", "дроч", "долбо"],
    "en": ["fuck", "shit", "nigg", "fagg", "motherf", "asshole", "bitch", "whore", "slut",
           "dickhead", "cocksuck", "porn"],
}
# Stems at the start of a word, after a verb prefix at most
BLOCK_RE = {
    "uk": re.compile(r"^((на|по|за|від|до|ні|не|о|ох|при|роз|ви|у|під|недо)?(ху[йєїяюеи]|[еєї]б)"
                     r"|бля($|д|т)|сук[аиуо]$|сукою$|сучк[аиуо]?$|сучкою$|сучар)"),
    "ru": re.compile(r"^((на|по|за|от|отъ|до|ни|не|о|ох|при|раз|разъ|вы|у|под|подъ|съ|въ|недо)?"
                     r"(ху[йеёяюи]|[её]б)|бля($|д|т)|сук[аиуео]$|сукой$|сучк[аиуе]?$|сучкой$|сучар)"),
    "en": re.compile(r"^(fag|fags|dick|dicks|cock|cocks|pussy|pussies|tits|cunts?|cunty|twats?"
                     r"|wank|wanks|wanker|wankers|wanking|retard|retards|retarded)$"),
}
# wordfreq is lower case; these English words are always written capitalised
EN_CAPITALS = ("i i'm i'll i've i'd monday tuesday wednesday thursday friday saturday sunday "
               "january february april june july august september october november december "
               "english american british european christmas easter").split()


# --- source -------------------------------------------------------------------------------

def wheel_path():
    path = CACHE / WHEEL
    if not path.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        print(f"downloading {WHEEL_URL}")
        with urllib.request.urlopen(WHEEL_URL, timeout=120) as r:
            data = r.read()
        if hashlib.sha256(data).hexdigest() != WHEEL_SHA256:
            sys.exit(f"{WHEEL}: SHA-256 mismatch, not using it")
        path.write_bytes(data)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != WHEEL_SHA256:
        sys.exit(f"{path}: SHA-256 {digest} does not match {WHEEL_SHA256}; delete it and rerun")
    return path


class MsgPack:
    """A minimal MessagePack reader: enough for wordfreq's cBpack files (arrays of strings)."""

    def __init__(self, data):
        self.d, self.i = data, 0

    def take(self, n):
        b = self.d[self.i:self.i + n]
        if len(b) != n:
            raise ValueError("truncated msgpack data")
        self.i += n
        return b

    def uint(self, n):
        return int.from_bytes(self.take(n), "big")

    def read(self):
        t = self.take(1)[0]
        if t <= 0x7f:
            return t
        if 0x80 <= t <= 0x8f:
            return self.map(t & 0x0f)
        if 0x90 <= t <= 0x9f:
            return [self.read() for _ in range(t & 0x0f)]
        if 0xa0 <= t <= 0xbf:
            return self.take(t & 0x1f).decode("utf-8")
        if t >= 0xe0:
            return t - 0x100
        simple = {0xc0: None, 0xc2: False, 0xc3: True}
        if t in simple:
            return simple[t]
        if t in (0xc4, 0xc5, 0xc6):
            return bytes(self.take(self.uint(1 << (t - 0xc4))))
        if t == 0xca:
            return struct.unpack(">f", self.take(4))[0]
        if t == 0xcb:
            return struct.unpack(">d", self.take(8))[0]
        if 0xcc <= t <= 0xcf:
            return self.uint(1 << (t - 0xcc))
        if 0xd0 <= t <= 0xd3:
            n = 1 << (t - 0xd0)
            return int.from_bytes(self.take(n), "big", signed=True)
        if t in (0xd9, 0xda, 0xdb):
            return self.take(self.uint(1 << (t - 0xd9))).decode("utf-8")
        if t in (0xdc, 0xdd):
            return [self.read() for _ in range(self.uint(2 if t == 0xdc else 4))]
        if t in (0xde, 0xdf):
            return self.map(self.uint(2 if t == 0xde else 4))
        raise ValueError(f"unsupported msgpack type 0x{t:02x}")

    def map(self, n):
        out = {}
        for _ in range(n):
            k = self.read()
            out[k] = self.read()
        return out


def load_zipf(wheel, lang):
    """word -> zipf frequency, in the order of the source (most frequent first). In a cBpack
    file, bucket i holds the words whose frequency is 10^(-i/100), i.e. zipf 9 - i/100."""
    with zipfile.ZipFile(wheel) as z:
        raw = gzip.decompress(z.read(f"wordfreq/data/large_{lang}.msgpack.gz"))
    data = MsgPack(raw).read()
    header = data[0]
    if not isinstance(header, dict) or header.get("format") != "cB" or header.get("version") != 1:
        sys.exit(f"large_{lang}: not a cBpack v1 file")
    out = {}
    for i, bucket in enumerate(data[1:]):
        for w in bucket:
            if isinstance(w, str) and w not in out:
                out[w] = 9 - i / 100
    return out


# --- cleaning -----------------------------------------------------------------------------

def normalize(w):
    return unicodedata.normalize("NFC", w).translate(APOSTROPHES).lower()


def blocked(lang, w):
    return any(s in w for s in BLOCK_SUBSTR[lang]) or bool(BLOCK_RE[lang].search(w))


def shape_ok(lang, w):
    letters, singles, apostrophe = LANGS[lang]
    allowed = set(letters) | {"-"} | ({"'"} if apostrophe else set())
    if not w or len(w) > MAX_LEN or not set(w) <= allowed:
        return False
    if not (w[0] in letters and w[-1] in letters):
        return False  # apostrophe and hyphen only inside a word
    if w.count("'") > 1 or "--" in w or "-'" in w or "'-" in w:
        return False
    if len(w) == 1 and w not in singles:
        return False
    if re.search(r"(.)\1\1", w):  # stretched spellings: бляяяя, sooo
        return False
    return True


def clean(lang, zipf, other):
    # merge spellings that normalise to the same word, adding up their frequencies
    freq = {}
    for w, z in zipf.items():
        n = normalize(w)
        freq[n] = freq.get(n, 0.0) + 10 ** z
    with_apostrophe = {}
    for w, f in freq.items():
        if "'" in w:
            b = w.replace("'", "")
            with_apostrophe[b] = max(with_apostrophe.get(b, 0.0), f)
    bare = {b for b, f in with_apostrophe.items()
            if b in freq and f >= freq[b]} - KEEP_BARE.get(lang, set())
    other_freq = {}
    if other is not None:
        for w, z in other.items():
            n = normalize(w)
            other_freq[n] = other_freq.get(n, 0.0) + 10 ** z
    out, dropped = {}, {"shape": 0, "bare": 0, "other": 0, "joined": 0, "blocked": 0}
    for w, f in freq.items():
        if not shape_ok(lang, w):
            dropped["shape"] += 1
            continue
        if w in bare:
            dropped["bare"] += 1
            continue
        if w in DROP.get(lang, ()):
            dropped["joined"] += 1
            continue
        if blocked(lang, w):
            dropped["blocked"] += 1
            continue
        if lang in OTHER_LANG and not (set(w) & OWN[lang]) and w not in KEEP_SHARED[lang]:
            margin = OTHER_LANG[lang][1]
            if other_freq.get(w, 0.0) >= f * 10 ** margin:
                dropped["other"] += 1
                continue
        for pattern in HYPHENATE.get(lang, ()):
            m = pattern.match(w)
            if m:
                w = m.group(1) + "-" + m.group(2)
                break
        if lang == "en" and w in EN_CAPITALS:
            w = "I" + w[1:] if w.startswith("i") and len(w) <= 4 else w.capitalize()
        out[w] = out.get(w, 0.0) + f
    for w in HYPHENATED.get(lang, ()):
        parts = w.split("-")
        if shape_ok(lang, w) and all(p in freq for p in parts):
            out[w] = max(out.get(w, 0.0), 1.0 / sum(1.0 / freq[p] for p in parts))
    return out, dropped


def scale(words):
    """(word, linear frequency) most frequent first -> (word, 1..255), log scale between the
    least and the most frequent kept word."""
    zmax, zmin = math.log10(words[0][1]), math.log10(words[-1][1])
    span = (zmax - zmin) or 1.0
    return [(w, max(1, min(255, round(1 + 254 * (math.log10(f) - zmin) / span))))
            for w, f in words]


def check(lang, rows):
    bad = {"uk": set("ыъэё"), "ru": set("ґєії")}.get(lang, set())
    for w, f in rows:
        assert 1 <= f <= 255 and "\t" not in w and "\n" not in w, (lang, w, f)
        assert not (set(w.lower()) & bad), (lang, w)
        assert unicodedata.is_normalized("NFC", w), (lang, w)


def main():
    wheel = wheel_path()
    zipf = {lang: load_zipf(wheel, lang) for lang in LANGS}
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for lang in LANGS:
        other = zipf[OTHER_LANG[lang][0]] if lang in OTHER_LANG else None
        words, dropped = clean(lang, zipf[lang], other)
        ranked = sorted(words.items(), key=lambda kv: -kv[1])[:TOP]
        rows = scale(ranked)
        check(lang, rows)
        text = "".join(f"{w}\t{f}\n" for w, f in rows).encode("utf-8")
        path = OUT_DIR / f"{lang}.txt"
        path.write_bytes(text)
        gz = len(gzip.compress(text, 9))
        print(f"{lang}: {len(rows)} words (of {len(zipf[lang])} in the source; left out: "
              f"{dropped}), {path.relative_to(ROOT)} {len(text)} bytes, gzip {gz} bytes")


if __name__ == "__main__":
    main()
