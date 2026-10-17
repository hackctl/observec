(function () {
  "use strict";

  var rowHeight = { modules: 31, probes: 31 };
  var adjusting = { modules: false, probes: false };

  var state = {
    modules: {},
    query: "",
    page: 1,
    probesPage: 1,
    probeQuery: "",
    probeResultFilter: "",
    modulesPageSize: 10,
    probesPageSize: 10,
    sortKey: "name",
    sortDir: 1,
    selected: {},
    jobs: [],
    jobQuery: "",
    kubeConfigs: [],
    ruleFiles: {},
    ruleDirectories: [],
    ruleQuery: "",
    ruleCollapsed: {},
    serviceFiles: {},
    serviceDirectories: [],
    serviceQuery: "",
    serviceCollapsed: {},
    receivers: [],
    routeReceiver: "",
    receiverQuery: "",
    routeConfig: null,
    selectedRoutePath: null,
    routeReceiverNames: [],
    routeCollapsed: {},
    pendingNewRouteParentPath: null,
    inhibitRules: [],
    inhibitRuleQuery: "",
    monitors: [],
    monitorsStatus: { overall: "empty", monitors: [], summary: { total: 0, up: 0, down: 0, paused: 0, pending: 0 } },
    monitorHeartbeats: {},
    monitorsTable: null,
    monitorQuery: "",
    monitorFilter: "",
    monitorRange: "24h",
    editingMonitorId: null,
    detailMonitorId: null,
    currentUser: null,
  };

  function computePageSize(kind) {
    var container = kind === "modules"
      ? document.querySelector("#section-modules .table-card")
      : document.querySelector("#section-probes .probes-scroll");

    if (!container || !container.clientHeight) {
      return kind === "modules" ? state.modulesPageSize : state.probesPageSize;
    }

    var head = container.querySelector("thead");
    var footer = container.querySelector(".table-footer, .probes-footer");
    var available = container.clientHeight
      - (head ? head.offsetHeight : 0)
      - (footer ? footer.offsetHeight : 0)
      - 2;

    return Math.max(1, Math.floor(available / rowHeight[kind]));
  }

  function settleRowHeight(kind, tbody) {
    var firstRow = tbody.querySelector("tr:not(.empty-row)");
    if (!firstRow || !firstRow.offsetHeight || adjusting[kind]) return;
    var measured = firstRow.offsetHeight;
    if (Math.abs(measured - rowHeight[kind]) <= 3) return;
    rowHeight[kind] = measured;
    adjusting[kind] = true;
    if (kind === "modules") renderTable(); else renderProbes();
    adjusting[kind] = false;
  }

  var KNOWN_PROBERS = ["http", "tcp", "icmp"];

  function $(id) { return document.getElementById(id); }

  var pendingTlsFiles = {};
  var uploadedTlsPaths = {};
  var TLS_PARTS = [["ca", "f-ca-file", "ca_file"], ["cert", "f-cert-file", "cert_file"], ["key", "f-key-file", "key_file"]];

  function api(path, options) {
    return fetch(path, Object.assign({ headers: { "Content-Type": "application/json" }, credentials: 'same-origin' }, options))
      .then(function (res) {
        return res.text().then(function (text) {
          if (!res.ok) {
            var message = text;
            try { message = JSON.parse(text).message || JSON.parse(text).error || text; }
            catch (e) {  }
            throw new Error(message);
          }
          return text;
        });
      });
  }

  function defineObject(key, value) {
    var obj = {};
    obj[key] = value;
    return obj;
  }

  function extractMessage(responseText) {
    try { return JSON.parse(responseText).message || responseText; }
    catch (e) { return responseText; }
  }

  function toast(message, isError) {
    var el = document.createElement("div");
    el.className = "toast" + (isError ? " error" : "");
    el.textContent = message;
    $("toasts").appendChild(el);
    setTimeout(function () { el.remove(); }, 4000);
  }

  /* Themed confirm (replaces native window.confirm so deletes follow
     the interface theme). Resolves true on Delete, false on Cancel,
     overlay click or Escape. */
  var confirmResolver = null;

  function uiConfirm(message, opts) {
    opts = opts || {};
    return new Promise(function (resolve) {
      if (confirmResolver) {
        try { confirmResolver(false); } catch (e) {}
      }
      confirmResolver = resolve;
      $("confirm-title").textContent = opts.title || "Confirm delete";
      var okBtn = $("confirm-ok");
      if (okBtn) {
        okBtn.textContent = opts.okLabel || "Delete";
        okBtn.className = "btn " + (opts.danger === false ? "primary" : "danger");
      }
      $("confirm-message").textContent = message;
      $("confirm-overlay").classList.remove("hidden");
      $("confirm-dialog").classList.remove("hidden");
      var cancel = $("confirm-cancel");
      if (cancel) cancel.focus();
    });
  }

  function settleConfirm(ok) {
    var overlay = $("confirm-overlay"), dialog = $("confirm-dialog");
    if (overlay) overlay.classList.add("hidden");
    if (dialog) dialog.classList.add("hidden");
    var pending = confirmResolver;
    confirmResolver = null;
    if (pending) {
      try { pending(!!ok); } catch (e) {}
    }
  }

  if ($("confirm-cancel")) $("confirm-cancel").addEventListener("click", function () { settleConfirm(false); });
  if ($("confirm-ok")) $("confirm-ok").addEventListener("click", function () { settleConfirm(true); });
  if ($("confirm-overlay")) $("confirm-overlay").addEventListener("click", function () { settleConfirm(false); });

  $("theme-toggle").addEventListener("click", function () {
    cycleTheme();
  });

  /* Appearance — themes + accents from style.hackctl.com.
     Themes: grey / black / white. Accents: green, blue, red, yellow,
     orange, purple, pink. Accent drives --acc/--primary/--success;
     ok/info/err/warn stay fixed per theme (green/blue/red/yellow). */
  var APPEARANCE_THEMES = ["grey", "black", "white"];
  var APPEARANCE_ACCENTS = [
    { name: "green", label: "Green", dark: "#5ee08a", greyDim: "#1f3125", blackDim: "#0d1f13", light: "#0f7a3a", lightDim: "#e7f0eb" },
    { name: "blue", label: "Blue", dark: "#5b9dff", greyDim: "#1f2836", blackDim: "#0d1624", light: "#2260cf", lightDim: "#e9eef7" },
    { name: "red", label: "Red", dark: "#ff5555", greyDim: "#361e1e", blackDim: "#240c0c", light: "#c92a2a", lightDim: "#f6e9e9" },
    { name: "yellow", label: "Yellow", dark: "#f2c94c", greyDim: "#342e1d", blackDim: "#221c0b", light: "#7f5f00", lightDim: "#f0eee6" },
    { name: "orange", label: "Orange", dark: "#ff9640", greyDim: "#36271b", blackDim: "#241509", light: "#a84c00", lightDim: "#f3ece6" },
    { name: "purple", label: "Purple", dark: "#b48cff", greyDim: "#2b2636", blackDim: "#191424", light: "#6a42d0", lightDim: "#eeebf7" },
    { name: "pink", label: "Pink", dark: "#ff7ac6", greyDim: "#36232e", blackDim: "#24111c", light: "#b82c74", lightDim: "#f5eaef" },
  ];

  function normalizeTheme(value) {
    if (value === "light") return "white";
    if (value === "dark") return "black";
    if (value === "grey" || value === "black" || value === "white") return value;
    return "black";
  }

  function normalizeAccent(value) {
    value = String(value || "").toLowerCase();
    for (var i = 0; i < APPEARANCE_ACCENTS.length; i++) {
      if (APPEARANCE_ACCENTS[i].name === value) return value;
    }
    return "orange";
  }

  function accentByName(name) {
    for (var i = 0; i < APPEARANCE_ACCENTS.length; i++) {
      if (APPEARANCE_ACCENTS[i].name === name) return APPEARANCE_ACCENTS[i];
    }
    return APPEARANCE_ACCENTS[0];
  }

  function hexToRgb(hex) {
    hex = String(hex || "").replace("#", "");
    if (hex.length === 3) hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
    return [
      parseInt(hex.slice(0, 2), 16),
      parseInt(hex.slice(2, 4), 16),
      parseInt(hex.slice(4, 6), 16),
    ];
  }

  function rgbToHex(rgb) {
    function part(n) {
      n = Math.max(0, Math.min(255, Math.round(n)));
      var h = n.toString(16);
      return h.length === 1 ? "0" + h : h;
    }
    return "#" + part(rgb[0]) + part(rgb[1]) + part(rgb[2]);
  }

  function mixHex(accHex, brightHex, accShare) {
    var a = hexToRgb(accHex), b = hexToRgb(brightHex);
    return rgbToHex([
      a[0] * accShare + b[0] * (1 - accShare),
      a[1] * accShare + b[1] * (1 - accShare),
      a[2] * accShare + b[2] * (1 - accShare),
    ]);
  }

  function getAppearance() {
    var theme = "black", accent = "orange";
    try {
      theme = normalizeTheme(
        localStorage.getItem("observec-theme") || localStorage.getItem("observec-appearance-theme") || "black"
      );
      accent = normalizeAccent(localStorage.getItem("observec-accent") || "orange");
    } catch (e) {}
    return { theme: theme, accent: accent };
  }

  function applyAppearance(theme, accent, persist) {
    theme = normalizeTheme(theme);
    accent = normalizeAccent(accent);
    var root = document.documentElement;
    var entry = accentByName(accent);
    var isWhite = theme === "white";

    root.dataset.theme = theme;
    root.dataset.accent = accent;
    root.classList.toggle("light", isWhite);

    var acc = isWhite ? entry.light : entry.dark;
    var accDim = isWhite ? entry.lightDim : (theme === "grey" ? entry.greyDim : entry.blackDim);
    var bright = isWhite ? "#000000" : "#ffffff";
    var hover = mixHex(acc, bright, 0.82);

    root.style.setProperty("--acc", acc);
    root.style.setProperty("--acc-dim", accDim);
    root.style.setProperty("--primary", acc);
    root.style.setProperty("--primary-hover", hover);
    root.style.setProperty("--success", acc);

    /* Semantic tones stay fixed per theme (style.hackctl.com):
       ok = green, info = blue, err = red, warn = yellow. */
    var ok = accentByName("green"), info = accentByName("blue"),
        err = accentByName("red"), warn = accentByName("yellow");
    if (isWhite) {
      root.style.setProperty("--ok", ok.light);
      root.style.setProperty("--ok-d", ok.lightDim);
      root.style.setProperty("--info", info.light);
      root.style.setProperty("--info-d", info.lightDim);
      root.style.setProperty("--err", err.light);
      root.style.setProperty("--err-d", err.lightDim);
      root.style.setProperty("--warn", warn.light);
      root.style.setProperty("--warn-d", warn.lightDim);
    } else if (theme === "grey") {
      root.style.setProperty("--ok", ok.dark);
      root.style.setProperty("--ok-d", ok.greyDim);
      root.style.setProperty("--info", info.dark);
      root.style.setProperty("--info-d", info.greyDim);
      root.style.setProperty("--err", err.dark);
      root.style.setProperty("--err-d", err.greyDim);
      root.style.setProperty("--warn", warn.dark);
      root.style.setProperty("--warn-d", warn.greyDim);
    } else {
      root.style.setProperty("--ok", ok.dark);
      root.style.setProperty("--ok-d", ok.blackDim);
      root.style.setProperty("--info", info.dark);
      root.style.setProperty("--info-d", info.blackDim);
      root.style.setProperty("--err", err.dark);
      root.style.setProperty("--err-d", err.blackDim);
      root.style.setProperty("--warn", warn.dark);
      root.style.setProperty("--warn-d", warn.blackDim);
    }

    if (persist !== false) {
      try {
        localStorage.setItem("observec-theme", theme);
        localStorage.setItem("observec-accent", accent);
      } catch (e) {}
    }
    syncAppearanceUi(theme, accent);
  }

  function cycleTheme() {
    var current = getAppearance();
    var next = APPEARANCE_THEMES[(APPEARANCE_THEMES.indexOf(current.theme) + 1) % APPEARANCE_THEMES.length];
    applyAppearance(next, current.accent);
  }

  function syncAppearanceUi(theme, accent) {
    Array.prototype.forEach.call(document.querySelectorAll("[data-theme-option]"), function (btn) {
      var active = btn.getAttribute("data-theme-option") === theme;
      btn.classList.toggle("active", active);
      if (active) btn.setAttribute("aria-pressed", "true");
      else btn.removeAttribute("aria-pressed");
    });
    Array.prototype.forEach.call(document.querySelectorAll("[data-accent-option]"), function (btn) {
      var active = btn.getAttribute("data-accent-option") === accent;
      if (active) btn.setAttribute("aria-pressed", "true");
      else btn.removeAttribute("aria-pressed");
    });
  }

  function renderAccentOptions() {
    var grid = $("accent-grid");
    if (!grid || grid.dataset.built === "true") return;
    grid.dataset.built = "true";
    var current = getAppearance();
    APPEARANCE_ACCENTS.forEach(function (entry) {
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "accent-swatch";
      btn.setAttribute("data-accent-option", entry.name);
      btn.title = entry.label;
      btn.setAttribute("aria-label", entry.label + " accent");
      var swatchTheme = current.theme || "black";
      var bg = swatchTheme === "white" ? entry.light : entry.dark;
      var dot = document.createElement("span");
      dot.className = "dot-swatch";
      dot.style.background = bg;
      btn.appendChild(dot);
      btn.addEventListener("click", function () {
        applyAppearance(getAppearance().theme, entry.name);
        renderAccentSwatchColors();
      });
      grid.appendChild(btn);
    });
  }

  function renderAccentSwatchColors() {
    var theme = getAppearance().theme;
    Array.prototype.forEach.call(document.querySelectorAll("[data-accent-option]"), function (btn) {
      var entry = accentByName(btn.getAttribute("data-accent-option"));
      var dot = btn.querySelector(".dot-swatch");
      if (dot) dot.style.background = theme === "white" ? entry.light : entry.dark;
    });
  }

  function openSettings() {
    renderAccentOptions();
    renderAccentSwatchColors();
    var current = getAppearance();
    syncAppearanceUi(current.theme, current.accent);
    if ($("dialog-overlay")) $("dialog-overlay").classList.remove("hidden");
    if ($("settings-dialog")) $("settings-dialog").classList.remove("hidden");
  }

  (function initAppearance() {
    renderAccentOptions();
    var current = getAppearance();
    try {
      // One-time rollout of the black + orange defaults: browsers that
      // saved the old green default get the new default once, then their
      // own choices are respected on later loads.
      if (!localStorage.getItem("observec-appearance-v")) {
        current = { theme: "black", accent: "orange" };
        localStorage.setItem("observec-appearance-v", "1");
      }
    } catch (e) {}
    applyAppearance(current.theme, current.accent, false);
    try {
      localStorage.setItem("observec-theme", current.theme);
      localStorage.setItem("observec-accent", current.accent);
    } catch (e) {}
  })();

  if ($("nav-appearance-btn")) $("nav-appearance-btn").addEventListener("click", function () {
    if (!groupOpen.appearance) setGroupOpen("appearance", true);
    Array.prototype.forEach.call(document.querySelectorAll(".nav-subitem"), function (item) {
      item.classList.toggle("active", item.id === "nav-appearance-btn");
    });
    openSettings();
  });
  if ($("settings-close")) $("settings-close").addEventListener("click", closeDialog);
  Array.prototype.forEach.call(document.querySelectorAll("[data-theme-option]"), function (btn) {
    btn.addEventListener("click", function () {
      applyAppearance(btn.getAttribute("data-theme-option"), getAppearance().accent);
      renderAccentSwatchColors();
    });
  });

  var CONFIG_TARGETS = {
    blackbox: "config-yaml",
    prometheus: "prom-config-yaml",
    alertmanager: "am-config-yaml",
  };

  var DOT_TITLES = {
    healthy: "Running",
    starting: "Starting",
    stopped: "Stopped",
  };

  function parseStatusState(responseText) {
    try {
      var payload = JSON.parse(responseText);
      if (payload.state === "healthy" || payload.state === "starting" || payload.state === "stopped") {
        return payload.state;
      }
      var message = (payload.message || "").toLowerCase();
      if (message.indexOf("healthy") !== -1) return "healthy";
      if (message.indexOf("starting") !== -1) return "starting";
      return "stopped";
    } catch (error) {
      return "stopped";
    }
  }

  function setDotState(id, state) {
    var dot = $(id);
    if (!dot) return;
    dot.className = "dot";
    if (state === "healthy") dot.classList.add("ok");
    else if (state === "starting") dot.classList.add("warn");
    else dot.classList.add("bad");
    dot.title = DOT_TITLES[state] || DOT_TITLES.stopped;

    var checked = (state === "healthy" || state === "starting");
    var sel = id === "blackbox-dot" ? ".blackbox-toggle-btn" : id === "prometheus-dot" ? ".prom-toggle-btn" : id === "alertmanager-dot" ? ".am-toggle-btn" : null;
    if (sel) {
      Array.prototype.forEach.call(document.querySelectorAll(sel), function (chk) { chk.checked = checked; });
    }
  }

  function refreshComponentStatus(path, dotId) {
    return api(path)
      .then(function (text) { setDotState(dotId, parseStatusState(text)); })
      .catch(function () { setDotState(dotId, "stopped"); });
  }

  function refreshStatus() {
    refreshComponentStatus("/blackbox/status", "blackbox-dot")
      .then(function () { setEngineBadge(true); })
      .catch(function () { setEngineBadge(false); });

    refreshComponentStatus("/prometheus/status", "prometheus-dot");
    refreshComponentStatus("/alertmanager/status", "alertmanager-dot");
  }

  function setEngineBadge(up) {
    var badge = $("engine-status");
    badge.textContent = up ? "engine online" : "engine offline";
    badge.className = "badge " + (up ? "ok" : "muted");
  }

  function loadModules() {
    return api("/blackbox/modules")
      .then(function (text) {
        state.modules = JSON.parse(text);
        renderTable();
      })
      .catch(function (err) { toast(err.message, true); });
  }

  function filteredNames() {
    return Object.keys(state.modules)
      .filter(function (name) {
        if (state.query && name.toLowerCase().indexOf(state.query) === -1) return false;
        if (state.proberFilter && (state.modules[name].prober || "") !== state.proberFilter) return false;
        return true;
      })
      .sort(function (a, b) {
        var va = sortValue(a), vb = sortValue(b);
        if (va < vb) return -1 * state.sortDir;
        if (va > vb) return 1 * state.sortDir;
        return 0;
      });
  }

  function sortValue(name) {
    var config = state.modules[name] || {};
    if (state.sortKey === "prober") return (config.prober || "") + name;
    if (state.sortKey === "method") return methodOf(config) + name;
    if (state.sortKey === "timeout") return (config.timeout || "") + name;
    return name;
  }

  function methodOf(config) {
    return config.prober === "http" && config.http && config.http.method
      ? config.http.method
      : "-";
  }

  function renderTable() {
    closeRowMenu();
    var tbody = $("module-tbody");
    tbody.innerHTML = "";

    var names = filteredNames();
    var pageSize = computePageSize("modules");
    var totalPages = Math.max(1, Math.ceil(names.length / pageSize));
    if (state.page > totalPages) state.page = totalPages;
    if (state.page < 1) state.page = 1;

    var pageNames = names.slice((state.page - 1) * pageSize, state.page * pageSize);
    pageNames.forEach(function (name) { tbody.appendChild(buildRow(name)); });

    if (!names.length) {
      var tr = document.createElement("tr");
      tr.className = "empty-row";
      var td = document.createElement("td");
      td.colSpan = 6;
      td.textContent = state.query
        ? "No modules match \"" + state.query + "\""
        : "No modules yet. Create one to start probing.";
      tr.appendChild(td);
      tbody.appendChild(tr);
    }

    $("table-count").textContent =
      names.length + " of " + Object.keys(state.modules).length + " module(s)";

    syncSelectionUi(names);
    renderArrows();
    renderPager(totalPages);
    settleRowHeight("modules", tbody);
  }

  function renderPagerInto(prevId, numbersId, nextId, currentPage, totalPages, onPage) {
    $(prevId).disabled = currentPage <= 1;
    $(nextId).disabled = currentPage >= totalPages;

    var numbersEl = $(numbersId);
    numbersEl.innerHTML = "";

    var pages = [];
    if (totalPages <= 7) {
      for (var i = 1; i <= totalPages; i++) pages.push(i);
    } else {
      pages.push(1);
      var start = Math.max(2, currentPage - 1);
      var end = Math.min(totalPages - 1, currentPage + 1);
      if (start > 2) pages.push("...");
      for (var j = start; j <= end; j++) pages.push(j);
      if (end < totalPages - 1) pages.push("...");
      pages.push(totalPages);
    }

    pages.forEach(function (item) {
      if (item === "...") {
        var dots = document.createElement("span");
        dots.className = "pager-ellipsis";
        dots.textContent = "\u2026";
        numbersEl.appendChild(dots);
        return;
      }
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "pager-page" + (item === currentPage ? " active" : "");
      btn.textContent = item;
      btn.addEventListener("click", function () { onPage(item); });
      numbersEl.appendChild(btn);
    });
  }

  function renderPager(totalPages) {
    renderPagerInto("page-prev", "page-numbers", "page-next", state.page, totalPages, function (p) {
      state.page = p;
      renderTable();
    });
  }

  function buildRow(name) {
    var config = state.modules[name] || {};
    var isSelected = Boolean(state.selected[name]);

    var tr = document.createElement("tr");
    if (isSelected) tr.className = "selected";

    var tdCheck = document.createElement("td");
    var cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = isSelected;
    cb.addEventListener("change", function () {
      if (cb.checked) state.selected[name] = true;
      else delete state.selected[name];
      renderTable();
    });
    tdCheck.appendChild(cb);
    tr.appendChild(tdCheck);

    var tdName = document.createElement("td");
    var spanName = document.createElement("span");
    spanName.className = "module-name";
    spanName.textContent = name;
    tdName.appendChild(spanName);
    tr.appendChild(tdName);

    var tdProber = document.createElement("td");
    var proberBadge = document.createElement("span");
    proberBadge.className = "badge prober";
    proberBadge.textContent = config.prober || "?";
    tdProber.appendChild(proberBadge);
    tr.appendChild(tdProber);

    var tdMethod = document.createElement("td");
    var methodText = document.createElement("span");
    methodText.className = "text-muted";
    methodText.textContent = methodOf(config);
    tdMethod.appendChild(methodText);
    tr.appendChild(tdMethod);

    var tdTimeout = document.createElement("td");
    tdTimeout.textContent = config.timeout || "-";
    tr.appendChild(tdTimeout);

    var tdActions = document.createElement("td");
    tdActions.className = "col-actions-cell";

    var kebab = document.createElement("button");
    kebab.className = "row-action kebab";
    kebab.title = "More actions";
    kebab.innerHTML = '<svg class="icon" viewBox="0 0 24 24"><circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/></svg>';
    kebab.addEventListener("click", function (event) {
      event.stopPropagation();
      openRowMenu(name, kebab);
    });
    tdActions.appendChild(kebab);

    tr.appendChild(tdActions);
    return tr;
  }

  var rowMenuAnchor = null;

  function openRowMenuAt(anchor, items) {

    if (rowMenuAnchor === anchor && !$("row-menu").classList.contains("hidden")) {
      closeRowMenu();
      return;
    }
    closeRowMenu();
    rowMenuAnchor = anchor;
    var menu = $("row-menu");

    items.forEach(function (item) {
      var menuItem = document.createElement("button");
      menuItem.className = "menu-item" + (item.danger ? " danger" : "");
      menuItem.textContent = item.label;
      menuItem.addEventListener("click", function (event) {
        event.stopPropagation();
        closeRowMenu();
        item.onClick();
      });
      menu.appendChild(menuItem);
    });

    menu.classList.remove("hidden");

    var rect = anchor.getBoundingClientRect();
    menu.style.top = Math.min(rect.bottom + 4, window.innerHeight - menu.offsetHeight - 8) + "px";
    menu.style.left = Math.max(8, rect.right - menu.offsetWidth) + "px";
  }

  function openRowMenu(name, anchor) {
    openRowMenuAt(anchor, [
      {
        label: "Edit",
        onClick: function () { openDialog(name); },
      },
      {
        label: "Delete",
        danger: true,
        onClick: function () {
          uiConfirm("Delete module \"" + name + "\"?").then(function (ok) {
            if (ok) deleteModules([name]);
          });
        },
      },
    ]);
  }

  function closeRowMenu() {
    rowMenuAnchor = null;
    var menu = $("row-menu");
    menu.innerHTML = "";
    menu.classList.add("hidden");
  }

  $("row-menu").addEventListener("click", function (event) { event.stopPropagation(); });
  document.addEventListener("click", closeRowMenu);
  window.addEventListener("scroll", closeRowMenu, true);
  window.addEventListener("resize", closeRowMenu);

  function renderArrows() {
    ["name", "prober", "method", "timeout"].forEach(function (key) {
      var arrow = document.querySelector('[data-arrow="' + key + '"]');
      arrow.textContent = state.sortKey === key ? (state.sortDir === 1 ? "\u2191" : "\u2193") : "";
    });
  }

  function selectedNames() {
    return Object.keys(state.selected).filter(function (n) { return state.modules[n]; });
  }

  function syncSelectionUi(names) {
    var count = selectedNames().length;
    var deleteBtn = $("delete-selected-btn");
    deleteBtn.classList.toggle("hidden", count === 0);
    deleteBtn.setAttribute("data-action-tip", count ? "Delete selected (" + count + ")" : "Delete selected");
    deleteBtn.removeAttribute("title");

    var selectAll = $("select-all");
    selectAll.checked = names.length > 0 && names.every(function (n) { return state.selected[n]; });
    selectAll.indeterminate = !selectAll.checked && names.some(function (n) { return state.selected[n]; });
  }

  $("select-all").addEventListener("change", function () {
    var names = filteredNames();
    if ($("select-all").checked) {
      names.forEach(function (n) { state.selected[n] = true; });
    } else {
      names.forEach(function (n) { delete state.selected[n]; });
    }
    renderTable();
  });

  $("delete-selected-btn").addEventListener("click", function () {
    var names = selectedNames();
    if (!names.length) return;
    uiConfirm("Delete " + names.length + " module(s)?\n" + names.join(", ")).then(function (ok) {
      if (ok) deleteModules(names);
    });
  });

  var editingName = null;

  function openDialog(name) {
    editingName = name || null;
    var isEdit = Boolean(name);
    var config = isEdit ? (state.modules[name] || {}) : {};

    $("dialog-title").textContent = isEdit ? "Edit module" : "Add module";
    $("dialog-subtitle").textContent = isEdit
      ? "Unknown configuration keys are preserved on save."
      : "";

    var nameInput = $("f-name");
    nameInput.value = name || "";
    nameInput.disabled = isEdit;

    setProberOptions(config.prober);
    $("f-prober").value = config.prober || "http";
    $("f-timeout").value = config.timeout || "5s";

    fillSection(config, "http");
    fillSection(config, "tcp");
    fillSection(config, "icmp");
    refreshTlsFields(currentTlsSectionConfig());

    hideFormError();
    $("f-delete").classList.toggle("hidden", !isEdit);
    $("f-save").textContent = isEdit ? "Save changes" : "Create module";

    $("dialog-overlay").classList.remove("hidden");
    $("module-dialog").classList.remove("hidden");
    updateHttpSection();
    nameInput.focus();
  }

  function closeDialog() {
    var routeDialogOpen = $("route-dialog") && !$("route-dialog").classList.contains("hidden");
    var settingsWasOpen = $("settings-dialog") && !$("settings-dialog").classList.contains("hidden");
    ["module-dialog", "job-dialog", "file-dialog", "receiver-dialog", "route-dialog", "inhibit-dialog", "monitor-dialog", "user-dialog", "git-dialog", "settings-dialog"].forEach(function (id) {
      var el = $(id);
      if (el) el.classList.add("hidden");
    });
    $("dialog-overlay").classList.add("hidden");
    editingName = null;
    editingJobName = null;
    editingReceiverName = null;
    editingInhibitRuleIndex = null;
    state.editingMonitorId = null;
    try { if (typeof editingUserId !== 'undefined') editingUserId = null; } catch (e) {}
    var ue = $("user-error");
    if (ue) ue.classList.add("hidden");
    fileEditor.kind = null;
    fileEditor.name = null;
    if (routeDialogOpen) {
      state.selectedRoutePath = null;
      state.pendingNewRouteParentPath = null;
      hideRouteError();
      renderRouteTree();
    }
    if (settingsWasOpen) setActiveSub(lastSection[activeComponent]);
  }

  function setProberOptions(current) {
    var select = $("f-prober");
    var values = KNOWN_PROBERS.slice();
    if (current && values.indexOf(current) === -1) values.push(current);
    select.innerHTML = "";
    values.forEach(function (value) {
      var option = document.createElement("option");
      option.value = value;
      option.textContent = value;
      select.appendChild(option);
    });
  }

  function fillSection(config, prober) {
    var section = config[prober] || {};
    var protoSelect = $("f-proto");

    if (section.preferred_ip_protocol === "ip6") protoSelect.value = "ip6";
    else protoSelect.value = "ip4";

    $("f-fallback").checked = section.ip_protocol_fallback === true;

    if (prober !== "http") return;

    $("f-method").value = section.method || "GET";
    $("f-content-type").value =
      (section.headers && section.headers["Content-Type"]) || "application/json";
    $("f-body").value = section.body || "";
    updatePostFields();
    $("f-codes").value = Array.isArray(section.valid_status_codes)
      ? section.valid_status_codes.join(", ")
      : "";
    var versions = section.valid_http_versions || [];
    $("f-v10").checked = versions.indexOf("HTTP/1.0") !== -1;
    $("f-v11").checked = versions.indexOf("HTTP/1.1") !== -1;
    $("f-v20").checked = versions.indexOf("HTTP/2.0") !== -1;
    $("f-follow").checked = section.follow_redirects === true;
  }

  function updateHttpSection() {
    $("http-section").classList.toggle("hidden", $("f-prober").value !== "http");
    updatePostFields();
    refreshTlsFields(currentTlsSectionConfig());
  }

  function currentTlsSectionConfig() {
    if (!editingName) return null;
    var config = state.modules[editingName];
    var prober = $("f-prober").value;
    return (config && config[prober]) || {};
  }

  $("f-tls-enable").addEventListener("change", function () {
    $("tls-fields").classList.toggle("hidden", !this.checked);
  });

  TLS_PARTS.forEach(function (part) {
    var trigger = document.querySelector('[data-tls-upload="' + part[0] + '"]');
    var input = $(part[1]);

    if (trigger) trigger.addEventListener("click", function () {
      input.click();
    });

    input.addEventListener("change", function () {
      if (!this.files.length) return;
      var moduleName = $("f-name").value.trim();
      if (!moduleName) {
        this.value = "";
        delete pendingTlsFiles[part[0]];
        if (trigger) trigger.textContent = "Choose file";
        toast("Enter the module name before uploading files.", true);
        return;
      }
      pendingTlsFiles[part[0]] = this.files[0];
      document.querySelector('[data-tls-name="' + part[0] + '"]').textContent = this.files[0].name;
    });
  });

  function updatePostFields() {
    var method = $("f-method").value;
    $("post-fields").classList.toggle("hidden", method !== "POST" && method !== "PUT");
  }

  $("f-method").addEventListener("change", updatePostFields);

  $("f-prober").addEventListener("change", updateHttpSection);

  function collectConfig(uploadedPaths) {
    var original = editingName ? (state.modules[editingName] || {}) : {};
    var config = JSON.parse(JSON.stringify(original));
    var prober = $("f-prober").value;

    config.prober = prober;
    config.timeout = $("f-timeout").value.trim() || "5s";

    var section = typeof config[prober] === "object" && config[prober] !== null
      ? config[prober] : {};
    section.preferred_ip_protocol = $("f-proto").value;
    section.ip_protocol_fallback = $("f-fallback").checked;
    config[prober] = section;

    if (prober === "http" || prober === "tcp") {
      if ($("f-tls-enable").checked) {
        applyTlsConfig(section, uploadedPaths || {});
      } else {
        delete section.tls;
        delete section.tls_config;
      }
    }

    if (prober === "http") {
      section.method = $("f-method").value;

      var method = section.method;
      if (method === "POST" || method === "PUT") {
        var body = $("f-body").value;
        if (body) { section.body = body; }
        else { delete section.body; }

        section.headers = typeof section.headers === "object" && section.headers !== null
          ? section.headers : {};
        section.headers["Content-Type"] = $("f-content-type").value;
      } else {
        delete section.body;
      }

      var codes = $("f-codes").value.split(",")
        .map(function (code) { return parseInt(code.trim(), 10); })
        .filter(function (code) { return !isNaN(code); });
      if (codes.length) section.valid_status_codes = codes;
      else delete section.valid_status_codes;

      var versions = ["f-v10", "f-v11", "f-v20"]
        .filter(function (id) { return $(id).checked; })
        .map(function (id) { return $(id).value; });
      if (versions.length) section.valid_http_versions = versions;
      else delete section.valid_http_versions;

      if ($("f-follow").checked) section.follow_redirects = true;
      else delete section.follow_redirects;
    }

    return config;
  }

  function applyTlsConfig(section, uploadedPaths) {

    var existing = section.tls_config && typeof section.tls_config === "object"
      ? section.tls_config : {};

    var serverName = $("f-server-name").value.trim();
    if (serverName) existing.server_name = serverName;
    else delete existing.server_name;

    TLS_PARTS.forEach(function (part) {
      var pathKey = part[2];
      var path = uploadedPaths[pathKey] || existing[pathKey];
      if (path) existing[pathKey] = path;
      else delete existing[pathKey];
    });

    section.tls_config = existing;
  }

  function showFormError(message) {
    var el = $("form-error");
    el.textContent = message;
    el.classList.remove("hidden");
  }

  function hideFormError() {
    $("form-error").classList.add("hidden");
  }

  $("module-form").addEventListener("submit", function (event) {
    event.preventDefault();
    hideFormError();

    var name = $("f-name").value.trim();
    if (!name) { showFormError("Module name is required."); return; }
    if (!editingName && state.modules[name]) {
      showFormError("A module named \"" + name + "\" already exists.");
      return;
    }

    var saveBtn = $("f-save");
    saveBtn.disabled = true;
    saveBtn.textContent = "Saving...";

    if (!$("f-tls-enable").checked) pendingTlsFiles = {};
    var uploadStep = $("f-tls-enable").checked
      ? runPendingTlsUploads(name)
      : Promise.resolve({});

    uploadStep
      .then(function (uploadedPaths) {
        var config = collectConfig(uploadedPaths);
        return editingName
          ? api("/blackbox/module", { method: "PUT", body: JSON.stringify(defineObject(name, config)) })
          : api("/blackbox/modules", { method: "POST", body: JSON.stringify(defineObject(name, config)) });
      })
      .then(function (message) {
        toast(extractMessage(message));
        closeDialog();
        return loadModules();
      })
      .catch(function (err) {
        showFormError(err.message);
        saveBtn.disabled = false;
        saveBtn.textContent = editingName ? "Save changes" : "Create module";
      });
  });

  $("f-delete").addEventListener("click", function () {
    if (!editingName) return;
    uiConfirm("Delete module \"" + editingName + "\"?").then(function (ok) {
      if (ok) deleteModules([editingName], closeDialog);
    });
  });

  $("f-cancel").addEventListener("click", closeDialog);
  $("dialog-overlay").addEventListener("click", closeDialog);
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape") {
      if ($("confirm-dialog") && !$("confirm-dialog").classList.contains("hidden")) {
        settleConfirm(false);
        return;
      }
      closeRowMenu();
      closeDialog();
    }
  });

  function deleteModules(names, done) {
    api("/blackbox/modules", {
      method: "DELETE",
      body: JSON.stringify(names),
    })
      .then(function (message) {
        toast(extractMessage(message));
        names.forEach(function (n) { delete state.selected[n]; });
        return loadModules();
      })
      .then(function () { if (done) done(); })
      .catch(function (err) { toast(err.message, true); });
  }

  $("add-module-btn").addEventListener("click", function () { openDialog(null); });

  $("module-search").addEventListener("input", function () {
    state.query = this.value.trim().toLowerCase();
    state.page = 1;
    renderTable();
  });

  $("page-prev").addEventListener("click", function () {
    state.page -= 1;
    renderTable();
  });

  $("page-next").addEventListener("click", function () {
    state.page += 1;
    renderTable();
  });

  Array.prototype.forEach.call(document.querySelectorAll("th.sortable"), function (th) {
    th.addEventListener("click", function () {
      var key = th.getAttribute("data-sort");
      if (state.sortKey === key) state.sortDir *= -1;
      else { state.sortKey = key; state.sortDir = 1; }
      state.page = 1;
      renderTable();
    });
  });

  var configLoadedOnce = false;

  function escapeHtml(text) {
    return String(text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function highlightYamlValue(value) {
    var trimmed = value.trim();
    var escaped = escapeHtml(trimmed);
    if (!trimmed) return "";
    if (trimmed === "true" || trimmed === "false") {
      return '<span class="yaml-b">' + escaped + "</span>";
    }
    if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
      return '<span class="yaml-n">' + escaped + "</span>";
    }
    if ((trimmed.charAt(0) === '"' && trimmed.charAt(trimmed.length - 1) === '"')
        || (trimmed.charAt(0) === "'" && trimmed.charAt(trimmed.length - 1) === "'")) {
      return '<span class="yaml-s">' + escaped + "</span>";
    }
    if (/^\d+[smhdwy]$/.test(trimmed)) {
      return '<span class="yaml-d">' + escaped + "</span>";
    }
    return '<span class="yaml-v">' + escaped + "</span>";
  }

  function highlightYaml(yaml) {
    return yaml.split("\n").map(function (line) {
      if (/^\s*#/.test(line)) {
        return '<span class="yaml-c">' + escapeHtml(line) + "</span>";
      }
      if (/^\s*---\s*$/.test(line)) {
        return '<span class="yaml-doc">' + escapeHtml(line) + "</span>";
      }

      var keyMatch = line.match(/^(\s*)([^:#\s][^:]*?)(\s*:\s*)(.*)$/);
      if (keyMatch) {
        return escapeHtml(keyMatch[1])
          + '<span class="yaml-k">' + escapeHtml(keyMatch[2]) + "</span>"
          + escapeHtml(keyMatch[3])
          + highlightYamlValue(keyMatch[4]);
      }

      var listMatch = line.match(/^(\s*-\s+)(.*)$/);
      if (listMatch) {
        return escapeHtml(listMatch[1]) + highlightYamlValue(listMatch[2]);
      }

      return escapeHtml(line);
    }).join("\n");
  }

  var CONFIG_FILENAMES = {
    "config-yaml": "blackboxConfig.yml",
    "prom-config-yaml": "prometheusConfig.yml",
    "am-config-yaml": "alertmanagerConfig.yml",
  };

  function renderConfigYaml(targetId, yaml) {
    var el = $(targetId);
    if (!el) return;
    el.dataset.rawYaml = yaml;
    if (!el.dataset.editorBuilt) {
      el.dataset.editorBuilt = "true";
      el.innerHTML =
        '<div class="editor-tab"><span class="editor-dot"></span>' +
        '<span class="editor-tab-name"></span><span class="editor-lang">YAML</span></div>' +
        '<div class="editor-body"></div>' +
        '<div class="editor-status"><span class="editor-lines"></span><span>read-only</span></div>';
      var tabName = el.querySelector(".editor-tab-name");
      if (tabName) tabName.textContent = CONFIG_FILENAMES[targetId] || "config.yml";
    }
    var highlighted = highlightYaml(yaml).split("\n");
    var body = el.querySelector(".editor-body");
    if (body) {
      body.innerHTML = highlighted.map(function (code, i) {
        return '<div class="yaml-line"><span class="yaml-ln">' + (i + 1) +
          '</span><span class="yaml-code">' + (code || "") + "</span></div>";
      }).join("");
    }
    var linesEl = el.querySelector(".editor-lines");
    if (linesEl) linesEl.textContent = highlighted.length + (highlighted.length === 1 ? " line" : " lines");
  }

  function loadConfigText(component) {
    var targetId = CONFIG_TARGETS[component];
    if (!targetId) return Promise.resolve();
    refreshConfigCommitTag(component);
    return api("/" + component + "/config")
      .then(function (yaml) {
        renderConfigYaml(targetId, yaml);
      })
      .catch(function (err) { toast(err.message, true); });
  }

  var CONFIG_COMMIT_EL = {
    blackbox: "blackbox-commit",
    prometheus: "prom-commit",
    alertmanager: "am-commit",
  };
  var CONFIG_COMMIT_MATCH = {
    blackbox: /blackbox/i,
    prometheus: /prometheus/i,
    alertmanager: /alertmanager/i,
  };

  function refreshConfigCommitTag(component) {
    var el = $(CONFIG_COMMIT_EL[component]);
    if (!el) return Promise.resolve();
    return api("/git/log")
      .then(function (text) {
        var data = JSON.parse(text);
        var commits = (data && data.commits) || [];
        var rx = CONFIG_COMMIT_MATCH[component];
        var found = null;
        for (var i = 0; i < commits.length; i++) {
          if (rx.test(commits[i].message || "")) { found = commits[i]; break; }
        }
        if (!found && commits.length) found = commits[0];
        if (found) {
          var label = (found.shortHash || (found.hash || "").substring(0, 7)) + " · " + (found.message || "");
          el.textContent = label;
          el.setAttribute("data-action-tip", label);
        } else {
          el.textContent = "";
          el.removeAttribute("data-action-tip");
        }
      })
      .catch(function () {});
  }

  function copyConfigText(targetId) {
    var el = $(targetId);
    var text = (el && el.dataset.rawYaml) ? el.dataset.rawYaml : (el ? el.textContent : "");
    return navigator.clipboard.writeText(text);
  }

  $("config-copy").addEventListener("click", function () {
    copyConfigText("config-yaml")
      .then(function () { toast("Config copied to clipboard"); })
      .catch(function (err) { toast(err.message, true); });
  });

  $("prom-config-copy").addEventListener("click", function () {
    copyConfigText("prom-config-yaml")
      .then(function () { toast("Config copied to clipboard"); })
      .catch(function (err) { toast(err.message, true); });
  });

  $("am-config-copy").addEventListener("click", function () {
    copyConfigText("am-config-yaml")
      .then(function () { toast("Config copied to clipboard"); })
      .catch(function (err) { toast(err.message, true); });
  });

  function handleBlackboxReload(btn) {
    btn.addEventListener("click", function () {
      btn.disabled = true;
      api("/blackbox/reload", { method: "POST" })
        .then(function (message) { toast(extractMessage(message)); refreshStatus(); })
        .catch(function (err) { toast(err.message, true); })
        .finally(function () { btn.disabled = false; });
    });
  }
  function handleBlackboxToggle(chk) {
    chk.addEventListener("change", function () {
      var enable = chk.checked;
      chk.disabled = true;
      var path = enable ? "/blackbox/enable" : "/blackbox/disable";
      api(path, { method: "POST" })
        .then(function (message) { toast(extractMessage(message)); refreshStatus(); })
        .catch(function (err) {
          toast(err.message, true);
          chk.checked = !enable;
        })
        .finally(function () { chk.disabled = false; });
    });
  }
  if ($("reload-btn")) handleBlackboxReload($("reload-btn"));
  Array.prototype.forEach.call(document.querySelectorAll(".blackbox-reload-btn"), handleBlackboxReload);
  Array.prototype.forEach.call(document.querySelectorAll(".blackbox-toggle-btn"), handleBlackboxToggle);

  function tlsApplies() {
    var prober = $("f-prober").value;
    return prober === "http" || prober === "tcp";
  }

  function updateTlsUi() {
    $("tls-section").classList.toggle("hidden", !tlsApplies());
    $("tls-fields").classList.toggle("hidden", !$("f-tls-enable").checked);
  }

  function baseName(path) {
    if (!path) return "";
    return String(path).split("/").pop();
  }

  function refreshTlsFields(sectionConfig) {
    pendingTlsFiles = {};
    uploadedTlsPaths = {};

    var section = sectionConfig || {};
    var tlsConfig = section.tls_config && typeof section.tls_config === "object" ? section.tls_config : {};

    $("f-tls-enable").checked = section.tls === true || Object.keys(tlsConfig).length > 0;
    $("f-server-name").value = tlsConfig.server_name || "";

    TLS_PARTS.forEach(function (part) {
      var nameEl = document.querySelector('[data-tls-name="' + part[0] + '"]');
      if (nameEl) nameEl.textContent = baseName(tlsConfig[part[2]]);
      var input = $(part[1]);
      if (input) input.value = "";
    });

    updateTlsUi();
  }

  function runPendingTlsUploads(moduleName) {
    var jobs = TLS_PARTS.filter(function (part) { return pendingTlsFiles[part[0]]; });
    if (!jobs.length) return Promise.resolve({});

    return jobs.reduce(function (chain, part) {
      return chain.then(function () {
        var formData = new FormData();
        formData.append(part[0], pendingTlsFiles[part[0]]);

        return fetch("/blackbox/module/" + encodeURIComponent(moduleName) + "/tls", {
          method: "POST",
          body: formData,
        }).then(function (res) {
          return res.text().then(function (bodyText) {
            if (!res.ok) throw new Error(extractMessage(bodyText));
            var parsed = JSON.parse(bodyText);
            var pathKey = part[2];
            if (parsed[pathKey]) uploadedTlsPaths[pathKey] = parsed[pathKey];
          });
        });
      });
    }, Promise.resolve({})).then(function () { return uploadedTlsPaths; });
  }

  var recentProbes = [];

  function formatDuration(secondsText) {
    var seconds = parseFloat(secondsText);
    if (isNaN(seconds)) return "";
    if (seconds < 1) return Math.round(seconds * 1000) + "ms";
    if (seconds < 60) return (Math.round(seconds * 10) / 10) + "s";
    var minutes = Math.floor(seconds / 60);
    var rest = Math.round(seconds % 60);
    if (minutes < 60) return minutes + "m " + rest + "s";
    return Math.floor(minutes / 60) + "h " + (minutes % 60) + "m";
  }

  function probeHaystack(probe) {
    return (probe.module + " " + probe.target + " " +
      formatDuration(probe.duration) + " " +
      (probe.time || "")).toLowerCase();
  }

  function fetchProbes() {
    return api("/blackbox/probes")
      .then(function (text) {
        var parsed = JSON.parse(text);
        recentProbes = parsed.probes || [];
        renderProbes();
      })
      .catch(function () { recentProbes = []; renderProbes(); });
  }

  function filteredProbeRows() {

    return recentProbes.filter(function (probe) {
      if (state.probeResultFilter && probe.result !== state.probeResultFilter) return false;
      if (state.probeQuery && probeHaystack(probe).indexOf(state.probeQuery) === -1) return false;
      return true;
    });
  }

  function renderProbes() {
    var tbody = $("probes-tbody");
    tbody.innerHTML = "";

    var rows = filteredProbeRows();
    var pageSize = computePageSize("probes");
    var totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
    if (state.probesPage > totalPages) state.probesPage = totalPages;
    if (state.probesPage < 1) state.probesPage = 1;

    var probes = rows.slice((state.probesPage - 1) * pageSize, state.probesPage * pageSize);

    if (!probes.length) {
      var tr = document.createElement("tr");
      tr.className = "empty-row";
      var td = document.createElement("td");
      td.colSpan = 4;
      td.textContent = recentProbes.length
        ? "No probes match the current search or filter."
        : "no probes recorded yet";
      tr.appendChild(td);
      tbody.appendChild(tr);
      return;
    }

    probes.forEach(function (probe) {
      var row = document.createElement("tr");

      var tdModule = document.createElement("td");
      var nameSpan = document.createElement("span");
      nameSpan.className = "module-name";
      nameSpan.textContent = probe.module;
      tdModule.appendChild(nameSpan);
      row.appendChild(tdModule);

      var tdTarget = document.createElement("td");
      var targetSpan = document.createElement("span");
      targetSpan.className = "target-cell";
      targetSpan.textContent = probe.target;
      targetSpan.title = probe.target;
      tdTarget.appendChild(targetSpan);
      row.appendChild(tdTarget);

      var tdResult = document.createElement("td");
      var resultSpan = document.createElement("span");
      resultSpan.textContent = probe.result;
      resultSpan.className = probe.result === "Success" ? "result-success" : "result-failure";
      tdResult.appendChild(resultSpan);
      row.appendChild(tdResult);

      var tdLogs = document.createElement("td");
      tdLogs.className = "col-actions-cell";
      var logsBtn = document.createElement("button");
      logsBtn.type = "button";
      logsBtn.className = "btn ghost sm icon-only";
      logsBtn.setAttribute("data-action-tip", "View logs");
      logsBtn.setAttribute("aria-label", "View logs");
      logsBtn.innerHTML = '<svg class="icon" viewBox="0 0 24 24"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>';
      logsBtn.addEventListener("click", function () { openProbeLog(probe.logId, probe.module, probe.target); });
      tdLogs.appendChild(logsBtn);
      row.appendChild(tdLogs);

      tbody.appendChild(row);
    });

    renderPagerInto("probe-page-prev", "probe-page-numbers", "probe-page-next", state.probesPage, totalPages, function (p) {
      state.probesPage = p;
      renderProbes();
    });
    settleRowHeight("probes", tbody);
  }

  function openProbeLog(id, moduleName) {
    var url = "/probe-log.html?id=" + encodeURIComponent(id) +
      "&module=" + encodeURIComponent(moduleName || "");
    window.open(url, "_blank");
  }

  setInterval(function () {
    fetchProbes();
  }, 5000);

  function highlightLogSyntax(text) {
    var escaped = escapeHtml(text);
    escaped = escaped.replace(/\bts=[^\s]+/g, function (match) {
      return '<span class="log-ts">' + match + "</span>";
    });
    escaped = escaped.replace(/caller=[^\s]+/g, function (match) {
      return '<span class="log-caller">' + match + "</span>";
    });
    escaped = escaped.replace(/level=error/g, '<span class="log-err">level=error</span>');
    escaped = escaped.replace(/level=warn(?:ing)?/g, function (match) {
      return '<span class="log-warn">' + match + "</span>";
    });
    escaped = escaped.replace(/level=info/g, '<span class="log-info">level=info</span>');
    escaped = escaped.replace(/level=debug/g, '<span class="log-dbg">level=debug</span>');
    escaped = escaped.replace(/msg="([^"]*)"/g, function (_match, message) {
      return 'msg="<span class="log-msg">' + message + '</span>"';
    });
    return escaped;
  }

  function highlightLogLine(text, query) {
    if (!text) return "";
    if (!query) return highlightLogSyntax(text);

    var parts = [];
    var lower = text.toLowerCase();
    var needle = query.toLowerCase();
    var position = 0;
    var found;

    while ((found = lower.indexOf(needle, position)) !== -1) {
      if (found > position) {
        parts.push({ type: "text", value: text.slice(position, found) });
      }
      parts.push({ type: "mark", value: text.slice(found, found + needle.length) });
      position = found + needle.length;
    }
    if (position < text.length) {
      parts.push({ type: "text", value: text.slice(position) });
    }

    return parts.map(function (part) {
      if (part.type === "mark") {
        return '<mark class="hl">' + escapeHtml(part.value) + "</mark>";
      }
      return highlightLogSyntax(part.value);
    }).join("");
  }

  function createLogLine(index, text, query) {
    var line = document.createElement("div");
    line.className = "log-line";

    var num = document.createElement("span");
    num.className = "ln";
    num.textContent = index + 1;

    var content = document.createElement("span");
    content.className = "log-text";
    content.innerHTML = highlightLogLine(text, query || "");

    line.appendChild(num);
    line.appendChild(content);
    return line;
  }

  function createLogPanel(ids) {
    var lines = [];
    var query = "";
    var marks = [];
    var currentMatch = -1;
    var searchTimer = null;

    function fetchLogs(keepView) {
      return api("/" + ids.component + "/logs")
        .then(function (text) {
          var parsed = JSON.parse(text);
          var incoming = parsed.lines || [];
          if (incoming.join("\n") === lines.join("\n")) return;
          lines = incoming;
          renderLogs(keepView);
        })
        .catch(function () {  });
    }

    function renderLogs(keepScroll) {
      var body = $(ids.logsBody);
      var prevTop = body.scrollTop;
      var wasAtBottom = body.scrollHeight - body.scrollTop - body.clientHeight < 48;

      body.innerHTML = "";
      marks = [];
      currentMatch = -1;

      lines.forEach(function (text, index) {
        body.appendChild(createLogLine(index, text, query));
      });

      if (!lines.length) {
        var empty = document.createElement("div");
        empty.className = "logs-empty";
        empty.innerHTML = "<p>No logs yet</p>";
        body.appendChild(empty);
      }

      applyHighlight();
      updateMatchUi();

      if (!keepScroll || wasAtBottom) {
        body.scrollTop = body.scrollHeight;
      } else {
        body.scrollTop = Math.min(prevTop, body.scrollHeight);
      }
    }

    function applyHighlight() {
      var body = $(ids.logsBody);
      var rows = body.querySelectorAll(".log-line");
      marks = [];
      currentMatch = -1;

      Array.prototype.forEach.call(rows, function (row, index) {
        var raw = lines[index] !== undefined ? lines[index] : "";
        var content = row.querySelector(".log-text");
        if (!content) return;
        content.innerHTML = highlightLogLine(raw, query);
        if (query) {
          Array.prototype.forEach.call(content.querySelectorAll(".hl"), function (mark) {
            marks.push(mark);
          });
        }
      });
    }

    function updateMatchUi() {
      var total = marks.length;
      var countEl = $(ids.matchCount);

      if (!query) {
        countEl.textContent = "";
      } else if (!total) {
        countEl.textContent = "no matches";
      } else {
        countEl.textContent = (currentMatch + 1) + "/" + total;
      }

      $(ids.prev).disabled = !total;
      $(ids.next).disabled = !total;
    }

    function jumpToMatch(direction) {
      if (!marks.length) return;
      if (currentMatch >= 0 && marks[currentMatch]) {
        marks[currentMatch].classList.remove("current");
      }
      currentMatch = (currentMatch + direction + marks.length) % marks.length;
      var mark = marks[currentMatch];
      mark.classList.add("current");
      mark.scrollIntoView({ block: "center" });
      updateMatchUi();
    }

    $(ids.search).addEventListener("input", function () {
      var value = this.value.trim();
      if (searchTimer) clearTimeout(searchTimer);
      searchTimer = setTimeout(function () {
        query = value;
        applyHighlight();
        updateMatchUi();
      }, 180);
    });

    $(ids.search).addEventListener("keydown", function (event) {
      if (event.key === "Enter") {
        event.preventDefault();
        jumpToMatch(event.shiftKey ? -1 : 1);
      }
    });

    $(ids.prev).addEventListener("click", function () { jumpToMatch(-1); });
    $(ids.next).addEventListener("click", function () { jumpToMatch(1); });

    $(ids.oldest).addEventListener("click", function () {
      $(ids.logsBody).scrollTop = 0;
    });

    $(ids.latest).addEventListener("click", function () {
      var body = $(ids.logsBody);
      body.scrollTop = body.scrollHeight;
    });

    return { fetch: fetchLogs };
  }

  var blackboxLogs = createLogPanel({
    component: "blackbox",
    search: "log-search",
    matchCount: "log-match-count",
    oldest: "log-oldest",
    latest: "log-latest",
    prev: "log-prev",
    next: "log-next",
    logsBody: "logs-body",
  });

  var prometheusLogs = createLogPanel({
    component: "prometheus",
    search: "prom-log-search",
    matchCount: "prom-log-match-count",
    oldest: "prom-log-oldest",
    latest: "prom-log-latest",
    prev: "prom-log-prev",
    next: "prom-log-next",
    logsBody: "prom-logs-body",
  });

  var alertmanagerLogs = createLogPanel({
    component: "alertmanager",
    search: "am-log-search",
    matchCount: "am-log-match-count",
    oldest: "am-log-oldest",
    latest: "am-log-latest",
    prev: "am-log-prev",
    next: "am-log-next",
    logsBody: "am-logs-body",
  });

  setInterval(function () {
    blackboxLogs.fetch(true);
  }, 5000);

  setInterval(function () {
    prometheusLogs.fetch(true);
  }, 5000);

  setInterval(function () {
    alertmanagerLogs.fetch(true);
  }, 5000);

  $("probe-search").addEventListener("input", function () {
    state.probeQuery = this.value.trim().toLowerCase();
    state.probesPage = 1;
    renderProbes();
  });

  Array.prototype.forEach.call(document.querySelectorAll("[data-result-filter]"), function (chip) {
    chip.addEventListener("click", function () {
      state.probeResultFilter = this.getAttribute("data-result-filter");
      Array.prototype.forEach.call(document.querySelectorAll("[data-result-filter]"), function (other) {
        other.classList.toggle("active", other === chip);
      });
      state.probesPage = 1;
      renderProbes();
    });
  });

  $("probe-page-prev").addEventListener("click", function () {
    state.probesPage -= 1;
    renderProbes();
  });

  $("probe-page-next").addEventListener("click", function () {
    state.probesPage += 1;
    renderProbes();
  });

  var PROM_SD_DIR = "/observec/observecData/prometheus/serviceDiscoveryFiles/";
  var PROM_KUBE_DIR = "/observec/observecData/prometheus/kubeConfigs/";
  var PROM_SELF_JOB = "observecPrometheusJobSelf";
  var pendingKubeconfigFile = null;
  var editingJobName = null;
  var editingReceiverName = null;
  var editingInhibitRuleIndex = null;
  var AM_DEFAULT_RECEIVER = "observecAlertmanagerReceiverDefault";
  var fileEditor = { kind: null, name: null };

  function appendEmptyRow(tbody, colSpan, message) {
    var tr = document.createElement("tr");
    tr.className = "empty-row";
    var td = document.createElement("td");
    td.colSpan = colSpan;
    td.textContent = message;
    tr.appendChild(td);
    tbody.appendChild(tr);
  }

  function promNameCell(name, isDirectory) {
    var td = document.createElement("td");
    var span = document.createElement("span");
    span.className = "module-name" + (isDirectory ? " text-muted" : "");
    span.textContent = isDirectory ? name + "/" : name;
    td.appendChild(span);
    return td;
  }

  function promJobNameCell(name) {
    var td = document.createElement("td");
    var span = document.createElement("span");
    span.className = "module-name";
    span.textContent = name;
    td.appendChild(span);
    if (name === PROM_SELF_JOB) {
      var hint = document.createElement("span");
      hint.className = "text-muted";
      hint.textContent = " · self-scrape";
      td.appendChild(hint);
    }
    return td;
  }

  function buildKebab(items) {
    var td = document.createElement("td");
    td.className = "col-actions-cell";

    var kebab = document.createElement("button");
    kebab.className = "row-action kebab";
    kebab.title = "More actions";
    kebab.innerHTML = '<svg class="icon" viewBox="0 0 24 24"><circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/></svg>';
    kebab.addEventListener("click", function (event) {
      event.stopPropagation();
      openRowMenuAt(kebab, items);
    });
    td.appendChild(kebab);
    return td;
  }

  function normalizeYamlFileName(value) {
    value = value.trim().replace(/\\/g, "/").replace(/^\/+|\/+$/g, "");
    if (!value) return "";
    var parts = value.split("/");
    var last = parts[parts.length - 1];
    if (last && !/\.(ya?ml)$/.test(last)) last += ".yml";
    parts[parts.length - 1] = last;
    return parts.join("/");
  }

  function normalizeManagedDirectory(value) {
    return value.trim().replace(/\\/g, "/").replace(/^\/+|\/+$/g, "");
  }

  function relativeSdPath(fullPath) {
    if (!fullPath) return "";
    var path = String(fullPath);
    if (path.indexOf(PROM_SD_DIR) === 0) return path.slice(PROM_SD_DIR.length);
    return path;
  }

  function jobByName(name) {
    var found = null;
    state.jobs.forEach(function (job) {
      if (job.job_name === name) found = job;
    });
    return found;
  }

  function isProbeJob(job) {
    return job.metrics_path === "/probe" && job.params && Array.isArray(job.params.module) && job.params.module.length;
  }

  function isKubernetesJob(job) {
    return job.kubernetes_sd_configs && Array.isArray(job.kubernetes_sd_configs)
      && job.kubernetes_sd_configs.length > 0;
  }

  function firstKubeSdEntry(job) {
    var entries = job.kubernetes_sd_configs || [];
    return entries.length ? entries[0] : {};
  }

  function relativeKubePath(fullPath) {
    if (!fullPath) return "";
    var path = String(fullPath);
    if (path.indexOf(PROM_KUBE_DIR) === 0) return path.slice(PROM_KUBE_DIR.length);
    return path;
  }

  function kubeJobSummary(job) {
    var entry = firstKubeSdEntry(job);
    var role = entry.role || "kubernetes";
    var kubeconfig = relativeKubePath(entry.kubeconfig_file || "");
    return kubeconfig ? role + " via " + kubeconfig : role;
  }

  function detectJobType(job) {
    if (job.job_name === PROM_SELF_JOB) return "static";
    if (isProbeJob(job)) return "probe";
    if (isKubernetesJob(job)) return "kubernetes";
    if (job.file_sd_configs && job.file_sd_configs.length) return "file_sd";
    if (job.static_configs && job.static_configs.length) return "static";
    return "static";
  }

  function jobTypeLabel(job) {
    if (job.job_name === PROM_SELF_JOB) return "self-scrape";
    if (isProbeJob(job)) return "probe";
    if (isKubernetesJob(job)) return "kubernetes";
    if (job.file_sd_configs && job.file_sd_configs.length) return "file_sd";
    if (job.static_configs && job.static_configs.length) return "static";
    return "-";
  }

  function firstStaticTarget(job) {
    var configs = job.static_configs || [];
    for (var i = 0; i < configs.length; i++) {
      var targets = configs[i] && configs[i].targets;
      if (Array.isArray(targets) && targets.length) return targets.join(", ");
    }
    return "";
  }

  function jobSdPaths(job) {
    var names = [];
    (job.file_sd_configs || []).forEach(function (config) {
      (config.files || []).forEach(function (file) { names.push(relativeSdPath(file)); });
    });
    return names;
  }

  function jobTargetsSummary(job) {
    if (job.job_name === PROM_SELF_JOB) return "localhost:9090";
    if (isProbeJob(job)) {
      var staticTarget = firstStaticTarget(job);
      if (staticTarget) return staticTarget;
      var sdPaths = jobSdPaths(job);
      return sdPaths.length ? sdPaths.join(", ") : "-";
    }
    if (isKubernetesJob(job)) return kubeJobSummary(job);
    if (job.file_sd_configs && job.file_sd_configs.length) {
      var paths = jobSdPaths(job);
      return paths.length ? paths.join(", ") : "-";
    }
    return firstStaticTarget(job) || "-";
  }

  function probeRelabelConfigs(blackboxAddress) {
    return [
      { source_labels: ["__address__"], target_label: "__param_target" },
      { source_labels: ["__param_target"], target_label: "instance" },
      { target_label: "__address__", replacement: blackboxAddress || "localhost:9115" },
    ];
  }

  function probeBlackboxAddress(job) {
    var replacement = "localhost:9115";
    (job.relabel_configs || []).forEach(function (rule) {
      if (rule.target_label === "__address__" && rule.replacement) replacement = rule.replacement;
    });
    return replacement;
  }

  function buildRelabelRuleCard(rule) {
    rule = rule || {};
    var card = document.createElement("div");
    card.className = "editor-subcard relabel-card";

    var bar = document.createElement("div");
    bar.className = "editor-bar";
    var title = document.createElement("span");
    title.className = "editor-title";
    title.textContent = "Relabel rule";
    bar.appendChild(title);
    bar.appendChild(makeMiniButton("\u00d7", "Remove relabel rule", function () { card.remove(); }));
    card.appendChild(bar);

    var grid = document.createElement("div");
    grid.className = "relabel-grid";

    function addField(labelText, placeholder, value, className) {
      var field = document.createElement("div");
      field.className = "editor-field";
      var label = document.createElement("label");
      label.className = "editor-label";
      label.textContent = labelText;
      var input = document.createElement("input");
      input.className = "input mono " + (className || "");
      input.placeholder = placeholder || "";
      input.value = value || "";
      field.appendChild(label);
      field.appendChild(input);
      grid.appendChild(field);
      return input;
    }

    addField(
      "Source labels",
      "__address__",
      (rule.source_labels || []).join(", "),
      "relabel-source-labels"
    );

    var actionField = document.createElement("div");
    actionField.className = "editor-field";
    var actionLabel = document.createElement("label");
    actionLabel.className = "editor-label";
    actionLabel.textContent = "Action";
    var actionSelect = document.createElement("select");
    actionSelect.className = "input select relabel-action";
    ["replace", "keep", "drop", "hashmod", "labelmap", "labeldrop", "labelkeep"].forEach(function (action) {
      var option = document.createElement("option");
      option.value = action;
      option.textContent = action;
      actionSelect.appendChild(option);
    });
    actionSelect.value = rule.action || "replace";
    actionField.appendChild(actionLabel);
    actionField.appendChild(actionSelect);
    grid.appendChild(actionField);

    var targetLabelInput = addField("Target label", "__param_target", rule.target_label || "", "relabel-target-label");
    var regexInput = addField("Regex", "(.*)", rule.regex || "", "relabel-regex");
    var replacementInput = addField("Replacement", "localhost:9115", rule.replacement || "", "relabel-replacement");

    card.appendChild(grid);

    card._collect = function () {
      var collected = {};
      var sourceLabels = card.querySelector(".relabel-source-labels").value
        .split(",")
        .map(function (value) { return value.trim(); })
        .filter(Boolean);
      if (sourceLabels.length) collected.source_labels = sourceLabels;

      var action = actionSelect.value;
      if (action && action !== "replace") collected.action = action;

      var targetLabel = targetLabelInput.value.trim();
      if (targetLabel) collected.target_label = targetLabel;

      var regex = regexInput.value.trim();
      if (regex) collected.regex = regex;

      var replacement = replacementInput.value.trim();
      if (replacement) collected.replacement = replacement;

      return collected;
    };

    return card;
  }

  function renderJobRelabelConfigs(rules) {
    var wrap = $("job-relabel-rules");
    if (!wrap) return;
    wrap.innerHTML = "";
    (rules || []).forEach(function (rule) {
      wrap.appendChild(buildRelabelRuleCard(rule));
    });
  }

  function collectRelabelConfigs() {
    var rules = [];
    if (!$("job-relabel-rules")) return rules;
    Array.prototype.forEach.call($("job-relabel-rules").querySelectorAll(".relabel-card"), function (card) {
      var rule = card._collect();
      if (Object.keys(rule).length) rules.push(rule);
    });
    return rules;
  }

  function syncProbeRelabelAddress(address) {
    if ($("f-job-type").value !== "probe" || !$("job-relabel-rules")) return;
    Array.prototype.forEach.call($("job-relabel-rules").querySelectorAll(".relabel-card"), function (card) {
      var targetLabel = card.querySelector(".relabel-target-label");
      var replacement = card.querySelector(".relabel-replacement");
      if (targetLabel && targetLabel.value.trim() === "__address__" && replacement) {
        replacement.value = address || "localhost:9115";
      }
    });
  }

  var MANAGED_JOB_KEYS = [
    "scrape_interval",
    "metrics_path",
    "static_configs",
    "file_sd_configs",
    "kubernetes_sd_configs",
    "params",
    "relabel_configs",
  ];

  function loadJobs() {
    return api("/prometheus/jobs")
      .then(function (text) {
        state.jobs = JSON.parse(text);
        renderJobsTable();
      })
      .catch(function (err) { toast(err.message, true); });
  }

  function renderJobsTable() {
    closeRowMenu();
    var tbody = $("job-tbody");
    tbody.innerHTML = "";

    var jobs = state.jobs.filter(function (job) {
      var name = job.job_name || "";
      return !state.jobQuery || name.toLowerCase().indexOf(state.jobQuery) !== -1;
    });

    jobs.forEach(function (job) {
      var name = job.job_name || "(unnamed)";

      var tr = document.createElement("tr");
      tr.appendChild(promJobNameCell(name));

      var tdType = document.createElement("td");
      var typeText = jobTypeLabel(job);
      if (isProbeJob(job) && job.params.module.length) {
        typeText += " · " + job.params.module.join(", ");
      }
      tdType.textContent = typeText;
      tdType.title = typeText;
      tr.appendChild(tdType);

      var targetsText = jobTargetsSummary(job);
      var tdTargets = document.createElement("td");
      tdTargets.textContent = targetsText;
      tdTargets.title = targetsText;
      tr.appendChild(tdTargets);

      var tdInterval = document.createElement("td");
      tdInterval.textContent = job.scrape_interval || "-";
      tr.appendChild(tdInterval);

      var jobActions = [
        {
          label: "Edit",
          onClick: function () { openJobDialog(name); },
        },
      ];
      if (state.jobs.length > 1) {
        jobActions.push({
          label: "Delete",
          danger: true,
          onClick: function () {
            uiConfirm("Delete job \"" + name + "\"?").then(function (ok) {
              if (ok) deleteJobs([name]);
            });
          },
        });
      }
      tr.appendChild(buildKebab(jobActions));

      tbody.appendChild(tr);
    });

    if (!jobs.length) {
      appendEmptyRow(tbody, 5,
        state.jobQuery ? "No jobs match \"" + state.jobQuery + "\"" : "No scrape jobs yet.");
    }

    $("job-count").textContent =
      jobs.length + " of " + state.jobs.length + " job(s)";
  }

  function deleteJobs(names, done) {
    api("/prometheus/jobs", {
      method: "DELETE",
      body: JSON.stringify(names),
    })
      .then(function (message) {
        toast(extractMessage(message));
        return loadJobs();
      })
      .then(function () { if (done) done(); })
      .catch(function (err) { toast(err.message, true); });
  }

  function ruleStats(content) {
    var groups = content && Array.isArray(content.groups) ? content.groups : [];
    var rules = 0;
    groups.forEach(function (group) {
      rules += Array.isArray(group.rules) ? group.rules.length : 0;
    });
    return { groups: groups.length, rules: rules };
  }

  function loadRules() {
    return Promise.all([
      api("/prometheus/rules"),
      api("/prometheus/rule-directories"),
    ])
      .then(function (results) {
        state.ruleFiles = JSON.parse(results[0]);
        state.ruleDirectories = JSON.parse(results[1]);
        renderRuleTree();
      })
      .catch(function (err) { toast(err.message, true); });
  }

  var FT_TWISTIE_SVG = '<svg class="icon" viewBox="0 0 24 24"><path d="m9 18 6-6-6-6"/></svg>';
  var FT_FOLDER_SVG = '<svg class="ft-icon" viewBox="0 0 24 24"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/></svg>';
  var FT_FILE_SVG = '<svg class="ft-icon" viewBox="0 0 24 24"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v5h5"/></svg>';
  var FT_NEW_FILE_SVG = '<svg class="icon" viewBox="0 0 24 24"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4h4"/><path d="M12 11v6M9 14h6"/></svg>';
  var FT_NEW_FOLDER_SVG = '<svg class="icon" viewBox="0 0 24 24"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/><path d="M12 10v6M9 13h6"/></svg>';
  var FT_EDIT_SVG = '<svg class="icon" viewBox="0 0 24 24"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/></svg>';
  var FT_DELETE_SVG = '<svg class="icon" viewBox="0 0 24 24"><path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/></svg>';

  function fileTreeBaseName(path) {
    return String(path).split("/").pop();
  }

  function managedFileLabel(kind) {
    return kind === "rule" ? "rule file" : "service discovery file";
  }

  function buildFileTreeRoot(fileNames, dirNames) {
    var root = { name: "", path: "", dirs: {}, dirOrder: [], files: [], explicit: true };
    function ensureDir(path) {
      if (!path) return root;
      var node = root;
      var current = "";
      path.split("/").forEach(function (part) {
        current = current ? current + "/" + part : part;
        if (!node.dirs[part]) {
          node.dirs[part] = { name: part, path: current, dirs: {}, dirOrder: [], files: [], explicit: false };
          node.dirOrder.push(part);
        }
        node = node.dirs[part];
      });
      return node;
    }
    dirNames.forEach(function (path) { ensureDir(path).explicit = true; });
    fileNames.forEach(function (path) {
      var slash = path.lastIndexOf("/");
      var dir = slash === -1 ? root : ensureDir(path.slice(0, slash));
      dir.files.push(path);
    });
    (function sortNode(node) {
      node.dirOrder.sort();
      node.files.sort();
      node.dirOrder.forEach(function (key) { sortNode(node.dirs[key]); });
    })(root);
    return root;
  }

  function ftAction(svg, title, danger, onClick) {
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "ft-action" + (danger ? " danger" : "");
    btn.title = title;
    btn.innerHTML = svg;
    btn.addEventListener("click", function (event) {
      event.stopPropagation();
      onClick();
    });
    return btn;
  }

  function countTreeFiles(node) {
    var total = node.files.length;
    node.dirOrder.forEach(function (key) { total += countTreeFiles(node.dirs[key]); });
    return total;
  }

  function subtreeMatches(node, query) {
    if (node.path.toLowerCase().indexOf(query) !== -1) return true;
    for (var i = 0; i < node.files.length; i++) {
      if (node.files[i].toLowerCase().indexOf(query) !== -1) return true;
    }
    for (var j = 0; j < node.dirOrder.length; j++) {
      if (subtreeMatches(node.dirs[node.dirOrder[j]], query)) return true;
    }
    return false;
  }

  function renderFileTree(options) {
    closeRowMenu();
    var el = $(options.containerId);
    el.innerHTML = "";
    var query = options.query || "";
    var searching = Boolean(query);
    var shownFiles = 0;

    function appendDir(node, parent) {
      if (searching && !subtreeMatches(node, query)) return;
      var expanded = searching || !options.collapsed[node.path];

      var row = document.createElement("div");
      row.className = "ft-row dir" + (expanded ? " open" : "");
      row.title = node.path + "/";

      var twistie = document.createElement("button");
      twistie.type = "button";
      twistie.className = "ft-twistie";
      twistie.title = expanded ? "Collapse folder" : "Expand folder";
      twistie.innerHTML = FT_TWISTIE_SVG;
      twistie.addEventListener("click", function (event) {
        event.stopPropagation();
        options.collapsed[node.path] = expanded;
        options.refresh();
      });
      row.appendChild(twistie);

      var folder = document.createElement("span");
      folder.style.display = "inline-flex";
      folder.innerHTML = FT_FOLDER_SVG;
      row.appendChild(folder.firstChild);

      var name = document.createElement("span");
      name.className = "ft-name";
      name.textContent = node.name;
      row.appendChild(name);

      var total = countTreeFiles(node);
      var meta = document.createElement("span");
      meta.className = "ft-meta";
      meta.textContent = total ? total + (total === 1 ? " file" : " files") : "empty";
      row.appendChild(meta);

      var dirActions = document.createElement("span");
      dirActions.className = "ft-actions";
      dirActions.appendChild(ftAction(FT_NEW_FILE_SVG, "New file in " + node.path + "/", false, function () {
        openFileDialog(options.kind, null);
        $("f-file-name").value = node.path + "/";
      }));
      dirActions.appendChild(ftAction(FT_NEW_FOLDER_SVG, "New folder in " + node.path + "/", false, function () {
        createManagedDirectory(options.kind, node.path + "/");
      }));
      if (node.explicit) {
        dirActions.appendChild(ftAction(FT_DELETE_SVG, "Delete folder", true, function () {
          uiConfirm("Delete directory \"" + node.path + "/\"?").then(function (ok) {
            if (ok) deleteManagedDirectory(options.kind, node.path);
          });
        }));
      }
      row.appendChild(dirActions);
      row.addEventListener("click", function () {
        options.collapsed[node.path] = expanded;
        options.refresh();
      });
      parent.appendChild(row);

      if (expanded) {
        var kids = document.createElement("div");
        kids.className = "ft-children";
        node.dirOrder.forEach(function (key) { appendDir(node.dirs[key], kids); });
        node.files.forEach(function (path) { appendFile(path, kids); });
        if (kids.firstChild) parent.appendChild(kids);
      }
    }

    function appendFile(path, parent) {
      if (searching && path.toLowerCase().indexOf(query) === -1) return;
      shownFiles += 1;

      var row = document.createElement("div");
      row.className = "ft-row file";
      row.title = path;

      var gap = document.createElement("span");
      gap.className = "ft-twistie placeholder";
      row.appendChild(gap);

      var icon = document.createElement("span");
      icon.style.display = "inline-flex";
      icon.innerHTML = FT_FILE_SVG;
      row.appendChild(icon.firstChild);

      var name = document.createElement("span");
      name.className = "ft-name";
      name.textContent = fileTreeBaseName(path);
      row.appendChild(name);

      var metaText = options.fileMeta(path);
      if (metaText) {
        var meta = document.createElement("span");
        meta.className = "ft-meta";
        meta.textContent = metaText;
        row.appendChild(meta);
      }

      var fileActions = document.createElement("span");
      fileActions.className = "ft-actions";
      fileActions.appendChild(ftAction(FT_EDIT_SVG, "Edit file", false, function () {
        options.onOpenFile(options.kind, path);
      }));
      fileActions.appendChild(ftAction(FT_DELETE_SVG, "Delete file", true, function () {
        uiConfirm("Delete " + managedFileLabel(options.kind) + " \"" + path + "\"?").then(function (ok) {
          if (ok) deleteManagedFiles(options.kind, [path]);
        });
      }));
      row.appendChild(fileActions);
      row.addEventListener("click", function () { options.onOpenFile(options.kind, path); });
      parent.appendChild(row);
    }

    options.root.dirOrder.forEach(function (key) { appendDir(options.root.dirs[key], el); });
    options.root.files.forEach(function (path) { appendFile(path, el); });

    if (!el.firstChild) {
      var empty = document.createElement("div");
      empty.className = "ft-empty";
      empty.textContent = searching ? "No files match \"" + query + "\"" : options.emptyText;
      el.appendChild(empty);
    }

    return shownFiles;
  }

  function renderRuleTree() {
    var fileNames = Object.keys(state.ruleFiles);
    var root = buildFileTreeRoot(fileNames, state.ruleDirectories);
    var shown = renderFileTree({
      containerId: "rule-tree",
      kind: "rule",
      root: root,
      query: state.ruleQuery,
      collapsed: state.ruleCollapsed,
      fileMeta: function (path) {
        var stats = ruleStats(state.ruleFiles[path]);
        return stats.groups + (stats.groups === 1 ? " group" : " groups") +
          " · " + stats.rules + (stats.rules === 1 ? " rule" : " rules");
      },
      onOpenFile: openFileDialog,
      refresh: renderRuleTree,
      emptyText: "No rule files yet.",
    });
    $("rule-count").textContent = shown + " of " + fileNames.length + " file(s)";
  }

  function targetCount(content) {
    var total = 0;
    (Array.isArray(content) ? content : []).forEach(function (entry) {
      total += entry && Array.isArray(entry.targets) ? entry.targets.length : 0;
    });
    return total;
  }

  function loadServices() {
    return Promise.all([
      api("/prometheus/service-files"),
      api("/prometheus/service-directories"),
    ])
      .then(function (results) {
        state.serviceFiles = JSON.parse(results[0]);
        state.serviceDirectories = JSON.parse(results[1]);
        renderServiceTree();
      })
      .catch(function (err) { toast(err.message, true); });
  }

  function renderServiceTree() {
    var fileNames = Object.keys(state.serviceFiles);
    var root = buildFileTreeRoot(fileNames, state.serviceDirectories);
    var shown = renderFileTree({
      containerId: "service-tree",
      kind: "service",
      root: root,
      query: state.serviceQuery,
      collapsed: state.serviceCollapsed,
      fileMeta: function (path) {
        var total = targetCount(state.serviceFiles[path]);
        return total + (total === 1 ? " target" : " targets");
      },
      onOpenFile: openFileDialog,
      refresh: renderServiceTree,
      emptyText: "No service discovery files yet.",
    });
    $("service-count").textContent = shown + " of " + fileNames.length + " file(s)";
  }

  function deleteManagedFiles(kind, names, done) {
    api(kind === "rule" ? "/prometheus/rules" : "/prometheus/service-files", {
      method: "DELETE",
      body: JSON.stringify(names),
    })
      .then(function (message) {
        toast(extractMessage(message));
        return kind === "rule" ? loadRules() : loadServices();
      })
      .then(function () { if (done) done(); })
      .catch(function (err) { toast(err.message, true); });
  }

  function createManagedDirectory(kind, initial) {
    var label = kind === "rule" ? "rule file" : "service discovery";
    var path = window.prompt("Directory path (for example team/prod):", initial || "");
    if (path === null) return;
    path = normalizeManagedDirectory(path);
    if (!path) {
      toast("Directory path is required.", true);
      return;
    }

    var endpoint = kind === "rule" ? "/prometheus/rule-directories" : "/prometheus/service-directories";
    api(endpoint, {
      method: "POST",
      body: JSON.stringify(path),
    })
      .then(function (message) {
        toast(extractMessage(message));
        return kind === "rule" ? loadRules() : loadServices();
      })
      .catch(function (err) { toast(err.message, true); });
  }

  function deleteManagedDirectory(kind, path, done) {
    var endpoint = kind === "rule" ? "/prometheus/rule-directories" : "/prometheus/service-directories";
    api(endpoint, {
      method: "DELETE",
      body: JSON.stringify(path),
    })
      .then(function (message) {
        toast(extractMessage(message));
        return kind === "rule" ? loadRules() : loadServices();
      })
      .then(function () { if (done) done(); })
      .catch(function (err) { toast(err.message, true); });
  }

  $("add-job-btn").addEventListener("click", function () { openJobDialog(null); });

  $("add-rule-btn").addEventListener("click", function () { openFileDialog("rule", null); });
  $("add-rule-dir-btn").addEventListener("click", function () { createManagedDirectory("rule"); });

  $("add-service-btn").addEventListener("click", function () { openFileDialog("service", null); });
  $("add-service-dir-btn").addEventListener("click", function () { createManagedDirectory("service"); });

  $("job-search").addEventListener("input", function () {
    state.jobQuery = this.value.trim().toLowerCase();
    renderJobsTable();
  });

  $("rule-search").addEventListener("input", function () {
    state.ruleQuery = this.value.trim().toLowerCase();
    renderRuleTree();
  });

  $("service-search").addEventListener("input", function () {
    state.serviceQuery = this.value.trim().toLowerCase();
    renderServiceTree();
  });

  Array.prototype.forEach.call(document.querySelectorAll(".prom-reload-btn"), function (btn) {
    btn.addEventListener("click", function () {
      btn.disabled = true;
      api("/prometheus/reload", { method: "POST" })
        .then(function (message) { toast(extractMessage(message)); refreshStatus(); })
        .catch(function (err) { toast(err.message, true); })
        .finally(function () { btn.disabled = false; });
    });
  });

  function handlePromToggle(chk) {
    chk.addEventListener("change", function () {
      var enable = chk.checked;
      chk.disabled = true;
      var path = enable ? "/prometheus/enable" : "/prometheus/disable";
      api(path, { method: "POST" })
        .then(function (message) { toast(extractMessage(message)); refreshStatus(); })
        .catch(function (err) { toast(err.message, true); chk.checked = !enable; })
        .finally(function () { chk.disabled = false; });
    });
  }
  Array.prototype.forEach.call(document.querySelectorAll(".prom-toggle-btn"), handlePromToggle);

  function handleAmToggle(chk) {
    chk.addEventListener("change", function () {
      var enable = chk.checked;
      chk.disabled = true;
      var path = enable ? "/alertmanager/enable" : "/alertmanager/disable";
      api(path, { method: "POST" })
        .then(function (message) { toast(extractMessage(message)); refreshStatus(); })
        .catch(function (err) { toast(err.message, true); chk.checked = !enable; })
        .finally(function () { chk.disabled = false; });
    });
  }
  Array.prototype.forEach.call(document.querySelectorAll(".am-reload-btn"), function (btn) {
    btn.addEventListener("click", function () {
      btn.disabled = true;
      api("/alertmanager/reload", { method: "POST" })
        .then(function (message) { toast(extractMessage(message)); refreshStatus(); })
        .catch(function (err) { toast(err.message, true); })
        .finally(function () { btn.disabled = false; });
    });
  });
  Array.prototype.forEach.call(document.querySelectorAll(".am-toggle-btn"), handleAmToggle);

  function showJobError(message) {
    var el = $("job-error");
    el.textContent = message;
    el.classList.remove("hidden");
  }

  function hideJobError() {
    $("job-error").classList.add("hidden");
  }

  function refreshJobDatalists() {
    var modulesList = $("job-modules-datalist");
    modulesList.innerHTML = "";
    Object.keys(state.modules).forEach(function (name) {
      var option = document.createElement("option");
      option.value = name;
      modulesList.appendChild(option);
    });

    var sdList = $("job-sd-datalist");
    sdList.innerHTML = "";
    Object.keys(state.serviceFiles).forEach(function (name) {
      var option = document.createElement("option");
      option.value = name;
      sdList.appendChild(option);
    });
  }

  function setJobFieldGroup(className, visible) {
    Array.prototype.forEach.call(document.querySelectorAll("." + className), function (el) {
      el.classList.toggle("hidden", !visible);
    });
  }

  function updateJobTypeUi() {
    var type = $("f-job-type").value;
    var isSelfJob = editingJobName === PROM_SELF_JOB;

    $("f-job-type").disabled = isSelfJob;

    setJobFieldGroup("job-fields-static", isSelfJob || type === "static");
    setJobFieldGroup("job-fields-file-sd", !isSelfJob && type === "file_sd");
    setJobFieldGroup("job-fields-kubernetes", !isSelfJob && type === "kubernetes");
    setJobFieldGroup("job-fields-probe", !isSelfJob && type === "probe");
    $("job-relabel-section").classList.toggle("hidden", isSelfJob);
    updateKubeconfigUploadRow();

    if (!isSelfJob && type === "probe") {
      updateProbeModeUi();
      if (!$("job-relabel-rules").children.length) {
        renderJobRelabelConfigs(probeRelabelConfigs($("f-job-probe-address").value.trim()));
      }
    } else {
      setJobFieldGroup("job-fields-probe-single", false);
      setJobFieldGroup("job-fields-probe-multiple", false);
    }

    var subtitles = {
      static: "",
      file_sd: "Discover scrape targets from YAML files in the Service Files tab.",
      kubernetes: "Discover targets from a Kubernetes cluster using a kubeconfig stored under prometheus/kubeConfigs.",
      probe: "Probe targets through the blackbox exporter with automatic relabeling.",
    };
    $("job-dialog-subtitle").textContent = isSelfJob
      ? "Built-in Prometheus self-scrape job. Only the scrape interval can be changed."
      : (subtitles[type] || subtitles.static);
  }

  function updateProbeModeUi() {
    if ($("f-job-type").value !== "probe" || editingJobName === PROM_SELF_JOB) return;
    var multiple = $("f-job-probe-mode").value === "multiple";
    setJobFieldGroup("job-fields-probe-single", !multiple);
    setJobFieldGroup("job-fields-probe-multiple", multiple);
  }

  function setSelfJobFieldState() {
    var isSelfJob = editingJobName === PROM_SELF_JOB;
    var lock = isSelfJob;
    $("f-job-target").disabled = lock;
    $("f-job-static-path").disabled = lock;
    $("f-job-sd").disabled = lock;
    $("f-job-file-path").disabled = lock;
    $("kubeconfig-choose-btn").disabled = lock;
    $("f-job-kube-role").disabled = lock;
    $("f-job-kube-api").disabled = lock;
    $("f-job-kube-namespaces").disabled = lock;
    $("f-job-kube-path").disabled = lock;
    $("f-job-probe-mode").disabled = lock;
    $("f-job-probe-target").disabled = lock;
    $("f-job-probe-sd").disabled = lock;
    $("f-job-module").disabled = lock;
    $("f-job-probe-address").disabled = lock;
    $("add-relabel-rule-btn").disabled = lock;
  }

  function parseSdFileList(value) {
    return value.split(",")
      .map(normalizeYamlFileName)
      .filter(Boolean);
  }

  function loadKubeConfigs() {
    return api("/prometheus/kube-configs")
      .then(function (text) {
        var parsed = JSON.parse(text);
        state.kubeConfigs = parsed.kubeconfigs || [];
        refreshKubeconfigSelect();
      })
      .catch(function (err) {
        state.kubeConfigs = [];
        refreshKubeconfigSelect();
      });
  }

  function refreshKubeconfigSelect() {
    var wrap = $("kubeconfig-chips");
    if (!wrap) return;
    var current = $("f-job-kubeconfig") ? $("f-job-kubeconfig").value : "";
    wrap.innerHTML = "";
    if (!state.kubeConfigs.length) {
      var empty = document.createElement("span");
      empty.className = "text-muted hidden";
      empty.style.fontSize = "12px";
      empty.textContent = "";
      wrap.appendChild(empty);
    }
    state.kubeConfigs.forEach(function (name) {
      var chip = document.createElement("button");
      chip.type = "button";
      chip.className = "chip kube-chip" + (name === current ? " active" : "");
      chip.textContent = name;
      chip.title = name;
      chip.addEventListener("click", function () {
        selectKubeconfig(name);
      });
      wrap.appendChild(chip);
    });
    syncKubeconfigPicker();
  }

  function selectKubeconfig(name) {
    $("f-job-kubeconfig").value = name || "";
    pendingKubeconfigFile = null;
    $("f-job-kubeconfig-file").value = "";
    $("kubeconfig-upload-name").textContent = "";
    syncKubeconfigPicker();
  }

  function setKubeconfigSelectValue(name) {
    if (!$("f-job-kubeconfig")) return;
    refreshKubeconfigSelect();
    if (name && state.kubeConfigs.indexOf(name) === -1) {
      var wrap = $("kubeconfig-chips");
      var missing = document.createElement("button");
      missing.type = "button";
      missing.className = "chip kube-chip missing active";
      missing.textContent = name + " (missing)";
      missing.title = name;
      missing.addEventListener("click", function () {
        selectKubeconfig(name);
      });
      wrap.insertBefore(missing, wrap.firstChild);
    }
    selectKubeconfig(name || "");
  }

  function syncKubeconfigPicker() {
    var current = $("f-job-kubeconfig") ? $("f-job-kubeconfig").value : "";
    var wrap = $("kubeconfig-chips");
    if (wrap) {
      Array.prototype.forEach.call(wrap.querySelectorAll(".kube-chip"), function (chip) {
        var chipName = chip.title || chip.textContent;
        var isMissing = chip.classList.contains("missing");
        var base = isMissing ? chipName : chip.textContent;
        chip.classList.toggle("active", !pendingKubeconfigFile && base === current);
      });
    }
    var saveRow = $("kubeconfig-saveas-row");
    if (saveRow) saveRow.classList.toggle("hidden", !pendingKubeconfigFile);
  }

  function updateKubeconfigUploadRow() {
    syncKubeconfigPicker();
  }

  function runPendingKubeconfigUpload() {
    if (!pendingKubeconfigFile) return Promise.resolve(null);
    var saveName = $("f-job-kubeconfig-name").value.trim();
    var formData = new FormData();
    formData.append("name", saveName);
    formData.append("file", pendingKubeconfigFile);
    return fetch("/prometheus/kube-config", { method: "POST", body: formData })
      .then(function (res) {
        return res.text().then(function (bodyText) {
          if (!res.ok) throw new Error(extractMessage(bodyText));
          var parsed = JSON.parse(bodyText);
          if (state.kubeConfigs.indexOf(parsed.name) === -1) {
            state.kubeConfigs.push(parsed.name);
          }
          refreshKubeconfigSelect();
          selectKubeconfig(parsed.name);
          return parsed.path;
        });
      });
  }

  function openJobDialog(jobName) {
    editingJobName = jobName || null;
    var isEdit = Boolean(jobName);
    var job = isEdit ? (jobByName(jobName) || {}) : {};
    var type = detectJobType(job);

    $("job-dialog-title").textContent = isEdit ? "Edit job" : "Add job";
    $("f-job-type").value = type;
    $("f-job-name").value = jobName || "";
    $("f-job-name").disabled = isEdit;
    $("f-job-interval").value = job.scrape_interval || "";

    $("f-job-target").value = firstStaticTarget(job);
    $("f-job-static-path").value = job.metrics_path || "/metrics";
    $("f-job-sd").value = jobSdPaths(job).join(", ");
    $("f-job-file-path").value = job.metrics_path || "/metrics";

    pendingKubeconfigFile = null;
    var kubeEntry = firstKubeSdEntry(job);
    $("f-job-kube-role").value = kubeEntry.role || "node";
    $("f-job-kube-api").value = kubeEntry.api_server || "";
    var kubeNamespaces = kubeEntry.namespaces && Array.isArray(kubeEntry.namespaces.names)
      ? kubeEntry.namespaces.names.join(", ")
      : "";
    $("f-job-kube-namespaces").value = kubeNamespaces;
    $("f-job-kube-path").value = job.metrics_path || "/metrics";
    loadKubeConfigs().then(function () {
      setKubeconfigSelectValue(relativeKubePath(kubeEntry.kubeconfig_file || ""));
    });

    if (isProbeJob(job)) {
      $("f-job-probe-mode").value = job.file_sd_configs && job.file_sd_configs.length ? "multiple" : "single";
      $("f-job-probe-target").value = firstStaticTarget(job);
      $("f-job-probe-sd").value = jobSdPaths(job).join(", ");
      $("f-job-module").value = job.params.module.join(", ");
      $("f-job-probe-address").value = probeBlackboxAddress(job);
    } else {
      $("f-job-probe-mode").value = "single";
      $("f-job-probe-target").value = "";
      $("f-job-probe-sd").value = "";
      $("f-job-module").value = "";
      $("f-job-probe-address").value = "localhost:9115";
    }

    if (jobName === PROM_SELF_JOB) {
      $("f-job-target").value = "localhost:9090";
      $("f-job-static-path").value = "/metrics";
      renderJobRelabelConfigs([]);
    } else {
      var relabelRules = Array.isArray(job.relabel_configs)
        ? JSON.parse(JSON.stringify(job.relabel_configs))
        : [];
      if (!isEdit && type === "probe" && !relabelRules.length) {
        relabelRules = probeRelabelConfigs($("f-job-probe-address").value.trim());
      }
      renderJobRelabelConfigs(relabelRules);
    }

    refreshJobDatalists();
    updateJobTypeUi();
    setSelfJobFieldState();
    hideJobError();
    $("f-job-delete").classList.toggle("hidden", !isEdit || state.jobs.length <= 1);
    $("f-job-save").textContent = isEdit ? "Save changes" : "Create job";

    $("dialog-overlay").classList.remove("hidden");
    $("job-dialog").classList.remove("hidden");
    $("f-job-name").focus();
  }

  function buildJobConfig() {
    if (editingJobName === PROM_SELF_JOB) {
      var selfJob = JSON.parse(JSON.stringify(jobByName(PROM_SELF_JOB) || {}));
      delete selfJob.job_name;
      var selfInterval = $("f-job-interval").value.trim();
      if (selfInterval) selfJob.scrape_interval = selfInterval;
      else delete selfJob.scrape_interval;
      return selfJob;
    }

    var original = editingJobName ? (jobByName(editingJobName) || {}) : {};
    var config = {};
    var type = $("f-job-type").value;
    var interval = $("f-job-interval").value.trim();
    if (interval) config.scrape_interval = interval;

    if (type === "static") {
      config.metrics_path = $("f-job-static-path").value.trim() || "/metrics";
      config.static_configs = [{ targets: splitTargets($("f-job-target").value) }];
    } else if (type === "file_sd") {
      var sdFiles = parseSdFileList($("f-job-sd").value);
      config.metrics_path = $("f-job-file-path").value.trim() || "/metrics";
      config.file_sd_configs = [{
        files: sdFiles.map(function (name) { return PROM_SD_DIR + name; }),
      }];
    } else if (type === "kubernetes") {
      var kubeconfigName = $("f-job-kubeconfig").value;
      var kubeEntry = { role: $("f-job-kube-role").value };
      var kubeApi = $("f-job-kube-api").value.trim();
      if (kubeApi) kubeEntry.api_server = kubeApi;
      kubeEntry.kubeconfig_file = PROM_KUBE_DIR + kubeconfigName;
      var kubeNamespaces = $("f-job-kube-namespaces").value.split(",")
        .map(function (value) { return value.trim(); })
        .filter(Boolean);
      if (kubeNamespaces.length) kubeEntry.namespaces = { names: kubeNamespaces };
      config.metrics_path = $("f-job-kube-path").value.trim() || "/metrics";
      config.kubernetes_sd_configs = [kubeEntry];
    } else if (type === "probe") {
      var modules = $("f-job-module").value.split(",")
        .map(function (value) { return value.trim(); })
        .filter(Boolean);
      config.metrics_path = "/probe";
      config.params = { module: modules };
      if ($("f-job-probe-mode").value === "multiple") {
        var probeSdFiles = parseSdFileList($("f-job-probe-sd").value);
        config.file_sd_configs = [{
          files: probeSdFiles.map(function (name) { return PROM_SD_DIR + name; }),
        }];
      } else {
        config.static_configs = [{ targets: splitTargets($("f-job-probe-target").value) }];
      }
    }

    var relabelConfigs = collectRelabelConfigs();
    if (relabelConfigs.length) {
      config.relabel_configs = relabelConfigs;
    } else if (type === "probe") {
      config.relabel_configs = probeRelabelConfigs($("f-job-probe-address").value.trim() || "localhost:9115");
    }

    Object.keys(original).forEach(function (key) {
      if (key !== "job_name" && MANAGED_JOB_KEYS.indexOf(key) === -1) {
        config[key] = original[key];
      }
    });
    return config;
  }

  $("kubeconfig-choose-btn").addEventListener("click", function () {
    $("f-job-kubeconfig-file").click();
  });
  $("f-job-kubeconfig-file").addEventListener("change", function () {
    if (!this.files.length) {
      pendingKubeconfigFile = null;
      $("kubeconfig-upload-name").textContent = "";
      syncKubeconfigPicker();
      return;
    }
    pendingKubeconfigFile = this.files[0];
    $("f-job-kubeconfig-file").value = "";
    $("kubeconfig-upload-name").textContent = pendingKubeconfigFile.name;
    if (!$("f-job-kubeconfig-name").value.trim()) {
      $("f-job-kubeconfig-name").value = pendingKubeconfigFile.name;
    }
    syncKubeconfigPicker();
  });

  function validateJobForm() {
    var name = $("f-job-name").value.trim();
    if (!name) return "Job name is required.";

    if (editingJobName === PROM_SELF_JOB) return "";

    var type = $("f-job-type").value;
    if (type === "static") {
      if (!$("f-job-target").value.trim()) return "Target is required for static jobs.";
      return "";
    }
    if (type === "file_sd") {
      if (!parseSdFileList($("f-job-sd").value).length) {
        return "At least one service discovery file is required.";
      }
      return "";
    }
    if (type === "kubernetes") {
      if (pendingKubeconfigFile) {
        if (!$("f-job-kubeconfig-name").value.trim()) {
          return "Give the uploaded kubeconfig a name.";
        }
      } else if (!$("f-job-kubeconfig").value) {
        return "Pick an uploaded kubeconfig or upload a new one.";
      }
      if (!$("f-job-kube-role").value) return "Role is required for kubernetes jobs.";
      return "";
    }
    if (type === "probe") {
      if (!$("f-job-module").value.trim()) return "Blackbox module is required for probe jobs.";
      if (!$("f-job-probe-address").value.trim()) return "Blackbox exporter address is required.";
      if ($("f-job-probe-mode").value === "multiple") {
        if (!parseSdFileList($("f-job-probe-sd").value).length) {
          return "At least one service discovery file is required for multiple probe targets.";
        }
      } else if (!$("f-job-probe-target").value.trim()) {
        return "Probe target is required for single-target probe jobs.";
      }
      return "";
    }
    return "";
  }

  $("job-form").addEventListener("submit", function (event) {
    event.preventDefault();
    hideJobError();

    var validationError = validateJobForm();
    if (validationError) { showJobError(validationError); return; }

    var name = $("f-job-name").value.trim();
    if (!editingJobName && jobByName(name)) {
      showJobError("A job named \"" + name + "\" already exists.");
      return;
    }

    var saveBtn = $("f-job-save");
    saveBtn.disabled = true;
    saveBtn.textContent = "Saving...";

    var needsKubeUpload = $("f-job-type").value === "kubernetes"
      && pendingKubeconfigFile;

    function saveJob() {
      var payload = defineObject(name, buildJobConfig());
      api(editingJobName ? "/prometheus/job" : "/prometheus/jobs", {
        method: editingJobName ? "PUT" : "POST",
        body: JSON.stringify(payload),
      })
        .then(function (message) {
          toast(extractMessage(message));
          closeDialog();
          return loadJobs();
        })
        .catch(function (err) {
          showJobError(err.message);
          saveBtn.disabled = false;
          saveBtn.textContent = editingJobName ? "Save changes" : "Create job";
        });
    }

    if (needsKubeUpload) {
      saveBtn.textContent = "Uploading kubeconfig...";
      runPendingKubeconfigUpload().then(saveJob, function (err) {
        showJobError(err.message);
        saveBtn.disabled = false;
        saveBtn.textContent = editingJobName ? "Save changes" : "Create job";
      });
    } else {
      saveJob();
    }
  });

  $("f-job-delete").addEventListener("click", function () {
    if (!editingJobName) return;
    uiConfirm("Delete job \"" + editingJobName + "\"?").then(function (ok) {
      if (ok) deleteJobs([editingJobName], closeDialog);
    });
  });

  $("f-job-cancel").addEventListener("click", closeDialog);
  $("f-job-type").addEventListener("change", updateJobTypeUi);
  $("f-job-probe-mode").addEventListener("change", updateProbeModeUi);
  $("add-relabel-rule-btn").addEventListener("click", function () {
    $("job-relabel-rules").appendChild(buildRelabelRuleCard({}));
  });
  $("f-job-probe-address").addEventListener("input", function () {
    syncProbeRelabelAddress(this.value.trim());
  });

  function showFileError(message) {
    var el = $("file-error");
    el.textContent = message;
    el.classList.remove("hidden");
  }

  function hideFileError() {
    $("file-error").classList.add("hidden");
  }

  function makeMiniButton(label, title, onClick) {
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "mini-btn";
    btn.textContent = label;
    btn.title = title || "";
    btn.addEventListener("click", onClick);
    return btn;
  }

  var TARGET_INFO_TIP = "One target or comma separated. For HTTP use URL, TCP use host:port, Ping use hostname/IP.";

  function infoTipButton(text) {
    var tip = document.createElement("button");
    tip.type = "button";
    tip.className = "info-tip";
    tip.setAttribute("data-tip", text);
    tip.textContent = "i";
    return tip;
  }

  var infoTipEl = null;
  var infoTipAnchor = null;

  function getInfoTipEl() {
    if (!infoTipEl) {
      infoTipEl = document.createElement("div");
      infoTipEl.className = "info-tip-float";
      infoTipEl.setAttribute("role", "tooltip");
      document.body.appendChild(infoTipEl);
    }
    return infoTipEl;
  }

  function positionInfoTip(anchor) {
    var el = getInfoTipEl();
    var r = anchor.getBoundingClientRect();
    var tipRect = el.getBoundingClientRect();
    var gap = 8;
    var margin = 8;
    var top = r.top - tipRect.height - gap;
    if (top < margin) top = r.bottom + gap;
    if (top + tipRect.height > window.innerHeight - margin) {
      top = Math.max(margin, window.innerHeight - tipRect.height - margin);
    }
    var left = r.left + r.width / 2 - tipRect.width / 2;
    left = Math.max(margin, Math.min(left, window.innerWidth - tipRect.width - margin));

    if (tipRect.width > window.innerWidth - margin * 2) {
      left = margin;
      el.style.maxWidth = (window.innerWidth - margin * 2) + "px";
    }
    el.style.left = left + "px";
    el.style.top = top + "px";
  }

  function showInfoTip(anchor) {
    if (!anchor) return;
    var text = anchor.getAttribute("data-tip") || anchor.getAttribute("data-action-tip");
    if (!text) return;
    var el = getInfoTipEl();
    if (infoTipAnchor === anchor && el.classList.contains("visible")) {
      positionInfoTip(anchor);
      return;
    }
    infoTipAnchor = anchor;
    el.textContent = text;
    el.style.maxWidth = "";
    el.style.left = "0px";
    el.style.top = "0px";
    el.classList.add("visible");
    positionInfoTip(anchor);
  }

  function hideInfoTip() {
    infoTipAnchor = null;
    if (infoTipEl) infoTipEl.classList.remove("visible");
  }

  function tipAnchorFrom(target) {
    return target && target.closest ? target.closest(".info-tip, [data-action-tip]") : null;
  }

  document.addEventListener("mouseover", function (e) {
    var t = tipAnchorFrom(e.target);
    if (t) showInfoTip(t);
  });
  document.addEventListener("mouseout", function (e) {
    if (!infoTipAnchor) return;
    var to = tipAnchorFrom(e.relatedTarget);
    if (to !== infoTipAnchor) hideInfoTip();
  });
  document.addEventListener("focusin", function (e) {
    var t = tipAnchorFrom(e.target);
    if (t) showInfoTip(t);
  });
  document.addEventListener("focusout", function (e) {
    var t = tipAnchorFrom(e.target);
    if (t) hideInfoTip();
  });
  document.addEventListener("click", function () { hideInfoTip(); });
  window.addEventListener("scroll", function () { hideInfoTip(); }, true);
  window.addEventListener("resize", function () { hideInfoTip(); });

  /* Panel-top toolbar buttons show their action in the themed bubble
     instead of the native browser tooltip. */
  Array.prototype.forEach.call(document.querySelectorAll(
    ".section-header button[title], .monitors-header button[title], " +
    ".section-header label[title], .monitors-header label[title]"
  ), function (el) {
    el.setAttribute("data-action-tip", el.getAttribute("title"));
    el.removeAttribute("title");
  });

  document.addEventListener("click", function (e) {
    var t = e.target && e.target.closest ? e.target.closest(".info-tip") : null;
    if (t) {
      showInfoTip(t);
    } else {
      hideInfoTip();
    }
  });
  document.addEventListener("scroll", function () { hideInfoTip(); }, true);
  window.addEventListener("resize", function () { hideInfoTip(); });
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") hideInfoTip(); });

  function splitTargets(value) {
    return String(value || "").split(",")
      .map(function (part) { return part.trim(); })
      .filter(Boolean);
  }

  function makeFieldHead(text, buttonText, onAdd, infoTip) {
    var head = document.createElement("div");
    head.className = "field-head";
    var label = document.createElement("span");
    label.className = "editor-label";
    var required = false;
    if (typeof text === "string" && text.charAt(text.length - 1) === "*") {
      required = true;
      text = text.slice(0, -1);
    }
    label.textContent = text;
    if (required) {
      var req = document.createElement("span");
      req.className = "req";
      req.textContent = "*";
      label.appendChild(req);
    }
    head.appendChild(label);
    if (infoTip) head.appendChild(infoTipButton(infoTip));
    if (buttonText) {
      var addBtn = document.createElement("button");
      addBtn.type = "button";
      addBtn.className = "btn ghost sm";
      addBtn.textContent = buttonText;
      addBtn.addEventListener("click", onAdd);
      head.appendChild(addBtn);
    }
    return head;
  }

  function addKvRow(container, key, value) {
    var row = document.createElement("div");
    row.className = "kv-row";

    var keyInput = document.createElement("input");
    keyInput.className = "input mono";
    keyInput.placeholder = "Key";
    keyInput.value = key || "";

    var valueInput = document.createElement("input");
    valueInput.className = "input mono";
    valueInput.placeholder = "Value";
    valueInput.value = value === undefined || value === null ? "" : String(value);

    row.appendChild(keyInput);
    row.appendChild(valueInput);
    row.appendChild(makeMiniButton("\u00d7", "Remove", function () { row.remove(); }));
    container.appendChild(row);
  }

  function collectKv(container) {
    var collected = {};
    Array.prototype.forEach.call(container.querySelectorAll(".kv-row"), function (row) {
      var inputs = row.querySelectorAll("input");
      var key = inputs[0].value.trim();
      if (!key) return;
      collected[key] = inputs[1].value.trim();
    });
    return collected;
  }

  function mergePreserving(original, rebuilt, managedKeys) {
    var merged = JSON.parse(JSON.stringify(original || {}));
    managedKeys.forEach(function (key) { delete merged[key]; });
    Object.keys(rebuilt).forEach(function (key) { merged[key] = rebuilt[key]; });
    return merged;
  }

  function addTargetRow(container, value) {
    var row = document.createElement("div");
    row.className = "target-row";

    var input = document.createElement("input");
    input.className = "input mono";
    input.placeholder = "e.g., example.com:9100 or https://example.com";
    input.value = value || "";

    row.appendChild(input);
    row.appendChild(makeMiniButton("\u00d7", "Remove target", function () { row.remove(); }));
    container.appendChild(row);
  }

  function buildSdGroupCard(entry) {
    entry = entry || {};
    var card = document.createElement("div");
    card.className = "editor-card";
    card._original = entry;

    var bar = document.createElement("div");
    bar.className = "editor-bar";
    var title = document.createElement("span");
    title.className = "editor-title";
    title.textContent = "Target Group";
    bar.appendChild(title);
    bar.appendChild(makeMiniButton("\u00d7", "Remove target group", function () { card.remove(); }));
    card.appendChild(bar);

    var targetsWrap = document.createElement("div");
    targetsWrap.className = "sd-targets";
    card.appendChild(makeFieldHead("Targets*", "+ Add target", function () {
      addTargetRow(targetsWrap);
    }, TARGET_INFO_TIP));
    card.appendChild(targetsWrap);
    (entry.targets && entry.targets.length ? entry.targets : [""]).forEach(function (target) {
      addTargetRow(targetsWrap, target);
    });

    var labelsWrap = document.createElement("div");
    labelsWrap.className = "sd-labels";
    card.appendChild(makeFieldHead("Labels", "+ Add label", function () {
      addKvRow(labelsWrap);
    }));
    card.appendChild(labelsWrap);
    Object.keys(entry.labels || {}).forEach(function (key) {
      addKvRow(labelsWrap, key, entry.labels[key]);
    });

    card._collect = function () {
      var targets = [];
      Array.prototype.forEach.call(targetsWrap.querySelectorAll(".target-row input"), function (input) {
        var value = input.value.trim();
        if (value) targets.push(value);
      });
      return mergePreserving(card._original,
        { targets: targets, labels: collectKv(labelsWrap) },
        ["targets", "labels"]);
    };

    return card;
  }

  function collectSdEntries() {
    var collected = [];
    Array.prototype.forEach.call($("sd-groups").querySelectorAll(":scope > .editor-card"), function (card) {
      var entry = card._collect();
      var hasTargets = entry.targets.length > 0;
      var hasLabels = Object.keys(entry.labels).length > 0;
      if (!hasTargets && !hasLabels) return;
      collected.push(entry);
    });
    return collected;
  }

  function ruleFieldValue(ruleData, snakeKey, camelKey) {
    if (!ruleData) return "";
    if (ruleData[snakeKey] !== undefined && ruleData[snakeKey] !== null) {
      return String(ruleData[snakeKey]);
    }
    if (ruleData[camelKey] !== undefined && ruleData[camelKey] !== null) {
      return String(ruleData[camelKey]);
    }
    return "";
  }

  function isDurationValue(value) {
    return /^\d+(ms|s|m|h|d|w|y)$/.test(value);
  }

  function buildRuleCard(ruleData) {
    ruleData = ruleData || {};
    var sub = document.createElement("div");
    sub.className = "editor-subcard rule-subcard";
    sub._original = ruleData;

    var bar = document.createElement("div");
    bar.className = "editor-bar";
    var title = document.createElement("span");
    title.className = "mini-label rule-title";
    title.textContent = ruleFieldValue(ruleData, "alert", "alert")
      || ruleFieldValue(ruleData, "record", "record")
      || "Unnamed rule";
    bar.appendChild(title);
    bar.appendChild(makeMiniButton("\u00d7", "Remove rule", function () { sub.remove(); }));
    sub.appendChild(bar);

    function addTextField(labelText, placeholder, value) {
      var field = document.createElement("div");
      field.className = "editor-field";
      var label = document.createElement("span");
      label.className = "mini-label";
      var required = false;
      if (typeof labelText === "string" && labelText.charAt(labelText.length - 1) === "*") {
        required = true;
        labelText = labelText.slice(0, -1);
      }
      label.textContent = labelText;
      if (required) {
        var req = document.createElement("span");
        req.className = "req";
        req.textContent = "*";
        label.appendChild(req);
      }
      var input = document.createElement("input");
      input.className = "input mono";
      input.placeholder = placeholder;
      input.value = value || "";
      field.appendChild(label);
      field.appendChild(input);
      sub.appendChild(field);
      return input;
    }

    var alertInput = addTextField("Alert name", "HighErrorRate", ruleFieldValue(ruleData, "alert", "alert"));
    alertInput.addEventListener("input", function () {
      title.textContent = alertInput.value.trim() || ruleFieldValue(ruleData, "record", "record") || "Unnamed rule";
    });
    var recordInput = addTextField("Record name", "job:http_requests:rate5m", ruleFieldValue(ruleData, "record", "record"));
    recordInput.addEventListener("input", function () {
      if (recordInput.value.trim()) title.textContent = recordInput.value.trim();
    });
    var exprInput = addTextField("Expression*", "up{job='myjob'} > 0.5", ruleFieldValue(ruleData, "expr", "expr"));
    var forInput = addTextField("For", "e.g., 5m", ruleFieldValue(ruleData, "for", "for"));
    var keepFiringForInput = addTextField(
      "Keep firing for",
      "e.g., 5m",
      ruleFieldValue(ruleData, "keep_firing_for", "keepFiringFor"));

    var labelsWrap = document.createElement("div");
    labelsWrap.className = "rule-labels";
    sub.appendChild(makeFieldHead("Labels", "+ Add label", function () {
      addKvRow(labelsWrap);
    }));
    sub.appendChild(labelsWrap);
    Object.keys(ruleData.labels || {}).forEach(function (key) {
      addKvRow(labelsWrap, key, ruleData.labels[key]);
    });

    var annotationsWrap = document.createElement("div");
    annotationsWrap.className = "rule-annotations";
    sub.appendChild(makeFieldHead("Annotations", "+ Add annotation", function () {
      addKvRow(annotationsWrap);
    }));
    sub.appendChild(annotationsWrap);
    Object.keys(ruleData.annotations || {}).forEach(function (key) {
      addKvRow(annotationsWrap, key, ruleData.annotations[key]);
    });

    sub._collect = function () {
      var rebuilt = {
        expr: exprInput.value.trim(),
      };
      var alertName = alertInput.value.trim();
      var recordName = recordInput.value.trim();
      if (alertName) rebuilt.alert = alertName;
      if (recordName) rebuilt.record = recordName;
      if (forInput.value.trim()) rebuilt["for"] = forInput.value.trim();
      if (keepFiringForInput.value.trim()) rebuilt.keep_firing_for = keepFiringForInput.value.trim();
      rebuilt.labels = collectKv(labelsWrap);
      rebuilt.annotations = collectKv(annotationsWrap);

      var merged = mergePreserving(sub._original, rebuilt,
        ["alert", "record", "expr", "for", "keep_firing_for", "labels", "annotations"]);
      if (!Object.keys(merged.labels || {}).length) delete merged.labels;
      if (!Object.keys(merged.annotations || {}).length) delete merged.annotations;
      return merged;
    };

    return sub;
  }

  function buildRuleGroupCard(groupData) {
    groupData = groupData || {};
    var card = document.createElement("div");
    card.className = "editor-card";
    card._original = groupData;

    var bar = document.createElement("div");
    bar.className = "editor-bar";
    var title = document.createElement("span");
    title.className = "editor-title";
    title.textContent = "Group";
    bar.appendChild(title);
    bar.appendChild(makeMiniButton("\u00d7", "Remove group", function () { card.remove(); }));
    card.appendChild(bar);

    var nameField = document.createElement("div");
    nameField.className = "editor-field";
    var nameLabel = document.createElement("span");
    nameLabel.className = "mini-label";
    nameLabel.textContent = "Group name";
    var nameReq = document.createElement("span");
    nameReq.className = "req";
    nameReq.textContent = "*";
    nameLabel.appendChild(nameReq);
    var nameInput = document.createElement("input");
    nameInput.className = "input mono group-name";
    nameInput.placeholder = "observecRuleGroup";
    nameInput.value = groupData.name || "";
    nameField.appendChild(nameLabel);
    nameField.appendChild(nameInput);
    card.appendChild(nameField);

    var groupLabelsWrap = document.createElement("div");
    groupLabelsWrap.className = "group-labels";
    card.appendChild(makeFieldHead("Labels", "+ Add label", function () {
      addKvRow(groupLabelsWrap);
    }));
    card.appendChild(groupLabelsWrap);
    Object.keys(groupData.labels || {}).forEach(function (key) {
      addKvRow(groupLabelsWrap, key, groupData.labels[key]);
    });

    var rulesWrap = document.createElement("div");
    rulesWrap.className = "rules-wrap";
    card.appendChild(makeFieldHead("Rules", "+ Add rule", function () {
      rulesWrap.appendChild(buildRuleCard(null));
    }));
    card.appendChild(rulesWrap);
    (groupData.rules && groupData.rules.length ? groupData.rules : [null]).forEach(function (rule) {
      rulesWrap.appendChild(buildRuleCard(rule));
    });

    card._collect = function () {
      var rules = [];
      Array.prototype.forEach.call(rulesWrap.querySelectorAll(":scope > .rule-subcard"), function (sub) {
        var rule = sub._collect();
        if (!rule.alert && !rule.record && !rule.expr) return;
        rules.push(rule);
      });

      var rebuilt = { name: nameInput.value.trim(), rules: rules };
      rebuilt.labels = collectKv(groupLabelsWrap);
      var merged = mergePreserving(card._original, rebuilt, ["name", "labels", "rules"]);
      if (!Object.keys(merged.labels).length) delete merged.labels;
      return merged;
    };

    return card;
  }

  function collectRuleGroups() {
    var collected = [];
    Array.prototype.forEach.call($("rule-groups").querySelectorAll(":scope > .editor-card"), function (card) {
      var group = card._collect();
      var hasRules = group.rules.length > 0;
      var hasLabels = group.labels && Object.keys(group.labels).length > 0;
      if (!group.name && !hasRules && !hasLabels) return;
      collected.push(group);
    });
    return collected;
  }

  function openFileDialog(kind, name) {
    fileEditor.kind = kind;
    fileEditor.name = name || null;
    var isEdit = Boolean(name);
    var store = kind === "rule" ? state.ruleFiles : state.serviceFiles;

    $("file-dialog-title").textContent =
      (isEdit ? "Edit " : "New ") +
      (kind === "rule" ? "rule file" : "service discovery file");
    $("file-dialog-subtitle").textContent = "";

    $("f-file-name").value = name || "";
    $("f-file-name").disabled = isEdit;

    var body = $("file-editor-body");
    body.innerHTML = "";

    var scroll = document.createElement("div");
    scroll.className = "editor-scroll";

    var bar = document.createElement("div");
    bar.className = "builder-bar";
    var heading = document.createElement("span");
    heading.className = "builder-heading";
    heading.textContent = kind === "rule" ? "Groups" : "Target Groups";

    var groupsWrap = document.createElement("div");
    groupsWrap.id = kind === "rule" ? "rule-groups" : "sd-groups";

    var addBtn = document.createElement("button");
    addBtn.type = "button";
    addBtn.className = "btn ghost sm";
    addBtn.textContent = kind === "rule" ? "+ Add group" : "+ Add target group";
    addBtn.addEventListener("click", function () {
      groupsWrap.appendChild(kind === "rule" ? buildRuleGroupCard(null) : buildSdGroupCard(null));
    });
    bar.appendChild(heading);
    bar.appendChild(addBtn);
    scroll.appendChild(bar);

    var existing = isEdit && store[name] !== undefined ? store[name] : null;
    if (kind === "rule") {
      var groups = existing && Array.isArray(existing.groups) ? existing.groups : [null];
      groups.forEach(function (group) { groupsWrap.appendChild(buildRuleGroupCard(group)); });
    } else {
      var entries = Array.isArray(existing) ? existing : [null];
      entries.forEach(function (entry) { groupsWrap.appendChild(buildSdGroupCard(entry)); });
    }

    scroll.appendChild(groupsWrap);
    body.appendChild(scroll);

    hideFileError();
    $("f-file-delete").classList.toggle("hidden", !isEdit);
    $("f-file-save").textContent = isEdit ? "Save changes" : "Create file";

    $("dialog-overlay").classList.remove("hidden");
    $("file-dialog").classList.remove("hidden");
    $("f-file-name").focus();
  }

  $("file-form").addEventListener("submit", function (event) {
    event.preventDefault();
    hideFileError();

    var name = normalizeYamlFileName($("f-file-name").value);
    if (!name || name === ".yml") { showFileError("File name is required."); return; }
    if (!fileEditor.name && (
      (fileEditor.kind === "rule" && state.ruleFiles[name] !== undefined) ||
      (fileEditor.kind === "service" && state.serviceFiles[name] !== undefined))) {
      showFileError("A file named \"" + name + "\" already exists.");
      return;
    }

    var contents;
    if (fileEditor.kind === "service") {
      contents = collectSdEntries();
      var anyTarget = contents.some(function (entry) { return entry.targets.length > 0; });
      if (!anyTarget) {
        showFileError("Add at least one target.");
        return;
      }
    } else {
      contents = { groups: collectRuleGroups() };
      if (!contents.groups.length) {
        showFileError("Add at least one rule group.");
        return;
      }
      var durationError = validateRuleDurations(contents.groups);
      if (durationError) {
        showFileError(durationError);
        return;
      }
    }

    var saveBtn = $("f-file-save");
    saveBtn.disabled = true;
    saveBtn.textContent = "Saving...";

    var kind = fileEditor.kind;
    var baseEndpoint = kind === "rule" ? "/prometheus/rules" : "/prometheus/service-files";
    api(fileEditor.name ? baseEndpoint.replace("/rules", "/rule").replace("/service-files", "/service-file") : baseEndpoint, {
      method: fileEditor.name ? "PUT" : "POST",
      body: JSON.stringify(defineObject(name, contents)),
    })
      .then(function (message) {
        toast(extractMessage(message));
        closeDialog();
        return kind === "rule" ? loadRules() : loadServices();
      })
      .catch(function (err) {
        showFileError(err.message);
        saveBtn.disabled = false;
        saveBtn.textContent = fileEditor.name ? "Save changes" : "Create file";
      });
  });

  $("f-file-delete").addEventListener("click", function () {
    if (!fileEditor.name) return;
    uiConfirm("Delete \"" + fileEditor.name + "\"?").then(function (ok) {
      if (ok) deleteManagedFiles(fileEditor.kind, [fileEditor.name], closeDialog);
    });
  });

  $("f-file-cancel").addEventListener("click", closeDialog);

  function validateRuleDurations(groups) {
    for (var groupIndex = 0; groupIndex < groups.length; groupIndex++) {
      var group = groups[groupIndex];
      var rules = Array.isArray(group.rules) ? group.rules : [];
      for (var ruleIndex = 0; ruleIndex < rules.length; ruleIndex++) {
        var rule = rules[ruleIndex];
        if (rule.for && !isDurationValue(rule.for)) {
          return "Rule \"" + (rule.alert || rule.record || ("#" + (ruleIndex + 1))) +
            "\" has an invalid For duration. Use values like 30s, 5m, or 1h.";
        }
        if (rule.keep_firing_for && !isDurationValue(rule.keep_firing_for)) {
          return "Rule \"" + (rule.alert || rule.record || ("#" + (ruleIndex + 1))) +
            "\" has an invalid Keep firing for duration. Use values like 30s, 5m, or 1h.";
        }
      }
    }
    return "";
  }

  function receiverByName(name) {
    return state.receivers.find(function (receiver) {
      return receiver.name === name;
    });
  }

  function receiverHasWebhook(receiver) {
    return receiver.webhook_configs && receiver.webhook_configs.length;
  }

  function receiverHasEmail(receiver) {
    return receiver.email_configs && receiver.email_configs.length;
  }

  function receiverHasSlack(receiver) {
    return receiver.slack_configs && receiver.slack_configs.length;
  }

  function detectReceiverType(receiver) {
    if (receiverHasSlack(receiver)) return "slack";
    if (receiverHasEmail(receiver)) return "email";
    return "webhook";
  }

  function receiverTypeLabel(receiver) {
    var types = [];
    if (receiverHasWebhook(receiver)) types.push("Webhook");
    if (receiverHasEmail(receiver)) types.push("Email");
    if (receiverHasSlack(receiver)) types.push("Slack");
    return types.length ? types.join(" + ") : "-";
  }

  function receiverDestinationSummary(receiver) {
    if (receiverHasWebhook(receiver)) {
      return receiver.webhook_configs[0].url || "-";
    }
    if (receiverHasSlack(receiver)) {
      var slack = receiver.slack_configs[0];
      if (slack.channel) return slack.channel;
      return slack.api_url || "-";
    }
    if (receiverHasEmail(receiver)) {
      var email = receiver.email_configs[0];
      return (email.to || "-") + " via " + (email.smarthost || "-");
    }
    return "-";
  }

  function setReceiverFieldGroup(className, visible) {
    Array.prototype.forEach.call(document.querySelectorAll("." + className), function (el) {
      el.classList.toggle("hidden", !visible);
    });
  }

  function updateReceiverTypeUi() {
    var type = $("f-receiver-type").value;
    setReceiverFieldGroup("receiver-fields-webhook", type === "webhook");
    setReceiverFieldGroup("receiver-fields-email", type === "email");
    setReceiverFieldGroup("receiver-fields-slack", type === "slack");
  }

  function hideReceiverError() {
    $("receiver-error").classList.add("hidden");
    $("receiver-error").textContent = "";
  }

  function showReceiverError(message) {
    $("receiver-error").textContent = message;
    $("receiver-error").classList.remove("hidden");
  }

  function loadReceivers() {
    return api("/alertmanager/receivers")
      .then(function (text) {
        var payload = JSON.parse(text);
        state.receivers = payload.receivers || [];
        state.routeReceiver = payload.route_receiver || AM_DEFAULT_RECEIVER;
        renderReceiversTable();
      })
      .catch(function (err) { toast(err.message, true); });
  }

  function renderReceiversTable() {
    closeRowMenu();
    var tbody = $("receiver-tbody");
    if (!tbody) return;
    tbody.innerHTML = "";

    var receivers = state.receivers.filter(function (receiver) {
      var name = receiver.name || "";
      return !state.receiverQuery || name.toLowerCase().indexOf(state.receiverQuery) !== -1;
    });

    receivers.forEach(function (receiver) {
      var name = receiver.name || "(unnamed)";
      var tr = document.createElement("tr");

      var tdName = document.createElement("td");
      tdName.className = "mono";
      tdName.textContent = name;
      tr.appendChild(tdName);

      var tdType = document.createElement("td");
      tdType.textContent = receiverTypeLabel(receiver);
      tr.appendChild(tdType);

      var tdRoute = document.createElement("td");
      if (name === state.routeReceiver) {
        var badge = document.createElement("span");
        badge.className = "config-filename";
        badge.textContent = "default route";
        tdRoute.appendChild(badge);
      } else {
        tdRoute.textContent = "-";
      }
      tr.appendChild(tdRoute);

      var actions = [{
        label: "Edit",
        onClick: function () { openReceiverDialog(name); },
      }];
      if (name !== AM_DEFAULT_RECEIVER && state.receivers.length > 1) {
        actions.push({
          label: "Delete",
          danger: true,
          onClick: function () {
            uiConfirm("Delete receiver \"" + name + "\"?").then(function (ok) {
              if (ok) deleteReceivers([name]);
            });
          },
        });
      }
      tr.appendChild(buildKebab(actions));
      tbody.appendChild(tr);
    });

    if (!receivers.length) {
      appendEmptyRow(tbody, 4,
        state.receiverQuery
          ? "No receivers match \"" + state.receiverQuery + "\""
          : "No receivers yet.");
    }

    $("receiver-count").textContent =
      receivers.length + " of " + state.receivers.length + " receiver(s)";
  }

  function deleteReceivers(names, done) {
    api("/alertmanager/receivers", {
      method: "DELETE",
      body: JSON.stringify(names),
    })
      .then(function (message) {
        toast(extractMessage(message));
        return loadReceivers();
      })
      .then(function () { if (done) done(); })
      .catch(function (err) { toast(err.message, true); });
  }

  function openReceiverDialog(receiverName) {
    editingReceiverName = receiverName || null;
    var isEdit = Boolean(receiverName);
    var receiver = isEdit ? (receiverByName(receiverName) || {}) : {};
    var isDefault = receiverName === AM_DEFAULT_RECEIVER;

    $("receiver-dialog-title").textContent = isEdit ? "Edit receiver" : "Add receiver";
    $("receiver-dialog-subtitle").textContent = isDefault
      ? "Built-in observec webhook receiver. The name cannot be changed."
      : "";

    $("f-receiver-name").value = receiverName || "";
    $("f-receiver-name").disabled = isEdit;

    $("f-receiver-type").value = isEdit ? detectReceiverType(receiver) : "webhook";
    $("f-receiver-type").disabled = isDefault;

    var webhook = receiverHasWebhook(receiver) ? receiver.webhook_configs[0] : {};
    $("f-receiver-webhook-url").value = webhook.url || "";
    $("f-receiver-send-resolved").checked = webhook.send_resolved !== false;

    var email = receiverHasEmail(receiver) ? receiver.email_configs[0] : {};
    $("f-receiver-email-to").value = email.to || "";
    $("f-receiver-email-from").value = email.from || "";
    $("f-receiver-email-smarthost").value = email.smarthost || "";
    $("f-receiver-email-tls").checked = email.require_tls === true;

    var slack = receiverHasSlack(receiver) ? receiver.slack_configs[0] : {};
    $("f-receiver-slack-url").value = slack.api_url || "";
    $("f-receiver-slack-channel").value = slack.channel || "";
    $("f-receiver-slack-title").value = slack.title || "";
    $("f-receiver-slack-text").value = slack.text || "";
    $("f-receiver-slack-send-resolved").checked = slack.send_resolved !== false;

    updateReceiverTypeUi();
    hideReceiverError();
    $("f-receiver-delete").classList.toggle("hidden", !isEdit || isDefault || state.receivers.length <= 1);
    $("f-receiver-save").textContent = isEdit ? "Save changes" : "Create receiver";

    $("dialog-overlay").classList.remove("hidden");
    $("receiver-dialog").classList.remove("hidden");
    if (!isEdit) $("f-receiver-name").focus();
    else if ($("f-receiver-type").value === "email") $("f-receiver-email-to").focus();
    else if ($("f-receiver-type").value === "slack") $("f-receiver-slack-url").focus();
    else $("f-receiver-webhook-url").focus();
  }

  function buildReceiverConfig() {
    var type = $("f-receiver-type").value;
    if (type === "email") {
      return {
        email_configs: [{
          to: $("f-receiver-email-to").value.trim(),
          from: $("f-receiver-email-from").value.trim(),
          smarthost: $("f-receiver-email-smarthost").value.trim(),
          require_tls: $("f-receiver-email-tls").checked,
        }],
      };
    }
    if (type === "slack") {
      var slackConfig = {
        api_url: $("f-receiver-slack-url").value.trim(),
        send_resolved: $("f-receiver-slack-send-resolved").checked,
      };
      var channel = $("f-receiver-slack-channel").value.trim();
      if (channel) slackConfig.channel = channel;
      var title = $("f-receiver-slack-title").value.trim();
      if (title) slackConfig.title = title;
      var text = $("f-receiver-slack-text").value.trim();
      if (text) slackConfig.text = text;
      return { slack_configs: [slackConfig] };
    }
    return {
      webhook_configs: [{
        url: $("f-receiver-webhook-url").value.trim(),
        send_resolved: $("f-receiver-send-resolved").checked,
      }],
    };
  }

  $("add-receiver-btn").addEventListener("click", function () { openReceiverDialog(null); });
  $("f-receiver-type").addEventListener("change", updateReceiverTypeUi);
  $("f-receiver-cancel").addEventListener("click", closeDialog);

  $("receiver-search").addEventListener("input", function () {
    state.receiverQuery = this.value.trim().toLowerCase();
    renderReceiversTable();
  });

  $("receiver-form").addEventListener("submit", function (event) {
    event.preventDefault();
    hideReceiverError();

    var name = editingReceiverName || $("f-receiver-name").value.trim();
    if (!name) {
      showReceiverError("Receiver name is required.");
      return;
    }

    var config = buildReceiverConfig();
    if (config.webhook_configs && !config.webhook_configs[0].url) {
      showReceiverError("Webhook URL is required.");
      return;
    }
    if (config.email_configs) {
      var email = config.email_configs[0];
      if (!email.to || !email.from || !email.smarthost) {
        showReceiverError("Email to, from, and SMTP server are required.");
        return;
      }
    }
    if (config.slack_configs && !config.slack_configs[0].api_url) {
      showReceiverError("Slack API URL is required.");
      return;
    }

    var saveBtn = $("f-receiver-save");
    saveBtn.disabled = true;
    saveBtn.textContent = "Saving...";

    var payload = defineObject(name, config);
    var request = editingReceiverName
      ? api("/alertmanager/receiver", { method: "PUT", body: JSON.stringify(payload) })
      : api("/alertmanager/receivers", { method: "POST", body: JSON.stringify(payload) });

    request
      .then(function (message) {
        toast(extractMessage(message));
        closeDialog();
        return loadReceivers();
      })
      .catch(function (err) { showReceiverError(err.message); })
      .finally(function () {
        saveBtn.disabled = false;
        saveBtn.textContent = editingReceiverName ? "Save changes" : "Create receiver";
      });
  });

  $("f-receiver-delete").addEventListener("click", function () {
    if (!editingReceiverName) return;
    uiConfirm("Delete receiver \"" + editingReceiverName + "\"?").then(function (ok) {
      if (ok) deleteReceivers([editingReceiverName], closeDialog);
    });
  });

  function encodeRoutePath(pathParts) {
    return pathParts.length ? pathParts.join("-") : "";
  }

  function parseRoutePath(pathValue) {
    if (!pathValue) return [];
    return pathValue.split("-").map(function (part) { return Number(part); });
  }

  function getRouteByPath(root, pathValue) {
    if (!root) return null;
    if (!pathValue) return root;
    var node = root;
    parseRoutePath(pathValue).forEach(function (index) {
      node = node && node.routes ? node.routes[index] : null;
    });
    return node;
  }

  function getRouteParent(root, pathValue) {
    if (!pathValue) return null;
    var parts = parseRoutePath(pathValue);
    var parentPath = encodeRoutePath(parts.slice(0, -1));
    return getRouteByPath(root, parentPath);
  }

  function routeSummary(route) {
    var parts = [];
    if (route.matchers && route.matchers.length) {
      parts = parts.concat(route.matchers);
    }
    if (route.match) {
      Object.keys(route.match).forEach(function (key) {
        parts.push(key + " = " + route.match[key]);
      });
    }
    if (route.match_re) {
      Object.keys(route.match_re).forEach(function (key) {
        parts.push(key + " =~ " + route.match_re[key]);
      });
    }
    return parts.length ? parts.join(" · ") : "matches all alerts";
  }

  function routeMetaChips(route) {
    var chips = [];
    if (route.group_by && route.group_by.length) chips.push("group: " + route.group_by.join(", "));
    if (route.group_wait) chips.push("wait " + route.group_wait);
    if (route.continue) chips.push("continue");
    return chips;
  }

  function populateRouteReceiverSelect(selected) {
    var select = $("f-route-receiver");
    select.innerHTML = "";
    state.routeReceiverNames.forEach(function (name) {
      var option = document.createElement("option");
      option.value = name;
      option.textContent = name;
      select.appendChild(option);
    });
    if (selected) select.value = selected;
  }

  function addMatcherRow(container, value) {
    var row = document.createElement("div");
    row.className = "route-matcher-row";
    var input = document.createElement("input");
    input.className = "input mono";
    input.placeholder = "severity = critical";
    input.value = value || "";
    row.appendChild(input);
    row.appendChild(makeMiniButton("\u00d7", "Remove matcher", function () { row.remove(); }));
    container.appendChild(row);
  }

  function addMatchRow(container, key, value, placeholder) {
    var row = document.createElement("div");
    row.className = "route-match-row";
    var keyInput = document.createElement("input");
    keyInput.className = "input mono";
    keyInput.placeholder = "Label";
    keyInput.value = key || "";
    var valueInput = document.createElement("input");
    valueInput.className = "input mono";
    valueInput.placeholder = placeholder || "Value";
    valueInput.value = value || "";
    row.appendChild(keyInput);
    row.appendChild(valueInput);
    row.appendChild(makeMiniButton("\u00d7", "Remove match", function () { row.remove(); }));
    container.appendChild(row);
  }

  function collectMatcherRows(container) {
    var matchers = [];
    Array.prototype.forEach.call(container.querySelectorAll(".route-matcher-row input"), function (input) {
      var value = input.value.trim();
      if (value) matchers.push(value);
    });
    return matchers;
  }

  function collectMatchRows(container) {
    var match = {};
    Array.prototype.forEach.call(container.querySelectorAll(".route-match-row"), function (row) {
      var inputs = row.querySelectorAll("input");
      var key = inputs[0].value.trim();
      var value = inputs[1].value.trim();
      if (key) match[key] = value;
    });
    return match;
  }

  function hideRouteError() {
    $("route-error").classList.add("hidden");
    $("route-error").textContent = "";
  }

  function showRouteError(message) {
    $("route-error").textContent = message;
    $("route-error").classList.remove("hidden");
  }

  function isRouteCollapsed(pathValue) {
    return state.routeCollapsed[pathValue] === true;
  }

  function toggleRouteCollapsed(pathValue) {
    state.routeCollapsed[pathValue] = !isRouteCollapsed(pathValue);
    renderRouteTree();
  }

  function buildRouteKebab(pathValue, isRoot) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "row-action kebab route-kebab";
    button.title = "Route actions";
    button.innerHTML = '<svg class="icon" viewBox="0 0 24 24"><circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/></svg>';
    button.addEventListener("click", function (event) {
      event.stopPropagation();
      var items = [{
        label: "Edit",
        onClick: function () {
          applyRouteEditor();
          selectRoute(pathValue);
        },
      }, {
        label: "Add child route",
        onClick: function () {
          addChildRoute(pathValue);
        },
      }];
      if (!isRoot) {
        items.push({
          label: "Delete",
          danger: true,
          onClick: function () {
            state.selectedRoutePath = pathValue;
            deleteSelectedRoute();
          },
        });
      }
      openRowMenuAt(button, items);
    });
    return button;
  }

  function routeRowIconSvg(hasChildren, collapsed) {
    if (hasChildren) {
      if (collapsed) {
        return '<svg class="icon" viewBox="0 0 24 24"><path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/></svg>';
      }
      return '<svg class="icon" viewBox="0 0 24 24"><path d="m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.93a2 2 0 0 1 1.66.9l.82 1.2A2 2 0 0 0 13.07 8H20a2 2 0 0 1 2 2v1"/></svg>';
    }
    return '<svg class="icon" viewBox="0 0 24 24"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/></svg>';
  }

  function renderRouteTreeNode(route, pathValue, depth) {
    var hasChildren = route.routes && route.routes.length > 0;
    var collapsed = hasChildren && isRouteCollapsed(pathValue);
    var isRoot = !pathValue;

    var item = document.createElement("div");
    item.className = "route-tree-item";

    var row = document.createElement("div");
    row.className = "route-tree-row";
    if (pathValue === state.selectedRoutePath) row.classList.add("active");

    var routeCol = document.createElement("div");
    routeCol.className = "route-tree-route-col";
    routeCol.style.paddingLeft = (depth * 16) + "px";

    var toggle = document.createElement("button");
    toggle.type = "button";
    toggle.className = "route-toggle" + (hasChildren ? "" : " placeholder");
    toggle.setAttribute("aria-label", hasChildren ? (collapsed ? "Expand route" : "Collapse route") : "");
    if (hasChildren) {
      toggle.innerHTML = collapsed
        ? '<svg class="icon" viewBox="0 0 24 24"><path d="m9 18 6-6-6-6"/></svg>'
        : '<svg class="icon" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6"/></svg>';
      toggle.addEventListener("click", function (event) {
        event.stopPropagation();
        toggleRouteCollapsed(pathValue);
      });
    }

    var main = document.createElement("div");
    main.className = "route-row-main";

    var icon = document.createElement("span");
    icon.className = "route-row-icon";
    icon.innerHTML = routeRowIconSvg(hasChildren, collapsed);

    var title = document.createElement("span");
    title.className = "route-row-title module-name";
    title.textContent = (isRoot ? "root / " : "") + (route.receiver || "(no receiver)");

    main.appendChild(icon);
    main.appendChild(title);
    routeCol.appendChild(toggle);
    routeCol.appendChild(main);

    var matchCol = document.createElement("div");
    matchCol.className = "route-tree-match-col";
    matchCol.textContent = routeSummary(route);

    var actionsCol = document.createElement("div");
    actionsCol.className = "route-tree-actions-col";
    actionsCol.appendChild(buildRouteKebab(pathValue, isRoot));

    row.appendChild(routeCol);
    row.appendChild(matchCol);
    row.appendChild(actionsCol);
    row.addEventListener("click", function (event) {
      if (event.target.closest(".route-toggle, .route-kebab, #row-menu")) return;
      applyRouteEditor();
      selectRoute(pathValue);
    });
    item.appendChild(row);

    if (hasChildren && !collapsed) {
      var children = document.createElement("div");
      children.className = "route-children";
      route.routes.forEach(function (childRoute, index) {
        var childPath = encodeRoutePath(parseRoutePath(pathValue).concat([index]));
        children.appendChild(renderRouteTreeNode(childRoute, childPath, depth + 1));
      });
      item.appendChild(children);
    }

    return item;
  }

  function renderRouteTree() {
    var tree = $("route-tree");
    if (!tree) return;
    tree.innerHTML = "";
    if (!state.routeConfig) {
      tree.innerHTML = "<div class=\"route-tree-empty text-muted\">No route configuration loaded.</div>";
      return;
    }
    tree.appendChild(renderRouteTreeNode(state.routeConfig, "", 0));
  }

  function fillRouteEditor(route, pathValue, isNew) {
    populateRouteReceiverSelect(route.receiver || state.routeReceiver || AM_DEFAULT_RECEIVER);
    $("route-dialog-title").textContent = isNew
      ? "Add child route"
      : (pathValue ? "Edit child route" : "Edit root route");
    if (isNew) {
      var parentPath = state.pendingNewRouteParentPath;
      $("route-dialog-subtitle").textContent = parentPath
        ? "Child of route " + parentPath
        : "Child of root route";
    } else {
      $("route-dialog-subtitle").textContent = pathValue
        ? "Route path " + pathValue
        : "Top-level Alertmanager routing tree";
    }

    $("f-route-group-by").value = (route.group_by || []).join(", ");
    $("f-route-group-wait").value = route.group_wait || "";
    $("f-route-group-interval").value = route.group_interval || "";
    $("f-route-repeat-interval").value = route.repeat_interval || "";
    $("f-route-continue").checked = route.continue === true;

    var matchersList = $("route-matchers-list");
    matchersList.innerHTML = "";
    (route.matchers || []).forEach(function (matcher) { addMatcherRow(matchersList, matcher); });

    var matchRows = $("route-match-rows");
    matchRows.innerHTML = "";
    Object.keys(route.match || {}).forEach(function (key) {
      addMatchRow(matchRows, key, route.match[key], "Exact value");
    });

    var matchReRows = $("route-match-re-rows");
    matchReRows.innerHTML = "";
    Object.keys(route.match_re || {}).forEach(function (key) {
      addMatchRow(matchReRows, key, route.match_re[key], "Regex");
    });

    $("route-delete-btn").classList.toggle("hidden", !pathValue || isNew);
    $("route-apply-btn").textContent = isNew ? "Create route" : "Apply changes";
    hideRouteError();
  }

  function openRouteDialog(pathValue) {
    if (!state.routeConfig) return;
    var route = getRouteByPath(state.routeConfig, pathValue);
    if (!route) return;
    state.pendingNewRouteParentPath = null;
    state.selectedRoutePath = pathValue;
    renderRouteTree();
    fillRouteEditor(route, pathValue, false);
    $("dialog-overlay").classList.remove("hidden");
    $("route-dialog").classList.remove("hidden");
  }

  function openNewChildRouteDialog(parentPathValue) {
    if (!state.routeConfig) return;
    var parent = getRouteByPath(state.routeConfig, parentPathValue);
    if (!parent) return;
    state.pendingNewRouteParentPath = parentPathValue;
    state.selectedRoutePath = null;
    renderRouteTree();
    fillRouteEditor({
      receiver: state.routeReceiver || AM_DEFAULT_RECEIVER,
      matchers: [],
    }, null, true);
    $("dialog-overlay").classList.remove("hidden");
    $("route-dialog").classList.remove("hidden");
  }

  function selectRoute(pathValue) {
    openRouteDialog(pathValue);
  }

  function collectRouteFromEditor() {
    var existing = getRouteByPath(state.routeConfig, state.selectedRoutePath) || {};
    var route = {
      receiver: $("f-route-receiver").value.trim(),
    };

    var groupBy = $("f-route-group-by").value.split(",")
      .map(function (value) { return value.trim(); })
      .filter(Boolean);
    if (groupBy.length) route.group_by = groupBy;

    var groupWait = $("f-route-group-wait").value.trim();
    if (groupWait) route.group_wait = groupWait;

    var groupInterval = $("f-route-group-interval").value.trim();
    if (groupInterval) route.group_interval = groupInterval;

    var repeatInterval = $("f-route-repeat-interval").value.trim();
    if (repeatInterval) route.repeat_interval = repeatInterval;

    if ($("f-route-continue").checked) route.continue = true;

    var matchers = collectMatcherRows($("route-matchers-list"));
    if (matchers.length) route.matchers = matchers;

    var match = collectMatchRows($("route-match-rows"));
    if (Object.keys(match).length) route.match = match;

    var matchRe = collectMatchRows($("route-match-re-rows"));
    if (Object.keys(matchRe).length) route.match_re = matchRe;

    if (existing.routes && existing.routes.length) route.routes = existing.routes;
    return route;
  }

  function applyRouteEditor() {
    if (!state.routeConfig) return false;

    if (state.pendingNewRouteParentPath !== null) {
      var created = collectRouteFromEditor();
      if (!created.receiver) return false;
      var parent = getRouteByPath(state.routeConfig, state.pendingNewRouteParentPath);
      if (!parent) return false;
      if (!parent.routes) parent.routes = [];
      parent.routes.push(created);
      state.pendingNewRouteParentPath = null;
      return true;
    }

    if (state.selectedRoutePath === null) return false;
    var updated = collectRouteFromEditor();
    if (!updated.receiver) return false;

    if (!state.selectedRoutePath) {
      var children = state.routeConfig.routes;
      Object.keys(state.routeConfig).forEach(function (key) { delete state.routeConfig[key]; });
      Object.assign(state.routeConfig, updated);
      if (children) state.routeConfig.routes = children;
      return true;
    }

    var routeParent = getRouteParent(state.routeConfig, state.selectedRoutePath);
    var index = parseRoutePath(state.selectedRoutePath).pop();
    if (!routeParent || !routeParent.routes) return false;
    var childRoutes = updated.routes || (routeParent.routes[index] && routeParent.routes[index].routes) || [];
    updated.routes = childRoutes;
    routeParent.routes[index] = updated;
    return true;
  }

  function addChildRoute(pathValue) {
    if (!state.routeConfig) return;
    if (state.pendingNewRouteParentPath !== null || state.selectedRoutePath !== null) {
      applyRouteEditor();
    }
    openNewChildRouteDialog(pathValue);
  }

  function deleteSelectedRoute() {
    if (!state.selectedRoutePath) return;
    uiConfirm("Delete this route and all of its child routes?").then(function (ok) {
      if (!ok) return;
      var parent = getRouteParent(state.routeConfig, state.selectedRoutePath);
      var index = parseRoutePath(state.selectedRoutePath).pop();
      if (!parent || !parent.routes) return;
      parent.routes.splice(index, 1);
      state.selectedRoutePath = null;
      closeDialog();
      renderRouteTree();
    });
  }

  function loadRoutes() {
    return api("/alertmanager/routes")
      .then(function (text) {
        var payload = JSON.parse(text);
        state.routeConfig = payload.route || {};
        state.routeReceiverNames = payload.receiver_names || [];
        state.selectedRoutePath = null;
        state.routeCollapsed = {};
        state.pendingNewRouteParentPath = null;
        renderRouteTree();
      })
      .catch(function (err) { toast(err.message, true); });
  }

  function saveRoutes() {
    if (!state.routeConfig) return;
    applyRouteEditor();
    hideRouteError();
    var saveBtn = $("routes-save-btn");
    saveBtn.disabled = true;

    api("/alertmanager/routes", {
      method: "PUT",
      body: JSON.stringify({ route: state.routeConfig }),
    })
      .then(function (message) {
        toast(extractMessage(message));
        return loadRoutes();
      })
      .catch(function (err) { showRouteError(err.message); })
      .finally(function () {
        saveBtn.disabled = false;
      });
  }

  $("routes-save-btn").addEventListener("click", saveRoutes);
  $("route-apply-btn").addEventListener("click", function () {
    var isNew = state.pendingNewRouteParentPath !== null;
    if (!applyRouteEditor()) {
      showRouteError("Receiver is required.");
      return;
    }
    renderRouteTree();
    toast(isNew ? "Route created" : "Route changes applied");
    closeDialog();
  });
  $("route-cancel-btn").addEventListener("click", closeDialog);
  $("route-delete-btn").addEventListener("click", deleteSelectedRoute);
  $("route-add-matcher-btn").addEventListener("click", function () {
    addMatcherRow($("route-matchers-list"));
  });
  $("route-add-match-btn").addEventListener("click", function () {
    addMatchRow($("route-match-rows"), "", "", "Exact value");
  });
  $("route-add-match-re-btn").addEventListener("click", function () {
    addMatchRow($("route-match-re-rows"), "", "", "Regex");
  });

  function inhibitSideSummary(rule, side) {
    var parts = [];
    var matchers = rule[side + "_matchers"];
    if (matchers && matchers.length) parts = parts.concat(matchers);
    if (rule[side + "_match"]) {
      Object.keys(rule[side + "_match"]).forEach(function (key) {
        parts.push(key + " = " + rule[side + "_match"][key]);
      });
    }
    if (rule[side + "_match_re"]) {
      Object.keys(rule[side + "_match_re"]).forEach(function (key) {
        parts.push(key + " =~ " + rule[side + "_match_re"][key]);
      });
    }
    return parts.length ? parts.join(" · ") : "-";
  }

  function hideInhibitError() {
    $("inhibit-error").classList.add("hidden");
    $("inhibit-error").textContent = "";
  }

  function showInhibitError(message) {
    $("inhibit-error").textContent = message;
    $("inhibit-error").classList.remove("hidden");
  }

  function loadInhibitRules() {
    return api("/alertmanager/inhibit-rules")
      .then(function (text) {
        var payload = JSON.parse(text);
        state.inhibitRules = payload.inhibit_rules || [];
        renderInhibitRulesTable();
      })
      .catch(function (err) { toast(err.message, true); });
  }

  function renderInhibitRulesTable() {
    closeRowMenu();
    var tbody = $("inhibit-rule-tbody");
    if (!tbody) return;
    tbody.innerHTML = "";

    var rules = state.inhibitRules.map(function (rule, index) {
      return { rule: rule, index: index };
    }).filter(function (entry) {
      if (!state.inhibitRuleQuery) return true;
      var haystack = [
        inhibitSideSummary(entry.rule, "source"),
        inhibitSideSummary(entry.rule, "target"),
        (entry.rule.equal || []).join(", "),
      ].join(" ").toLowerCase();
      return haystack.indexOf(state.inhibitRuleQuery) !== -1;
    });

    rules.forEach(function (entry) {
      var rule = entry.rule;
      var index = entry.index;
      var tr = document.createElement("tr");

      var tdSource = document.createElement("td");
      var sourceSummary = inhibitSideSummary(rule, "source");
      tdSource.textContent = sourceSummary;
      tdSource.title = sourceSummary;
      tr.appendChild(tdSource);

      var tdTarget = document.createElement("td");
      var targetSummary = inhibitSideSummary(rule, "target");
      tdTarget.textContent = targetSummary;
      tdTarget.title = targetSummary;
      tr.appendChild(tdTarget);

      var tdEqual = document.createElement("td");
      tdEqual.className = "mono";
      tdEqual.textContent = (rule.equal && rule.equal.length) ? rule.equal.join(", ") : "-";
      tr.appendChild(tdEqual);

      tr.appendChild(buildKebab([
        {
          label: "Edit",
          onClick: function () { openInhibitRuleDialog(index); },
        },
        {
          label: "Delete",
          danger: true,
          onClick: function () {
            uiConfirm("Delete this inhibit rule?").then(function (ok) {
              if (ok) deleteInhibitRule(index);
            });
          },
        },
      ]));
      tbody.appendChild(tr);
    });

    if (!rules.length) {
      appendEmptyRow(tbody, 4,
        state.inhibitRuleQuery
          ? "No inhibit rules match \"" + state.inhibitRuleQuery + "\""
          : "No inhibit rules yet.");
    }

    $("inhibit-rule-count").textContent =
      rules.length + " of " + state.inhibitRules.length + " inhibit rule(s)";
  }

  function persistInhibitRules(rules, done) {
    return api("/alertmanager/inhibit-rules", {
      method: "PUT",
      body: JSON.stringify({ inhibit_rules: rules }),
    })
      .then(function (message) {
        toast(extractMessage(message));
        return loadInhibitRules();
      })
      .then(function () { if (done) done(); })
      .catch(function (err) {
        if (done) showInhibitError(err.message);
        else toast(err.message, true);
      });
  }

  function deleteInhibitRule(index) {
    var rules = state.inhibitRules.slice();
    rules.splice(index, 1);
    persistInhibitRules(rules);
  }

  function openInhibitRuleDialog(index) {
    editingInhibitRuleIndex = typeof index === "number" ? index : null;
    var isEdit = editingInhibitRuleIndex !== null;
    var rule = isEdit ? (state.inhibitRules[editingInhibitRuleIndex] || {}) : {};

    $("inhibit-dialog-title").textContent = isEdit ? "Edit inhibit rule" : "Add inhibit rule";
    $("inhibit-dialog-subtitle").textContent =
      "Suppress target alerts when a matching source alert is firing.";

    var sourceList = $("inhibit-source-matchers-list");
    sourceList.innerHTML = "";
    (rule.source_matchers || []).forEach(function (matcher) {
      addMatcherRow(sourceList, matcher);
    });

    var targetList = $("inhibit-target-matchers-list");
    targetList.innerHTML = "";
    (rule.target_matchers || []).forEach(function (matcher) {
      addMatcherRow(targetList, matcher);
    });

    $("f-inhibit-equal").value = (rule.equal || []).join(", ");
    hideInhibitError();
    $("inhibit-delete-btn").classList.toggle("hidden", !isEdit);
    $("inhibit-save-btn").textContent = isEdit ? "Save changes" : "Create rule";

    $("dialog-overlay").classList.remove("hidden");
    $("inhibit-dialog").classList.remove("hidden");
  }

  function collectInhibitRuleFromEditor() {
    var rule = {};
    var sourceMatchers = collectMatcherRows($("inhibit-source-matchers-list"));
    if (sourceMatchers.length) rule.source_matchers = sourceMatchers;

    var targetMatchers = collectMatcherRows($("inhibit-target-matchers-list"));
    if (targetMatchers.length) rule.target_matchers = targetMatchers;

    var equal = $("f-inhibit-equal").value.split(",")
      .map(function (value) { return value.trim(); })
      .filter(Boolean);
    if (equal.length) rule.equal = equal;

    return rule;
  }

  $("add-inhibit-rule-btn").addEventListener("click", function () { openInhibitRuleDialog(null); });
  $("inhibit-cancel-btn").addEventListener("click", closeDialog);
  $("inhibit-add-source-matcher-btn").addEventListener("click", function () {
    addMatcherRow($("inhibit-source-matchers-list"));
  });
  $("inhibit-add-target-matcher-btn").addEventListener("click", function () {
    addMatcherRow($("inhibit-target-matchers-list"));
  });
  $("inhibit-rule-search").addEventListener("input", function () {
    state.inhibitRuleQuery = this.value.trim().toLowerCase();
    renderInhibitRulesTable();
  });

  $("inhibit-form").addEventListener("submit", function (event) {
    event.preventDefault();
    hideInhibitError();

    var rule = collectInhibitRuleFromEditor();
    if (!rule.source_matchers || !rule.source_matchers.length) {
      showInhibitError("At least one source matcher is required.");
      return;
    }
    if (!rule.target_matchers || !rule.target_matchers.length) {
      showInhibitError("At least one target matcher is required.");
      return;
    }

    var rules = state.inhibitRules.slice();
    if (editingInhibitRuleIndex === null) rules.push(rule);
    else rules[editingInhibitRuleIndex] = rule;

    var isEdit = editingInhibitRuleIndex !== null;
    var saveBtn = $("inhibit-save-btn");
    saveBtn.disabled = true;
    saveBtn.textContent = "Saving...";

    persistInhibitRules(rules, closeDialog)
      .finally(function () {
        saveBtn.disabled = false;
        saveBtn.textContent = isEdit ? "Save changes" : "Create rule";
      });
  });

  $("inhibit-delete-btn").addEventListener("click", function () {
    if (editingInhibitRuleIndex === null) return;
    uiConfirm("Delete this inhibit rule?").then(function (ok) {
      if (!ok) return;
      var rules = state.inhibitRules.slice();
      rules.splice(editingInhibitRuleIndex, 1);
      persistInhibitRules(rules, closeDialog);
    });
  });

  function loadAlertmanagerNotifications() {
    return api("/alertmanager/notifications")
      .then(function (text) {
        var payload = JSON.parse(text);
        renderAlertmanagerNotifications(payload.notifications || []);
      })
      .catch(function (err) { toast(err.message, true); });
  }

  function renderAlertmanagerNotifications(notifications) {
    var list = $("am-notifications");
    if (!list) return;
    list.innerHTML = "";
    if (!notifications.length) {
      var empty = document.createElement("div");
      empty.className = "logs-empty";
      empty.innerHTML = "<p>No notifications received yet</p>";
      list.appendChild(empty);
      return;
    }

    var wrap = document.createElement("div");
    wrap.className = "notification-list";

    notifications.slice().reverse().forEach(function (notification) {
      var item = document.createElement("div");
      item.className = "notification-item";

      var meta = document.createElement("div");
      meta.className = "notification-meta";

      var status = document.createElement("span");
      var statusText = (notification.status || "unknown").toLowerCase();
      status.className = "notification-status " + (statusText === "resolved" ? "resolved" : "firing");
      status.textContent = notification.status || "unknown";
      meta.appendChild(status);

      if (notification.receivedAt) {
        var time = document.createElement("span");
        time.className = "notification-time mono";
        time.textContent = notification.receivedAt;
        meta.appendChild(time);
      }

      var title = document.createElement("div");
      title.className = "notification-title";
      title.textContent = notification.alertname || "Alert notification";

      var detail = document.createElement("div");
      detail.className = "notification-detail";
      var detailParts = [
        notification.instance,
        notification.severity,
        notification.summary,
      ].filter(Boolean);
      detail.textContent = detailParts.join(" · ");

      item.appendChild(meta);
      item.appendChild(title);
      if (detailParts.length) item.appendChild(detail);
      wrap.appendChild(item);
    });

    list.appendChild(wrap);
  }

  function statusDotClass(status) {
    if (status === "up") return "ok";
    if (status === "down") return "bad";
    if (status === "paused") return "muted";
    if (status === "pending") return "warn";
    return "bad";
  }

  function monitorsOverallDot(status, summary) {
    var dot = $("monitors-dot");
    if (!dot) return;
    dot.className = "dot";
    if (!summary || summary.total === 0) {
      dot.classList.add("ok");
      dot.title = "No monitors";
      return;
    }
    if (summary.down > 0) {
      dot.classList.add("bad");
      dot.title = summary.down + " down";
    } else if (summary.pending > 0) {
      dot.classList.add("warn");
      dot.title = summary.pending + " pending";
    } else if (summary.paused === summary.total) {
      dot.style.background = "var(--muted-foreground)";
      dot.classList.add("warn");
      dot.title = "All paused";
    } else {
      dot.classList.add("ok");
      dot.title = "All up";
    }
  }

  function loadMonitors() {
    return api("/monitors")
      .then(function (text) {
        try { state.monitors = JSON.parse(text); } catch (e) { state.monitors = []; }
        if (!Array.isArray(state.monitors)) state.monitors = [];
        return loadMonitorsStatus();
      })
      .catch(function (err) { toast(err.message, true); });
  }

  function loadMonitorsStatus() {
    return api("/monitors/status")
      .then(function (text) {
        try { state.monitorsStatus = JSON.parse(text); } catch (e) {}
        return api("/monitors/heartbeats").then(function (hbText) {
          try { state.monitorHeartbeats = JSON.parse(hbText); } catch (e) { state.monitorHeartbeats = {}; }
          return loadMonitorsTable();
        }).catch(function () { return loadMonitorsTable(); });
      })
      .catch(function () { return loadMonitorsTable(); });
  }

  function loadMonitorsTable() {
    var range = state.monitorRange || "24h";
    return api("/monitors/table?range=" + encodeURIComponent(range))
      .then(function (text) {
        try { state.monitorsTable = JSON.parse(text); } catch (e) { state.monitorsTable = null; }
        renderMonitors();
      })
      .catch(function () { renderMonitors(); });
  }

  function filteredMonitors() {
    var q = state.monitorQuery;
    var f = state.monitorFilter;
    var statusMap = {};
    (state.monitorsStatus.monitors || []).forEach(function (m) { statusMap[m.id] = m; });
    return state.monitors.filter(function (m) {
      var id = m.id || "";
      var name = m.name || id;
      var target = m.target || "";
      var hay = (name + " " + target + " " + (m.type || "")).toLowerCase();
      if (q && hay.indexOf(q) === -1) return false;
      if (f) {
        var st = statusMap[id] ? statusMap[id].status : "pending";
        if (f === "paused" && st !== "paused") return false;
        if (f === "up" && st !== "up") return false;
        if (f === "down" && st !== "down") return false;
      }
      return true;
    });
  }

  function formatTimeAgo(epochSeconds) {
    if (!epochSeconds) return "-";
    var d = new Date(epochSeconds * 1000);
    return d.toLocaleString();
  }

  function formatSslCell(entry) {
    if (entry.sslExpiresInDays == null) return "-";
    if (entry.sslExpiresIn) return entry.sslExpiresIn;
    var days = entry.sslExpiresInDays;
    if (days < 0) return "expired";
    if (days < 1) return Math.round(days * 24) + "h";
    return Math.round(days) + "d";
  }

  function renderMonitors() {
    closeRowMenu();
    var tbody = $("monitors-tbody");
    var listCard = $("monitors-list");
    var empty = $("monitors-empty");
    if (!tbody || !listCard) return;

    var summary = state.monitorsStatus.summary || { total: state.monitors.length, up: 0, down: 0, paused: 0, pending: 0 };
    var overall = state.monitorsStatus.overall || "unknown";
    var _el;
    if ((_el = $("monitors-summary"))) _el.textContent = summary.total ? summary.total + " monitor(s) · " + summary.up + " up · " + summary.down + " down" : "No monitors";
    if ((_el = $("monitors-count"))) _el.textContent = summary.total ? summary.total + " monitor(s)" : "";
    if ((_el = $("monitors-range-label"))) _el.textContent = state.monitorRange;
    monitorsOverallDot(overall, summary);

    if (!state.monitors.length) {
      tbody.innerHTML = "";
      listCard.classList.add("hidden");
      var footer = document.querySelector("#section-monitors .table-footer");
      if (footer) footer.classList.add("hidden");
      empty.classList.remove("hidden");
      return;
    }
    empty.classList.add("hidden");
    listCard.classList.remove("hidden");
    var footer2 = document.querySelector("#section-monitors .table-footer");
    if (footer2) footer2.classList.remove("hidden");
    tbody.innerHTML = "";

    var table = state.monitorsTable;
    var rows = [];
    if (table && table.monitors && table.monitors.length) {

      rows = table.monitors.filter(function (entry) {
        var hay = ((entry.name || "") + " " + (entry.endpoint || "") + " " + (entry.type || "")).toLowerCase();
        if (state.monitorQuery && hay.indexOf(state.monitorQuery) === -1) return false;
        if (state.monitorFilter) {
          if (entry.status !== state.monitorFilter) return false;
        }
        return true;
      });
    } else {

      var fallback = filteredMonitors();
      rows = fallback.map(function (mon) {
        var statusMap = {};
        (state.monitorsStatus.monitors || []).forEach(function (m) { statusMap[m.id] = m; });
        var se = statusMap[mon.id] || {};
        return {
          id: mon.id,
          name: mon.name || mon.id,
          endpoint: mon.target || mon.targets || "",
          type: mon.type || "http",
          status: se.status || (mon.enabled === false ? "paused" : "pending"),
          responseTime: se.duration != null ? se.duration : null,
          lastFail: null,
          lastSuccess: null,
          uptime: null,
          sslExpiresIn: null,
          sslExpiresInDays: null
        };
      }).filter(function (entry) {
        var hay = ((entry.name || "") + " " + (entry.endpoint || "")).toLowerCase();
        if (state.monitorQuery && hay.indexOf(state.monitorQuery) === -1) return false;
        if (state.monitorFilter && entry.status !== state.monitorFilter) return false;
        return true;
      });
    }

    if (!rows.length) {
      var trEmpty = document.createElement("tr");
      trEmpty.className = "empty-row";
      var tdEmpty = document.createElement("td");
      tdEmpty.colSpan = 9;
      tdEmpty.textContent = state.monitorQuery || state.monitorFilter ? 'No monitors match filter' : 'No monitors';
      trEmpty.appendChild(tdEmpty);
      tbody.appendChild(trEmpty);
      if ((_el = $("monitors-count"))) _el.textContent = "0 of " + (table && table.monitors ? table.monitors.length : state.monitors.length) + " monitor(s)";
      return;
    }

    rows.forEach(function (entry) {
      var tr = buildMonitorRow(entry);
      tbody.appendChild(tr);
    });

    if ((_el = $("monitors-count"))) {
      var total = table && table.monitors ? table.monitors.length : state.monitors.length;
      _el.textContent = rows.length + " of " + total + " monitor(s) · range " + state.monitorRange;
    }
  }

  function buildMonitorRow(entry) {
    var tr = document.createElement("tr");
    if (entry.status === "paused") tr.style.opacity = "0.7";

    var tdName = document.createElement("td");
    var nameWrap = document.createElement("div");
    nameWrap.style.display = "flex";
    nameWrap.style.alignItems = "center";
    nameWrap.style.gap = "8px";
    var dot = document.createElement("span");
    dot.className = "dot " + statusDotClass(entry.status);
    if (entry.status === "paused") { dot.style.background = "var(--muted-foreground)"; dot.className = "dot"; }
    dot.title = entry.status;
    nameWrap.appendChild(dot);
    var nameSpan = document.createElement("span");
    nameSpan.className = "module-name";
    nameSpan.textContent = entry.name || entry.id;
    nameWrap.appendChild(nameSpan);
    tdName.appendChild(nameWrap);
    tr.appendChild(tdName);

    var tdType = document.createElement("td");
    var typeBadge = document.createElement("span");
    typeBadge.className = "badge";
    typeBadge.style.fontSize = "11px";
    typeBadge.style.height = "20px";
    typeBadge.textContent = (entry.type || "http").toUpperCase();
    tdType.appendChild(typeBadge);
    tr.appendChild(tdType);

    var tdEndpoint = document.createElement("td");
    var epSpan = document.createElement("span");
    epSpan.className = "mono text-muted";
    epSpan.style.fontSize = "12px";
    epSpan.style.maxWidth = "220px";
    epSpan.style.display = "inline-block";
    epSpan.style.overflow = "hidden";
    epSpan.style.textOverflow = "ellipsis";
    epSpan.style.whiteSpace = "nowrap";
    epSpan.textContent = entry.endpoint || "-";
    epSpan.title = entry.endpoint || "";
    tdEndpoint.appendChild(epSpan);
    tr.appendChild(tdEndpoint);

    var tdResp = document.createElement("td");
    tdResp.className = "mono";
    tdResp.style.fontSize = "12px";
    if (entry.responseTime != null) {
      tdResp.textContent = formatDuration(String(entry.responseTime));
      tdResp.title = entry.responseTime + "s";
    } else {
      tdResp.textContent = "-";
      tdResp.className += " text-muted";
    }
    tr.appendChild(tdResp);

    var tdFail = document.createElement("td");
    tdFail.style.fontSize = "12px";
    if (entry.lastFail) {
      var failAgo = formatTimeAgo(entry.lastFail);
      tdFail.textContent = failAgo;
      tdFail.title = new Date(entry.lastFail * 1000).toLocaleString();
      if (entry.status === "down") tdFail.style.color = "var(--err)";
      else tdFail.className = "text-muted";
    } else {
      tdFail.textContent = "-";
      tdFail.className = "text-muted";
    }
    tr.appendChild(tdFail);

    var tdSucc = document.createElement("td");
    tdSucc.style.fontSize = "12px";
    if (entry.lastSuccess) {
      tdSucc.textContent = formatTimeAgo(entry.lastSuccess);
      tdSucc.title = new Date(entry.lastSuccess * 1000).toLocaleString();
      tdSucc.className = "text-muted";
    } else {
      tdSucc.textContent = "-";
      tdSucc.className = "text-muted";
    }
    tr.appendChild(tdSucc);

    var tdUptime = document.createElement("td");
    tdUptime.style.fontSize = "12px";
    if (entry.uptime != null) {
      tdUptime.textContent = entry.uptime + "%";
      tdUptime.className = "text-muted";
    } else {
      tdUptime.textContent = "-";
      tdUptime.className = "text-muted";
    }
    tr.appendChild(tdUptime);

    var tdSsl = document.createElement("td");
    tdSsl.className = "mono";
    tdSsl.style.fontSize = "12px";
    var sslText = formatSslCell(entry);
    tdSsl.textContent = sslText;
    if (sslText === "-" || sslText === "expired") tdSsl.className += " text-muted";
    else if (entry.sslExpiresInDays != null && entry.sslExpiresInDays < 7) tdSsl.style.color = "var(--err)";
    else if (entry.sslExpiresInDays != null && entry.sslExpiresInDays < 30) tdSsl.style.color = "var(--warn)";
    tr.appendChild(tdSsl);

    var tdActions = document.createElement("td");
    tdActions.className = "col-actions-cell";
    var kebab = document.createElement("button");
    kebab.className = "row-action kebab";
    kebab.title = "More actions";
    kebab.innerHTML = '<svg class="icon" viewBox="0 0 24 24"><circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/></svg>';
    kebab.addEventListener("click", function (event) {
      event.stopPropagation();
      openMonitorRowMenu(kebab, entry.id, entry.status);
    });
    tdActions.appendChild(kebab);

    tr.style.cursor = "pointer";
    tr.addEventListener("click", function () { openMonitorDialog(entry.id); });

    tr.appendChild(tdActions);
    return tr;
  }

  function buildMonitorCard(mon, statusEntry) {
    var id = mon.id;
    var status = statusEntry ? statusEntry.status : (mon.enabled === false ? "paused" : "pending");
    var duration = statusEntry && statusEntry.duration != null ? statusEntry.duration : null;

    var card = document.createElement("div");
    card.className = "monitor-card " + (status === "paused" ? "paused" : "");
    card.addEventListener("click", function () { openMonitorDialog(id); });

    var topLeft = document.createElement("div");
    topLeft.className = "monitor-card-top";
    var left = document.createElement("div");
    left.className = "monitor-card-title";

    var nameRow = document.createElement("div");
    nameRow.className = "monitor-name";
    nameRow.textContent = mon.name || id;
    left.appendChild(nameRow);

    var targetRow = document.createElement("div");
    targetRow.className = "monitor-target mono";
    targetRow.textContent = mon.target || "";
    targetRow.title = mon.target || "";
    left.appendChild(targetRow);

    var meta = document.createElement("div");
    meta.className = "monitor-card-meta";
    var typeBadge = document.createElement("span");
    typeBadge.className = "monitor-type-badge";
    typeBadge.textContent = (mon.type || "http").toUpperCase();
    meta.appendChild(typeBadge);
    var iv = document.createElement("span");
    iv.className = "monitor-interval mono";
    iv.textContent = mon.interval || "60s";
    meta.appendChild(iv);
    if (mon.description) {
      var desc = document.createElement("span");
      desc.className = "text-muted";
      desc.textContent = mon.description;
      desc.style.fontSize = "11px";
      meta.appendChild(desc);
    }
    left.appendChild(meta);

    var beats = state.monitorHeartbeats[id] || [];
    var hbStrip = buildHeartbeatStrip(beats, status, 50);
    left.appendChild(hbStrip);

    topLeft.appendChild(left);
    card.appendChild(topLeft);

    var right = document.createElement("div");
    right.className = "monitor-card-right";

    var statusRow = document.createElement("div");
    statusRow.className = "monitor-status";
    var dot = document.createElement("span");
    dot.className = "dot " + statusDotClass(status);
    if (status === "paused") { dot.style.background = "var(--muted-foreground)"; dot.className = "dot"; }
    statusRow.appendChild(dot);
    var label = document.createElement("span");
    label.textContent = status === "up" ? "Up" : status === "down" ? "Down" : status === "paused" ? "Paused" : "Pending";
    label.style.color = status === "down" ? "var(--err)" : status === "up" ? "var(--ok)" : "var(--muted-foreground)";
    statusRow.appendChild(label);
    if (status !== "paused" && status !== "pending") {
      var uptime = computeUptime(beats);
      var up = document.createElement("span");
      up.className = "monitor-uptime";
      up.textContent = uptime != null ? uptime + "%" : (duration != null ? formatDuration(duration) : "");
      statusRow.appendChild(up);
    }
    right.appendChild(statusRow);

    if (duration != null && status === "up") {
      var resp = document.createElement("div");
      resp.className = "text-muted mono";
      resp.style.fontSize = "11px";
      resp.textContent = formatDuration(duration);
      right.appendChild(resp);
    }

    var actions = document.createElement("div");
    actions.className = "monitor-actions";
    var editBtn = document.createElement("button");
    editBtn.className = "btn ghost sm";
    editBtn.textContent = "Edit";
    editBtn.addEventListener("click", function (e) { e.stopPropagation(); openMonitorDialog(id); });
    actions.appendChild(editBtn);
    var pauseBtn = document.createElement("button");
    pauseBtn.className = "btn ghost sm";
    pauseBtn.textContent = status === "paused" ? "Resume" : "Pause";
    pauseBtn.addEventListener("click", function (e) {
      e.stopPropagation();
      if (status === "paused") resumeMonitor(id); else pauseMonitor(id);
    });
    actions.appendChild(pauseBtn);
    var kebab = document.createElement("button");
    kebab.className = "row-action kebab";
    kebab.innerHTML = '<svg class="icon" viewBox="0 0 24 24"><circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/></svg>';
    kebab.addEventListener("click", function (e) {
      e.stopPropagation();
      openMonitorRowMenu(kebab, id, status);
    });
    actions.appendChild(kebab);
    right.appendChild(actions);

    card.appendChild(right);
    return card;
  }

  function buildHeartbeatStrip(beats, status, limit) {
    var wrap = document.createElement("div");
    wrap.className = "heartbeat-strip";
    if (status === "paused") {
      for (var i = 0; i < 12; i++) {
        var b = document.createElement("span");
        b.className = "heartbeat paused";
        b.title = "Paused";
        wrap.appendChild(b);
      }
      return wrap;
    }
    if (!beats || !beats.length) {
      for (var j = 0; j < 12; j++) {
        var e = document.createElement("span");
        e.className = "heartbeat pending";
        wrap.appendChild(e);
      }
      return wrap;
    }

    var slice = beats.slice(Math.max(0, beats.length - 50));
    slice.forEach(function (beat) {
      var el = document.createElement("span");
      var v = beat.value;
      el.className = "heartbeat " + (v === 1 ? "up" : v === 0 ? "down" : "pending");
      var d = new Date(beat.time * 1000).toLocaleTimeString();
      el.title = d + " : " + (v === 1 ? "Up" : "Down");
      wrap.appendChild(el);
    });

    if (slice.length < 12) {
      for (var k = slice.length; k < 12; k++) {
        var pad = document.createElement("span");
        pad.className = "heartbeat empty";
        wrap.appendChild(pad);
      }
    }
    return wrap;
  }

  function computeUptime(beats) {
    if (!beats || !beats.length) return null;
    var up = 0;
    beats.forEach(function (b) { if (b.value === 1) up++; });
    return Math.round((up / beats.length) * 1000) / 10;
  }

  function openMonitorRowMenu(anchor, id, status) {
    var items = [
      { label: "Edit", onClick: function () { openMonitorDialog(id); } },
      { label: status === "paused" ? "Resume" : "Pause", onClick: function () { if (status === "paused") resumeMonitor(id); else pauseMonitor(id); } },
      { label: "Delete", danger: true, onClick: function () { uiConfirm('Delete monitor "' + id + '"? This removes its job, module and alert rule.').then(function (ok) { if (ok) deleteMonitor(id); }); } },
    ];
    openRowMenuAt(anchor, items);
  }

  function deleteMonitor(id) {
    api("/monitors/" + encodeURIComponent(id), { method: "DELETE" })
      .then(function (msg) { toast(extractMessage(msg)); return loadMonitors(); })
      .catch(function (err) { toast(err.message, true); });
  }

  function pauseMonitor(id) {
    api("/monitors/" + encodeURIComponent(id) + "/pause", { method: "POST" })
      .then(function (msg) { toast(extractMessage(msg)); return loadMonitors(); })
      .catch(function (err) { toast(err.message, true); });
  }

  function resumeMonitor(id) {
    api("/monitors/" + encodeURIComponent(id) + "/resume", { method: "POST" })
      .then(function (msg) { toast(extractMessage(msg)); return loadMonitors(); })
      .catch(function (err) { toast(err.message, true); });
  }

  function openMonitorDetail(id) {
    openMonitorDialog(id);
  }

  var pendingMonitorTlsFiles = {};
  var uploadedMonitorTlsPaths = {};
  var MONITOR_TLS_PARTS = [["ca", "f-monitor-ca-file", "tlsCaFile"], ["cert", "f-monitor-cert-file", "tlsCertFile"], ["key", "f-monitor-key-file", "tlsKeyFile"]];

  function baseNameMonitor(path) {
    if (!path) return "";
    return String(path).split("/").pop();
  }

  function refreshMonitorTlsFields(mon) {
    pendingMonitorTlsFiles = {};
    uploadedMonitorTlsPaths = {};
    var useTls = mon && mon.tlsEnabled === true;

    if (mon && !useTls && (mon.tlsCaFile || mon.tlsCertFile || mon.tlsKeyFile)) useTls = true;
    $("f-monitor-tls-enable").checked = useTls;
    $("f-monitor-server-name").value = mon && mon.tlsServerName ? mon.tlsServerName : "";
    MONITOR_TLS_PARTS.forEach(function (part) {
      var nameEl = document.querySelector('[data-monitor-tls-name="' + part[0] + '"]');
      if (nameEl) nameEl.textContent = baseNameMonitor(mon ? mon[part[2]] : "");
      var input = $(part[1]);
      if (input) input.value = "";
    });
    $("monitor-tls-fields").classList.toggle("hidden", !$("f-monitor-tls-enable").checked);
  }

  function updateMonitorTlsUi() {
    var prober = $("f-monitor-type").value;
    var applies = prober === "http" || prober === "https" || prober === "keyword" || prober === "tcp";
    $("monitor-tls-section").classList.toggle("hidden", !applies);
    $("monitor-tls-fields").classList.toggle("hidden", !$("f-monitor-tls-enable").checked);
  }

  function runPendingMonitorTlsUploads(monitorId) {
    var jobs = MONITOR_TLS_PARTS.filter(function (part) { return pendingMonitorTlsFiles[part[0]]; });
    if (!jobs.length) return Promise.resolve({});
    var moduleName = "observecMonitorModule_" + monitorId.replaceAll(/[^A-Za-z0-9_-]/g, "_");
    return jobs.reduce(function (chain, part) {
      return chain.then(function () {
        var formData = new FormData();
        formData.append(part[0], pendingMonitorTlsFiles[part[0]]);
        return fetch("/blackbox/module/" + encodeURIComponent(moduleName) + "/tls", {
          method: "POST",
          body: formData,
        }).then(function (res) {
          return res.text().then(function (bodyText) {
            if (!res.ok) throw new Error(extractMessage(bodyText));
            var parsed = JSON.parse(bodyText);
            var pathKey = part[2] === "tlsCaFile" ? "ca_file" : part[2] === "tlsCertFile" ? "cert_file" : "key_file";

            var fieldMap = { "ca_file": "tlsCaFile", "cert_file": "tlsCertFile", "key_file": "tlsKeyFile" };
            var field = fieldMap[pathKey];
            if (parsed[pathKey]) uploadedMonitorTlsPaths[field] = parsed[pathKey];
            else if (parsed[field]) uploadedMonitorTlsPaths[field] = parsed[field];
          });
        });
      });
    }, Promise.resolve({})).then(function () { return uploadedMonitorTlsPaths; });
  }

  function openMonitorDialog(id) {
    state.editingMonitorId = id || null;
    var isEdit = Boolean(id);
    var mon = isEdit ? state.monitors.find(function (m) { return m.id === id; }) : null;
    $("monitor-dialog-title").textContent = isEdit ? "Edit Monitor" : "New Monitor";
    $("monitor-dialog-subtitle").textContent = "";
    $("f-monitor-name").value = mon ? (mon.name || "") : "";

    var targetVal = "";
    if (mon) {
      if (mon.targets && mon.targets !== mon.target) targetVal = mon.targets;
      else targetVal = mon.target || "";
    }
    $("f-monitor-target").value = targetVal;
    $("f-monitor-type").value = mon ? (mon.type || "http") : "http";
    $("f-monitor-interval").value = mon ? (mon.interval || "60s") : "60s";
    $("f-monitor-retries").value = mon && mon.retries != null ? String(mon.retries) : "1";
    $("f-monitor-timeout").value = mon ? (mon.timeout || "10s") : "10s";
    $("f-monitor-slack-url").value = mon ? (mon.slackWebhookUrl || "") : "";
    $("f-monitor-slack-channel").value = mon ? (mon.slackChannel || "") : "";
    $("f-monitor-desc").value = mon ? (mon.description || "") : "";

    $("f-monitor-proto").value = mon && mon.preferredIpProtocol === "ip6" ? "ip6" : "ip4";
    $("f-monitor-fallback").checked = mon ? mon.ipProtocolFallback !== false : true;
    $("f-monitor-method").value = mon && mon.method ? mon.method : "GET";
    $("f-monitor-content-type").value = mon && mon.contentType ? mon.contentType : "application/json";
    $("f-monitor-body").value = mon && mon.body ? mon.body : "";
    $("f-monitor-codes").value = mon && mon.expectedStatusCodes ? mon.expectedStatusCodes.join(", ") : "";
    $("f-monitor-keyword").value = mon && mon.keyword ? mon.keyword : "";
    $("f-monitor-keyword-inverted").checked = mon ? Boolean(mon.keywordInverted) : false;
    $("f-monitor-follow").checked = mon ? mon.followRedirects !== false : true;
    var versions = mon && mon.validHttpVersions ? mon.validHttpVersions : ["HTTP/1.1","HTTP/2.0"];
    $("f-monitor-v10").checked = versions.indexOf("HTTP/1.0") !== -1;
    $("f-monitor-v11").checked = versions.indexOf("HTTP/1.1") !== -1;
    $("f-monitor-v20").checked = versions.indexOf("HTTP/2.0") !== -1;
    refreshMonitorTlsFields(mon);
    updateMonitorTypeUi();
    updateMonitorTlsUi();
    updateMonitorPostFields();
    hideMonitorError();
    $("f-monitor-delete").classList.toggle("hidden", !isEdit);
    $("f-monitor-save").textContent = isEdit ? "Save changes" : "Create Monitor";
    $("dialog-overlay").classList.remove("hidden");
    $("monitor-dialog").classList.remove("hidden");
    $("f-monitor-name").focus();
  }

  function hideMonitorError() { $("monitor-error").classList.add("hidden"); $("monitor-error").textContent = ""; }
  function showMonitorError(msg) { var el = $("monitor-error"); el.textContent = msg; el.classList.remove("hidden"); }

  function updateMonitorTypeUi() {
    var type = $("f-monitor-type").value;
    var isHttp = type === "http" || type === "https";
    var isKeyword = type === "keyword";
    var isHttpLike = isHttp || isKeyword;
    $("monitor-http-section").classList.toggle("hidden", !isHttpLike);
    Array.prototype.forEach.call(document.querySelectorAll(".monitor-field-http"), function (el) {
      var show = isHttpLike;
      el.classList.toggle("hidden", !show);
    });
    Array.prototype.forEach.call(document.querySelectorAll(".monitor-field-keyword"), function (el) {
      el.classList.toggle("hidden", !isKeyword);
    });
  }

  function collectMonitorPayload() {
    var payload = {
      name: $("f-monitor-name").value.trim(),
      target: $("f-monitor-target").value.trim(),
      type: $("f-monitor-type").value,
      interval: $("f-monitor-interval").value,
      retries: parseInt($("f-monitor-retries").value, 10),
      timeout: $("f-monitor-timeout").value.trim(),
      slackWebhookUrl: $("f-monitor-slack-url").value.trim() || undefined,
      slackChannel: $("f-monitor-slack-channel").value.trim() || undefined,
      description: $("f-monitor-desc").value.trim() || undefined,
      preferredIpProtocol: $("f-monitor-proto").value,
      ipProtocolFallback: $("f-monitor-fallback").checked,
      tlsEnabled: $("f-monitor-tls-enable").checked,
      tlsServerName: $("f-monitor-server-name").value.trim() || undefined,
      followRedirects: $("f-monitor-follow").checked,
    };

    var versions = ["f-monitor-v10", "f-monitor-v11", "f-monitor-v20"]
      .filter(function (id) { return $(id).checked; })
      .map(function (id) { return $(id).value; });
    if (versions.length) payload.validHttpVersions = versions;

    var type = payload.type;
    if (type === "http" || type === "keyword" || type === "https") {
      payload.method = $("f-monitor-method").value;
      var codes = $("f-monitor-codes").value.split(",").map(function (s) { return parseInt(s.trim(), 10); }).filter(function (n) { return !isNaN(n); });
      if (codes.length) payload.expectedStatusCodes = codes;
      if ($("f-monitor-method").value === "POST" || $("f-monitor-method").value === "PUT") {
        payload.body = $("f-monitor-body").value;
        if (!payload.body) delete payload.body;
        payload.contentType = $("f-monitor-content-type").value;
      }
    }
    if (type === "keyword") {
      payload.keyword = $("f-monitor-keyword").value.trim();
      payload.keywordInverted = $("f-monitor-keyword-inverted").checked;
    }

    var existing = state.editingMonitorId ? state.monitors.find(function (m) { return m.id === state.editingMonitorId; }) : null;
    MONITOR_TLS_PARTS.forEach(function (part) {
      var field = part[2];
      if (uploadedMonitorTlsPaths[field]) {
        payload[field] = uploadedMonitorTlsPaths[field];
      } else if (existing && existing[field]) {
        payload[field] = existing[field];
      }
    });
    if (state.editingMonitorId) payload.id = state.editingMonitorId;
    return payload;
  }

  $("monitor-search").addEventListener("input", function () {
    state.monitorQuery = this.value.trim().toLowerCase();
    renderMonitors();
  });

  Array.prototype.forEach.call(document.querySelectorAll("[data-monitor-filter]"), function (chip) {
    chip.addEventListener("click", function () {
      state.monitorFilter = this.getAttribute("data-monitor-filter");
      Array.prototype.forEach.call(document.querySelectorAll("[data-monitor-filter]"), function (other) {
        other.classList.toggle("active", other === chip);
      });
      renderMonitors();
    });
  });

  $("add-monitor-btn").addEventListener("click", function () { openMonitorDialog(null); });
  $("empty-add-monitor-btn").addEventListener("click", function () { openMonitorDialog(null); });
  $("monitors-refresh-btn").addEventListener("click", function () { loadMonitors(); });
  var _rangeEl = $("monitor-range");
  if (_rangeEl) {
    _rangeEl.addEventListener("change", function () {
      state.monitorRange = this.value;
      loadMonitorsTable();
    });
  }

  $("f-monitor-type").addEventListener("change", function () {
    updateMonitorTypeUi();
    updateMonitorTlsUi();
    updateMonitorPostFields();
  });
  $("f-monitor-tls-enable").addEventListener("change", function () {
    $("monitor-tls-fields").classList.toggle("hidden", !this.checked);
  });
  $("f-monitor-method").addEventListener("change", updateMonitorPostFields);
  function updateMonitorPostFields() {
    var method = $("f-monitor-method").value;
    $("monitor-post-fields").classList.toggle("hidden", method !== "POST" && method !== "PUT");
  }
  MONITOR_TLS_PARTS.forEach(function (part) {
    var trigger = document.querySelector('[data-monitor-tls-upload="' + part[0] + '"]');
    var input = $(part[1]);
    if (trigger) trigger.addEventListener("click", function () { input.click(); });
    if (input) input.addEventListener("change", function () {
      if (!this.files.length) return;
      var monitorName = $("f-monitor-name").value.trim();
      if (!monitorName && !state.editingMonitorId) {
        this.value = "";
        delete pendingMonitorTlsFiles[part[0]];
        if (trigger) trigger.textContent = "Choose file";
        toast("Enter friendly name before uploading TLS files.", true);
        return;
      }
      pendingMonitorTlsFiles[part[0]] = this.files[0];
      var nameEl = document.querySelector('[data-monitor-tls-name="' + part[0] + '"]');
      if (nameEl) nameEl.textContent = this.files[0].name;
    });
  });

  $("f-monitor-cancel").addEventListener("click", closeDialog);
  $("f-monitor-delete").addEventListener("click", function () {
    if (!state.editingMonitorId) return;
    uiConfirm('Delete monitor "' + state.editingMonitorId + '"?').then(function (ok) {
      if (!ok) return;
      deleteMonitor(state.editingMonitorId);
      closeDialog();
    });
  });
  $("monitor-form").addEventListener("submit", function (event) {
    event.preventDefault();
    hideMonitorError();
    var isEdit = Boolean(state.editingMonitorId);

    var payload = collectMonitorPayload();
    if (!payload.name) { showMonitorError("Friendly name is required."); return; }
    if (!payload.target) { showMonitorError("Target(s) is required."); return; }
    if (payload.type === "keyword" && !payload.keyword) { showMonitorError("Keyword is required for keyword monitors."); return; }
    if (payload.slackWebhookUrl && payload.slackWebhookUrl.indexOf("http") !== 0) { showMonitorError("Slack webhook must be http(s) URL."); return; }
    if (payload.tlsEnabled && $("f-monitor-tls-enable").checked) {

    }
    var saveBtn = $("f-monitor-save");
    saveBtn.disabled = true;
    saveBtn.textContent = "Saving...";

    function doSave(finalPayload) {
      var request;
      if (isEdit) {
        request = api("/monitors/" + encodeURIComponent(state.editingMonitorId), { method: "PUT", body: JSON.stringify(finalPayload) });
      } else {
        request = api("/monitors", { method: "POST", body: JSON.stringify(finalPayload) });
      }
      return request.then(function (msg) {

        var newId = isEdit ? state.editingMonitorId : null;
        if (!isEdit) {
          try {
            var parsed = JSON.parse(msg);
            var m = parsed.message || "";
            var match = m.match(/monitor created : (.+)/);
            if (match) newId = match[1].trim();
            else {

              newId = payload.name.toLowerCase().trim().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
            }
          } catch (e) {}
        }
        var hasPendingTls = $("f-monitor-tls-enable").checked && Object.keys(pendingMonitorTlsFiles).length > 0;
        if (hasPendingTls && newId) {
          var formData = new FormData();
          MONITOR_TLS_PARTS.forEach(function (part) {
            if (pendingMonitorTlsFiles[part[0]]) formData.append(part[0], pendingMonitorTlsFiles[part[0]]);
          });
          return fetch("/monitors/" + encodeURIComponent(newId) + "/tls", {
            method: "POST",
            body: formData
          }).then(function (res) {
            return res.text().then(function (txt) {
              if (!res.ok) throw new Error(extractMessage(txt));
              toast(extractMessage(msg));
              closeDialog();
              return loadMonitors();
            });
          });
        } else {
          toast(extractMessage(msg));
          closeDialog();
          return loadMonitors();
        }
      });
    }

    if (isEdit && $("f-monitor-tls-enable").checked && Object.keys(pendingMonitorTlsFiles).length > 0) {
      runPendingMonitorTlsUploads(state.editingMonitorId).then(function (paths) {

        var finalPayload = collectMonitorPayload();
        return doSave(finalPayload);
      }).catch(function (err) {
        showMonitorError(err.message);
        saveBtn.disabled = false;
        saveBtn.textContent = isEdit ? "Save changes" : "Create Monitor";
      });
    } else {
      doSave(payload).catch(function (err) {
        showMonitorError(err.message);
      }).finally(function () {
        saveBtn.disabled = false;
        saveBtn.textContent = isEdit ? "Save changes" : "Create Monitor";
      });
    }
  });

  var COMPONENT_SECTIONS = {
    monitors: ["monitors"],
    blackbox: ["modules", "probes", "logs", "config"],
    prometheus: ["jobs", "rules", "services", "prom-logs", "prom-config"],
    alertmanager: ["am-receivers", "am-routes", "am-inhibit-rules", "am-notifications", "am-logs", "am-config"],
    access: ["users"],
    changelog: ["changelog"],
  };
  var SECTION_COMPONENT = {};
  Object.keys(COMPONENT_SECTIONS).forEach(function (component) {
    COMPONENT_SECTIONS[component].forEach(function (key) {
      SECTION_COMPONENT[key] = component;
    });
  });
  var activeComponent = "monitors";
  var lastSection = { monitors: "monitors", blackbox: "modules", prometheus: "jobs", alertmanager: "am-receivers", access: "users", changelog: "changelog" };

  var HOME_SECTION = { monitors: "monitors", blackbox: "modules", prometheus: "jobs", alertmanager: "am-receivers", access: "users", changelog: "changelog" };

  function setActiveSub(sectionKey) {
    Array.prototype.forEach.call(document.querySelectorAll(".nav-subitem"), function (item) {
      item.classList.toggle("active", item.getAttribute("data-section") === "section-" + sectionKey);
    });
  }

  function switchComponent(name, section) {
    activeComponent = name;
    $("monitors-view").classList.toggle("hidden", name !== "monitors");
    $("blackbox-view").classList.toggle("hidden", name !== "blackbox");
    $("prometheus-view").classList.toggle("hidden", name !== "prometheus");
    $("alertmanager-view").classList.toggle("hidden", name !== "alertmanager");
    if ($("access-view")) $("access-view").classList.toggle("hidden", name !== "access");
    if ($("changelog-view")) $("changelog-view").classList.toggle("hidden", name !== "changelog");

    Array.prototype.forEach.call(document.querySelectorAll(".nav-item[data-component]"), function (btn) {
      btn.classList.toggle("active", btn.getAttribute("data-component") === name);
    });

    var target = section || lastSection[name];
    switchSection(target);
    setActiveSub(target);
  }

  function switchSection(name) {
    lastSection[activeComponent] = name;
    Object.keys(COMPONENT_SECTIONS).forEach(function (component) {
      COMPONENT_SECTIONS[component].forEach(function (key) {
        var el = document.getElementById("section-" + key);
        if (el) el.classList.toggle("hidden", !(component === activeComponent && key === name));
      });
    });

    if (name === "modules") renderTable();
    else if (name === "probes") renderProbes();
    else if (name === "config") loadConfigText("blackbox");
    else if (name === "jobs") loadJobs();
    else if (name === "rules") loadRules();
    else if (name === "services") loadServices();
    else if (name === "prom-config") loadConfigText("prometheus");
    else if (name === "am-receivers") loadReceivers();
    else if (name === "am-routes") loadRoutes();
    else if (name === "am-inhibit-rules") loadInhibitRules();
    else if (name === "am-notifications") loadAlertmanagerNotifications();
    else if (name === "am-config") loadConfigText("alertmanager");
    else if (name === "monitors") loadMonitors();
    else if (name === "users") loadUsers();
    else if (name === "changelog") loadChangelog();

    window.scrollTo(0, 0);
  }

  $("am-notifications-refresh").addEventListener("click", loadAlertmanagerNotifications);

  var groupOpen = { monitors: true, blackbox: true, prometheus: true, alertmanager: true, access: true, changelog: true, appearance: true };
  try {
    var savedOpen = JSON.parse(localStorage.getItem("observec-nav-open") || "null");
    if (savedOpen && typeof savedOpen === "object") {
      Object.keys(groupOpen).forEach(function (name) {
        if (typeof savedOpen[name] === "boolean") groupOpen[name] = savedOpen[name];
      });
    }
  } catch (e) {}

  function setGroupOpen(name, open) {
    groupOpen[name] = !!open;
    var group = document.querySelector('.nav-group[data-nav-group="' + name + '"]');
    if (!group) return;
    group.setAttribute("data-open", open ? "true" : "false");
    var btn = group.querySelector(".nav-item[data-component]");
    if (btn) btn.setAttribute("aria-expanded", open ? "true" : "false");
    try { localStorage.setItem("observec-nav-open", JSON.stringify(groupOpen)); } catch (e) {}
  }

  Array.prototype.forEach.call(document.querySelectorAll(".nav-subitem[data-section]"), function (btn) {
    btn.addEventListener("click", function () {
      var key = btn.getAttribute("data-section").replace("section-", "");
      var component = SECTION_COMPONENT[key];
      if (!groupOpen[component]) setGroupOpen(component, true);
      if (component !== activeComponent) {
        switchComponent(component, key);
      } else {
        switchSection(key);
        setActiveSub(key);
      }
    });
  });

  // Super items are pure collapse toggles: clicking one only expands /
  // collapses its child tree (and its vertical guide line). Navigation
  // happens via the sub-items, which auto-expand their group.
  // When the sidebar itself is collapsed to icons, the sub tree is hidden
  // so a parent click navigates to the component home instead.
  function isSidebarCollapsed() {
    return document.body.classList.contains("sidebar-collapsed");
  }

  function updateSidebarTriggerIcon() {
    var icon = $("sidebar-trigger-icon");
    if (!icon) return;
    var collapsed = isSidebarCollapsed();
    icon.innerHTML = collapsed
      ? '<path d="m6 17 5-5-5-5"/><path d="m13 17 5-5-5-5"/>'
      : '<path d="m11 17-5-5 5-5"/><path d="m18 17-5-5 5-5"/>';
  }

  function setSidebarCollapsed(collapsed) {
    document.body.classList.toggle("sidebar-collapsed", !!collapsed);
    try { localStorage.setItem("observec-sidebar-collapsed", collapsed ? "true" : "false"); } catch (e) {}
    updateSidebarTriggerIcon();
  }

  function toggleSidebar() {
    setSidebarCollapsed(!isSidebarCollapsed());
  }

  Array.prototype.forEach.call(document.querySelectorAll(".nav-item[data-component]"), function (btn) {
    btn.addEventListener("click", function () {
      var name = btn.getAttribute("data-component");
      if (isSidebarCollapsed()) {
        switchComponent(name, HOME_SECTION[name] || lastSection[name]);
        return;
      }
      setGroupOpen(name, !groupOpen[name]);
    });
  });

  if ($("sidebar-trigger")) $("sidebar-trigger").addEventListener("click", toggleSidebar);
  if ($("sidebar-rail")) $("sidebar-rail").addEventListener("click", toggleSidebar);
  document.addEventListener("keydown", function (event) {
    if ((event.ctrlKey || event.metaKey) && !event.shiftKey && !event.altKey &&
        (event.key === "b" || event.key === "B")) {
      var tag = (event.target && event.target.tagName) || "";
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" ||
          (event.target && event.target.isContentEditable)) return;
      event.preventDefault();
      toggleSidebar();
    }
  });
  try {
    if (localStorage.getItem("observec-sidebar-collapsed") === "true") {
      document.body.classList.add("sidebar-collapsed");
    }
  } catch (e) {}
  updateSidebarTriggerIcon();

  /* Custom selects — style.hackctl.com .sel spec. Every native <select>
     stays in the DOM as the data source (all existing .value reads,
     .disabled writes, option rebuilds and change handlers work untouched)
     but is never shown, so no browser dropdown chrome is used. */
  var selState = { current: null };
  var selList = null;

  function selOptions(native) {
    var out = [];
    Array.prototype.forEach.call(native.options, function (opt) {
      out.push({ value: opt.value, text: opt.textContent, disabled: !!opt.disabled });
    });
    return out;
  }

  function selSelectedIndex(native) {
    if (native.selectedIndex >= 0 && native.options[native.selectedIndex]) return native.selectedIndex;
    return native.options.length ? 0 : -1;
  }

  function selMirrorClasses(native, wrap) {
    var skip = { select: 1, input: 1, mono: 1, "select-enhanced": 1 };
    var classes = ["sel"];
    Array.prototype.forEach.call(native.classList, function (c) {
      if (!skip[c]) classes.push(c);
    });
    wrap.className = classes.join(" ");
  }

  function selRefresh(native) {
    var wrap = native._selWrap, btn = native._selBtn, label = native._selLabel;
    if (!wrap || !btn) return;
    selMirrorClasses(native, wrap);
    btn.disabled = !!native.disabled;
    var idx = selSelectedIndex(native);
    var opts = native.options;
    if (label) {
      if (idx >= 0 && opts[idx]) {
        label.textContent = opts[idx].textContent;
        label.classList.remove("ph");
      } else {
        label.textContent = "";
        label.classList.add("ph");
      }
    }
    btn.setAttribute("aria-expanded", selState.current && selState.current.native === native ? "true" : "false");
  }

  function selClose() {
    if (selList && selList.parentNode) selList.parentNode.removeChild(selList);
    selList = null;
    if (selState.current) {
      selState.current.btn.setAttribute("data-open", "false");
      selState.current.btn.setAttribute("aria-expanded", "false");
      selState.current = null;
    }
  }

  function selPosition(btn) {
    var rect = btn.getBoundingClientRect();
    var listH = Math.min(selList.scrollHeight, 220);
    var top = rect.bottom + 4;
    if (top + listH > window.innerHeight - 8) {
      top = Math.max(8, rect.top - listH - 4);
    }
    selList.style.minWidth = rect.width + "px";
    selList.style.maxWidth = Math.max(rect.width, 200) + "px";
    selList.style.left = Math.min(rect.left, window.innerWidth - Math.max(rect.width, 200) - 8) + "px";
    selList.style.top = top + "px";
  }

  function selRenderList(entry) {
    selList.innerHTML = "";
    entry.options.forEach(function (opt, i) {
      var item = document.createElement("button");
      item.type = "button";
      item.className = "sel-opt";
      item.setAttribute("role", "option");
      item.setAttribute("aria-selected", i === entry.selected ? "true" : "false");
      item.setAttribute("data-active", i === entry.active ? "true" : "false");
      if (opt.disabled) item.setAttribute("data-disabled", "true");
      var text = document.createElement("span");
      text.textContent = opt.text;
      item.appendChild(text);
      if (i === entry.selected) {
        var tick = document.createElement("span");
        tick.className = "tick";
        tick.textContent = "✓";
        item.appendChild(tick);
      }
      if (!opt.disabled) {
        item.addEventListener("click", function (event) {
          event.stopPropagation();
          selPick(entry, i);
        });
        item.addEventListener("mousemove", function () {
          if (entry.active !== i) {
            entry.active = i;
            selRenderList(entry);
          }
        });
      }
      selList.appendChild(item);
    });
  }

  function selPick(entry, i) {
    var opt = entry.options[i];
    if (!opt || opt.disabled) return;
    entry.native.value = opt.value;
    selClose();
    entry.btn.focus();
    var event;
    try {
      event = new Event("change", { bubbles: true });
    } catch (e) {
      event = document.createEvent("HTMLEvents");
      event.initEvent("change", true, false);
    }
    entry.native.dispatchEvent(event);
    selRefresh(entry.native);
  }

  function selOpen(native, btn) {
    if (btn.disabled) return;
    if (selState.current && selState.current.native === native) {
      selClose();
      return;
    }
    selClose();
    var options = selOptions(native);
    if (!options.length) return;
    var selected = selSelectedIndex(native);
    var entry = { native: native, btn: btn, options: options, selected: selected, active: selected };
    selList = document.createElement("div");
    selList.className = "sel-list";
    selList.setAttribute("role", "listbox");
    document.body.appendChild(selList);
    selRenderList(entry);
    selPosition(btn);
    btn.setAttribute("data-open", "true");
    btn.setAttribute("aria-expanded", "true");
    selState.current = entry;
    var activeEl = selList.querySelector('[data-active="true"]');
    if (activeEl && activeEl.scrollIntoView) {
      try { activeEl.scrollIntoView({ block: "nearest" }); } catch (e) {}
    }
  }

  function selMove(dir) {
    var entry = selState.current;
    if (!entry) return;
    var i = entry.active, n = entry.options.length, guard = 0;
    do {
      i = (i + dir + n) % n;
      guard++;
    } while (entry.options[i].disabled && guard <= n);
    if (entry.options[i].disabled) return;
    entry.active = i;
    selRenderList(entry);
    var activeEl = selList.querySelectorAll(".sel-opt")[i];
    if (activeEl && activeEl.scrollIntoView) {
      try { activeEl.scrollIntoView({ block: "nearest" }); } catch (e) {}
    }
  }

  function enhanceSelect(native) {
    if (!native || native.tagName !== "SELECT" || native._selWrap) return;
    var wrap = document.createElement("span");
    wrap.className = "sel";
    native.parentNode.insertBefore(wrap, native);
    wrap.appendChild(native);
    var measuredH = native.offsetHeight || 34;
    wrap.style.height = measuredH + "px";
    if (native.style.width) wrap.style.width = native.style.width;
    native.classList.add("select-enhanced");
    selMirrorClasses(native, wrap);

    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "sel-btn";
    btn.setAttribute("data-open", "false");
    btn.setAttribute("aria-haspopup", "listbox");
    btn.setAttribute("aria-expanded", "false");
    if (native.style.fontSize) btn.style.fontSize = native.style.fontSize;
    var label = document.createElement("span");
    label.className = "sel-value";
    btn.appendChild(label);
    var chev = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    chev.setAttribute("class", "icon sel-chev");
    chev.setAttribute("viewBox", "0 0 24 24");
    var chevPath = document.createElementNS("http://www.w3.org/2000/svg", "path");
    chevPath.setAttribute("d", "m6 9 6 6 6-6");
    chev.appendChild(chevPath);
    btn.appendChild(chev);
    wrap.appendChild(btn);

    native._selWrap = wrap;
    native._selBtn = btn;
    native._selLabel = label;

    btn.addEventListener("click", function (event) {
      event.stopPropagation();
      selRefresh(native);
      selOpen(native, btn);
    });
    btn.addEventListener("keydown", function (event) {
      var openHere = selState.current && selState.current.native === native;
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        if (openHere) selPick(selState.current, selState.current.active);
        else { selRefresh(native); selOpen(native, btn); }
      } else if (event.key === "ArrowDown") {
        event.preventDefault();
        if (openHere) selMove(1);
        else { selRefresh(native); selOpen(native, btn); }
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        if (openHere) selMove(-1);
        else { selRefresh(native); selOpen(native, btn); }
      } else if (event.key === "Escape" && openHere) {
        event.preventDefault();
        selClose();
      }
    });
    native.addEventListener("change", function () { selRefresh(native); });

    if (window.MutationObserver) {
      new MutationObserver(function (mutations) {
        var reopen = false;
        mutations.forEach(function (m) {
          if (m.type === "childList") reopen = true;
        });
        selRefresh(native);
        if (reopen && selState.current && selState.current.native === native) {
          var entry = selState.current;
          entry.options = selOptions(native);
          entry.selected = selSelectedIndex(native);
          entry.active = entry.selected;
          selRenderList(entry);
          selPosition(btn);
        }
      }).observe(native, { childList: true, attributes: true, attributeFilter: ["class", "disabled"] });
    }
    selRefresh(native);
  }

  function insideFloat(t) {
    while (t && t !== document) {
      if (t.classList && (t.classList.contains("sel-list") || t.classList.contains("sel-btn"))) return true;
      if (t.tagName === "INPUT" && t.hasAttribute && t.hasAttribute("data-sug")) return true;
      t = t.parentNode;
    }
    return false;
  }

  document.addEventListener("click", function (event) {
    if (!selState.current && !sugState.list) return;
    if (!insideFloat(event.target)) {
      selClose();
      sugClose();
    }
  });
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && selState.current) {
      var entry = selState.current;
      selClose();
      if (entry.btn) entry.btn.focus();
    }
  });
  window.addEventListener("resize", function () { selClose(); sugClose(); });
  window.addEventListener("scroll", function (event) {
    if (event.target && event.target.closest && event.target.closest(".sel-list")) return;
    selClose();
    sugClose();
  }, true);

  Array.prototype.forEach.call(document.querySelectorAll("select.select"), function (native) { enhanceSelect(native); });
  if (window.MutationObserver) {
    new MutationObserver(function (mutations) {
      mutations.forEach(function (m) {
        var el = m.target;
        if (el.classList && el.classList.contains("dialog") && !el.classList.contains("hidden")) {
          Array.prototype.forEach.call(el.querySelectorAll("select.select"), function (native) { selRefresh(native); });
        }
      });
    }).observe(document.body, { subtree: true, attributes: true, attributeFilter: ["class"] });
  }

  /* Custom input suggestions — replaces native <datalist> popups (unstylable
     browser UI) with the same list UI as the custom selects. The <datalist>
     elements stay as live data sources; the `list` attribute is detached so
     the browser never shows its own popup. */
  var sugState = { input: null, list: null, items: [], active: -1 };

  function sugClose() {
    var input = sugState.input;
    if (sugState.list && sugState.list.parentNode) sugState.list.parentNode.removeChild(sugState.list);
    sugState.input = null;
    sugState.list = null;
    sugState.items = [];
    sugState.active = -1;
    if (input) input.setAttribute("aria-expanded", "false");
  }

  function sugSource(input) {
    var id = input.getAttribute("data-sug");
    var dl = id && document.getElementById(id);
    var out = [];
    if (dl) {
      Array.prototype.forEach.call(dl.querySelectorAll("option"), function (opt) {
        var v = opt.value || opt.textContent;
        if (v) out.push(v);
      });
    }
    return out;
  }

  function sugFilter(input) {
    var needle = (input.value || "").toLowerCase();
    return sugSource(input).filter(function (v) {
      return !needle || v.toLowerCase().indexOf(needle) !== -1;
    });
  }

  function sugPosition() {
    if (!sugState.list || !sugState.input) return;
    var rect = sugState.input.getBoundingClientRect();
    var listH = Math.min(sugState.list.scrollHeight, 220);
    var top = rect.bottom + 4;
    if (top + listH > window.innerHeight - 8) {
      top = Math.max(8, rect.top - listH - 4);
    }
    sugState.list.style.minWidth = rect.width + "px";
    sugState.list.style.maxWidth = Math.max(rect.width, 200) + "px";
    sugState.list.style.left = Math.min(rect.left, window.innerWidth - Math.max(rect.width, 200) - 8) + "px";
    sugState.list.style.top = top + "px";
  }

  function sugRender() {
    if (!sugState.list) return;
    sugState.list.innerHTML = "";
    if (!sugState.items.length) {
      var empty = document.createElement("div");
      empty.className = "sel-empty";
      empty.textContent = "No matches";
      sugState.list.appendChild(empty);
      return;
    }
    sugState.items.forEach(function (text, i) {
      var item = document.createElement("button");
      item.type = "button";
      item.className = "sel-opt";
      item.setAttribute("role", "option");
      item.setAttribute("aria-selected", "false");
      item.setAttribute("data-active", i === sugState.active ? "true" : "false");
      item.textContent = text;
      item.addEventListener("click", function (event) {
        event.stopPropagation();
        sugPick(i);
      });
      item.addEventListener("mousemove", function () {
        if (sugState.active !== i) {
          sugState.active = i;
          sugRender();
        }
      });
      sugState.list.appendChild(item);
    });
    var activeEl = sugState.list.querySelector('[data-active="true"]');
    if (activeEl && activeEl.scrollIntoView) {
      try { activeEl.scrollIntoView({ block: "nearest" }); } catch (e) {}
    }
  }

  function sugPick(i) {
    var input = sugState.input;
    var text = sugState.items[i];
    if (!input || text == null) return;
    sugClose();
    input.focus();
    sugState.justPicked = true;
    input.value = text;
    var event;
    try {
      event = new Event("input", { bubbles: true });
    } catch (e) {
      event = document.createEvent("HTMLEvents");
      event.initEvent("input", true, false);
    }
    input.dispatchEvent(event);
    try {
      event = new Event("change", { bubbles: true });
    } catch (e) {
      event = document.createEvent("HTMLEvents");
      event.initEvent("change", true, false);
    }
    input.dispatchEvent(event);
  }

  function sugOpen(input) {
    var items = sugFilter(input);
    if (!items.length && !sugSource(input).length) {
      if (sugState.input === input) sugClose();
      return;
    }
    if (sugState.list && sugState.input === input) {
      sugState.items = items;
      if (sugState.active >= items.length) sugState.active = items.length - 1;
      sugRender();
      sugPosition();
      return;
    }
    sugClose();
    sugState.input = input;
    sugState.items = items;
    sugState.active = -1;
    sugState.list = document.createElement("div");
    sugState.list.className = "sel-list";
    sugState.list.setAttribute("role", "listbox");
    document.body.appendChild(sugState.list);
    input.setAttribute("aria-expanded", "true");
    sugRender();
    sugPosition();
  }

  function sugMove(dir) {
    if (!sugState.list || !sugState.items.length) return;
    var n = sugState.items.length;
    sugState.active = (((sugState.active + dir) % n) + n) % n;
    sugRender();
    var rows = sugState.list.querySelectorAll(".sel-opt");
    var activeEl = rows[sugState.active];
    if (activeEl && activeEl.scrollIntoView) {
      try { activeEl.scrollIntoView({ block: "nearest" }); } catch (e) {}
    }
  }

  Array.prototype.forEach.call(document.querySelectorAll("input[list]"), function (input) {
    input.setAttribute("data-sug", input.getAttribute("list"));
    input.removeAttribute("list");
    input.setAttribute("autocomplete", "off");
    input.setAttribute("role", "combobox");
    input.setAttribute("aria-expanded", "false");
    input.setAttribute("aria-autocomplete", "list");
    input.addEventListener("focus", function () { sugOpen(input); });
    input.addEventListener("input", function () {
      if (sugState.justPicked) { sugState.justPicked = false; return; }
      if (!sugState.list || sugState.input !== input) sugOpen(input);
      else {
        sugState.items = sugFilter(input);
        sugState.active = -1;
        sugRender();
        sugPosition();
      }
    });
    input.addEventListener("keydown", function (event) {
      var openHere = sugState.list && sugState.input === input;
      if (event.key === "ArrowDown") {
        event.preventDefault();
        if (openHere) sugMove(1);
        else sugOpen(input);
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        if (openHere) sugMove(-1);
      } else if (event.key === "Enter") {
        if (openHere && sugState.active >= 0) {
          event.preventDefault();
          sugPick(sugState.active);
        }
      } else if (event.key === "Escape" && openHere) {
        event.preventDefault();
        sugClose();
      }
    });
    input.addEventListener("blur", function () {
      input.setAttribute("aria-expanded", "false");
    });
  });

  Object.keys(groupOpen).forEach(function (name) { setGroupOpen(name, groupOpen[name]); });

  var resizeTimer = null;
  window.addEventListener("resize", function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      renderTable();
      renderProbes();
    }, 150);
  });

  function showLogin() {
    var overlay = $("login-overlay");
    if (overlay) overlay.classList.remove("hidden");
    document.getElementById("layout").style.filter = "blur(2px)";
    document.querySelector(".header").style.filter = "blur(2px)";
  }
  function hideLogin() {
    var overlay = $("login-overlay");
    if (overlay) overlay.classList.add("hidden");
    document.getElementById("layout").style.filter = "";
    document.querySelector(".header").style.filter = "";
  }
  function updateAuthUI() {
    var user = state.currentUser;
    var wrap = $("user-menu-wrap");
    var initialsEl = $("user-initials");
    var dropUser = $("dropdown-username");
    var dropEmail = $("dropdown-email");
    var accessGroup = $("access-nav-group");
    if (user) {
      if (wrap) wrap.classList.remove("hidden");
      if (initialsEl) initialsEl.textContent = (user.username ? user.username[0].toUpperCase() : "U");
      if (dropUser) dropUser.textContent = user.username;
      if (dropEmail) dropEmail.textContent = user.email || "";
      if (accessGroup) {
        if (user.role === "ADMIN") accessGroup.classList.remove("hidden");
        else accessGroup.classList.add("hidden");
      }
    } else {
      if (wrap) wrap.classList.add("hidden");
      if (accessGroup) accessGroup.classList.add("hidden");

      var dd = $("user-dropdown");
      if (dd) dd.classList.add("hidden");
    }
    applyRolePermissions();
  }
  function applyRolePermissions() {
    var isReader = state.currentUser && state.currentUser.role === "READER";
    var writeSelectors = [
      "#add-monitor-btn", "#empty-add-monitor-btn", "#add-module-btn", "#add-job-btn", "#add-rule-btn",
      "#add-service-btn", "#add-rule-dir-btn", "#add-service-dir-btn", "#add-receiver-btn", "#add-inhibit-rule-btn",
      "#add-user-btn"
    ];
    writeSelectors.forEach(function (sel) {
      var el = document.querySelector(sel);
      if (el) {
        el.style.display = isReader ? "none" : "";
        el.disabled = isReader;
        if (isReader) el.title = "Read-only user";
      }
    });

    if (isReader) document.body.classList.add("reader-mode");
    else document.body.classList.remove("reader-mode");
  }
  function checkAuth() {
    return api("/api/auth/me", { method: "GET" })
      .then(function (text) {
        var data = JSON.parse(text);
        state.currentUser = data.user;
        hideLogin();
        updateAuthUI();

        refreshStatus();
        loadMonitors();
        loadModules();
        blackboxLogs.fetch(false);
        prometheusLogs.fetch(false);
        alertmanagerLogs.fetch(false);
        fetchProbes();
        if (state.currentUser && state.currentUser.role === "ADMIN") loadUsers();
        return data;
      })
      .catch(function () {
        state.currentUser = null;
        updateAuthUI();
        showLogin();

        $("monitors-tbody").innerHTML = "";
        throw new Error("not authenticated");
      });
  }

  var loginForm = $("login-form");
  if (loginForm) {
    loginForm.addEventListener("submit", function (e) {
      e.preventDefault();
      var u = $("login-username").value.trim();
      var p = $("login-password").value;
      var errEl = $("login-error");
      errEl.classList.add("hidden");
      errEl.textContent = "";
      var btn = $("login-btn");
      btn.disabled = true;
      btn.textContent = "Signing in...";
      api("/api/auth/login", { method: "POST", body: JSON.stringify({ username: u, password: p }) })
        .then(function (text) {
          var data = JSON.parse(text);
          state.currentUser = data.user;
          $("login-password").value = "";
          hideLogin();
          updateAuthUI();
          toast("Signed in as " + data.user.username);
          refreshStatus();
          loadMonitors();
          loadModules();
          blackboxLogs.fetch(false);
          prometheusLogs.fetch(false);
          alertmanagerLogs.fetch(false);
          fetchProbes();
          if (state.currentUser.role === "ADMIN") loadUsers();
        })
        .catch(function (err) {
          errEl.textContent = err.message || "Login failed";
          errEl.classList.remove("hidden");
        })
        .finally(function () {
          btn.disabled = false;
          btn.textContent = "Sign in";
        });
    });
  }
  var userDp = $("user-dp");
  if (userDp) {
    userDp.addEventListener("click", function (e) {
      e.stopPropagation();
      var dd = $("user-dropdown");
      if (dd) dd.classList.toggle("hidden");
    });
  }
  document.addEventListener("click", function (e) {
    var wrap = $("user-menu-wrap");
    var dd = $("user-dropdown");
    if (wrap && dd && !wrap.contains(e.target)) {
      dd.classList.add("hidden");
    }
  });
  var ddLogout = $("dropdown-logout");
  if (ddLogout) {
    ddLogout.addEventListener("click", function () {
      var dd = $("user-dropdown");
      if (dd) dd.classList.add("hidden");
      api("/api/auth/logout", { method: "POST" })
        .then(function () {
          state.currentUser = null;
          updateAuthUI();
          showLogin();
          toast("Signed out");
        })
        .catch(function () {
          state.currentUser = null;
          updateAuthUI();
          showLogin();
        });
    });
  }

  var _origApi = api;

  api = function (path, options) {
    return _origApi(path, options).catch(function (err) {
      if (err.message && (err.message.indexOf("not authenticated") !== -1 || err.message.indexOf("Unauthorized") !== -1)) {
        state.currentUser = null;
        updateAuthUI();
        showLogin();
      }
      throw err;
    });
  };

  var editingUserId = null;
  function loadUsers() {
    if (!state.currentUser || state.currentUser.role !== "ADMIN") return;
    return api("/api/users", { method: "GET" })
      .then(function (text) {
        var data = JSON.parse(text);
        renderUsers(data.users || []);
      })
      .catch(function (err) {  });
  }
  function renderUsers(users) {
    var tbody = $("users-tbody");
    if (!tbody) return;
    tbody.innerHTML = "";
    var q = ($("user-search") ? $("user-search").value.trim().toLowerCase() : "");
    var filtered = users.filter(function (u) {
      if (!q) return true;
      return (u.username + " " + (u.email || "") + " " + u.role).toLowerCase().indexOf(q) !== -1;
    });
    filtered.forEach(function (u) {
      var tr = document.createElement("tr");
      var tdUser = document.createElement("td");
      tdUser.textContent = u.username;
      tdUser.className = "mono";
      tr.appendChild(tdUser);
      var tdEmail = document.createElement("td");
      tdEmail.textContent = u.email || "-";
      tdEmail.className = "text-muted mono";
      tdEmail.style.fontSize = "12px";
      tr.appendChild(tdEmail);
      var tdRole = document.createElement("td");
      var badge = document.createElement("span");
      badge.className = "badge " + (u.role === "ADMIN" ? "ok" : "muted");
      badge.textContent = u.role;
      tdRole.appendChild(badge);
      tr.appendChild(tdRole);
      var tdCreated = document.createElement("td");
      tdCreated.textContent = u.createdAt ? new Date(u.createdAt).toLocaleDateString() : "-";
      tdCreated.className = "text-muted";
      tr.appendChild(tdCreated);
      var tdActions = document.createElement("td");
      tdActions.className = "col-actions-cell";
      var kebab = document.createElement("button");
      kebab.className = "row-action kebab";
      kebab.innerHTML = '<svg class="icon" viewBox="0 0 24 24"><circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/></svg>';
      kebab.addEventListener("click", function (e) {
        e.stopPropagation();
        openUserRowMenu(kebab, u);
      });
      tdActions.appendChild(kebab);
      tr.appendChild(tdActions);
      tbody.appendChild(tr);
    });
    if (!filtered.length) {
      var tr2 = document.createElement("tr");
      tr2.className = "empty-row";
      var td2 = document.createElement("td");
      td2.colSpan = 5;
      td2.textContent = users.length ? 'No users match "' + q + '"' : "No users";
      tr2.appendChild(td2);
      tbody.appendChild(tr2);
    }
    var countEl = $("users-count");
    if (countEl) countEl.textContent = filtered.length + " of " + users.length + " user(s)";
  }
  function openUserRowMenu(anchor, user) {
    var items = [];
    items.push({
      label: "Edit",
      onClick: function () { openUserDialog(user); }
    });
    if (state.currentUser && state.currentUser.id !== user.id) {
      items.push({
        label: "Delete",
        danger: true,
        onClick: function () {
          uiConfirm('Delete user "' + user.username + '"?').then(function (ok) {
            if (ok) {
              api("/api/users/" + user.id, { method: "DELETE" })
                .then(function (msg) { toast(extractMessage(msg)); return loadUsers(); })
                .catch(function (err) { toast(err.message, true); });
            }
          });
        }
      });
    }
    openRowMenuAt(anchor, items);
  }
  function openUserDialog(user) {
    var isEdit = !!user;
    editingUserId = isEdit ? user.id : null;
    $("user-dialog-title").textContent = isEdit ? "Edit user" : "Add user";
    $("user-dialog-subtitle").textContent = isEdit ? "Update email, role or password." : "Create a new user. Readers can only view.";
    $("f-user-username").value = isEdit ? user.username : "";
    $("f-user-username").disabled = isEdit;
    $("f-user-email").value = isEdit ? (user.email || "") : "";
    $("f-user-password").value = "";
    $("f-user-password").placeholder = isEdit ? "Leave empty to keep" : "••••";
    $("f-user-role").value = isEdit ? user.role : "READER";
    $("f-user-delete").classList.toggle("hidden", !isEdit || (state.currentUser && state.currentUser.id === user.id));
    $("f-user-save").textContent = isEdit ? "Save changes" : "Create user";
    $("user-error").classList.add("hidden");
    $("dialog-overlay").classList.remove("hidden");
    $("user-dialog").classList.remove("hidden");
    $("f-user-username").focus();
  }
  var userSearch = $("user-search");
  if (userSearch) userSearch.addEventListener("input", function () { loadUsers(); });
  var addUserBtn = $("add-user-btn");
  if (addUserBtn) addUserBtn.addEventListener("click", function () { openUserDialog(null); });
  var userCancel = $("f-user-cancel");
  if (userCancel) userCancel.addEventListener("click", closeDialog);
  var userDelete = $("f-user-delete");
  if (userDelete) userDelete.addEventListener("click", function () {
    if (!editingUserId) return;
    uiConfirm("Delete this user?").then(function (ok) {
      if (!ok) return;
      api("/api/users/" + editingUserId, { method: "DELETE" })
        .then(function (msg) { toast(extractMessage(msg)); closeDialog(); return loadUsers(); })
        .catch(function (err) { var el = $("user-error"); el.textContent = err.message; el.classList.remove("hidden"); });
    });
  });
  var userForm = $("user-form");
  if (userForm) {
    userForm.addEventListener("submit", function (e) {
      e.preventDefault();
      var errEl = $("user-error");
      errEl.classList.add("hidden");
      var username = $("f-user-username").value.trim();
      var email = $("f-user-email").value.trim();
      var password = $("f-user-password").value;
      var role = $("f-user-role").value;
      if (!username) { errEl.textContent = "Username required"; errEl.classList.remove("hidden"); return; }
      if (!email) { errEl.textContent = "Email required"; errEl.classList.remove("hidden"); return; }
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { errEl.textContent = "Invalid email"; errEl.classList.remove("hidden"); return; }
      var btn = $("f-user-save");
      btn.disabled = true;
      btn.textContent = "Saving...";
      var payload = {};
      if (!editingUserId) {
        payload.username = username;
        payload.email = email;
        payload.password = password;
        payload.role = role;
        if (!password) { errEl.textContent = "Password required"; errEl.classList.remove("hidden"); btn.disabled=false; btn.textContent="Create user"; return; }
        api("/api/users", { method: "POST", body: JSON.stringify(payload) })
          .then(function (msg) { toast(extractMessage(msg)); closeDialog(); return loadUsers(); })
          .catch(function (err) { errEl.textContent = err.message; errEl.classList.remove("hidden"); })
          .finally(function () { btn.disabled=false; btn.textContent="Create user"; });
      } else {
        if (username !== $("f-user-username").value.trim()) payload.username = username;

        payload.email = email;
        if (password) payload.password = password;
        payload.role = role;
        api("/api/users/" + editingUserId, { method: "PUT", body: JSON.stringify(payload) })
          .then(function (msg) { toast(extractMessage(msg)); closeDialog(); return loadUsers(); })
          .catch(function (err) { errEl.textContent = err.message; errEl.classList.remove("hidden"); })
          .finally(function () { btn.disabled=false; btn.textContent="Save changes"; });
      }
    });
  }

  var gitAction = "push";
  function loadChangelog() {
    var tbody = $("changelog-tbody");
    if (tbody) tbody.innerHTML = '<tr class="empty-row"><td colspan="4">loading...</td></tr>';
    loadGitStatus();
    return api("/git/log").then(function (text) {
      var data = JSON.parse(text);
      renderChangelog(data.commits || []);
    }).catch(function (err) { toast(err.message, true); });
  }
  function loadGitStatus() {
    return api("/git/status").then(function (text) {
      var data = JSON.parse(text);
      var branchEl = $("git-branch");
      if (branchEl) branchEl.textContent = data.branch ? "branch: " + data.branch : "";
      var remoteEl = $("git-remote");
      if (remoteEl) remoteEl.textContent = data.remote ? data.remote : "";
      var statusEl = $("changelog-status");
      if (statusEl) statusEl.textContent = data.hasChanges ? "uncommitted changes" : data.lastCommit ? "last: " + data.lastCommit.substring(0,7) : "";
    }).catch(function () {});

    api("/git/remote").then(function (text) {
      try {
        var d = JSON.parse(text);
        if (d.remoteUrl && $("f-git-url") && !$("f-git-url").value) $("f-git-url").value = d.remoteUrl;
        if (d.branch && $("f-git-branch") && !$("f-git-branch").value) $("f-git-branch").value = d.branch;
        if (d.username && $("f-git-username") && !$("f-git-username").value) $("f-git-username").value = d.username;
      } catch(e){}
    }).catch(function(){});
  }
  function renderChangelog(commits) {
    var tbody = $("changelog-tbody");
    if (!tbody) return;
    tbody.innerHTML = "";
    if (!commits.length) {
      var tr = document.createElement("tr");
      tr.className = "empty-row";
      var td = document.createElement("td");
      td.colSpan = 5;
      td.textContent = "No commits yet — changes will appear after you edit configs.";
      tr.appendChild(td);
      tbody.appendChild(tr);
    } else {
      commits.forEach(function (c) {
        var tr = document.createElement("tr");
        var tdHash = document.createElement("td");
        tdHash.className = "mono";
        tdHash.style.fontSize = "12px";
        tdHash.textContent = c.shortHash || c.hash.substring(0,7);
        tdHash.title = c.hash;
        tr.appendChild(tdHash);
        var tdMsg = document.createElement("td");
        tdMsg.textContent = c.message;
        tdMsg.title = c.message;
        tdMsg.style.maxWidth = "320px";
        tdMsg.style.overflow = "hidden";
        tdMsg.style.textOverflow = "ellipsis";
        tdMsg.style.whiteSpace = "nowrap";
        tr.appendChild(tdMsg);
        var tdAuthor = document.createElement("td");
        tdAuthor.textContent = c.author;
        tdAuthor.className = "text-muted";
        tdAuthor.style.fontSize = "12px";
        tr.appendChild(tdAuthor);
        var tdDate = document.createElement("td");
        tdDate.textContent = c.date ? new Date(c.date).toLocaleString() : "";
        tdDate.className = "text-muted";
        tdDate.style.fontSize = "12px";
        tdDate.style.whiteSpace = "nowrap";
        tr.appendChild(tdDate);
        var tdAct = document.createElement("td");
        tdAct.className = "col-actions-cell";
        var applyBtn = document.createElement("button");
        applyBtn.className = "btn ghost sm";
        applyBtn.textContent = "Apply";
        applyBtn.title = "Apply this commit to top (cherry-pick)";
        applyBtn.addEventListener("click", function () {
          uiConfirm("Apply commit " + (c.shortHash || c.hash.substring(0, 7)) + " to top?\n\n" + (c.message || ""), { title: "Apply commit", okLabel: "Apply", danger: false }).then(function (ok) {
            if (!ok) return;
            applyBtn.disabled = true;
            applyBtn.textContent = "Applying...";
          api("/git/apply", { method: "POST", body: JSON.stringify({ hash: c.hash }) })
            .then(function (text) {
              var data = JSON.parse(text);
              if (data.status === "error") throw new Error(data.message);
              toast(data.message || "Applied");
              loadChangelog();
            })
            .catch(function (err) { toast(err.message, true); })
            .finally(function () { applyBtn.disabled = false; applyBtn.textContent = "Apply"; });
          });
        });
        tdAct.appendChild(applyBtn);
        tr.appendChild(tdAct);
        tbody.appendChild(tr);
      });
    }
    var countEl = $("changelog-count");
    if (countEl) countEl.textContent = commits.length + " commit(s)";
  }

  function openGitDialog(action) {
    gitAction = action;
    var title = $("git-dialog-title");
    var subtitle = $("git-dialog-subtitle");
    var submit = $("f-git-submit");
    if (action === "push") {
      if (title) title.textContent = "Push to remote";
      if (subtitle) subtitle.textContent = "Push local commits to remote. Token is sent securely and not logged.";
      if (submit) submit.textContent = "Push";
    } else {
      if (title) title.textContent = "Pull from remote";
      if (subtitle) subtitle.textContent = "Pull and merge remote commits. Provide same remote if not yet configured.";
      if (submit) submit.textContent = "Pull";
    }

    api("/git/remote").then(function (text) {
      try {
        var d = JSON.parse(text);
        if (d.remoteUrl) $("f-git-url").value = d.remoteUrl;
        if (d.branch) $("f-git-branch").value = d.branch || "main";
        if (d.username) $("f-git-username").value = d.username;
      } catch(e){}
    }).catch(function(){});
    $("git-error").classList.add("hidden");
    $("dialog-overlay").classList.remove("hidden");
    $("git-dialog").classList.remove("hidden");
    $("f-git-url").focus();
  }
  function closeGitDialog() {
    if ($("git-dialog")) $("git-dialog").classList.add("hidden");

    var anyOpen = ["module-dialog","job-dialog","file-dialog","receiver-dialog","route-dialog","inhibit-dialog","monitor-dialog","user-dialog","git-dialog"].some(function(id){ var el=$(id); return el && !el.classList.contains("hidden"); });
    if (!anyOpen) $("dialog-overlay").classList.add("hidden");
  }

  if ($("changelog-refresh-btn")) $("changelog-refresh-btn").addEventListener("click", function(){ loadChangelog(); toast("Changelog refreshed"); });
  if ($("git-push-btn")) $("git-push-btn").addEventListener("click", function(){ openGitDialog("push"); });
  if ($("git-pull-btn")) $("git-pull-btn").addEventListener("click", function(){ openGitDialog("pull"); });
  if ($("f-git-cancel")) $("f-git-cancel").addEventListener("click", function(){ closeGitDialog(); });

  (function(){
    var origClose = closeDialog;

  })();
  if ($("git-form")) $("git-form").addEventListener("submit", function(e){
    e.preventDefault();
    var btn = $("f-git-submit");
    var errEl = $("git-error");
    errEl.classList.add("hidden");
    var payload = {
      remoteUrl: $("f-git-url").value.trim(),
      branch: $("f-git-branch").value.trim() || "main",
      username: $("f-git-username").value.trim(),
      token: $("f-git-token").value
    };
    if (gitAction === "push" && !payload.remoteUrl) { errEl.textContent = "Remote URL required"; errEl.classList.remove("hidden"); return; }
    if (gitAction === "push" && !payload.token) {

    }
    btn.disabled = true;
    btn.textContent = gitAction === "push" ? "Pushing..." : "Pulling...";
    var path = gitAction === "push" ? "/git/push" : "/git/pull";
    api(path, { method: "POST", body: JSON.stringify(payload) })
      .then(function (text) {
        var data = JSON.parse(text);
        if (data.status === "error") throw new Error(data.message || "failed");
        toast(extractMessage(text) || (gitAction==="push" ? "Pushed" : "Pulled"));
        closeGitDialog();
        loadChangelog();
      })
      .catch(function (err) { errEl.textContent = err.message; errEl.classList.remove("hidden"); })
      .finally(function () { btn.disabled=false; btn.textContent = gitAction === "push" ? "Push" : "Pull"; });
  });

  document.addEventListener("keydown", function(e){ if(e.key==="Escape" && $("git-dialog") && !$("git-dialog").classList.contains("hidden")) closeGitDialog(); });

  checkAuth().catch(function () {

  });

  setInterval(function () {
    if (state.currentUser) refreshStatus();
  }, 30000);
  setInterval(function () { if (state.currentUser && activeComponent === "monitors") loadMonitorsStatus(); }, 30000);

})();
