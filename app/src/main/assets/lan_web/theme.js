(function () {
  var KEY = "pcm-theme";
  var root = document.documentElement;
  function read() { try { return localStorage.getItem(KEY) || "auto"; } catch (e) { return "auto"; } }
  function apply(v) {
    if (v === "light" || v === "dark") root.setAttribute("data-theme", v);
    else root.removeAttribute("data-theme");
  }
  var cur = read();
  apply(cur);

  var OPTS = [
    ["auto", "Auto", "\u25D0"],
    ["light", "Light", "\u2600"],
    ["dark", "Dark", "\u263E"]
  ];
  var switches = [];
  function sync() {
    switches.forEach(function (sw) {
      sw.querySelectorAll("button").forEach(function (b) {
        b.setAttribute("aria-pressed", String(b.dataset.theme === cur));
      });
    });
  }
  function build() {
    var sw = document.createElement("div");
    sw.className = "themeSw";
    sw.setAttribute("role", "group");
    sw.setAttribute("aria-label", "Theme");
    OPTS.forEach(function (o) {
      var b = document.createElement("button");
      b.type = "button";
      b.dataset.theme = o[0];
      b.setAttribute("aria-label", o[1]);
      b.title = o[1];
      b.innerHTML = '<span class="ico" aria-hidden="true">' + o[2] + '</span><span class="lbl">' + o[1] + "</span>";
      b.addEventListener("click", function () {
        cur = o[0];
        apply(cur);
        try { localStorage.setItem(KEY, cur); } catch (e) {}
        sync();
      });
      sw.appendChild(b);
    });
    switches.push(sw);
    return sw;
  }
  document.addEventListener("DOMContentLoaded", function () {
    var lo = document.getElementById("logoutBtn");
    if (lo) {
      lo.setAttribute("aria-label", "Log out");
      lo.insertAdjacentHTML("beforeend", '<svg class="logoutIco" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="M16 17l5-5-5-5"/><path d="M21 12H9"/></svg>');
    }
    document.querySelectorAll(".topActions").forEach(function (box) {
      box.insertBefore(build(), document.getElementById("logoutBtn"));
      var top = build(); // phone width: shown in the title row instead
      top.classList.add("themeTop");
      box.parentNode.appendChild(top);
    });
    var login = document.getElementById("loginView");
    if (login) { var s = build(); s.classList.add("loginTheme"); login.insertBefore(s, login.firstChild); }
    sync();
  });
})();
