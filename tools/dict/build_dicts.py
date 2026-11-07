# Builds the built-in main dictionaries (res/raw-<lang>/main.dict) for Ukrainian, Russian
# and English from the wordfreq word frequencies (data: CC BY-SA 4.0, see
# app/src/main/assets/licenses/NOTICE).
# Needs wordfreq 3.1.1 (pip install wordfreq==3.1.1); run with that interpreter:
#   python tools/dict/build_dicts.py app/src/main/res
import re, sys, pathlib
import wordfreq as wf

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import makedict

LANGS = {
    # language: (letters, how many of the most frequent words, one-letter words that exist)
    "uk": ("абвгґдеєжзиіїйклмнопрстуфхцчшщьюя", 160000, "авзійоу"),
    "ru": ("абвгдеёжзийклмнопрстуфхцчшщъыьэюя", 160000, "авиксуоя"),
    "en": ("abcdefghijklmnopqrstuvwxyz", 110000, "ai"),
}
# Letters only one of the two Cyrillic languages has: a word with them is surely not a
# loanword from the other one
OWN = {"uk": set("ґєії'"), "ru": set("ёъыэ")}
# How much more frequent (zipf) a word must be in the other language to count as mixed in
OTHER_LANG = {"uk": ("ru", 1.0), "ru": ("uk", 1.5)}

ZIPF_TOP = 7.5

# Words kept valid (never autocorrected away) but never suggested
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
# Solid spellings of hyphenated words (чтото for что-то) are common in the data: the
# dictionary gets the hyphenated word instead, offered as a completion
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
# wordfreq splits words at hyphens, so the common hyphenated words are added from this list,
# with the frequency wordfreq estimates for them
_UK_PRON = "хто що де як коли куди звідки який яка яке які якого якому якою яких кого кому чого чим чий чия чиє чиї".split()
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

# Two words written as one: left out, so they get corrected
DROP = {
    "uk": {"будьласка", "незнаю"},
    "ru": {"чтоли", "чтож", "тоесть", "вообщем", "вобщем", "всмысле", "какбы", "потомучто",
           "незнаю", "всетаки", "всётаки", "чутьчуть", "вотвот", "изпод", "какникак"},
}
# Real words that only differ from an apostrophe word by the apostrophe (its / it's); other
# such spellings (dont, мясо) are left out so they get corrected to the apostrophe word
KEEP_BARE = {"en": {"its", "well", "ill", "id", "hell", "shell", "were", "wed", "shed", "lets"}}

# wordfreq is lower case; English needs these capitalised
EN_CAPITALS = ("i i'm i'll i've i'd monday tuesday wednesday thursday friday saturday sunday "
               "january february april june july august september october november december "
               "english american british european christmas easter").split()

def blocked(lang, w):
    return any(s in w for s in BLOCK_SUBSTR[lang]) or bool(BLOCK_RE[lang].search(w))


def words_for(lang):
    letters, count, singles = LANGS[lang]
    allowed = set(letters) | ({"'"} if lang in ("uk", "en") else set())
    top = wf.top_n_list(lang, count, wordlist="large")
    zmin = wf.zipf_frequency(top[-1], lang, wordlist="large")
    bare = {w.replace("'", "") for w in top if "'" in w} - KEEP_BARE.get(lang, set())
    out, mixed, swear = [], 0, 0
    for w in top:
        if not set(w) <= allowed or not w[0].isalpha() or not w[-1].isalpha() or w.count("'") > 1:
            continue
        if len(w) == 1 and w not in singles:
            continue
        if w in bare or w in DROP.get(lang, ()):
            continue
        if re.search(r"(.)\1\1", w):  # stretched spellings: бляяяя, sooo
            continue
        z = wf.zipf_frequency(w, lang, wordlist="large")
        freq = max(1, min(255, round(1 + 254 * (z - zmin) / (ZIPF_TOP - zmin))))
        if lang in OTHER_LANG and not (set(w) & OWN[lang]):
            other, margin = OTHER_LANG[lang]
            if wf.zipf_frequency(w, other, wordlist="large") >= z + margin:
                freq, mixed = 0, mixed + 1
        if blocked(lang, w):
            freq, swear = 0, swear + 1
        for pattern in HYPHENATE.get(lang, ()):
            m = pattern.match(w)
            if m:
                w = m.group(1) + "-" + m.group(2)
        if lang == "en" and w in EN_CAPITALS:
            w = "I" + w[1:] if w.startswith("i") and len(w) <= 4 else w.capitalize()
        out.append((w, freq))
    for w in HYPHENATED.get(lang, ()):
        z = wf.zipf_frequency(w, lang, wordlist="large")
        if z >= zmin:
            out.append((w, max(1, min(255, round(1 + 254 * (z - zmin) / (ZIPF_TOP - zmin))))))
    print(f"{lang}: {len(out)} words (zipf >= {zmin:.2f}), {mixed} from the other language "
          f"and {swear} swear words kept unsuggested")
    return out


def main():
    res = pathlib.Path(sys.argv[1])
    for lang in LANGS:
        d = res / f"raw-{lang}"
        d.mkdir(exist_ok=True)
        size = makedict.write(d / "main.dict", words_for(lang))
        print(f"  raw-{lang}/main.dict: {size / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
