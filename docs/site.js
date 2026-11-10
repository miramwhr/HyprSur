(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const root = document.documentElement;
  const store = { get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }, set(k, v) { try { localStorage.setItem(k, v); } catch (e) {} } };

  /* ================= данные ================= */
  const APPS = [
    { id: "finder", name: "Finder", lang: "Rust", w: 1018, h: 638, ext: "svg",
      say: "Файлы там, где ты их ждёшь.",
      list: ["Избранное, места и цветные теги в боковом меню", "Четыре вида: значки, список, колонки, галерея", "Строка пути и счётчик свободного места внизу"],
      path: "System/Library/CoreServices/Finder.app" },
    { id: "settings", name: "Настройки", lang: "Python", w: 920, h: 640, dark: true,
      say: "Переключил, и оно уже применилось.",
      list: ["Оформление, Дисплей, Обои, Dock, Пункт управления, Универсальный доступ", "Девять цветов акцента", "Проверка обновлений через GitHub Releases", "Каждая настройка сразу пишется в plist"],
      path: "System/Applications/Settings.app", data: "/Hyprsur/Library/Preferences/" },
    { id: "appstore", name: "App Store", lang: "Rust + Python", w: 1180, h: 760,
      say: "Один магазин для pacman, AUR и Flatpak.",
      list: ["Установка, удаление и обновление программ", "AUR через yay или paru", "hyprSur ID хранится только на этом компьютере"],
      path: "System/Applications/App_Store.app" },
    { id: "calendar", name: "Календарь", lang: "Python", w: 1100, h: 720, dark: true, ext: "svg",
      say: "День, неделя, месяц и год.",
      list: ["Несколько календарей со своими цветами", "События по часам и на весь день", "Мини-календарь в боковом меню"],
      path: "System/Applications/Calendar.app", data: "~/Library/Application Support/Calendar/" },
    { id: "notes", name: "Заметки", lang: "Python", w: 1000, h: 640, dark: true,
      say: "Первая строка становится заголовком.",
      list: ["Папки, закрепление и поиск", "Список сам делится на «Сегодня», «Вчера», «7 дней»", "«Недавно удалённые» вместо мгновенного удаления"],
      path: "System/Applications/Notes.app", data: "~/Library/Application Support/Notes/" },
    { id: "reminders", name: "Напоминания", lang: "Python", w: 900, h: 600, dark: true,
      say: "Списки, которые считают себя сами.",
      list: ["Сегодня, Запланировано, Все, С флажком, Завершено", "Свои списки с цветами", "Сроки, флажки и заметки к пунктам"],
      path: "System/Applications/Reminders.app", data: "~/Library/Application Support/Reminders/" },
    { id: "pages", name: "Pages", lang: "Python", w: 1180, h: 820,
      say: "Лист A4 и ничего лишнего.",
      list: ["Стили абзаца, начертания, размер, цвет, выравнивание", "Отмена и повтор", "Свой формат .pages, открывает и .txt"],
      path: "System/Applications/Pages.app" },
    { id: "numbers", name: "Numbers", lang: "Python", w: 1180, h: 780,
      say: "Формулы понимают и SUM, и СУММ.",
      list: ["Листы вкладками", "=A1+B2*2, SUM, AVERAGE, MIN, MAX, COUNT, ROUND, ABS", "Открывает и сохраняет .csv"],
      path: "System/Applications/Numbers.app" },
    { id: "keynote", name: "Keynote", lang: "Python", w: 1200, h: 760,
      say: "Слайды 16:9 и показ на весь экран.",
      list: ["Навигатор слайдов и макеты", "Три темы: белая, чёрная, градиент", "Показ по Ctrl+Alt+P"],
      path: "System/Applications/Keynote.app" },
    { id: "terminal", name: "Терминал", lang: "Python + VTE", w: 720, h: 460, live: "term",
      say: "Чёрное на белом, как на маке.",
      list: ["Вкладки и окна", "Ctrl+Shift+K очищает экран", "Размер шрифта на Ctrl и плюс или минус"],
      path: "System/Applications/Terminal.app" },
    { id: "calculator", name: "Калькулятор", lang: "Python", w: 232, h: 363, live: "calc",
      say: "Девять цифр и точный счёт.",
      list: ["Считает на Decimal без ошибок округления", "Управляется с клавиатуры", "Окно всегда тёмное, как в оригинале"],
      path: "System/Applications/Calculator.app" },
    { id: "iconcomposer", name: "Icon Composer", lang: "Rust", w: 1440, h: 900,
      say: "Значки приложений собираются из слоёв.",
      list: ["Слои и группы слева, холст в центре", "Заливка и градиент справа", "Варианты значка переключаются внизу"],
      path: "System/Applications/Icon_Composer.app" }
  ];
  const byId = (id) => APPS.find((a) => a.id === id);
  const icon = (a) => "img/icon/" + a.id + "." + (a.ext || "webp");
  const ACCENTS = [
    [null, "#007AFF", "Разноцветный"], [4, "#007AFF", "Синий"], [5, "#AF52DE", "Фиолетовый"], [6, "#FF2D55", "Розовый"],
    [0, "#FF3B30", "Красный"], [1, "#FF9500", "Оранжевый"], [2, "#FFCC00", "Желтый"], [3, "#34C759", "Зеленый"], [-1, "#8E8E93", "Графитовый"]
  ];
  const DARK_INK = { "#FF9500": 1, "#FFCC00": 1, "#34C759": 1 };
  const VER = [
    ["15", "Тип сборки. 15 это HS, сам HyprSur. Обновления ищутся только среди релизов своего типа."],
    ["V", "Метка: дальше идёт версия."],
    ["03", "Публичная версия, 0.3. Внутренняя сейчас 2.79 и может скакать, публичная растёт по порядку."],
    ["B", "Канал. B значит бета, V значит обычный выпуск. При одинаковом номере выпуск новее беты."],
    ["00", "Патч в тысячных. 96 дало бы версию 0.396. Нужен для срочных исправлений."],
    ["NR", "Not Released. Пометка для людей, при сравнении версий не учитывается."]
  ];

  /* ================= тема, акцент, стекло ================= */
  const state = { dark: false, glass: true, solid: false, accent: 3 };
  const sysDark = () => window.matchMedia && matchMedia("(prefers-color-scheme: dark)").matches;
  const desk = $("desk");
  function log(file, key, val) {
    const box = $("cc-log");
    box.textContent = file + "\n" + (val === null ? "− " + key + "  (ключ удалён)" : key + " = " + val) + "\nтема " + themeNo() + " из 4";
  }
  function themeNo() { return (state.dark ? 2 : 1) + (state.solid ? 2 : 0); }
  function applyTheme(save) {
    root.setAttribute("data-theme", state.dark ? "dark" : "light");
    if (save) store.set("hs-theme", state.dark ? "dark" : "light");
    $("sw-dark").setAttribute("aria-checked", String(state.dark));
    desk.classList.toggle("no-glass", !state.glass);
    desk.classList.toggle("solid", state.solid);
    $("sw-glass").setAttribute("aria-checked", String(state.glass));
    $("sw-solid").setAttribute("aria-checked", String(state.solid));
    refreshShots();
  }
  function applyAccent(n) {
    state.accent = n;
    const a = ACCENTS.find((x) => x[0] === n) || ACCENTS[7];
    root.style.setProperty("--accent", a[1]);
    root.style.setProperty("--on-accent", DARK_INK[a[1]] ? "#06210f" : "#ffffff");
    Array.prototype.forEach.call($("dots").children, (b, i) => b.setAttribute("aria-pressed", String(ACCENTS[i][0] === n)));
  }
  ACCENTS.forEach((a) => {
    const b = el("button"); b.type = "button"; b.title = a[2]; b.setAttribute("aria-label", a[2]);
    b.style.setProperty("--c", a[0] === null ? "conic-gradient(#ff3b30,#ff9500,#ffcc00,#34c759,#007aff,#af52de,#ff3b30)" : a[1]);
    b.addEventListener("click", () => { applyAccent(a[0]); log(".GlobalPreferences.plist", "AppleAccentColor", a[0]); store.set("hs-accent", String(a[0])); });
    $("dots").appendChild(b);
  });
  $("sw-dark").addEventListener("click", () => { state.dark = !state.dark; applyTheme(true); log(".GlobalPreferences.plist", "AppleInterfaceStyle", state.dark ? "Dark" : null); });
  $("sw-glass").addEventListener("click", () => { state.glass = !state.glass; applyTheme(); log(".GlobalPreferences.plist", "HyprsurLiquidGlass", state.glass); });
  $("sw-solid").addEventListener("click", () => { state.solid = !state.solid; applyTheme(); log("com.hyprsur.universalaccess.plist", "reduceTransparency", state.solid); });
  $("theme").addEventListener("click", () => { state.dark = !state.dark; applyTheme(true); });

  /* ================= окна ================= */
  const wins = $("wins"), dock = $("dock");
  const open = {}; let zTop = 10, order = 0;
  const small = () => desk.clientWidth < 620;

  function shotSrc(a) { return "img/" + a.id + (state.dark && a.dark ? "-dark" : "") + ".webp"; }
  function refreshShots() {
    Object.keys(open).forEach((id) => { const a = byId(id); if (a && !a.live && open[id].img) open[id].img.src = shotSrc(a); });
    if (typeof showApp === "function" && curApp != null) showApp(curApp);
  }
  function focusWin(id) {
    Object.keys(open).forEach((k) => open[k].node.classList.toggle("front", k === id));
    if (id && open[id]) { open[id].node.style.zIndex = ++zTop; $("m-app").textContent = open[id].title; }
    else $("m-app").textContent = "Finder";
  }
  function topWin() {
    let best = null, z = -1;
    Object.keys(open).forEach((k) => { const n = open[k].node; if (!n.hidden && +n.style.zIndex > z) { z = +n.style.zIndex; best = k; } });
    return best;
  }
  function markDock() { Array.prototype.forEach.call(dock.querySelectorAll("button[data-id]"), (b) => b.classList.toggle("run", !!open[b.dataset.id])); }
  function closeWin(id) { if (!open[id]) return; open[id].node.remove(); delete open[id]; markDock(); focusWin(topWin()); }

  function makeWin(id, title, body, width) {
    const node = el("div", "win"), bar = el("div", "win-bar");
    [["c", "Закрыть"], ["m", "Свернуть"], ["z", "На весь стол"]].forEach((d) => {
      const b = el("button", "lt " + d[0]); b.type = "button"; b.title = d[1]; b.setAttribute("aria-label", d[1] + ": " + title);
      b.addEventListener("pointerdown", (e) => e.stopPropagation());
      b.addEventListener("click", () => {
        if (d[0] === "c") closeWin(id);
        else if (d[0] === "m") { node.hidden = true; focusWin(topWin()); }
        else { node.classList.toggle("max"); focusWin(id); }
      });
      bar.appendChild(b);
    });
    bar.appendChild(el("b", null, title));
    const wb = el("div", "win-body"); wb.appendChild(body);
    node.appendChild(bar); node.appendChild(wb);
    const W = wins.clientWidth, H = wins.clientHeight - 84;
    const w = Math.min(width, W - 16);
    node.style.width = w + "px";
    const n = order++ % 6;
    node.style.left = Math.max(8, Math.min(W - w - 8, W * 0.05 + n * 34)) + "px";
    node.style.top = (10 + n * 24) + "px";
    node.addEventListener("pointerdown", () => focusWin(id));
    drag(node, bar);
    wins.appendChild(node);
    open[id] = { node, title, img: body.tagName === "IMG" ? body : null };
    // не даём окну уйти ниже дока
    requestAnimationFrame(() => { const over = node.offsetTop + node.offsetHeight - H; if (over > 0) node.style.top = Math.max(4, node.offsetTop - over) + "px"; });
    markDock(); focusWin(id);
    return node;
  }
  function drag(node, bar) {
    let sx, sy, ox, oy, on = false;
    bar.addEventListener("pointerdown", (e) => {
      if (node.classList.contains("max")) return;
      on = true; sx = e.clientX; sy = e.clientY; ox = node.offsetLeft; oy = node.offsetTop;
      bar.setPointerCapture(e.pointerId);
    });
    bar.addEventListener("pointermove", (e) => {
      if (!on) return;
      const W = wins.clientWidth, H = wins.clientHeight;
      node.style.left = Math.max(60 - node.offsetWidth, Math.min(W - 60, ox + e.clientX - sx)) + "px";
      node.style.top = Math.max(0, Math.min(H - 110, oy + e.clientY - sy)) + "px";
    });
    const end = () => { on = false; };
    bar.addEventListener("pointerup", end); bar.addEventListener("pointercancel", end);
  }
  function openApp(id, pos) {
    closePops();
    if (id === "launchpad") { togglePop("lpad"); return; }
    if (open[id]) { open[id].node.hidden = false; focusWin(id); return; }
    let node;
    if (id === "about") node = makeWin(id, "Об этой системе", aboutBody(), 380);
    else {
      const a = byId(id); if (!a) return;
      if (a.live === "calc") node = makeWin(id, a.name, calcBody(), 232);
      else if (a.live === "term") node = makeWin(id, "hyprsur — bash — 78×21", termBody(), 560);
      else {
        const img = el("img"); img.src = shotSrc(a); img.alt = a.name + ", окно приложения"; img.width = a.w; img.height = a.h; img.draggable = false;
        const W = wins.clientWidth, H = wins.clientHeight - 84 - 28;
        node = makeWin(id, a.name, img, Math.min(a.w * 0.66, W * 0.74, H * a.w / a.h));
      }
    }
    if (pos && !small()) { node.style.left = pos[0] * wins.clientWidth + "px"; node.style.top = pos[1] + "px"; }
  }

  /* ---- Калькулятор ---- */
  function calcBody() {
    const box = el("div", "calc"), out = el("output", null, "0"), keys = el("div", "calc-keys");
    box.tabIndex = 0;
    let cur = "0", acc = null, op = null, fresh = true;
    const ops = { "÷": (a, b) => a / b, "×": (a, b) => a * b, "−": (a, b) => a - b, "+": (a, b) => a + b };
    function fmt(s) {
      let n = Number(s); if (!isFinite(n)) return "Ошибка";
      if (/[.]$|[.]\d*0$/.test(s) && s.replace(/[-.]/g, "").length <= 9) { const p = s.split("."); return Number(p[0]).toLocaleString("ru-RU") + "," + p[1]; }
      if (Math.abs(n) >= 1e9 || (n !== 0 && Math.abs(n) < 1e-8)) return n.toExponential(4).replace(".", ",");
      n = Number(n.toPrecision(9));
      return n.toLocaleString("ru-RU", { maximumFractionDigits: 8 });
    }
    function paint() {
      const t = fmt(cur); out.textContent = t;
      out.className = t.length > 9 ? "xs" : t.length > 7 ? "s" : "";
      Array.prototype.forEach.call(keys.querySelectorAll(".op"), (b) => b.classList.toggle("held", fresh && op === b.textContent));
    }
    function calc() { if (op && acc !== null) { cur = String(Number(ops[op](acc, Number(cur)).toPrecision(12))); } }
    function press(k) {
      if (/^\d$/.test(k)) { if (fresh) { cur = k; fresh = false; } else if (cur.replace(/[-.]/g, "").length < 9) cur = cur === "0" ? k : cur + k; }
      else if (k === ".") { if (fresh) { cur = "0."; fresh = false; } else if (cur.indexOf(".") < 0) cur += "."; }
      else if (k === "AC") { cur = "0"; acc = null; op = null; fresh = true; }
      else if (k === "+/−") { cur = String(-Number(cur)); }
      else if (k === "%") { cur = String(Number(cur) / 100); }
      else if (k === "=") { calc(); acc = null; op = null; fresh = true; }
      else if (k === "⌫") { if (!fresh) cur = cur.length > 1 ? cur.slice(0, -1) : "0"; }
      else { if (!fresh || acc === null) { calc(); acc = Number(cur); } op = k; fresh = true; }
      paint();
    }
    [["AC", "fn"], ["+/−", "fn"], ["%", "fn"], ["÷", "op"], ["7"], ["8"], ["9"], ["×", "op"], ["4"], ["5"], ["6"], ["−", "op"], ["1"], ["2"], ["3"], ["+", "op"], ["0", "wide"], ["."], ["=", "op"]]
      .forEach((k) => { const b = el("button", k[1] || "", k[0] === "." ? "," : k[0]); b.type = "button"; b.addEventListener("click", () => press(k[0])); keys.appendChild(b); });
    box.addEventListener("keydown", (e) => {
      const map = { "/": "÷", "*": "×", "-": "−", "+": "+", "Enter": "=", "=": "=", "Escape": "AC", "Backspace": "⌫", ",": ".", ".": ".", "%": "%" };
      const k = /^\d$/.test(e.key) ? e.key : map[e.key];
      if (k) { e.preventDefault(); e.stopPropagation(); press(k); }
    });
    box.appendChild(out); box.appendChild(keys);
    return box;
  }

  /* ---- Терминал ---- */
  function termBody() {
    const box = el("div", "term"), log = el("pre"), form = el("form"), ps = el("span", null, "hyprsur@vm:~$"), inp = el("input");
    inp.type = "text"; inp.autocomplete = "off"; inp.spellcheck = false; inp.setAttribute("aria-label", "Команда"); inp.id = "term-in";
    form.appendChild(ps); form.appendChild(inp); box.appendChild(log); box.appendChild(form);
    const print = (t) => { log.textContent += t + "\n"; box.scrollTop = box.scrollHeight; };
    const hist = []; let hi = 0;
    const cmds = {
      help: () => "help         эта справка\nls           что лежит в /Hyprsur/System/Applications\nopen ИМЯ     открыть приложение (open notes, open finder)\nversion      номер сборки\ntheme dark|light   сменить оформление\nglass on|off Liquid Glass\ninstall      перейти к установке\nclear        очистить экран",
      ls: () => "App_Store.app      Icon_Composer.app  Notes.app      Reminders.app  Utilities\nCalculator.app     Keynote.app        Numbers.app    Settings.app\nCalendar.app       Launchpad.app      Pages.app      Terminal.app",
      version: () => "HyprSur 15V03B00NR  (публичная 0.3, внутренняя 2.79, канал: бета)",
      whoami: () => "hyprsur",
      uname: () => "Linux",
      pwd: () => "/home/hyprsur",
      clear: () => { log.textContent = ""; return null; },
      install: () => { location.hash = "#install"; return "открываю раздел «Установка»"; },
      open: (a) => {
        const q = (a || "").toLowerCase(); if (!q) return "open: укажи имя, например open notes";
        const app = APPS.find((x) => x.id === q || x.id.indexOf(q) === 0 || x.name.toLowerCase().indexOf(q) === 0 || x.path.toLowerCase().indexOf("/" + q) >= 0);
        if (!app) return "open: не нашёл «" + a + "». Попробуй ls";
        setTimeout(() => openApp(app.id), 0); return "открываю " + app.name;
      },
      theme: (a) => { if (a !== "dark" && a !== "light") return "theme: dark или light"; state.dark = a === "dark"; applyTheme(true); log.textContent += ""; return ".GlobalPreferences.plist: AppleInterfaceStyle " + (state.dark ? "= Dark" : "удалён"); },
      glass: (a) => { if (a !== "on" && a !== "off") return "glass: on или off"; state.glass = a === "on"; applyTheme(); return ".GlobalPreferences.plist: HyprsurLiquidGlass = " + state.glass; }
    };
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      const line = inp.value.trim(); inp.value = "";
      print("hyprsur@vm:~$ " + line);
      if (!line) return;
      hist.push(line); hi = hist.length;
      const p = line.split(/\s+/), f = cmds[p[0]];
      const res = f ? f(p[1]) : "bash: " + p[0] + ": команда не найдена. Набери help";
      if (res != null) print(res);
    });
    inp.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (e.key === "ArrowUp" && hi > 0) { inp.value = hist[--hi]; e.preventDefault(); }
      if (e.key === "ArrowDown") { hi = Math.min(hist.length, hi + 1); inp.value = hist[hi] || ""; e.preventDefault(); }
    });
    box.addEventListener("click", () => { if (!String(getSelection())) inp.focus({ preventScroll: true }); });
    print("Это настоящая строка ввода. Набери help и нажми Enter.");
    return box;
  }

  /* ---- Об этой системе ---- */
  function verWidget(rowEl, outEl, start) {
    const segs = [];
    function pick(i) {
      segs.forEach((b, n) => b.setAttribute("aria-pressed", String(n === i)));
      outEl.textContent = VER[i][1];
    }
    VER.forEach((v, i) => { const b = el("button", "seg", v[0]); b.type = "button"; b.addEventListener("click", () => pick(i)); rowEl.appendChild(b); segs.push(b); });
    pick(start);
  }
  function aboutBody() {
    const box = el("div", "about");
    box.appendChild(el("h4", null, "HyprSur"));
    box.appendChild(el("p", null, "Версия 0.3, бета"));
    const row = el("div", "ver-row"), out = el("p", "ver-out");
    box.appendChild(row); box.appendChild(out); verWidget(row, out, 2);
    return box;
  }
  Array.prototype.forEach.call(document.querySelectorAll("[data-ver]"), (row) => verWidget(row, row.parentNode.querySelector(".ver-out"), 3));

  /* ================= док, меню, панели ================= */
  const DOCK = ["finder", "launchpad", "settings", "appstore", "calendar", "notes", "reminders", "pages", "numbers", "keynote", "|", "terminal", "calculator", "iconcomposer"];
  DOCK.forEach((id) => {
    if (id === "|") { dock.appendChild(el("span", "sep")); return; }
    const a = byId(id), b = el("button"), img = el("img");
    b.type = "button"; b.dataset.id = id; b.dataset.name = a ? a.name : "Launchpad"; b.setAttribute("aria-label", b.dataset.name);
    img.src = a ? icon(a) : "img/icon/launchpad.webp"; img.alt = ""; img.width = 50; img.height = 50; img.draggable = false;
    b.appendChild(img);
    b.addEventListener("click", () => openApp(id));
    dock.appendChild(b);
  });
  const dockBtns = Array.prototype.slice.call(dock.querySelectorAll("button"));
  dock.addEventListener("pointermove", (e) => {
    if (e.pointerType !== "mouse" || small()) return;
    dockBtns.forEach((b) => { const r = b.getBoundingClientRect(), d = Math.abs(e.clientX - (r.left + r.width / 2)); b.style.transform = "scale(" + (1 + Math.max(0, 1 - d / 110) * 0.42).toFixed(3) + ")"; });
  });
  dock.addEventListener("pointerleave", () => dockBtns.forEach((b) => { b.style.transform = ""; }));

  const POPS = { menu: "m-logo", cc: "m-cc", spot: "m-search", lpad: null };
  function closePops(except) {
    Object.keys(POPS).forEach((k) => { if (k !== except) { $(k).hidden = true; if (POPS[k]) $(POPS[k]).setAttribute("aria-expanded", "false"); } });
  }
  function togglePop(k) {
    const show = $(k).hidden; closePops();
    $(k).hidden = !show; if (POPS[k]) $(POPS[k]).setAttribute("aria-expanded", String(show));
    if (show && k === "spot") { $("spot-in").value = ""; spotFill(""); $("spot-in").focus({ preventScroll: true }); }
    if (show && k === "cc" && !$("cc-log").textContent) log(".GlobalPreferences.plist", "AppleAccentColor", state.accent);
  }
  $("m-logo").addEventListener("click", (e) => { e.stopPropagation(); togglePop("menu"); });
  $("m-cc").addEventListener("click", (e) => { e.stopPropagation(); togglePop("cc"); });
  $("m-search").addEventListener("click", (e) => { e.stopPropagation(); togglePop("spot"); });
  ["menu", "cc", "spot"].forEach((k) => $(k).addEventListener("pointerdown", (e) => e.stopPropagation()));
  $("lpad").addEventListener("click", () => closePops());
  desk.addEventListener("pointerdown", (e) => { if (!e.target.closest(".mbar")) closePops("lpad"); });
  wins.addEventListener("pointerdown", (e) => { if (e.target === wins) focusWin(null); });

  function resetDesk() {
    Object.keys(open).forEach((k) => { open[k].node.remove(); delete open[k]; });
    order = 0;
    if (small()) { openApp("settings"); openApp("calculator"); open.calculator.node.style.left = (wins.clientWidth - 244) + "px"; open.calculator.node.style.top = "150px"; }
    else { openApp("notes", [0.34, 22]); openApp("settings", [0.05, 62]); openApp("calculator", [0.76, 96]); }
    markDock();
  }
  $("menu").addEventListener("click", (e) => {
    const act = e.target.dataset && e.target.dataset.act; if (!act) return;
    closePops();
    if (act === "about") openApp("about");
    else if (act === "settings") openApp("settings");
    else if (act === "launchpad") togglePop("lpad");
    else if (act === "spotlight") togglePop("spot");
    else if (act === "closeall") { Object.keys(open).forEach(closeWin); }
    else if (act === "reset") resetDesk();
  });

  APPS.forEach((a) => {
    const b = el("button"), img = el("img"); b.type = "button"; img.src = icon(a); img.alt = ""; img.width = 62; img.height = 62;
    b.appendChild(img); b.appendChild(el("span", null, a.name));
    b.addEventListener("click", (e) => { e.stopPropagation(); openApp(a.id); });
    $("lpad-grid").appendChild(b);
  });

  /* ---- Spotlight ---- */
  const PLACES = [["Установка", "#install"], ["Аксиомы", "#axioms"], ["Приложения", "#apps"], ["Система", "#system"], ["Устройство папок", "#anatomy"], ["Обои surw", "#wallpaper"], ["Версии", "#versions"], ["Разработчикам", "#dev"], ["Что не готово", "#plan"], ["Вопросы", "#faq"]];
  let spotItems = [], spotSel = 0;
  function spotFill(q) {
    q = q.trim().toLowerCase();
    const res = $("spot-res"); res.textContent = ""; spotItems = []; spotSel = 0;
    APPS.forEach((a) => { if (!q || a.name.toLowerCase().indexOf(q) >= 0 || a.id.indexOf(q) >= 0) spotItems.push({ t: a.name, k: "Приложение", i: icon(a), go: () => openApp(a.id) }); });
    if ("об этой системе версия".indexOf(q) >= 0 || !q) spotItems.push({ t: "Об этой системе", k: "Система", go: () => openApp("about") });
    PLACES.forEach((p) => { if (q && p[0].toLowerCase().indexOf(q) >= 0) spotItems.push({ t: p[0], k: "Раздел сайта", go: () => { closePops(); location.hash = p[1]; } }); });
    spotItems = spotItems.slice(0, 7);
    spotItems.forEach((it, n) => {
      const b = el("button", n === 0 ? "sel" : ""); b.type = "button";
      if (it.i) { const im = el("img"); im.src = it.i; im.alt = ""; b.appendChild(im); }
      b.appendChild(el("span", null, it.t)); b.appendChild(el("small", null, it.k));
      b.addEventListener("click", it.go); res.appendChild(b);
    });
    if (!spotItems.length) res.appendChild(el("button", null, "Ничего не нашлось")).disabled = true;
  }
  $("spot-in").addEventListener("input", (e) => spotFill(e.target.value));
  $("spot-in").addEventListener("keydown", (e) => {
    e.stopPropagation();
    if (e.key === "Enter" && spotItems[spotSel]) spotItems[spotSel].go();
    else if (e.key === "Escape") closePops();
    else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault(); if (!spotItems.length) return;
      spotSel = (spotSel + (e.key === "ArrowDown" ? 1 : -1) + spotItems.length) % spotItems.length;
      Array.prototype.forEach.call($("spot-res").children, (b, n) => b.classList.toggle("sel", n === spotSel));
    }
  });
  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") { e.preventDefault(); desk.scrollIntoView({ block: "center" }); if ($("spot").hidden) togglePop("spot"); }
    else if (e.key === "Escape") closePops();
  });
  Array.prototype.forEach.call(document.querySelectorAll("[data-desk]"), (a) => a.addEventListener("click", () => {
    const k = a.dataset.desk; setTimeout(() => { closePops(); if (k === "spotlight") togglePop("spot"); else if (k === "launchpad") togglePop("lpad"); }, 450);
  }));

  function tick() {
    const d = new Date();
    $("m-time").textContent = d.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
    $("m-date").textContent = d.toLocaleDateString("ru-RU", { weekday: "short", day: "numeric", month: "short" }).replace(/\./g, "");
  }
  tick(); setInterval(tick, 15000);

  /* ================= раздел «Приложения» ================= */
  let curApp = null, shotDark = false, tabs = [];
  function showApp(i) {
    curApp = (i + APPS.length) % APPS.length;
    const a = APPS[curApp], dk = shotDark && a.dark;
    tabs.forEach((t, n) => { t.setAttribute("aria-selected", String(n === curApp)); t.tabIndex = n === curApp ? 0 : -1; });
    const img = $("shot-img");
    img.src = "img/" + a.id + (dk ? "-dark" : "") + ".webp"; img.width = a.w; img.height = a.h;
    img.alt = a.name + " в HyprSur, " + (dk ? "тёмная" : "светлая") + " тема";
    $("show-icon").src = icon(a); $("show-name").textContent = a.name; $("show-lang").textContent = a.lang; $("show-say").textContent = a.say;
    const ul = $("show-list"); ul.textContent = ""; a.list.forEach((t) => ul.appendChild(el("li", null, t)));
    const dl = $("show-facts"); dl.textContent = "";
    [["Лежит в", "/Hyprsur/" + a.path]].concat(a.data ? [["Данные", a.data]] : []).forEach((f) => { dl.appendChild(el("dt", null, f[0])); dl.appendChild(el("dd", null, f[1])); });
    $("shot-dark").disabled = !a.dark; $("shot-dark").title = a.dark ? "" : "Снята только светлая тема";
    $("shot-light").setAttribute("aria-pressed", String(!dk)); $("shot-dark").setAttribute("aria-pressed", String(!!dk));
  }
  APPS.forEach((a, i) => {
    const b = el("button", "chip"), im = el("img"); b.type = "button"; b.setAttribute("role", "tab"); im.src = icon(a); im.alt = ""; im.width = 24; im.height = 24;
    b.appendChild(im); b.appendChild(document.createTextNode(a.name));
    b.addEventListener("click", () => showApp(i)); $("chips").appendChild(b); tabs.push(b);
    const c = el("button"), th = el("span", "thumb"), ti = el("img"), cap = el("span", "cap");
    c.type = "button"; c.setAttribute("aria-label", "Показать " + a.name);
    ti.src = "img/" + a.id + ".webp"; ti.alt = ""; ti.loading = "lazy"; ti.width = a.w; ti.height = a.h;
    th.appendChild(ti); cap.appendChild(el("b", null, a.name)); cap.appendChild(el("span", null, a.say));
    c.appendChild(th); c.appendChild(cap);
    c.addEventListener("click", () => { showApp(i); $("chips").scrollIntoView({ block: "start" }); });
    $("wall-grid").appendChild(c);
  });
  $("chips").addEventListener("keydown", (e) => {
    const d = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0; if (!d) return;
    e.preventDefault(); showApp(curApp + d); tabs[curApp].focus();
  });
  $("shot-light").addEventListener("click", () => { shotDark = false; showApp(curApp); });
  $("shot-dark").addEventListener("click", () => { shotDark = true; showApp(curApp); });
  $("show-open").addEventListener("click", () => { const id = APPS[curApp].id; desk.scrollIntoView({ block: "center" }); setTimeout(() => openApp(id), 350); });
  const zoom = $("zoom");
  $("shot").addEventListener("click", () => {
    $("zoom-img").src = $("shot-img").src; $("zoom-img").alt = $("shot-img").alt; $("zoom-cap").textContent = APPS[curApp].name;
    if (zoom.showModal) zoom.showModal(); else zoom.setAttribute("open", "");
  });
  zoom.addEventListener("click", (e) => { if (e.target === zoom) zoom.close(); });

  /* ================= лента аксиом ================= */
  const rail = $("rail");
  function railStep(d) { const card = rail.querySelector(".ax"); rail.scrollBy({ left: d * (card.offsetWidth + 20), behavior: "smooth" }); }
  function railState() { $("rail-prev").disabled = rail.scrollLeft < 8; $("rail-next").disabled = rail.scrollLeft > rail.scrollWidth - rail.clientWidth - 8; }
  $("rail-prev").addEventListener("click", () => railStep(-1));
  $("rail-next").addEventListener("click", () => railStep(1));
  rail.addEventListener("scroll", railState, { passive: true }); railState();

  /* ================= имена значков ================= */
  const T4 = [["1", "светлая", "#f6f6f6", "#1d1d1f"], ["2", "тёмная", "#28282a", "#f5f5f7"], ["3", "светлая, без прозрачности", "#ffffff", "#1d1d1f"], ["4", "тёмная, без прозрачности", "#000000", "#f5f5f7"]];
  let gT = 0, gOn = true; const gBtns = [];
  function glyph() {
    gBtns.forEach((b, n) => b.setAttribute("aria-pressed", String(n === gT)));
    $("g-on").setAttribute("aria-pressed", String(gOn)); $("g-off").setAttribute("aria-pressed", String(!gOn));
    const f = $("fname"); f.textContent = "Bluetooth_";
    f.appendChild(el("mark", null, T4[gT][0])); f.appendChild(document.createTextNode("."));
    f.appendChild(el("mark", null, gOn ? "1" : "2")); f.appendChild(document.createTextNode(".svg"));
  }
  T4.forEach((t, n) => {
    const b = el("button"); b.type = "button"; b.style.setProperty("--t-bg", t[2]); b.style.setProperty("--t-fg", t[3]);
    b.appendChild(el("b", null, t[0])); b.appendChild(el("span", null, t[1]));
    b.addEventListener("click", () => { gT = n; glyph(); }); $("themes4").appendChild(b); gBtns.push(b);
  });
  $("g-on").addEventListener("click", () => { gOn = true; glyph(); });
  $("g-off").addEventListener("click", () => { gOn = false; glyph(); });
  glyph();

  /* ================= дерево ================= */
  const TREE = { n: "/Hyprsur", t: "Корень системы", d: "Вся система лежит в одной папке в корне диска. Путь вписан в приложения, поэтому менять его нельзя.", open: true, c: [
    { n: "System", t: "То, что ставится с системой", d: "Программы и служебные части. Обновление заменяет эту папку целиком и не трогает настройки.", open: true, c: [
      { n: "Applications", t: "Программы", d: "Двенадцать папок .app и папка Utilities. Док и Launchpad берут программы отсюда.", open: true, c: [
        { n: "Calculator.app", t: "Одно приложение", d: "Папка, а не файл. Внутри всё, что нужно программе: код, стили, значки и паспорт.", open: true, c: [
          { n: "Contents", t: "Содержимое", d: "Единственная папка внутри .app, как на маке.", open: true, c: [
            { n: "Info.plist", t: "Паспорт приложения", d: "Имя, идентификатор, исполняемый файл и класс окна, по которому док узнаёт приложение.", code: "<key>CFBundleIdentifier</key>\n<string>com.hyprsur.calculator</string>\n<key>CFBundleExecutable</key>\n<string>Calculator</string>\n<key>HyprsurWindowClass</key>\n<string>com.hyprsur.calculator</string>" },
            { n: "MacOS/Calculator", t: "Сама программа", d: "Скрипт на Python без расширения. У Finder, Dock и App Store на этом месте собранный файл на Rust.", code: "#!/usr/bin/env python3\n\"\"\"Калькулятор hyprSur — как Calculator.app (базовый режим).\"\"\"\nfrom appkit import ThemedCSS, make_window" },
            { n: "Resources/style.css", t: "Весь внешний вид", d: "Цвета заданы четыре раза, по одному на тему. Вместо @THEME@ подставляется номер текущей.", code: "@define-color calc_bg_1 rgba(48, 48, 50, 0.92);\n@define-color calc_bg_2 rgba(30, 30, 32, 0.88);\n@define-color calc_bg_3 rgb(48, 48, 50);\n@define-color calc_bg_4 rgb(30, 30, 32);" },
            { n: "Resources/Calculator_1.png", t: "Значок", d: "Цифра в имени это тема: 1 для светлой, 2 для тёмной." }
          ] }
        ] },
        { n: "Settings.app", t: "Настройки", d: "Самое большое приложение на Python. Пишет все файлы в Library/Preferences." },
        { n: "… ещё 10 приложений", t: "Остальные программы", d: "App Store, Calendar, Icon Composer, Keynote, Launchpad, Notes, Numbers, Pages, Reminders, Terminal." }
      ] },
      { n: "Library", t: "Служебные части", d: "То, что пользователь не запускает сам.", open: true, c: [
        { n: "CoreServices", t: "Оболочка", d: "Dock, MenuBar, ControlCenter, Finder, Spotlight, WindowManager, SecurityAgent, NotificationCenter, loginwindow и набор значков CoreGlyphs.bundle.", c: [
          { n: "Dock.app", t: "Док", d: "Собран на Rust. Список закреплённых системных значков лежит в Resources/Dock/SystemApps.plist." },
          { n: "MenuBar.app", t: "Строка меню", d: "Конфиг и стили в Resources/MenuBar. Формат модулей такой же, как у waybar." },
          { n: "ControlCenter.app", t: "Пункт управления", d: "Движок в Frameworks/core, описание панелей в Resources/eww.yuck." },
          { n: "CoreGlyphs.bundle", t: "Общие значки", d: "133 файла. В имени записаны тема и состояние: Bluetooth_2.1.svg это тёмная тема, включено." }
        ] },
        { n: "PrivateFrameworks", t: "Общий код", d: "Здесь лежит HyprsurKit: appkit.py и base.css с общими цветами окна, сайдбара и текста." }
      ] }
    ] },
    { n: "Library", t: "То, что меняется", d: "Настройки, обои и загруженные обновления. Эта папка переживает обновление системы.", open: true, c: [
      { n: "Preferences", t: "Настройки", d: "18 файлов plist. Приложения следят за ними и перестраиваются, когда файл меняется.", open: true, c: [
        { n: ".GlobalPreferences.plist", t: "Общие настройки", d: "Тема, акцент, язык и Liquid Glass. Его читают все приложения.", code: "<key>AppleAccentColor</key>\n<integer>3</integer>\n<key>AppleLanguages</key>\n<array><string>ru-RU</string></array>\n<key>HyprsurLiquidGlass</key>\n<true/>" },
        { n: "com.hyprsur.dock.plist", t: "Настройки дока", d: "Размер значков, автоскрытие и порядок программ в доке." },
        { n: "com.hyprsur.softwareupdate.plist", t: "Обновления", d: "Автопроверка, бета-канал и последняя увиденная версия.", code: "<key>autoCheck</key> <true/>\n<key>betaUpdates</key> <false/>\n<key>lastSeenTag</key> <string></string>" },
        { n: "… ещё 15 файлов", t: "По файлу на раздел Настроек", d: "bluetooth, controlcenter, desktop, display, finder, focus, general, network, notifications, privacy, screentime, siri, sound, universalaccess и appleid." }
      ] },
      { n: "Desktop Pictures", t: "Обои", d: "Картинки, которые показывает страница «Обои» в Настройках." },
      { n: "Updates", t: "Загруженные обновления", d: "Появляется при первом обновлении. Архив распаковывается сюда, потом запускается его install.sh." }
    ] }
  ] };
  const treeBox = $("tree"); let selBtn = null;
  function leaf(node, path, btn) {
    if (selBtn) selBtn.classList.remove("sel");
    selBtn = btn; btn.classList.add("sel");
    $("leaf-path").textContent = path; $("leaf-name").textContent = node.t; $("leaf-text").textContent = node.d;
    const code = $("leaf-code"); code.hidden = !node.code; if (node.code) code.querySelector("pre").textContent = node.code;
  }
  function build(node, parentPath, into) {
    const li = el("li"), b = el("button", node.c ? "dir" : "", node.n), path = parentPath ? parentPath + "/" + node.n : node.n;
    b.type = "button"; li.appendChild(b); into.appendChild(li);
    let ul = null;
    if (node.c) { ul = el("ul"); ul.hidden = !node.open; b.setAttribute("aria-expanded", String(!!node.open)); node.c.forEach((c) => build(c, path, ul)); li.appendChild(ul); }
    b.addEventListener("click", () => {
      if (ul) { const was = selBtn === b; if (was || ul.hidden) { ul.hidden = !ul.hidden; b.setAttribute("aria-expanded", String(!ul.hidden)); } }
      leaf(node, path, b);
    });
    if (node.n === "Calculator.app") setTimeout(() => leaf(node, path, b), 0);
  }
  const rootUl = el("ul"); treeBox.appendChild(rootUl); build(TREE, "", rootUl);

  /* ================= копирование ================= */
  function copyText(text, btn, label, selectNode) {
    const done = (ok) => { btn.textContent = ok ? "Скопировано" : "Выделено, жми Ctrl+C"; btn.classList.toggle("done", ok); setTimeout(() => { btn.textContent = label; btn.classList.remove("done"); }, 1800); };
    const sel = () => { if (selectNode) { const r = document.createRange(); r.selectNodeContents(selectNode); const s = getSelection(); s.removeAllRanges(); s.addRange(r); } done(false); };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(() => done(true), sel); else sel();
  }
  Array.prototype.forEach.call(document.querySelectorAll(".code.copyable"), (box) => {
    const pre = box.querySelector("pre"), b = el("button", "copy", "Скопировать"); b.type = "button";
    b.addEventListener("click", () => copyText(pre.textContent, b, "Скопировать", pre));
    box.appendChild(b);
  });
  $("copy-all").addEventListener("click", () => {
    const pres = $("steps").querySelectorAll("pre"), parts = [];
    for (let i = 0; i < 4; i++) parts.push(pres[i].textContent.trim());
    copyText(parts.join("\n") + "\n", $("copy-all"), "Скопировать шаги 1–4 одной командой", null);
  });

  /* ================= навигация ================= */
  const links = Array.prototype.slice.call($("lnav").querySelectorAll("a"));
  if ("IntersectionObserver" in window) {
    const seen = {}, secs = Array.prototype.slice.call(document.querySelectorAll("main > section[id]"));
    const io = new IntersectionObserver((es) => {
      es.forEach((e) => { seen[e.target.id] = e.isIntersecting; });
      let first = null; secs.forEach((x) => { if (!first && seen[x.id]) first = x.id; });
      links.forEach((l) => l.classList.toggle("on", l.getAttribute("href").slice(1) === first));
    }, { rootMargin: "-30% 0px -60% 0px" });
    secs.forEach((x) => io.observe(x));
  }

  /* ================= запуск ================= */
  const savedTheme = store.get("hs-theme");
  state.dark = savedTheme ? savedTheme === "dark" : sysDark();
  const savedAcc = store.get("hs-accent");
  applyAccent(savedAcc === null ? 3 : savedAcc === "null" ? null : Number(savedAcc));
  showApp(1);
  applyTheme(false);
  if (!savedTheme) root.removeAttribute("data-theme");
  resetDesk();
  let rz; window.addEventListener("resize", () => { clearTimeout(rz); rz = setTimeout(() => {
    Object.keys(open).forEach((k) => { const n = open[k].node; if (n.offsetLeft > wins.clientWidth - 60) n.style.left = Math.max(8, wins.clientWidth - n.offsetWidth - 8) + "px"; if (n.offsetWidth > wins.clientWidth - 16) n.style.width = (wins.clientWidth - 16) + "px"; });
  }, 150); });
})();
