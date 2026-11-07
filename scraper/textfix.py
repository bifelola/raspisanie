"""Чистка текста после OCR."""
import re
from collections import Counter

LAT2CYR = str.maketrans("ABCEHKMOPTXaceopxybvrunk", "АВСЕНКМОРТХасеорхуьугипк")
CYR = re.compile(r"[А-Яа-яЁё]")


def fix_token(tok):
    # латиница внутри русских слов, одиночные латинские буквы и инициалы
    if CYR.search(tok) or re.fullmatch(r"[ABCEHKMOPTXcoayexpk]\.?", tok):
        return tok.translate(LAT2CYR)
    return tok


def clean(s):
    s = s.replace("|", " ").replace("_", " ")
    s = re.sub(r"\s+", " ", s).strip()
    s = " ".join(fix_token(t) for t in s.split(" "))
    s = re.sub(r"(?<=[А-ЯЁA-Z]):", ".", s)          # "ПМ: 01" -> "ПМ. 01"
    s = re.sub(r"^(\d)\s*[,:]\s*", r"\1. ", s)        # "1, Химия" -> "1. Химия"
    s = re.sub(r"^(\d)\.(?=\S)", r"\1. ", s)
    return s


def fix_teacher(s):
    s = clean(s).strip(" -–—,;")
    s = re.sub(r"(?<=\.)[-–—]+(?=\s|$)", "", s)
    # инициалы, прочитанные латиницей/цифрами: "O.A", "TM.", "0."
    s = re.sub(r"\b[ABCEHKMOPTX0](?:\.?[ABCEHKMOPTX0])?\.?(?=\s|$|,)",
               lambda m: m.group(0).replace("0", "О").translate(LAT2CYR), s)
    s = re.sub(r"(?<![А-Яа-яЁё])([а-яё])\.", lambda m: m.group(1).upper() + ".", s)
    s = re.sub(r"\b([А-ЯЁ])\.([а-яё]{2,})", r"\1\2", s)           # "Х.рунова"
    # инициалы после фамилии: "ТЮ", "иС", "И.С" -> "Т. Ю.", "И. С."
    s = re.sub(r"(?<=[а-яё] )([А-ЯЁа-яё])\.?\s?([А-ЯЁ])\.?(?=\s|$|,)",
               lambda m: f"{m.group(1).upper()}. {m.group(2)}.", s)
    s = re.sub(r"\b([А-ЯЁ])(?=\s|$|,)(?!\.)", r"\1.", s)            # "Н А" -> "Н. А."
    s = re.sub(r"\b([А-ЯЁ])\.\s*([А-ЯЁ])\.?(?![а-яё])", r"\1. \2.", s)
    names = re.findall(r"[А-ЯЁ][а-яё-]+\s+[А-ЯЁ]\.\s*[А-ЯЁ]\.", s)
    if names and sum(map(len, names)) >= len(s.replace(" ", "")) * 0.6:
        return ", ".join(re.sub(r"\s+", " ", n) for n in names)
    return s


def fix_room(s):
    s = clean(s).replace(" ", "")
    s = s.replace("O", "0").replace("О", "0") if re.fullmatch(r"[\dOО]+", s) else s
    return s


def _lev(a, b, limit):
    if abs(len(a) - len(b)) > limit:
        return limit + 1
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        if min(cur) > limit:
            return limit + 1
        prev = cur
    return prev[-1]


class Vocabulary:
    """Исправляет редкие слова на близкие частые (по всем ячейкам документа)."""

    WORD = r"[А-Яа-яЁёA-Za-z]{4,}"

    def __init__(self, counts=None):
        self.counts = Counter(counts or {})
        self.fix = {}

    def feed(self, text):
        for w in re.findall(self.WORD, text):
            self.counts[w.lower()] += 1

    def build(self):
        """Слово -> частое похожее слово (OCR часто ошибается одинаково,
        поэтому смотрим на соотношение частот, а не на редкость)."""
        self.fix = {}
        frequent = [w for w, c in self.counts.items() if c >= 8 and CYR.search(w)]
        for w, c in self.counts.items():
            limit = 1 if len(w) < 9 else 2
            best = None
            for f in frequent:
                if (f != w and self.counts[f] >= 4 * c and f[-2:] == w[-2:]
                        and _lev(w, f, limit) <= limit):
                    if best is None or self.counts[f] > self.counts[best]:
                        best = f
            if best:
                self.fix[w] = best

    def apply(self, text):
        def rep(m):
            w = m.group(0)
            f = self.fix.get(w.lower())
            if not f:
                return w
            if w.isupper():
                return f.upper()
            return f.capitalize() if w[0].isupper() else f
        text = re.sub(self.WORD, rep, text)
        text = re.sub(r"\bМЛК\b", "МДК", text)
        text = re.sub(r"(?<=[\s.])[ОO](?=\d)|(?<=\d)[ОO]", "0", text)  # "О1.02" -> "01.02"
        return text
