"""Пост в Telegram-канал о новой версии приложения.

Переменные окружения:
  TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID — из секретов GitHub (если нет — тихо выходим);
  VERSION   — номер версии, например 1.0.12 (для поста о версии);
  NOTES     — список изменений, по одному на строку;
  TEXT      — готовый текст поста (вместо поста о версии, например приветствие);
  SITE_URL  — адрес сайта.
"""
import html
import json
import os
import sys
import urllib.request

token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
chat = os.environ.get("TELEGRAM_CHAT_ID", "").strip()
if not token or not chat:
    print("Telegram не настроен (нет секретов TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID) — пропускаю.")
    sys.exit(0)

site = os.environ.get("SITE_URL", "").rstrip("/") + "/"
version = os.environ.get("VERSION", "").strip()
custom = os.environ.get("TEXT", "").strip()
notes = [n.strip(" -•*\t") for n in os.environ.get("NOTES", "").splitlines()]
notes = [n for n in notes if n]

if custom:
    text = html.escape(custom)
else:
    lines = [f"🚀 <b>Вышла версия {html.escape(version)}</b>", ""]
    if notes:
        lines.append("<b>Что нового:</b>")
        lines += [f"• {html.escape(n)}" for n in notes[:15]]
        if len(notes) > 15:
            lines.append(f"• …и ещё {len(notes) - 15}")
    else:
        lines.append("Исправления и улучшения.")
    lines += ["", "Обновление ставится поверх старой версии — данные и архив сохранятся.", "", "#обновление"]
    text = "\n".join(lines)

buttons = [[
    {"text": "📲 Скачать APK", "url": site},
    {"text": "🌐 Веб-версия", "url": site + "app/"},
]]
body = {
    "chat_id": chat,
    "text": text,
    "parse_mode": "HTML",
    "link_preview_options": {"is_disabled": True},
    "reply_markup": {"inline_keyboard": buttons},
}
req = urllib.request.Request(
    f"https://api.telegram.org/bot{token}/sendMessage",
    data=json.dumps(body).encode(),
    headers={"Content-Type": "application/json"},
)
try:
    with urllib.request.urlopen(req, timeout=30) as r:
        print("Пост опубликован:", json.load(r).get("ok"))
except urllib.error.HTTPError as e:
    print("::warning::Telegram ответил ошибкой:", e.code, e.read().decode(errors="replace"))
