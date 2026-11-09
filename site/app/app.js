"use strict";

const DEFAULT_GROUP = "22 фо";
const PAIR_MINUTES = 90;
const DAY_NAMES = ["Воскресенье", "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"];
const DAY_SHORT = ["Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"];
const MONTHS = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"];

const $ = (id) => document.getElementById(id);
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* приватный режим */ } },
};

let index = null;
let group = null;
let weekIdx = 0;
let current = null;

const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const parseIso = (s) => { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); };
const addDays = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const fmtShort = (d) => `${d.getDate()}.${String(d.getMonth() + 1).padStart(2, "0")}`;
const fmtLong = (d) => `${d.getDate()} ${MONTHS[d.getMonth()]}`;

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

async function getJson(url) {
  const r = await fetch(url, { cache: "no-cache" });
  if (!r.ok) throw new Error(r.status);
  return r.json();
}

function pickWeek() {
  const now = new Date();
  let d = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (d.getDay() === 0) d = addDays(d, 1); // в воскресенье показываем следующую неделю
  const t = iso(d);
  let i = index.weeks.findIndex((w) => w.start <= t && t <= iso(addDays(parseIso(w.end), 1)));
  if (i >= 0) return i;
  i = index.weeks.findIndex((w) => w.start <= t); // последняя прошедшая
  return i >= 0 ? i : index.weeks.length - 1;
}

function weekLabel(w) {
  const s = parseIso(w.start), e = parseIso(w.end);
  return `${fmtLong(s)} — ${fmtLong(e)}`;
}

function relNote(w) {
  const today = iso(new Date());
  if (w.start <= today && today <= iso(addDays(parseIso(w.end), 1))) return "текущая неделя";
  if (w.start > today) return "следующая неделя";
  return "архив";
}

async function init() {
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
  try {
    index = await getJson("../data/index.json");
  } catch {
    $("status").textContent = "Не удалось загрузить данные. Проверьте интернет.";
    return;
  }
  if (!index.weeks.length) { $("status").textContent = "Расписаний пока нет."; return; }
  const params = new URLSearchParams(location.search);
  group = params.get("g") || store.get("group") || (index.groups.includes(DEFAULT_GROUP) ? DEFAULT_GROUP : index.groups[0]);
  weekIdx = pickWeek();
  $("updated").textContent = "Обновлено: " + new Date(index.updated).toLocaleString("ru-RU", { dateStyle: "medium", timeStyle: "short" });
  bind();
  render();
}

function bind() {
  $("prevWeek").onclick = () => { if (weekIdx < index.weeks.length - 1) { weekIdx++; render(); } };
  $("nextWeek").onclick = () => { if (weekIdx > 0) { weekIdx--; render(); } };
  $("groupBtn").onclick = openGroups;
  $("groupSearch").oninput = fillGroups;
  $("toggleScan").onclick = () => {
    const box = $("scanBox");
    box.hidden = !box.hidden;
    $("toggleScan").textContent = box.hidden ? "Показать оригинал" : "Скрыть оригинал";
    if (!box.hidden) box.scrollIntoView({ behavior: "smooth" });
  };
}

function openGroups() {
  $("groupSearch").value = "";
  fillGroups();
  $("groupDialog").showModal();
}

function fillGroups() {
  const q = $("groupSearch").value.trim().toLowerCase().replace(/\s+/g, "");
  const list = $("groupList");
  list.innerHTML = "";
  const byCourse = {};
  for (const g of index.groups) {
    if (q && !g.replace(/\s+/g, "").includes(q)) continue;
    const c = /^\d/.test(g) ? g[0] + " курс" : "Другие";
    (byCourse[c] = byCourse[c] || []).push(g);
  }
  for (const c of Object.keys(byCourse).sort()) {
    const h = document.createElement("h3");
    h.textContent = c;
    list.append(h);
    for (const g of byCourse[c]) {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = g;
      if (g === group) b.className = "sel";
      b.onclick = () => {
        group = g;
        store.set("group", g);
        $("groupDialog").close();
        render();
      };
      list.append(b);
    }
  }
  if (!list.children.length) list.innerHTML = '<p class="muted">Ничего не найдено</p>';
}

async function render() {
  const w = index.weeks[weekIdx];
  $("groupName").textContent = group;
  $("weekRange").textContent = weekLabel(w);
  $("weekNote").textContent = relNote(w);
  $("prevWeek").disabled = weekIdx >= index.weeks.length - 1;
  $("nextWeek").disabled = weekIdx <= 0;
  $("pdfLink").href = w.source;
  $("days").innerHTML = "";
  $("dayTabs").innerHTML = "";
  $("scans").innerHTML = "";
  const slug = w.groups[group];
  if (!slug) {
    $("status").textContent = `На эту неделю для группы «${group}» расписания нет.`;
    return;
  }
  $("status").textContent = "Загрузка…";
  const token = {};
  current = token;
  let data;
  try {
    data = await getJson(`../data/weeks/${w.start}/${slug}.json`);
  } catch {
    if (current === token) $("status").textContent = "Не удалось загрузить расписание.";
    return;
  }
  if (current !== token) return;
  $("status").textContent = "";
  drawWeek(w, data);
}

function drawWeek(w, data) {
  const start = parseIso(w.start);
  const todayIso = iso(new Date());
  const nowMin = new Date().getHours() * 60 + new Date().getMinutes();
  const days = $("days"), tabs = $("dayTabs");
  let todayEl = null;

  for (let i = 0; i < 6; i++) {
    const d = addDays(start, i);
    const key = iso(d);
    const pairs = data.days[key] || [];
    const isToday = key === todayIso;

    const sec = document.createElement("section");
    sec.className = "day" + (isToday ? " today" : "");
    sec.id = "d" + key;
    let html = `<div class="day-head"><h2>${DAY_NAMES[d.getDay()]}, ${fmtLong(d)}</h2>${isToday ? '<span class="badge">сегодня</span>' : ""}</div>`;
    if (!pairs.length) html += '<div class="no-pairs">Пар нет</div>';
    for (const p of pairs) {
      const [h, m] = p.time.split(":").map(Number);
      const s = h * 60 + m, e = s + PAIR_MINUTES;
      const end = `${Math.floor(e / 60)}:${String(e % 60).padStart(2, "0")}`;
      const now = isToday && nowMin >= s - 10 && nowMin < e;
      html += `<div class="pair${now ? " now" : ""}"><div class="time"><b>${esc(p.time)}</b><span>${end}</span></div><div>`;
      for (const it of p.items) {
        let subj = it.subject || "";
        let sub = "";
        const mm = subj.match(/^(\d)\.\s*/);
        if (mm) { sub = `<span class="sub">${mm[1]} подгр.</span>`; subj = subj.slice(mm[0].length); }
        const meta = [];
        if (it.room) meta.push(`<span class="room">каб. ${esc(it.room)}</span>`);
        if (it.teacher) meta.push(esc(it.teacher));
        html += `<div class="item"><div class="subj">${sub}${esc(subj)}</div>${meta.length ? `<div class="meta">${meta.join(" · ")}</div>` : ""}</div>`;
      }
      html += "</div></div>";
    }
    sec.innerHTML = html;
    days.append(sec);
    if (isToday) todayEl = sec;

    const t = document.createElement("button");
    t.className = "day-tab" + (isToday ? " today" : "") + (pairs.length ? "" : " empty");
    t.innerHTML = `<b>${DAY_SHORT[d.getDay()]}</b><span>${fmtShort(d)}</span>`;
    t.onclick = () => {
      tabs.querySelectorAll(".day-tab").forEach((x) => x.classList.remove("active"));
      t.classList.add("active");
      sec.scrollIntoView({ behavior: "smooth", block: "start" });
    };
    tabs.append(t);
  }

  for (const f of data.scans || []) {
    const img = document.createElement("img");
    img.loading = "lazy";
    img.alt = "Скан расписания";
    img.src = `../data/weeks/${w.start}/${f}`;
    const a = document.createElement("a");
    a.href = img.src;
    a.target = "_blank";
    a.rel = "noopener";
    a.title = "Открыть в полном размере";
    a.append(img);
    $("scans").append(a);
  }
  if (todayEl) setTimeout(() => todayEl.scrollIntoView({ block: "start" }), 50);
}

init();
