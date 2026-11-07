"""Разбор PDF-скана расписания в структуру {группа: {дата: [пары]}}."""
import datetime as dt
import os
import re

import cv2
import numpy as np
import pymupdf

from grid import Grid, deskew
from textfix import Vocabulary, clean, fix_room, fix_teacher

GROUP_RE = re.compile(r"(?<!\d)(\d{2})\s*-?\s*([а-яё]{1,4})(?![а-яё\d.])", re.I)
DATE_RE = re.compile(r"(\d{1,2})[.,](\d{2})(?:[.,](\d{2,4}))?")


class OCR:
    def __init__(self):
        import easyocr

        self.reader = easyocr.Reader(["ru", "en"], gpu=False, verbose=False)

    def lines(self, img, allowlist=None):
        """Список строк [(y_top, y_bottom, text)] сверху вниз."""
        if img is None or img.size == 0:
            return []
        # узкие ячейки (кабинет, время) увеличиваем и даём поля — иначе
        # детектор пропускает одиночные цифры
        scale = 2 if img.shape[1] < 160 else 1
        if scale != 1:
            img = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
        pad = 12
        img = cv2.copyMakeBorder(img, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=255)
        res = self.reader.readtext(
            img, detail=1, paragraph=False, allowlist=allowlist,
            width_ths=0.7, text_threshold=0.6, low_text=0.35,
        )
        boxes = []
        for box, text, conf in res:
            ys = [p[1] for p in box]
            xs = [p[0] for p in box]
            if conf < 0.15 or not text.strip():
                continue
            boxes.append([(min(ys) - pad) / scale, (max(ys) - pad) / scale,
                          min(xs), text.strip()])
        boxes.sort(key=lambda b: (b[0] + b[1]) / 2)
        merged = []
        for b in boxes:
            if merged:
                m = merged[-1]
                ov = min(m[5], b[1]) - max(m[4], b[0])
                if ov > 0.5 * min(m[5] - m[4], b[1] - b[0]):  # та же строка
                    m[3].append((b[2], b[3]))
                    m[0], m[1] = min(m[0], b[0]), max(m[1], b[1])
                    continue
            merged.append([b[0], b[1], 0, [(b[2], b[3])], b[0], b[1]])
        out = [(m[0], m[1], " ".join(t for _, t in sorted(m[3]))) for m in merged]
        if not out:
            out = self._recognize_whole(img, pad, scale, allowlist)
        return out

    def _recognize_whole(self, img, pad, scale, allowlist):
        """Детектор пропустил короткий текст (например «6») — читаем область целиком."""
        ys, xs = np.nonzero(img < 128)
        if len(xs) < 15:
            return []
        y0, y1, x0, x1 = ys.min(), ys.max(), xs.min(), xs.max()
        if (y1 - y0) < 8 or (y1 - y0) > 45 * scale:  # не одна строка — не гадаем
            return []
        crop = img[max(0, y0 - 6):y1 + 7, max(0, x0 - 6):x1 + 7]
        res = self.reader.recognize(crop, detail=1, allowlist=allowlist)
        if not res:
            return []
        _, text, conf = res[0]
        if conf < 0.3 or not text.strip():
            return []
        return [((y0 - pad) / scale, (y1 - pad) / scale, text.strip())]


def page_gray(doc, pno):
    page = doc[pno]
    imgs = page.get_images()
    if imgs:
        best = max(imgs, key=lambda i: i[2] * i[3])
        data = doc.extract_image(best[0])["image"]
        arr = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_GRAYSCALE)
        if arr is not None and arr.shape[1] > 1000:
            if arr.shape[0] > arr.shape[1] and page.rect.width > page.rect.height:
                arr = cv2.rotate(arr, cv2.ROTATE_90_COUNTERCLOCKWISE)
            return arr
    pix = page.get_pixmap(dpi=200, colorspace=pymupdf.csGRAY)
    return np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width).copy()


def parse_slot(text):
    d = re.sub(r"\D", "", text)
    if len(d) == 3:
        h, m = int(d[0]), int(d[1:])
    elif len(d) == 4:
        h, m = int(d[:2]), int(d[2:])
    else:
        return None
    if 7 <= h <= 21 and m < 60:
        return h * 60 + m
    return None


def fmt_slot(t):
    return f"{t // 60}:{t % 60:02d}"


def norm_group(num, letters):
    return f"{num} {letters.lower()}"


def complete_names(names, pno):
    """Дополнить названия, закрытые печатью или обрезанные OCR, по соседним группам."""
    names = list(names)
    known = [n for n in names if n]
    # "15 м" рядом с "14 мсо" -> "15 мсо"
    for i, n in enumerate(names):
        if not n:
            continue
        num, suf = n.split(" ", 1)
        longer = [k.split(" ", 1)[1] for k in known
                  if k.split(" ", 1)[1].startswith(suf) and len(k.split(" ", 1)[1]) > len(suf)]
        if longer:
            names[i] = f"{num} {max(set(longer), key=longer.count)}"
    # пропущенное название: соседняя группа +-1 с тем же суффиксом
    for _ in range(len(names)):
        for i, n in enumerate(names):
            if n:
                continue
            if i > 0 and names[i - 1]:
                num, suf = names[i - 1].split(" ", 1)
                names[i] = f"{int(num) + 1} {suf}"
            elif i + 1 < len(names) and names[i + 1]:
                num, suf = names[i + 1].split(" ", 1)
                names[i] = f"{int(num) - 1} {suf}"
    return [n or f"группа {i + 1} (стр. {pno + 1})" for i, n in enumerate(names)]


def next_study_day(d):
    d += dt.timedelta(days=1)
    if d.weekday() == 6:
        d += dt.timedelta(days=1)
    return d


def split_entries(disc, rooms, teachers):
    """Разложить строки ячеек на занятия (подгруппы 1./2. и т.п.)."""
    entries = []
    for y0, y1, t in disc:
        t = clean(t)
        if not t:
            continue
        m = re.match(r"^(\d)(?!\d)\s*[.,)]?\s*(?=[А-ЯЁA-Zа-яё])", t)
        if m:
            t = f"{m.group(1)}. " + t[m.end():]
        if not entries or m:
            entries.append({"y0": y0, "y1": y1, "subject": [t], "room": [], "teacher": []})
        else:
            e = entries[-1]
            e["subject"].append(t)
            e["y1"] = y1

    def attach(lines, key):
        for y0, y1, t in lines:
            if not t.strip():
                continue
            cy = (y0 + y1) / 2
            if not entries:
                entries.append({"y0": y0, "y1": y1, "subject": [], "room": [], "teacher": []})
            # последнее занятие, начавшееся выше середины строки
            above = [e for e in entries if e["y0"] - 8 <= cy]
            best = above[-1] if above else entries[0]
            best[key].append(t)

    # "с/за" + "л" на двух строках -> "с/зал"
    joined = []
    for ln in rooms:
        if joined and re.fullmatch(r"[а-яёa-z]{1,2}", ln[2].strip()):
            p = joined[-1]
            joined[-1] = (p[0], ln[1], p[2] + ln[2].strip())
        else:
            joined.append(ln)
    rooms = joined

    attach(rooms, "room")
    attach(teachers, "teacher")
    out = []
    for e in entries:
        subj = " ".join(e["subject"])
        subj = re.sub(r"-\s+(?=[а-яё])", "", subj)  # перенос по слогам
        out.append({
            "subject": subj,
            "room": ", ".join(fix_room(r) for r in e["room"] if fix_room(r)),
            "teacher": fix_teacher(" ".join(e["teacher"])),
        })
    out = [o for o in out if o["subject"] or o["teacher"]]
    # "Физкультура" + "2. Физкультура": у первой подгруппы OCR потерял "1."
    if len(out) > 1 and out[1]["subject"].startswith("2.") and not re.match(r"\d\.", out[0]["subject"]):
        out[0]["subject"] = "1. " + out[0]["subject"]
    rooms_set = {o["room"] for o in out if o["room"]}
    if len(out) > 1 and len(rooms_set) == 1:  # общий кабинет на подгруппы
        for o in out:
            o["room"] = o["room"] or next(iter(rooms_set))
    return out


def scan_strip(G, vl, c0, c1, ytop, ybot, path):
    """Сохранить картинку: колонки день/время + колонки группы."""
    left = G.gray[ytop:ybot, vl[0]:vl[3]]
    right = G.gray[ytop:ybot, c0:c1]
    img = np.hstack([left, np.full((ybot - ytop, 6), 0, np.uint8), right])
    scale = min(1.0, 1100 / img.shape[1])
    img = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    cv2.imwrite(path, img, [cv2.IMWRITE_JPEG_QUALITY, 60])


def slug(name):
    tr = str.maketrans("абвгдеёжзийклмнопрстуфхцчшщъыьэюя ",
                       "abvgdeejziiklmnoprstufhccss_y_eua-")
    return name.lower().translate(tr)


def parse_pdf(path, week_start, ocr, img_dir=None, img_prefix="", log=print, vocab=None):
    doc = pymupdf.open(path)
    groups = {}
    scans = {}
    raw_cells = []  # для словаря
    prev_names = None
    prev_vl = None
    cur_date = prev_t = None
    seg_cache = {}

    for pno in range(doc.page_count):
        gray, angle = deskew(page_gray(doc, pno))
        G = Grid(gray)
        # высота таблицы — по длинным горизонтальным линиям (рамка скана не в счёт)
        wide = G.hlines(G.box[0], G.box[2], 0.5)
        if len(wide) >= 2:
            by0, by1 = wide[0], wide[-1]
        else:
            by0, by1 = G.box[1], G.box[3]
        band0 = by0 + int((by1 - by0) * 0.35)
        vl = G.vlines(band0, by1 - 10, 0.5)
        if len(vl) < 4:
            log(f"  стр.{pno + 1}: таблица не найдена")
            continue
        if prev_vl and len(vl) != len(prev_vl):
            # страница-продолжение без шапки: колонки как на предыдущей странице,
            # подогнанные к линиям, найденным на этой
            loose = G.vlines(by0 + 5, by1 - 5, 0.3) or vl
            shift = vl[0] - prev_vl[0]
            snapped = []
            for x in prev_vl:
                x2 = x + shift
                near = min(loose, key=lambda v: abs(v - x2))
                snapped.append(near if abs(near - x2) <= 25 else x2)
            hl_try = G.hlines(snapped[2] + 4, snapped[3] - 4, 0.6)
            if hl_try and hl_try[0] < 150:  # таблица у верхнего края — продолжение
                vl = snapped
        if len(vl) < 7:
            log(f"  стр.{pno + 1}: таблица не найдена")
            continue
        extra = (len(vl) - 4) % 3
        if extra:
            # убрать лишние линии: самые близкие к соседям
            for _ in range(extra):
                gaps = [vl[i + 1] - vl[i] for i in range(len(vl) - 1)]
                i = int(np.argmin(gaps[3:])) + 3
                del vl[i + 1]
        ngr = (len(vl) - 4) // 3
        prev_vl = list(vl)
        hl = G.hlines(vl[2] + 4, vl[3] - 4, 0.6)
        rows = []
        for a, b in zip(hl, hl[1:]):
            if b - a < 18:
                continue
            crop = G.clean_crop(vl[2], a, vl[3], b)
            if not G.has_ink(crop, 20):
                continue
            txt = " ".join(t for *_, t in ocr.lines(crop, allowlist="0123456789.,:"))
            t = parse_slot(txt)
            if t is not None:
                rows.append((a, b, t))
        if not rows:
            log(f"  стр.{pno + 1}: нет строк со временем")
            continue

        # шапка: названия групп
        names = [None] * ngr
        if rows[0][0] - hl[0] > 60:
            for i in range(ngr):
                c0, c1 = vl[3 + 3 * i], vl[6 + 3 * i]
                crop = G.clean_crop(c0, hl[0], c1, rows[0][0], pad=3)
                for _, _, text in ocr.lines(crop):
                    if re.search(r"курс|год|обуч", text, re.I):
                        continue
                    # OCR путает 3 и З, 0 и О рядом с цифрами
                    text = re.sub(r"(?<=\d)[Зз]|[Зз](?=\d)", "3", text)
                    text = re.sub(r"(?<=\d)[Оо](?=\d)", "0", text)
                    m = GROUP_RE.search(text)
                    if m and m.group(2).lower() not in ("каб",):
                        names[i] = norm_group(m.group(1), m.group(2))
                        break
        if any(names):
            names = complete_names(names, pno)
            if names != prev_names:
                cur_date = prev_t = None  # новый курс — снова с понедельника
            prev_names = names
            log(f"  стр.{pno + 1}: группы {names}, наклон {angle:.2f}°")
        elif prev_names and len(prev_names) == ngr:
            names = prev_names
            log(f"  стр.{pno + 1}: продолжение {names}")
        else:
            log(f"  стр.{pno + 1}: не удалось определить группы")
            continue

        # границы дней: горизонтальные линии через колонки "день" и "дата"
        day_lines = G.hlines(vl[0] + 4, vl[2] - 4, 0.8)

        def day_of(y):
            return sum(1 for d in day_lines if d < y)

        def present(x, y0, y1):
            strip = G.vmask[y0 + 4:y1 - 4, max(0, x - 5):x + 6]
            return strip.size and (strip.max(axis=1) > 0).mean() > 0.6

        prev_blk = None
        for a, b, t in rows:
            blk = day_of((a + b) // 2)
            if cur_date is None:
                cur_date = week_start
            elif (prev_t is not None and t <= prev_t) or (
                prev_blk is not None and blk != prev_blk
            ):
                cur_date = next_study_day(cur_date)
            prev_t, prev_blk = t, blk

            bounds = [x for x in vl[3:] if present(x, a, b)]
            if not bounds or bounds[0] != vl[3]:
                bounds = [vl[3]] + bounds
            if bounds[-1] != vl[-1]:
                bounds.append(vl[-1])
            for gi, name in enumerate(names):
                cols = vl[3 + 3 * gi: 7 + 3 * gi]
                segs = []
                for k in range(3):
                    cx = (cols[k] + cols[k + 1]) // 2
                    L = max(x for x in bounds if x <= cx)
                    R = min(x for x in bounds if x > cx)
                    segs.append((L, R))
                result = []
                used = set()
                for k, (L, R) in enumerate(segs):
                    if (L, R) in used:
                        result.append([])
                        continue
                    used.add((L, R))
                    key = (pno, a, b, L, R)
                    if key not in seg_cache:
                        crop = G.clean_crop(L, a, R, b, pad=5)
                        seg_cache[key] = ocr.lines(crop) if G.has_ink(crop) else []
                    result.append(seg_cache[key])
                disc, rooms, teachers = result
                if segs[0] == segs[2]:  # одна объединённая ячейка
                    rooms = teachers = []
                items = split_entries(disc, rooms, teachers)
                if not items:
                    continue
                for it in items:
                    raw_cells.append(it)
                day = groups.setdefault(name, {}).setdefault(cur_date.isoformat(), [])
                day.append({"time": fmt_slot(t), "items": items})

        if img_dir:
            for gi, name in enumerate(names):
                c0, c1 = vl[3 + 3 * gi], vl[6 + 3 * gi]
                fn = f"{img_prefix}{slug(name)}_p{pno + 1}.jpg"
                scan_strip(G, vl, c0, c1, max(0, hl[0] - 2), min(G.h, rows[-1][1] + 3),
                           os.path.join(img_dir, fn))
                scans.setdefault(name, []).append(fn)

    # исправление опечаток OCR по частотному словарю (накапливается между неделями)
    voc = vocab if vocab is not None else Vocabulary()
    for it in raw_cells:
        voc.feed(it["subject"])
        voc.feed(it["teacher"])
    voc.build()
    for it in raw_cells:
        it["subject"] = voc.apply(it["subject"])
        it["teacher"] = voc.apply(it["teacher"])

    out = {}
    for name, days in groups.items():
        out[name] = {
            "days": {d: sorted(p, key=lambda x: int(x["time"].split(":")[0]) * 60
                               + int(x["time"].split(":")[1]))
                     for d, p in sorted(days.items())},
            "scans": scans.get(name, []),
        }
    for name in scans:
        out.setdefault(name, {"days": {}, "scans": scans[name]})
    return out
