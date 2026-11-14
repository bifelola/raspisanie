"""Список изменений для поста: строки «- …» из описаний коммитов, иначе заголовки коммитов.

Вход (stdin): git log --format='%x1e%s%x1f%b'.
"""
import sys

out = []
for rec in sys.stdin.read().split("\x1e"):
    if not rec.strip():
        continue
    subject, _, body = rec.partition("\x1f")
    bullets = [l.strip()[2:].strip() for l in body.splitlines() if l.strip().startswith("- ")]
    out += bullets or [subject.strip()]
print("\n".join(f"- {x}" for x in out if x))
