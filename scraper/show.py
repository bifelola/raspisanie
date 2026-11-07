"""Показать распознанное расписание группы текстом: python scraper/show.py 2026-11-09 "22 фо" [out.txt]"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from parse_pdf import slug  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
week, group = sys.argv[1], sys.argv[2]
path = os.path.join(ROOT, "site", "data", "weeks", week, slug(group) + ".json")
data = json.load(open(path, encoding="utf-8"))
out = open(sys.argv[3], "w", encoding="utf-8") if len(sys.argv) > 3 else sys.stdout
for day, pairs in data["days"].items():
    out.write(day + "\n")
    for p in pairs:
        for it in p["items"]:
            out.write(f"  {p['time']:>5} | {it['subject']} | {it['room']} | {it['teacher']}\n")
