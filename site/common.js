// Общее для страниц сайта: баннер cookies, анонимные счётчики, данные о версиях APK.
import { firebaseConfig, REPO } from "./firebase-config.js";

const FB = "https://www.gstatic.com/firebasejs/10.14.1/";

// ---------------- cookies ----------------

export function getConsent() {
  const m = document.cookie.match(/(?:^|; )consent=(all|necessary)/);
  return m ? m[1] : null;
}

function setConsent(v) {
  document.cookie = `consent=${v}; max-age=${60 * 60 * 24 * 365}; path=/; SameSite=Lax`;
}

export function initCookieBanner(onChange) {
  const show = () => {
    let el = document.getElementById("cookieBanner");
    if (el) { el.hidden = false; return; }
    el = document.createElement("div");
    el.id = "cookieBanner";
    el.className = "cookie";
    el.setAttribute("role", "dialog");
    el.setAttribute("aria-label", "Cookies");
    el.innerHTML = `
      <p><b>Мы используем cookies.</b> Обязательные — чтобы запомнить ваш выбор и группу.
      Аналитические — чтобы анонимно посчитать, сколько людей заходит на сайт.
      Подробнее — в <a href="${rootPath()}privacy.html#cookies">политике конфиденциальности</a>.</p>
      <div class="cookie-btns">
        <button class="btn btn-ghost" data-v="necessary">Только обязательные</button>
        <button class="btn btn-primary" data-v="all">Принять все</button>
      </div>`;
    el.addEventListener("click", (e) => {
      const v = e.target?.dataset?.v;
      if (!v) return;
      setConsent(v);
      el.hidden = true;
      onChange?.(v);
    });
    document.body.appendChild(el);
  };
  if (!getConsent()) show();
  document.querySelectorAll("[data-cookie-settings]").forEach((a) =>
    a.addEventListener("click", (e) => { e.preventDefault(); show(); })
  );
}

function rootPath() {
  return location.pathname.includes("/app/") ? "../" : "";
}

// ---------------- Firebase (лениво) ----------------

let fbPromise = null;
export function firebase() {
  if (!firebaseConfig) return Promise.resolve(null);
  if (!fbPromise) {
    fbPromise = Promise.all([
      import(FB + "firebase-app.js"),
      import(FB + "firebase-firestore.js"),
    ]).then(([app, fs]) => {
      const a = app.getApps()[0] || app.initializeApp(firebaseConfig);
      return { app: a, fs, db: fs.getFirestore(a) };
    }).catch(() => null);
  }
  return fbPromise;
}

// ---------------- анонимные счётчики ----------------
// Хранятся как суммы по дням (stats/d_ГГГГ-ММ-ДД) и за всё время (stats/total).
// Никаких данных о человеке — только +1 к числу.

function today() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export async function count(field) {
  try {
    const f = await firebase();
    if (!f) return;
    const { fs, db } = f;
    const inc = { [field]: fs.increment(1) };
    await Promise.all([
      fs.setDoc(fs.doc(db, "stats", "total"), inc, { merge: true }),
      fs.setDoc(fs.doc(db, "stats", "d_" + today()), inc, { merge: true }),
    ]);
  } catch { /* счётчик не важнее сайта */ }
}

/** Посещение: считаем один раз в день и только с согласия на аналитические cookies. */
export function countVisit() {
  if (getConsent() !== "all") return;
  const key = "seen_" + today();
  if (document.cookie.includes(key + "=1")) return;
  document.cookie = `${key}=1; max-age=${60 * 60 * 24}; path=/; SameSite=Lax`;
  count("visits");
}

// ---------------- версии APK из GitHub Releases ----------------

export async function loadReleases() {
  const r = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=100`, {
    headers: { Accept: "application/vnd.github+json" },
  });
  if (!r.ok) throw new Error("GitHub " + r.status);
  const list = await r.json();
  return list
    .filter((x) => !x.draft)
    .map((x) => {
      const apk = (x.assets || []).find((a) => a.name.endsWith(".apk"));
      return {
        tag: x.tag_name,
        title: x.name || x.tag_name,
        notes: (x.body || "").trim(),
        date: new Date(x.published_at || x.created_at),
        url: apk?.browser_download_url,
        size: apk?.size || 0,
        downloads: apk?.download_count || 0,
        page: x.html_url,
      };
    })
    .filter((x) => x.url)
    .sort((a, b) => b.date - a.date);
}

export const fmtDate = (d) =>
  d.toLocaleDateString("ru-RU", { day: "numeric", month: "long", year: "numeric" });
export const fmtSize = (b) => (b / 1024 / 1024).toFixed(1).replace(".", ",") + " МБ";

export function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
