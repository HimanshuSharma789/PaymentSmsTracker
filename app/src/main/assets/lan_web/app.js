(() => {
  const TOKEN_KEY = "lan_token";
  let token = sessionStorage.getItem(TOKEN_KEY) || "";
  let meta = null;
  let openAccess = false;
  let currentFilter = "needs_review";
  let editingId = null;
  let items = [];
  let selectMode = false;
  const selectedIds = new Set();

  const loginView = document.getElementById("loginView");
  const mainView = document.getElementById("mainView");
  const editView = document.getElementById("editView");
  const listEl = document.getElementById("list");
  const listEmpty = document.getElementById("listEmpty");
  const form = document.getElementById("editForm");
  const editError = document.getElementById("editError");
  const loginError = document.getElementById("loginError");
  const allocationBox = document.getElementById("allocationBox");
  const bulkBar = document.getElementById("bulkBar");
  const bulkCount = document.getElementById("bulkCount");
  const selectModeBtn = document.getElementById("selectModeBtn");
  const toastEl = document.getElementById("toast");
  let toastTimer = null;

  function showToast(text, kind = "ok", durationMs = 2600) {
    if (!toastEl) return;
    if (toastTimer) {
      clearTimeout(toastTimer);
      toastTimer = null;
    }
    if (!text) {
      toastEl.classList.remove("show");
      toastEl.hidden = true;
      return;
    }
    toastEl.hidden = false;
    toastEl.textContent = text;
    toastEl.className = `toast ${kind}`;
    requestAnimationFrame(() => toastEl.classList.add("show"));
    toastTimer = setTimeout(() => {
      toastEl.classList.remove("show");
      toastTimer = setTimeout(() => {
        toastEl.hidden = true;
        toastTimer = null;
      }, 200);
    }, durationMs);
  }

  function refreshBulkBar() {
    const n = selectedIds.size;
    bulkBar.hidden = !selectMode || n === 0;
    bulkCount.textContent = n === 1 ? "1 selected" : `${n} selected`;
    selectModeBtn.textContent = selectMode ? "Done" : "Select";
    selectModeBtn.classList.toggle("primary", selectMode);
    selectModeBtn.classList.toggle("secondary", !selectMode);
  }

  function setSelectMode(on) {
    selectMode = on;
    if (!on) selectedIds.clear();
    refreshBulkBar();
    renderList();
  }

  async function api(path, options = {}) {
    const headers = Object.assign({ "Content-Type": "application/json" }, options.headers || {});
    if (token) headers.Authorization = `Bearer ${token}`;
    const res = await fetch(path, Object.assign({}, options, { headers }));
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (_) { data = { error: text }; }
    if (!res.ok) {
      const err = new Error((data && data.error) || res.statusText || "Request failed");
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }

  function show(view) {
    loginView.hidden = view !== "login";
    mainView.hidden = view !== "main";
    editView.hidden = view !== "edit";
  }

  function money(n) {
    return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR" }).format(n || 0);
  }

  function ymd(ts) {
    const d = new Date(ts);
    const m = `${d.getMonth() + 1}`.padStart(2, "0");
    const day = `${d.getDate()}`.padStart(2, "0");
    return `${d.getFullYear()}-${m}-${day}`;
  }

  function ymdHm(ts) {
    const d = new Date(ts);
    const m = `${d.getMonth() + 1}`.padStart(2, "0");
    const day = `${d.getDate()}`.padStart(2, "0");
    const hh = `${d.getHours()}`.padStart(2, "0");
    const mm = `${d.getMinutes()}`.padStart(2, "0");
    return `${d.getFullYear()}-${m}-${day}T${hh}:${mm}`;
  }

  function formatTime(ts) {
    return new Date(ts).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  }

  function parseAllocations(raw) {
    const map = {};
    if (!raw) return map;
    String(raw).split(/[,|]/).forEach((part) => {
      const p = part.trim();
      if (!p) return;
      const sep = p.includes("=") ? "=" : ":";
      const i = p.indexOf(sep);
      if (i <= 0) return;
      const name = p.slice(0, i).trim();
      const value = p.slice(i + 1).trim();
      if (name) map[name] = value;
    });
    return map;
  }

  function selectedParticipants() {
    return Array.from(document.querySelectorAll("#participantChips input:checked")).map((el) => el.value);
  }

  function rebuildAllocations() {
    const mode = form.splitMode.value;
    const people = selectedParticipants();
    const needs = mode !== "equal" && people.length > 0;
    allocationBox.hidden = !needs;
    if (!needs) {
      allocationBox.innerHTML = "";
      return;
    }
    const existing = {};
    allocationBox.querySelectorAll("input[data-person]").forEach((input) => {
      existing[input.dataset.person] = input.value;
    });
    const fromTx = parseAllocations(form.dataset.alloc || "");
    allocationBox.innerHTML = people.map((name) => {
      const val = existing[name] ?? fromTx[name] ?? (mode === "weight" ? "1" : "");
      const hint = mode === "percent" ? "%" : mode === "weight" ? "shares" : "₹";
      return `<div class="allocRow"><span>${name}</span><input data-person="${name}" type="number" step="any" placeholder="${hint}" value="${val}" /></div>`;
    }).join("");
  }

  function fillMetaControls() {
    const accountSelect = document.getElementById("accountSelect");
    const categorySelect = document.getElementById("categorySelect");
    accountSelect.innerHTML = (meta.accounts || []).map((a) => `<option value="${a}">${a}</option>`).join("");
    const cats = Object.keys(meta.expenseCategories || {});
    categorySelect.innerHTML = [`<option value="">Uncategorized</option>`]
      .concat(cats.map((c) => `<option value="${c}">${c}</option>`)).join("");
    const parts = document.getElementById("participantChips");
    parts.innerHTML = (meta.participants || []).map((p) =>
      `<label><input type="checkbox" value="${p}" /> ${p}</label>`
    ).join("");
    parts.querySelectorAll("input").forEach((input) => {
      input.addEventListener("change", rebuildAllocations);
    });
  }

  function fillSubcategories() {
    const cat = form.category.value;
    const subs = (meta.expenseCategories && meta.expenseCategories[cat]) || [];
    const sel = document.getElementById("subcategorySelect");
    const current = sel.value;
    sel.innerHTML = [`<option value=""></option>`]
      .concat(subs.map((s) => `<option value="${s}">${s}</option>`)).join("");
    if (subs.includes(current)) sel.value = current;
  }

  function fillFilterControls() {
    const accountSel = document.getElementById("filterAccount");
    const categorySel = document.getElementById("filterCategory");
    const subSel = document.getElementById("filterSubcategory");
    if (!accountSel || !categorySel || !meta) return;
    const prevAccount = accountSel.value;
    const prevCategory = categorySel.value;
    const prevSub = subSel.value;
    accountSel.innerHTML = `<option value="">All accounts</option>` +
      (meta.accounts || []).map((a) => `<option value="${escapeHtml(a)}">${escapeHtml(a)}</option>`).join("");
    const cats = Object.keys(meta.expenseCategories || {});
    categorySel.innerHTML = `<option value="">All categories</option>` +
      cats.map((c) => `<option value="${escapeHtml(c)}">${escapeHtml(c)}</option>`).join("") +
      `<option value="__uncategorized__">Uncategorized</option>`;
    if ((meta.accounts || []).includes(prevAccount)) accountSel.value = prevAccount;
    if (prevCategory === "__uncategorized__" || cats.includes(prevCategory)) categorySel.value = prevCategory;
    fillFilterSubcategories(prevSub);
  }

  function fillFilterSubcategories(preferred) {
    const categorySel = document.getElementById("filterCategory");
    const subSel = document.getElementById("filterSubcategory");
    if (!categorySel || !subSel || !meta) return;
    const cat = categorySel.value;
    const subs = (cat && cat !== "__uncategorized__" && meta.expenseCategories)
      ? (meta.expenseCategories[cat] || [])
      : [];
    subSel.innerHTML = `<option value="">All subcategories</option>` +
      subs.map((s) => `<option value="${escapeHtml(s)}">${escapeHtml(s)}</option>`).join("");
    subSel.disabled = !cat || cat === "__uncategorized__" || subs.length === 0;
    if (preferred && subs.includes(preferred)) subSel.value = preferred;
  }

  function matchesTextSearch(tx, q) {
    if (!q) return true;
    const people = Array.isArray(tx.participants) ? tx.participants.join(" ") : "";
    const hay = [
      tx.merchantName,
      tx.notes,
      tx.account,
      tx.category,
      tx.subcategory,
      tx.reviewStatus,
      tx.referenceNumber,
      tx.originalSms,
      people,
      String(tx.amount ?? "")
    ].join(" ").toLowerCase();
    return q.split(/\s+/).filter(Boolean).every((term) => hay.includes(term));
  }

  function filteredItems() {
    const q = (document.getElementById("searchInput").value || "").trim().toLowerCase();
    const account = document.getElementById("filterAccount")?.value || "";
    const category = document.getElementById("filterCategory")?.value || "";
    const subcategory = document.getElementById("filterSubcategory")?.value || "";
    return items.filter((tx) => {
      if (!matchesTextSearch(tx, q)) return false;
      if (account && (tx.account || "") !== account) return false;
      if (category === "__uncategorized__") {
        if ((tx.category || "").trim()) return false;
      } else if (category && (tx.category || "") !== category) {
        return false;
      }
      if (subcategory && (tx.subcategory || "") !== subcategory) return false;
      return true;
    });
  }

  function updateFilterCount(shown, total) {
    const el = document.getElementById("filterCount");
    if (!el) return;
    el.textContent = shown === total ? `${total} shown` : `${shown} of ${total}`;
  }

  function renderList() {
    const filtered = filteredItems();
    listEmpty.hidden = filtered.length > 0;
    updateFilterCount(filtered.length, items.length);
    listEl.innerHTML = filtered.map((tx) => {
      const metaLine = [tx.account, tx.category].filter(Boolean).join(" · ") || "Uncategorized";
      const st = tx.reviewStatus || "needs_review";
      const status = currentFilter ? "" : `<div class="status ${escapeHtml(st)}">${escapeHtml(st)}</div>`;
      const checked = selectedIds.has(String(tx.id));
      const check = selectMode
        ? `<input type="checkbox" class="itemCheck" ${checked ? "checked" : ""} aria-label="Select" />`
        : "";
      return `<article class="item${selectMode ? " selectMode" : ""}${checked ? " selected" : ""}" data-id="${tx.id}" data-status="${escapeHtml(st)}">
        ${check}
        <div>
          <div class="merchant">${escapeHtml(tx.merchantName)}</div>
          <div class="meta">${escapeHtml(metaLine)} · ${ymd(tx.timestamp)} ${formatTime(tx.timestamp)}</div>
        </div>
        <div>
          <div class="amount">${money(tx.amount)}</div>
          ${status}
        </div>
      </article>`;
    }).join("");
    listEl.querySelectorAll(".item").forEach((el) => {
      el.addEventListener("click", (ev) => {
        const id = String(el.dataset.id);
        if (selectMode) {
          if (ev.target.classList.contains("itemCheck")) return;
          const box = el.querySelector(".itemCheck");
          if (!box) return;
          box.checked = !box.checked;
          if (box.checked) selectedIds.add(id);
          else selectedIds.delete(id);
          el.classList.toggle("selected", box.checked);
          refreshBulkBar();
          return;
        }
        openEdit(Number(id));
      });
      el.querySelector(".itemCheck")?.addEventListener("change", (ev) => {
        const id = String(el.dataset.id);
        if (ev.target.checked) selectedIds.add(id);
        else selectedIds.delete(id);
        el.classList.toggle("selected", ev.target.checked);
        refreshBulkBar();
      });
    });
    refreshBulkBar();
  }

  function escapeHtml(s) {
    return String(s || "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  async function refreshStatus() {
    try {
      const st = await api("/api/status");
      const mins = Math.ceil((st.remainingMs || 0) / 60000);
      document.getElementById("serverMeta").textContent =
        st.running ? `Server open · ~${mins} min left` : "Server stopped";
    } catch (_) {
      document.getElementById("serverMeta").textContent = "Server unavailable";
    }
  }

  async function loadList() {
    const qs = currentFilter ? `?status=${encodeURIComponent(currentFilter)}` : "";
    const data = await api(`/api/transactions${qs}`);
    items = data.items || [];
    fillFilterControls();
    renderList();
    refreshStatus();
  }

  async function openEdit(id) {
    editingId = id;
    editError.hidden = true;
    document.getElementById("editTitle").textContent = id ? "Edit transaction" : "New transaction";
    document.getElementById("reviewBtn").hidden = !id;
    document.getElementById("deleteBtn").hidden = !id;
    form.reset();
    form.dataset.alloc = "";
    fillMetaControls();
    if (id) {
      const tx = await api(`/api/transactions/${id}`);
      form.amount.value = tx.amount;
      form.merchantName.value = tx.merchantName || "";
      form.account.value = tx.account || "";
      form.transactionType.value = tx.transactionType || "Expense";
      form.category.value = tx.category || "";
      fillSubcategories();
      form.subcategory.value = tx.subcategory || "";
      form.date.value = ymdHm(tx.timestamp);
      form.notes.value = tx.notes || "";
      form.splitMode.value = tx.splitMode || "equal";
      form.dataset.alloc = tx.splitAllocations || "";
      const set = new Set(tx.participants || []);
      document.querySelectorAll("#participantChips input").forEach((input) => {
        input.checked = set.has(input.value);
      });
    } else {
      form.date.value = ymdHm(Date.now());
      fillSubcategories();
      const me = document.querySelector('#participantChips input[value="ME"]');
      if (me) me.checked = true;
    }
    rebuildAllocations();
    show("edit");
  }

  function collectBody() {
    const people = selectedParticipants();
    const mode = form.splitMode.value;
    const alloc = {};
    allocationBox.querySelectorAll("input[data-person]").forEach((input) => {
      if (input.value !== "") alloc[input.dataset.person] = Number(input.value);
    });
    const d = form.date.value ? new Date(form.date.value) : new Date();
    return {
      amount: Number(form.amount.value),
      merchantName: form.merchantName.value.trim(),
      account: form.account.value,
      transactionType: form.transactionType.value,
      category: form.category.value,
      subcategory: form.subcategory.value,
      notes: form.notes.value.trim(),
      timestamp: Number.isNaN(d.getTime()) ? Date.now() : d.getTime(),
      participants: people,
      splitMode: people.length ? mode : "equal",
      splitAllocations: alloc
    };
  }

  async function boot() {
    document.getElementById("loginBtn").onclick = async () => {
      loginError.hidden = true;
      try {
        const code = document.getElementById("codeInput").value.trim();
        const data = await api("/api/login", { method: "POST", body: JSON.stringify({ code }) });
        token = data.token;
        sessionStorage.setItem(TOKEN_KEY, token);
        meta = await api("/api/meta");
        fillFilterControls();
        show("main");
        await loadList();
      } catch (e) {
        loginError.hidden = false;
        loginError.textContent = e.message || "Login failed";
      }
    };

    document.querySelectorAll(".filters .chip").forEach((btn) => {
      btn.addEventListener("click", async () => {
        document.querySelectorAll(".filters .chip").forEach((b) => b.classList.remove("active"));
        btn.classList.add("active");
        currentFilter = btn.dataset.status || "";
        selectedIds.clear();
        await loadList();
      });
    });

    document.getElementById("searchInput").addEventListener("input", renderList);
    document.getElementById("filterAccount").addEventListener("change", renderList);
    document.getElementById("filterCategory").addEventListener("change", () => {
      fillFilterSubcategories("");
      renderList();
    });
    document.getElementById("filterSubcategory").addEventListener("change", renderList);
    document.getElementById("clearFiltersBtn").onclick = () => {
      document.getElementById("searchInput").value = "";
      document.getElementById("filterAccount").value = "";
      document.getElementById("filterCategory").value = "";
      fillFilterSubcategories("");
      renderList();
    };
    selectModeBtn.onclick = () => setSelectMode(!selectMode);
    document.getElementById("bulkReviewBtn").onclick = async () => {
      const ids = Array.from(selectedIds).map(Number).filter((n) => n > 0);
      if (!ids.length) return;
      try {
        const res = await api("/api/transactions/bulk", {
          method: "POST",
          body: JSON.stringify({ action: "review", ids })
        });
        showToast(`Marked ${res.count || ids.length} as reviewed`, "ok");
        selectedIds.clear();
        setSelectMode(false);
        await loadList();
      } catch (e) {
        showToast(e.message || "Mark reviewed failed", "err", 4200);
      }
    };
    document.getElementById("bulkDeleteBtn").onclick = async () => {
      const ids = Array.from(selectedIds).map(Number).filter((n) => n > 0);
      if (!ids.length) return;
      if (!confirm(`Delete ${ids.length} selected transaction(s)? This cannot be undone.`)) return;
      try {
        const res = await api("/api/transactions/bulk", {
          method: "POST",
          body: JSON.stringify({ action: "delete", ids })
        });
        showToast(`Deleted ${res.count || ids.length}`, "ok");
        selectedIds.clear();
        setSelectMode(false);
        await loadList();
      } catch (e) {
        showToast(e.message || "Delete failed", "err", 4200);
      }
    };
    document.getElementById("bulkClearBtn").onclick = () => {
      selectedIds.clear();
      renderList();
    };
    document.getElementById("newBtn").onclick = () => {
      setSelectMode(false);
      openEdit(null);
    };
    document.getElementById("backBtn").onclick = () => { show("main"); loadList(); };
    document.getElementById("logoutBtn").onclick = () => {
      if (openAccess) return;
      token = "";
      sessionStorage.removeItem(TOKEN_KEY);
      show("login");
    };
    form.category.addEventListener("change", fillSubcategories);
    form.splitMode.addEventListener("change", rebuildAllocations);

    form.onsubmit = async (ev) => {
      ev.preventDefault();
      editError.hidden = true;
      try {
        const body = collectBody();
        if (editingId) {
          await api(`/api/transactions/${editingId}`, { method: "PUT", body: JSON.stringify(body) });
        } else {
          await api("/api/transactions", { method: "POST", body: JSON.stringify(body) });
        }
        show("main");
        await loadList();
      } catch (e) {
        editError.hidden = false;
        editError.textContent = e.message || "Save failed";
      }
    };

    document.getElementById("reviewBtn").onclick = async () => {
      if (!editingId) return;
      editError.hidden = true;
      try {
        const body = collectBody();
        body.reviewStatus = "reviewed";
        await api(`/api/transactions/${editingId}`, { method: "PUT", body: JSON.stringify(body) });
        await api(`/api/transactions/${editingId}/review`, { method: "POST", body: "{}" });
        show("main");
        await loadList();
      } catch (e) {
        editError.hidden = false;
        editError.textContent = e.message || "Review failed";
      }
    };

    document.getElementById("deleteBtn").onclick = async () => {
      if (!editingId) return;
      if (!confirm("Delete this transaction?")) return;
      try {
        await api(`/api/transactions/${editingId}`, { method: "DELETE" });
        show("main");
        await loadList();
      } catch (e) {
        editError.hidden = false;
        editError.textContent = e.message || "Delete failed";
      }
    };

    if (token) {
      try {
        meta = await api("/api/meta");
        fillFilterControls();
        show("main");
        await loadList();
        return;
      } catch (_) {
        token = "";
        sessionStorage.removeItem(TOKEN_KEY);
      }
    }
    try {
      const status = await api("/api/status");
      if (status && status.accessCodeRequired === false) {
        openAccess = true;
        document.getElementById("logoutBtn").hidden = true;
        meta = await api("/api/meta");
        fillFilterControls();
        show("main");
        await loadList();
        return;
      }
    } catch (_) { /* fall through to login */ }
    openAccess = false;
    document.getElementById("logoutBtn").hidden = false;
    show("login");
  }

  boot();
})();
