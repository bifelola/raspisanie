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
let weekData = null;   // расписание группы на открытую неделю
let chgList = [];      // замены, на которые можно нажать (индекс в data-chg)

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
  bindExtras();
  render();
  setInterval(updateNow, 20000);
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
  weekData = null;
  updateNow();
  updateHistoryBtn();
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
  weekData = data;
  drawWeek(w, data);
  updateNow();
  updateHistoryBtn();
}

function drawWeek(w, data) {
  const start = parseIso(w.start);
  const todayIso = iso(new Date());
  const days = $("days"), tabs = $("dayTabs");
  let todayEl = null;
  const changes = latestChanges(data);
  chgList = [];
  const chgRef = (c) => { chgList.push(c); return chgList.length - 1; };

  for (let i = 0; i < 6; i++) {
    const d = addDays(start, i);
    const key = iso(d);
    const pairs = data.days[key] || [];
    const isToday = key === todayIso;
    const dch = changes[key] || {};
    const times = new Set(pairs.map((p) => p.time));
    const removed = Object.values(dch).filter((c) => c.kind === "removed" && !times.has(c.time));
    const entries = [...pairs, ...removed.map((c) => ({ removed: c, time: c.time }))].sort((a, b) => tmin(a.time) - tmin(b.time));

    const sec = document.createElement("section");
    sec.className = "day" + (isToday ? " today" : "");
    sec.id = "d" + key;
    let html = `<div class="day-head"><h2>${DAY_NAMES[d.getDay()]}, ${fmtLong(d)}</h2>${Object.keys(dch).length ? '<span class="badge chg">замены</span>' : ""}${isToday ? '<span class="badge">сегодня</span>' : ""}</div>`;
    if (!entries.length) html += '<div class="no-pairs">Пар нет</div>';
    for (const p of entries) {
      if (p.removed) {
        const c = p.removed;
        html += `<div class="pair removed"><div class="time"><b>${esc(c.time)}</b></div><div><div class="subj">${esc(subjects(c.old))}</div>` +
          `<button class="chg-badge" data-chg="${chgRef(c)}">отменена</button></div></div>`;
        continue;
      }
      const s = tmin(p.time), e = s + PAIR_MINUTES;
      const end = hm(e);
      const c = dch[p.time] && dch[p.time].kind !== "removed" ? dch[p.time] : null;
      html += `<div class="pair" data-date="${key}" data-s="${s}"><div class="time"><b>${esc(p.time)}</b><span>${end}</span></div><div>`;
      for (const it of p.items) {
        let subj = it.subject || "";
        let sub = "";
        const mm = subj.match(/^(\d)\.\s*/);
        if (mm) { sub = `<span class="sub">${mm[1]} подгр.</span>`; subj = subj.slice(mm[0].length); }
        const meta = [];
        if (it.room) meta.push(`<button class="room-link" data-room="${esc(canonRoom(it.room))}">каб. ${esc(it.room)}</button>`);
        if (it.teacher) meta.push(esc(it.teacher));
        html += `<div class="item"><div class="subj">${sub}${esc(subj)}</div>${meta.length ? `<div class="meta">${meta.join(" · ")}</div>` : ""}</div>`;
      }
      if (c) html += `<button class="chg-badge" data-chg="${chgRef(c)}">${changeLabel(c)}</button>`;
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
  markNow();
  if (todayEl) setTimeout(() => todayEl.scrollIntoView({ block: "start" }), 50);
}

// ───────── общее ─────────
const tmin = (t) => { const [h, m] = String(t).split(":").map(Number); return (h || 0) * 60 + (m || 0); };
const hm = (min) => `${Math.floor(min / 60)}:${String(min % 60).padStart(2, "0")}`;
const cleanSubj = (s) => String(s || "").replace(/^\d\.\s*/, "");
const uniq = (a) => [...new Set(a)];
const subjects = (l) => uniq((l || []).map((i) => cleanSubj(i.subject)).filter(Boolean)).join(" / ") || "пара";
const roomsOf = (l) => uniq((l || []).map((i) => i.room).filter(Boolean)).join(", ");
const teachersOf = (l) => uniq((l || []).map((i) => i.teacher).filter(Boolean)).join(", ");
const teacherNames = (t) => uniq(String(t || "").split(",").map((x) => x.trim()).filter(Boolean));
const nowMinutes = () => { const d = new Date(); return d.getHours() * 60 + d.getMinutes(); };
const favs = {
  get() { try { return new Set(JSON.parse(store.get("favs") || "[]")); } catch { return new Set(); } },
  toggle(t) { const f = this.get(); f.has(t) ? f.delete(t) : f.add(t); store.set("favs", JSON.stringify([...f])); },
};

/** То же, что canon_room на сервере: одно название для кабинета. */
function canonRoom(r) {
  const s = String(r || "").trim();
  const toks = s.split(/[,;]/).map((x) => x.trim()).filter(Boolean);
  if (toks.length && toks.every((t) => /^\d+$/.test(t))) return uniq(toks).join(", ");
  const low = s.toLowerCase().replace(/[^а-яёa-z0-9]/g, "");
  if (low.includes("акт")) return "Актовый зал";
  if (low.includes("спор") || low === "сзал" || low === "зал" || low.startsWith("сп")) return "Спортзал";
  return s;
}
const isRealRoom = (r) => r === "Спортзал" || r === "Актовый зал" || /^\d+(, \d+)*$/.test(r);

// ───────── замены ─────────
function latestChanges(data) {
  const out = {};
  for (const c of [...(data.changes || [])].sort((a, b) => (a.at < b.at ? -1 : 1))) (out[c.date] = out[c.date] || {})[c.time] = c;
  return out;
}
const changeLabel = (c) => (c.kind === "added" ? "новая" : c.kind === "removed" ? "отменена" : "изменено");
function changeWhen(c) { const d = parseIso(c.date); return `${DAY_SHORT[d.getDay()]} ${fmtShort(d)}, ${c.time}`; }
function describeChange(c) {
  if (c.kind === "removed") return `отменена: ${subjects(c.old)}`;
  if (c.kind === "added") return `новая пара: ${subjects(c.new)}` + (roomsOf(c.new) ? `, каб. ${roomsOf(c.new)}` : "");
  const so = subjects(c.old), sn = subjects(c.new), ro = roomsOf(c.old), rn = roomsOf(c.new);
  if (so !== sn) return `${so} → ${sn}` + (rn ? `, каб. ${rn}` : "");
  if (ro !== rn) return `${sn}: каб. ${ro || "—"} → ${rn || "—"}`;
  return `${sn}: преподаватель ${teachersOf(c.old) || "—"} → ${teachersOf(c.new) || "—"}`;
}
const fmtAt = (at) => { const d = new Date(at); return isNaN(d) ? at : `${fmtLong(d)}, ${d.getHours()}:${String(d.getMinutes()).padStart(2, "0")}`; };

function lessonsHtml(title, l, struck) {
  let h = `<h4>${title}</h4>`;
  if (!l || !l.length) return h + "<div>—</div>";
  for (const x of l) {
    const sg = (String(x.subject).match(/^(\d)\./) || [])[1];
    const meta = [x.room ? `каб. ${esc(x.room)}` : "", esc(x.teacher || "")].filter(Boolean).join(" · ");
    h += `<div class="${struck ? "struck" : ""}"><div class="subj">${sg ? `${sg} подгр. · ` : ""}${esc(cleanSubj(x.subject))}</div>${meta ? `<div class="meta">${meta}</div>` : ""}</div>`;
  }
  return h;
}

function openChange(c) {
  $("infoTitle").textContent = `Замена · ${changeWhen(c)}`;
  $("infoBody").innerHTML = (c.kind !== "added" ? lessonsHtml("Было", c.old, true) : "") +
    (c.kind !== "removed" ? lessonsHtml("Стало", c.new, false) : "") +
    `<p class="muted small" style="margin-top:14px">Колледж внёс изменение ${esc(fmtAt(c.at))}</p>`;
  if (!$("infoDialog").open) $("infoDialog").showModal();
}

function updateHistoryBtn() {
  const n = weekData && weekData.changes ? weekData.changes.length : 0;
  $("chgCount").textContent = n ? String(n) : "";
}

function openHistory() {
  const list = (weekData && weekData.changes) || [];
  $("infoTitle").textContent = "История изменений";
  if (!list.length) {
    $("infoBody").innerHTML = '<p class="muted">За эту неделю колледж ничего не менял. Если расписание перезальют с заменами — они появятся здесь, а изменённые пары подсветятся.</p>';
  } else {
    const byAt = {};
    for (const c of list) (byAt[c.at] = byAt[c.at] || []).push(c);
    chgList = chgList.slice(); // индексы дней не трогаем — добавляем в конец
    let h = "";
    for (const at of Object.keys(byAt).sort().reverse()) {
      h += `<h4>Обновление ${esc(fmtAt(at))}</h4>`;
      for (const c of byAt[at].sort((a, b) => (a.date + tmin(a.time)).localeCompare(b.date + tmin(b.time)))) {
        chgList.push(c);
        h += `<div class="chg-line" data-chg="${chgList.length - 1}"><span class="chg-badge">${changeLabel(c)}</span>` +
          `<div><div class="small muted">${esc(changeWhen(c))}</div><div>${esc(describeChange(c))}</div></div></div>`;
      }
    }
    $("infoBody").innerHTML = h;
  }
  $("infoDialog").showModal();
}

// ───────── сейчас / дальше ─────────
function markNow() {
  const today = iso(new Date()), m = nowMinutes();
  document.querySelectorAll(".pair[data-s]").forEach((el) => {
    const s = Number(el.dataset.s);
    el.classList.toggle("now", el.dataset.date === today && m >= s - 10 && m < s + PAIR_MINUTES);
  });
}

function updateNow() {
  markNow();
  const strip = $("nowStrip");
  if (!weekData) { strip.hidden = true; return; }
  const now = new Date(), today = iso(now), m = nowMinutes();
  let found = null;
  for (const date of Object.keys(weekData.days).sort()) {
    if (date < today) continue;
    for (const p of [...weekData.days[date]].sort((a, b) => tmin(a.time) - tmin(b.time))) {
      const s = tmin(p.time);
      if (date > today || m < s + PAIR_MINUTES) { found = { date, p, s, running: date === today && m >= s }; break; }
    }
    if (found) break;
  }
  if (!found) { strip.hidden = true; return; }
  const { date, p, s, running } = found;
  const left = (min) => (min < 60 ? `${min} мин` : `${Math.floor(min / 60)} ч${min % 60 ? ` ${min % 60} мин` : ""}`);
  const d = parseIso(date);
  let lbl, when;
  if (running) { lbl = "Сейчас"; when = `до конца ${left(s + PAIR_MINUTES - m)}`; }
  else if (date === today) { lbl = "Дальше"; when = `через ${left(s - m)}, в ${p.time}`; }
  else if (date === iso(addDays(now, 1))) { lbl = "Завтра"; when = `первая пара в ${p.time}`; }
  else { lbl = DAY_NAMES[d.getDay()]; when = `${fmtLong(d)}, в ${p.time}`; }
  const room = roomsOf(p.items);
  const extra = p.items.length > 1 ? ` +${p.items.length - 1}` : "";
  strip.className = "now-strip" + (running ? " running" : "");
  strip.innerHTML = `<span class="lbl">${lbl}</span><span class="txt"><div class="s1">${esc(cleanSubj((p.items[0] || {}).subject) || "Пара")}${extra}</div>` +
    `<div class="s2">${esc(when)}${room ? ` · каб. ${esc(room)}` : ""}</div></span>`;
  strip.dataset.date = date;
  strip.hidden = false;
}

// ───────── преподаватели и кабинеты ─────────
const collegeCache = {};
let cRows = null;
let cState = {};

function merge(rows) {
  const g = {};
  for (const r of rows) {
    const k = [r[0], r[1], r[4], r[5]].join("|");
    (g[k] = g[k] || []).push(r);
  }
  return Object.values(g).map((v) => {
    const cnt = {};
    for (const r of v) { const s = cleanSubj(r[3]); cnt[s] = (cnt[s] || 0) + 1; }
    const subj = Object.entries(cnt).sort((a, b) => b[1] - a[1])[0][0];
    return { date: v[0][0], time: v[0][1], room: v[0][4], teacher: v[0][5], subject: subj, groups: uniq(v.map((r) => r[2])) };
  }).sort((a, b) => (a.date === b.date ? tmin(a.time) - tmin(b.time) : a.date < b.date ? -1 : 1));
}

function statusOf(list, where) {
  const today = iso(new Date()), m = nowMinutes();
  const t = list.filter((o) => o.date === today);
  for (const o of t) {
    const s = tmin(o.time);
    if (m >= s && m < s + PAIR_MINUTES) return `Сейчас: ${where(o)} (до ${hm(s + PAIR_MINUTES)})`;
    if (m < s) return `Сегодня в ${o.time}: ${where(o)}`;
  }
  return t.length ? "Сегодня пар больше нет" : "Сегодня пар нет";
}

async function openCollege(opts = {}) {
  const w = index.weeks[weekIdx];
  cState = { tab: opts.room ? "r" : "t", teacher: null, room: opts.room || null, date: null, time: null, q: "" };
  $("collegeDialog").showModal();
  cRows = null;
  drawCollege();
  try {
    collegeCache[w.start] = collegeCache[w.start] || (await getJson(`../data/college/${w.start}.json`)).rows;
    cRows = collegeCache[w.start];
  } catch {
    $("cBody").innerHTML = '<p class="muted">Не удалось загрузить справочник. Проверьте интернет.</p>';
    return;
  }
  drawCollege();
}

function occHtml(list, body) {
  if (!list.length) return '<p class="muted">На этой неделе занятий нет</p>';
  const today = iso(new Date()), m = nowMinutes();
  let h = "", last = "";
  for (const o of list) {
    if (o.date !== last) {
      const d = parseIso(o.date);
      h += `<h4>${DAY_NAMES[d.getDay()]}, ${fmtLong(d)}${o.date === today ? " · сегодня" : ""}</h4>`;
      last = o.date;
    }
    const s = tmin(o.time);
    const now = o.date === today && m >= s && m < s + PAIR_MINUTES;
    h += `<div class="occ${now ? " now" : ""}"><b>${esc(o.time)}</b><div>${body(o)}</div></div>`;
  }
  return h;
}

function drawCollege() {
  const st = cState;
  const detail = st.teacher || st.room;
  $("cBack").hidden = !detail;
  $("cTabs").hidden = !!detail;
  $("cFav").hidden = !st.teacher;
  $("cTabs").querySelectorAll("button").forEach((b) => b.classList.toggle("on", b.dataset.tab === st.tab));
  const body = $("cBody");
  if (!cRows) { $("cTitle").textContent = "Поиск по колледжу"; $("cSub").textContent = ""; body.innerHTML = '<p class="muted">Загрузка…</p>'; return; }

  if (st.teacher) {
    const list = merge(cRows.filter((r) => teacherNames(r[5]).includes(st.teacher)));
    $("cTitle").textContent = st.teacher;
    $("cSub").textContent = statusOf(list, (o) => `каб. ${o.room || "?"}, ${o.groups.join(", ")}`);
    const on = favs.get().has(st.teacher);
    $("cFav").textContent = on ? "★" : "☆";
    $("cFav").classList.toggle("on", on);
    body.innerHTML = occHtml(list, (o) => `<div class="subj">${esc(o.subject)}</div><div class="meta">` +
      (o.room ? `<button class="room-link" data-room="${esc(o.room)}">каб. ${esc(o.room)}</button> · ` : "") + `${esc(o.groups.join(", "))}</div>`);
    return;
  }
  if (st.room) {
    const list = merge(cRows.filter((r) => r[4] === st.room || canonRoom(r[4]) === st.room));
    $("cTitle").textContent = /^\d/.test(st.room) ? `Кабинет ${st.room}` : st.room;
    $("cSub").textContent = statusOf(list, (o) => `${o.groups.join(", ")}, ${o.subject}`)
      .replace("Сегодня пар больше нет", "Сегодня больше свободен").replace("Сегодня пар нет", "Сегодня свободен весь день");
    body.innerHTML = occHtml(list, (o) => `<div class="subj">${esc(o.subject)}</div><div class="meta">${esc(o.groups.join(", "))}` +
      teacherNames(o.teacher).map((t, i) => `${i ? ", " : " · "}<button class="room-link" data-teacher="${esc(t)}">${esc(t)}</button>`).join("") + "</div>");
    return;
  }
  $("cTitle").textContent = "Поиск по колледжу";
  $("cSub").textContent = "";
  if (st.tab === "t") {
    body.innerHTML = `<input id="cSearch" class="c-search" type="search" placeholder="Фамилия, например Чусова" autocomplete="off"><div id="cList"></div>`;
    $("cSearch").value = st.q;
    $("cSearch").oninput = (e) => { st.q = e.target.value; fillTeachers(); };
    fillTeachers();
  } else {
    drawFreeRooms();
  }
}

function fillTeachers() {
  const by = {};
  for (const r of cRows) for (const t of teacherNames(r[5])) (by[t] = by[t] || []).push(r);
  const f = favs.get();
  const q = cState.q.trim().toLowerCase();
  const names = Object.keys(by).filter((t) => !q || t.toLowerCase().includes(q))
    .sort((a, b) => (f.has(b) - f.has(a)) || a.localeCompare(b, "ru"));
  $("cList").innerHTML = names.length ? names.map((t) => {
    const st = statusOf(merge(by[t]), (o) => `каб. ${o.room || "?"}`);
    return `<div class="c-row" data-teacher="${esc(t)}"><div class="grow"><div class="n">${esc(t)}</div><div class="st">${esc(st)}</div></div>` +
      `<button class="star${f.has(t) ? " on" : ""}" data-fav="${esc(t)}" aria-label="Избранное">${f.has(t) ? "★" : "☆"}</button></div>`;
  }).join("") : '<p class="muted">Никого не найдено</p>';
}

function drawFreeRooms() {
  const st = cState;
  const dates = uniq(cRows.map((r) => r[0])).sort();
  const today = iso(new Date());
  if (!st.date) st.date = dates.includes(today) ? today : dates[0];
  const times = uniq(cRows.filter((r) => r[0] === st.date).map((r) => r[1])).sort((a, b) => tmin(a) - tmin(b));
  if (!st.time || !times.includes(st.time)) {
    const m = nowMinutes();
    st.time = times.find((t) => st.date !== today || tmin(t) + PAIR_MINUTES > m) || times[0] || null;
  }
  const all = uniq(cRows.map((r) => r[4]).filter(isRealRoom))
    .sort((a, b) => (parseInt(a) || 1e9) - (parseInt(b) || 1e9) || a.localeCompare(b, "ru"));
  const busy = new Set(cRows.filter((r) => r[0] === st.date && r[1] === st.time).map((r) => r[4]));
  const free = all.filter((r) => !busy.has(r));
  $("cBody").innerHTML =
    `<div class="chips">${dates.map((d) => { const x = parseIso(d); return `<button class="chip${d === st.date ? " on" : ""}${d === today ? " today" : ""}" data-date="${d}">${DAY_SHORT[x.getDay()]} ${fmtShort(x)}</button>`; }).join("")}</div>` +
    `<div class="chips">${times.map((t) => `<button class="chip${t === st.time ? " on" : ""}" data-time="${esc(t)}">${esc(t)}</button>`).join("")}</div>` +
    `<p><b>${st.time ? `Свободно в ${esc(st.time)}: ${free.length} из ${all.length}` : "В этот день занятий нет — свободны все кабинеты."}</b></p>` +
    `<div class="chips wrap">${free.map((r) => `<button class="chip" data-room="${esc(r)}">${/^\d/.test(r) ? "каб. " : ""}${esc(r)}</button>`).join("")}</div>` +
    `<p class="muted small">По расписанию всех групп. Нажмите на кабинет, чтобы увидеть, кто и когда в нём занимается. Кабинет могут занять и вне расписания.</p>`;
  const on = $("cBody").querySelector(".chips .chip.on");
  if (on) on.scrollIntoView({ inline: "center", block: "nearest" });
}

function bindExtras() {
  $("openCollege").onclick = () => openCollege();
  $("openHistory").onclick = openHistory;
  $("nowStrip").onclick = () => {
    const el = document.getElementById("d" + $("nowStrip").dataset.date);
    if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
  };
  const onChg = (e) => {
    const c = e.target.closest("[data-chg]");
    if (c) { e.preventDefault(); openChange(chgList[Number(c.dataset.chg)]); return true; }
    return false;
  };
  $("days").addEventListener("click", (e) => {
    if (onChg(e)) return;
    const r = e.target.closest("[data-room]");
    if (r) openCollege({ room: r.dataset.room });
  });
  $("infoBody").addEventListener("click", onChg);
  $("cClose").onclick = () => $("collegeDialog").close();
  $("cBack").onclick = () => { cState.teacher = null; cState.room = null; drawCollege(); };
  $("cFav").onclick = () => { favs.toggle(cState.teacher); drawCollege(); };
  $("cTabs").onclick = (e) => { const b = e.target.closest("[data-tab]"); if (b) { cState.tab = b.dataset.tab; drawCollege(); } };
  $("cBody").addEventListener("click", (e) => {
    const fav = e.target.closest("[data-fav]");
    if (fav) { e.stopPropagation(); favs.toggle(fav.dataset.fav); fillTeachers(); return; }
    const t = e.target.closest("[data-teacher]");
    if (t) { cState.teacher = t.dataset.teacher; cState.room = null; drawCollege(); $("cBody").scrollTop = 0; return; }
    const r = e.target.closest("[data-room]");
    if (r) { cState.room = r.dataset.room; cState.teacher = null; drawCollege(); $("cBody").scrollTop = 0; return; }
    const d = e.target.closest("[data-date]");
    if (d) { cState.date = d.dataset.date; cState.time = null; drawFreeRooms(); return; }
    const tm = e.target.closest("[data-time]");
    if (tm) { cState.time = tm.dataset.time; drawFreeRooms(); }
  });
}

init();
