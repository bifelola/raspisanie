"""Скачать новые PDF с сайта колледжа, распознать и сохранить в архив.

Запуск:  python scraper/update.py            (данные пишутся в site/data)
         python scraper/update.py --pdf file.pdf --start 2026-11-09 --end 2026-11-14
"""
import argparse
import datetime as dt
import difflib
import hashlib
import html
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

PAGE = "https://mocomk.ru/student/raspisaniye/mitish_raspisanie/"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "site", "data")
UA = "Mozilla/5.0 (Linux; Android 14) schedule-bot/1.0"
LINK_RE = re.compile(
    r'aria-label="Расписание учебных занятий\s*(\d{2}\.\d{2}\.\d{4})\s*[-–—]\s*'
    r'(\d{2}\.\d{2}\.\d{4})"[^>]*?href="([^"]+?\.pdf)"', re.I)


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def to_date(s):
    d, m, y = map(int, s.split("."))
    return dt.date(y, m, d)


def find_weeks():
    page = get(PAGE).decode("utf-8", "replace")
    weeks = {}
    for a, b, href in LINK_RE.findall(page):
        url = urllib.parse.urljoin(PAGE, html.unescape(href))
        weeks[to_date(a)] = (to_date(b), url)
    return weeks


def quote_url(url):
    p = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit(p._replace(path=urllib.parse.quote(p.path)))


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, path)


def _norm(s):
    return re.sub(r"[\W_]+", "", (s or "").lower())


def _same_items(a, b):
    """Одинаковые ли пары. Мелкие расхождения распознавания (1–2 буквы) заменой не считаем."""
    if len(a) != len(b):
        return False
    for x, y in zip(a, b):
        for k in ("subject", "room", "teacher"):
            p, q = _norm(x.get(k)), _norm(y.get(k))
            if p == q:
                continue
            if k != "room" and p and q and difflib.SequenceMatcher(None, p, q).ratio() >= 0.85:
                continue
            return False
    return True


def _tkey(t):
    try:
        h, m = t.split(":")
        return int(h) * 60 + int(m)
    except ValueError:
        return 0


def diff_days(old, new, at):
    """Замены между прошлой и новой версией недели: пары добавлены, отменены или изменены."""
    out = []
    for date in sorted(set(old) | set(new)):
        o = {p["time"]: p["items"] for p in old.get(date, [])}
        n = {p["time"]: p["items"] for p in new.get(date, [])}
        for t in sorted(set(o) | set(n), key=_tkey):
            if t not in n:
                out.append({"at": at, "date": date, "time": t, "kind": "removed", "old": o[t]})
            elif t not in o:
                out.append({"at": at, "date": date, "time": t, "kind": "added", "new": n[t]})
            elif not _same_items(o[t], n[t]):
                out.append({"at": at, "date": date, "time": t, "kind": "changed", "old": o[t], "new": n[t]})
    return out


def canon_room(r):
    """Один кабинет — одно название: «27, 27» -> «27», «спорт:ал», «с/зал» -> «Спортзал», «Акт, зал» -> «Актовый зал»."""
    s = (r or "").strip()
    toks = [t.strip() for t in re.split(r"[,;]", s) if t.strip()]
    if toks and all(t.isdigit() for t in toks):
        return ", ".join(dict.fromkeys(toks))
    low = re.sub(r"[^а-яёa-z0-9]", "", s.lower())
    if "акт" in low:
        return "Актовый зал"
    if "спор" in low or low in ("сзал", "зал") or low.startswith("сп"):
        return "Спортзал"
    return s


def build_college(state):
    """Справочник по колледжу на каждую неделю: кто, где и когда (для поиска преподавателей и кабинетов)."""
    for wk, info in state["weeks"].items():
        rows = []
        for name, s in sorted(info["groups"].items()):
            g = load_json(os.path.join(DATA, "weeks", wk, s + ".json"), None)
            if not g:
                continue
            for date, pairs in sorted(g["days"].items()):
                for p in pairs:
                    for it in p["items"]:
                        teacher = ", ".join(dict.fromkeys(
                            t.strip() for t in (it.get("teacher") or "").split(",") if t.strip()))
                        rows.append([date, p["time"], name, it.get("subject", ""),
                                     canon_room(it.get("room", "")), teacher])
        rows.sort(key=lambda r: (r[0], _tkey(r[1]), r[2]))
        path = os.path.join(DATA, "college", wk + ".json")
        obj = {"week": wk, "rows": rows}
        if load_json(path, None) != obj:
            save_json(path, obj)


def process(pdf_path, start, end, source, ocr):
    from parse_pdf import parse_pdf, slug
    from textfix import Vocabulary

    wk = start.isoformat()
    wdir = os.path.join(DATA, "weeks", wk)
    tmpdir = wdir + ".new"
    shutil.rmtree(tmpdir, ignore_errors=True)
    os.makedirs(tmpdir)
    vpath = os.path.join(DATA, "vocab.json")
    vocab = Vocabulary(load_json(vpath, {}))
    res = parse_pdf(pdf_path, start, ocr, img_dir=tmpdir, vocab=vocab,
                    log=lambda s: print(s, flush=True))
    save_json(vpath, dict(vocab.counts.most_common(5000)))
    groups = {}
    at = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
    for name, g in res.items():
        s = slug(name)
        old = load_json(os.path.join(wdir, s + ".json"), None)
        changes = (old.get("changes") or []) if old else []
        if old:
            changes = changes + diff_days(old.get("days", {}), g["days"], at)
        save_json(os.path.join(tmpdir, s + ".json"), {
            "group": name, "start": wk, "end": end.isoformat(),
            "source": source, "days": g["days"], "scans": g["scans"],
            "changes": changes,
        })
        groups[name] = s
    shutil.rmtree(wdir, ignore_errors=True)
    os.replace(tmpdir, wdir)
    return groups


def rebuild_index(state):
    weeks = []
    for wk, info in sorted(state["weeks"].items(), reverse=True):
        weeks.append({"start": wk, "end": info["end"], "groups": info["groups"],
                      "source": info["source"], "updated": info["updated"]})
    all_groups = sorted({g for w in weeks for g in w["groups"]},
                        key=lambda g: (re.sub(r"\D", "", g)[:1], g))
    save_json(os.path.join(DATA, "index.json"), {
        "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "groups": all_groups,
        "weeks": weeks,
    })


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pdf")
    ap.add_argument("--start")
    ap.add_argument("--end")
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    state_path = os.path.join(DATA, "state.json")
    state = load_json(state_path, {"weeks": {}})
    ocr = None

    if args.pdf:
        jobs = {dt.date.fromisoformat(args.start):
                (dt.date.fromisoformat(args.end), os.path.abspath(args.pdf))}
    else:
        jobs = find_weeks()
        print(f"На сайте недель: {len(jobs)}")

    changed = False
    failed = 0
    for start, (end, url) in sorted(jobs.items()):
        wk = start.isoformat()
        if args.pdf:
            data = open(url, "rb").read()
        else:
            try:
                data = get(quote_url(url))
            except Exception as e:  # noqa: BLE001
                print(f"{wk}: не скачалось ({e})")
                failed += 1
                continue
        sha = hashlib.sha256(data).hexdigest()
        old = state["weeks"].get(wk)
        if old and old.get("sha") == sha and not args.force:
            print(f"{wk}: без изменений")
            continue
        print(f"{wk}: распознаю {url}")
        if ocr is None:
            from parse_pdf import OCR
            ocr = OCR()
        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
            f.write(data)
            tmp = f.name
        try:
            groups = process(tmp, start, end, url, ocr)
        finally:
            os.unlink(tmp)
        state["weeks"][wk] = {
            "end": end.isoformat(), "source": url, "sha": sha, "groups": groups,
            "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        }
        save_json(state_path, state)
        changed = True
        print(f"{wk}: групп {len(groups)}")

    if changed or not os.path.exists(os.path.join(DATA, "index.json")):
        rebuild_index(state)
    build_college(state)
    print("Готово" + ("" if changed else " (новых данных нет)"))
    if jobs and failed == len(jobs):
        sys.exit("Ни один PDF не скачался — сайт колледжа недоступен?")


if __name__ == "__main__":
    main()
