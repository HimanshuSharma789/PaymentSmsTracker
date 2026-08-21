(() => {
  const TOKEN_KEY = "lan_token";
  let token = sessionStorage.getItem(TOKEN_KEY) || "";
  let meta = null;
  let openAccess = false;
  let currentFilter = "needs_review";
  let items = [];
  const expandedIds = new Set();
  const selectedIds = new Set();

  const loginView = document.getElementById("loginView");
  const sheetPage = document.getElementById("sheetPage");
  const sheetBody = document.getElementById("sheetBody");
  const listEmpty = document.getElementById("listEmpty");
  const toastEl = document.getElementById("toast");
  const loginError = document.getElementById("loginError");
  const bulkBar = document.getElementById("bulkBar");
  const bulkCount = document.getElementById("bulkCount");
  const selectAll = document.getElementById("selectAll");
  const COLSPAN = 11;

  function typeOptions(selected) {
    const cur = selected || "Expense";
    return ["Expense", "Income", "Transfer-Out"].map((t) =>
      `<option value="${t}" ${t === cur ? "selected" : ""}>${t}</option>`
    ).join("");
  }

  function isTransferType(type) {
    return type === "Transfer-Out";
  }

  function categoryChoices(type) {
    if (isTransferType(type)) return meta.accounts || [];
    if (type === "Income") return meta.incomeCategories || [];
    return Object.keys(meta.expenseCategories || {});
  }

  function applyRowTypeUi(row, keepCategory) {
    const type = row.querySelector('[data-field="transactionType"]')?.value || "Expense";
    const transfer = isTransferType(type);
    const catSel = row.querySelector('[data-field="category"]');
    const subSel = row.querySelector('[data-field="subcategory"]');
    const prev = keepCategory != null ? keepCategory : (catSel?.value || "");
    if (catSel) {
      catSel.innerHTML = optionList(categoryChoices(type), prev, true);
      catSel.title = transfer ? "To account" : "Category";
    }
    if (subSel) {
      subSel.disabled = transfer;
      if (transfer) {
        subSel.innerHTML = optionList([], "", true);
      } else if (type === "Expense") {
        const subs = (meta.expenseCategories && meta.expenseCategories[catSel.value]) || [];
        const keepSub = subSel.value;
        subSel.innerHTML = optionList(subs, keepSub, true);
      } else {
        subSel.innerHTML = optionList([], "", true);
      }
    }
    const detail = detailRow(row.dataset.id);
    const splitBlock = detail?.querySelector(".splitBlock");
    if (splitBlock) splitBlock.hidden = transfer;
  }
  let toastTimer = null;

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
      throw err;
    }
    return data;
  }

  function showLoggedIn(ok) {
    loginView.hidden = ok;
    sheetPage.hidden = !ok;
  }

  function ymdHm(ts) {
    const d = new Date(ts);
    const m = `${d.getMonth() + 1}`.padStart(2, "0");
    const day = `${d.getDate()}`.padStart(2, "0");
    const hh = `${d.getHours()}`.padStart(2, "0");
    const mm = `${d.getMinutes()}`.padStart(2, "0");
    return `${d.getFullYear()}-${m}-${day}T${hh}:${mm}`;
  }

  function escapeHtml(s) {
    return String(s || "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
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

  function optionList(values, selected, includeBlank) {
    const opts = includeBlank ? [`<option value=""></option>`] : [];
    return opts.concat(
      (values || []).map((v) =>
        `<option value="${escapeHtml(v)}" ${v === selected ? "selected" : ""}>${escapeHtml(v)}</option>`
      )
    ).join("");
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
    const type = document.getElementById("filterType")?.value || "";
    return items.filter((tx) => {
      if (!matchesTextSearch(tx, q)) return false;
      if (account && (tx.account || "") !== account) return false;
      if (category === "__uncategorized__") {
        if ((tx.category || "").trim()) return false;
      } else if (category && (tx.category || "") !== category) {
        return false;
      }
      if (subcategory && (tx.subcategory || "") !== subcategory) return false;
      if (type && (tx.transactionType || "Expense") !== type) return false;
      return true;
    });
  }

  function updateFilterCount(shown, total) {
    const el = document.getElementById("filterCount");
    if (!el) return;
    el.textContent = shown === total ? `${total} shown` : `${shown} of ${total}`;
  }

  function refreshBulkBar() {
    const n = selectedIds.size;
    bulkBar.hidden = n === 0;
    bulkCount.textContent = n === 1 ? "1 selected" : `${n} selected`;
    const visible = filteredItems();
    if (selectAll) {
      selectAll.checked = visible.length > 0 && visible.every((tx) => selectedIds.has(String(tx.id)));
      selectAll.indeterminate = !selectAll.checked &&
        visible.some((tx) => selectedIds.has(String(tx.id)));
    }
  }

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

  function moneyPlain(n) {
    return (Math.round((Number(n) + Number.EPSILON) * 100) / 100).toFixed(2);
  }

  function resolveSplitPreview(amount, people, mode, alloc) {
    if (!people.length) {
      return { text: "No split", ok: true };
    }
    const total = Math.round((Number(amount) + Number.EPSILON) * 100) / 100;
    if (!(total > 0)) {
      return { text: `${people.join(", ")} · ${mode}`, ok: false, error: "Enter amount" };
    }

    let amounts = null;
    let error = null;
    if (mode === "exact") {
      const map = {};
      for (const name of people) {
        if (alloc[name] === undefined || alloc[name] === "") {
          error = `Enter amount for ${name}`;
          break;
        }
        map[name] = Math.round((Number(alloc[name]) + Number.EPSILON) * 100) / 100;
      }
      if (!error) {
        const sum = Object.values(map).reduce((a, b) => a + b, 0);
        if (Math.abs(sum - total) > 0.001) {
          error = `Amounts must add up to ₹${moneyPlain(total)} (now ₹${moneyPlain(sum)})`;
        } else {
          amounts = map;
        }
      }
    } else if (mode === "percent") {
      const percents = {};
      for (const name of people) {
        if (alloc[name] === undefined || alloc[name] === "") {
          error = `Enter % for ${name}`;
          break;
        }
        percents[name] = Number(alloc[name]);
      }
      if (!error) {
        const percentSum = Object.values(percents).reduce((a, b) => a + b, 0);
        if (Math.abs(percentSum - 100) > 0.001) {
          error = `Percentages must add up to 100 (now ${percentSum})`;
        } else {
          amounts = {};
          people.forEach((name) => {
            amounts[name] = Math.round((total * percents[name] / 100) * 100) / 100;
          });
          const sum = Object.values(amounts).reduce((a, b) => a + b, 0);
          const diff = Math.round((total - sum) * 100) / 100;
          if (diff !== 0) {
            const target = people.find((p) => p.toUpperCase() === "ME") || people[0];
            amounts[target] = Math.round((amounts[target] + diff) * 100) / 100;
          }
        }
      }
    } else if (mode === "weight") {
      const weights = {};
      for (const name of people) {
        if (alloc[name] === undefined || alloc[name] === "" || !(Number(alloc[name]) > 0)) {
          error = `Enter share for ${name}`;
          break;
        }
        weights[name] = Number(alloc[name]);
      }
      if (!error) {
        const weightSum = Object.values(weights).reduce((a, b) => a + b, 0);
        amounts = {};
        people.forEach((name) => {
          amounts[name] = Math.round((total * weights[name] / weightSum) * 100) / 100;
        });
        const sum = Object.values(amounts).reduce((a, b) => a + b, 0);
        const diff = Math.round((total - sum) * 100) / 100;
        if (diff !== 0) {
          const target = people.find((p) => p.toUpperCase() === "ME") || people[0];
          amounts[target] = Math.round((amounts[target] + diff) * 100) / 100;
        }
      }
    } else {
      const share = Math.round((total / people.length) * 100) / 100;
      amounts = {};
      people.forEach((name) => { amounts[name] = share; });
      const sum = Object.values(amounts).reduce((a, b) => a + b, 0);
      const diff = Math.round((total - sum) * 100) / 100;
      if (diff !== 0) {
        const target = people.find((p) => p.toUpperCase() === "ME") || people[0];
        amounts[target] = Math.round((amounts[target] + diff) * 100) / 100;
      }
    }

    if (error || !amounts) {
      return {
        text: `${people.join(", ")} · ${mode}`,
        ok: false,
        error: error || "Incomplete"
      };
    }
    const meKey = Object.keys(amounts).find((k) => k.toUpperCase() === "ME");
    const myShare = meKey ? amounts[meKey] : 0;
    const friendsOwe = Math.max(0, Math.round((total - myShare) * 100) / 100);
    return {
      text: `${people.join(", ")} · ${mode} · me ₹${moneyPlain(myShare)} / friends ₹${moneyPlain(friendsOwe)}`,
      ok: true
    };
  }

  function splitLabel(tx) {
    return resolveSplitPreview(
      tx.amount,
      Array.isArray(tx.participants) ? tx.participants : [],
      tx.splitMode || "equal",
      parseAllocations(tx.splitAllocations)
    ).text;
  }

  function refreshSplitMeta(row, detail) {
    const metaEl = detail.querySelector(".splitMeta");
    const errEl = detail.querySelector(".splitLiveError");
    if (!metaEl) return;
    const amount = Number(row.querySelector('[data-field="amount"]')?.value || 0);
    const people = selectedParticipants(detail);
    const mode = people.length
      ? (detail.querySelector('[data-field="splitMode"]')?.value || "equal")
      : "equal";
    const alloc = {};
    detail.querySelectorAll(".allocationBox input[data-person]").forEach((input) => {
      if (input.value !== "") alloc[input.dataset.person] = Number(input.value);
    });
    // For equal mode ignore alloc; for uneven use typed values (empty keys omitted → incomplete)
    const preview = resolveSplitPreview(amount, people, mode, alloc);
    metaEl.textContent = preview.text;
    metaEl.classList.toggle("invalid", !preview.ok);
    if (errEl) {
      errEl.hidden = preview.ok || !preview.error;
      errEl.textContent = preview.error || "";
    }
  }

  function splitwiseMeta() {
    return meta.splitwise || { signedIn: false, groups: [], defaultGroupId: 0 };
  }

  function splitwiseGroupById(id) {
    return (splitwiseMeta().groups || []).find((g) => String(g.id) === String(id));
  }

  function memberChipsHtml(groupId, selectedNames, selectedIdMap) {
    const group = splitwiseGroupById(groupId);
    if (!groupId || Number(groupId) <= 0) {
      return `<p class="muted detailHint">Choose a Splitwise group, then pick members.</p>`;
    }
    if (!group || !(group.members || []).length) {
      return `<p class="muted detailHint">No members in this group. Refresh groups in Admin.</p>`;
    }
    const byName = new Set((selectedNames || []).map((n) => String(n).toLowerCase()));
    const byId = selectedIdMap || {};
    const hasSelection = byName.size > 0 || Object.keys(byId).length > 0;
    return `<div class="chips participantChips">${(group.members || []).map((m) => {
      const label = m.label || m.displayName || `User ${m.id}`;
      const id = Number(m.id) || 0;
      let on = false;
      if (hasSelection) {
        on = byName.has(String(label).toLowerCase()) ||
          Object.entries(byId).some(([k, v]) => Number(v) === id || String(k).toLowerCase() === String(label).toLowerCase());
      } else {
        on = String(label).toUpperCase() === "ME";
      }
      return `<button type="button" class="partChip${on ? " active" : ""}" data-participant="${escapeHtml(label)}" data-user-id="${id}">${escapeHtml(label)}</button>`;
    }).join("")}</div>`;
  }

  function parseParticipantIdMap(raw) {
    const map = {};
    if (!raw) return map;
    if (typeof raw === "object" && !Array.isArray(raw)) {
      Object.entries(raw).forEach(([k, v]) => {
        const id = Number(v);
        if (k && id > 0) map[k] = id;
      });
      return map;
    }
    String(raw).split(/[,|]/).forEach((part) => {
      const p = part.trim();
      if (!p) return;
      const sep = p.includes("=") ? "=" : ":";
      const i = p.indexOf(sep);
      if (i <= 0) return;
      const name = p.slice(0, i).trim();
      const id = Number(p.slice(i + 1).trim());
      if (name && id > 0) map[name] = id;
    });
    return map;
  }

  function selectedParticipants(detail) {
    return Array.from(detail.querySelectorAll(".partChip.active"))
      .map((el) => el.dataset.participant)
      .filter(Boolean);
  }

  function selectedParticipantIdMap(detail) {
    const map = {};
    detail.querySelectorAll(".partChip.active").forEach((el) => {
      const name = el.dataset.participant;
      const id = Number(el.dataset.userId);
      if (name && id > 0) map[name] = id;
    });
    return map;
  }

  function participantChipsHtml(selected) {
    // legacy no-op kept for safety; use memberChipsHtml
    return memberChipsHtml(0, selected, {});
  }

  function allocationPlaceholder(mode) {
    if (mode === "percent") return "%";
    if (mode === "weight") return "shares";
    return "₹";
  }

  function rebuildDetailAllocations(detail, txAllocRaw) {
    const mode = detail.querySelector('[data-field="splitMode"]')?.value || "equal";
    const box = detail.querySelector(".allocationBox");
    if (!box) return;
    const people = selectedParticipants(detail);
    const needs = mode !== "equal" && people.length > 0;
    box.hidden = !needs;
    if (!needs) {
      box.innerHTML = "";
      return;
    }
    const existing = {};
    box.querySelectorAll("input[data-person]").forEach((input) => {
      existing[input.dataset.person] = input.value;
    });
    const fromTx = parseAllocations(txAllocRaw || detail.dataset.alloc || "");
    const ph = allocationPlaceholder(mode);
    const hint = mode === "percent"
      ? "Enter % for each person (must total 100)."
      : mode === "weight"
        ? "Enter share weights for each person."
        : "Enter ₹ amounts for each person (must total expense).";
    box.innerHTML =
      `<p class="muted detailHint">${hint}</p>` +
      people.map((name) => {
        const val = existing[name] ?? fromTx[name] ?? (mode === "weight" ? "1" : "");
        return `<div class="allocRow"><span>${escapeHtml(name)}</span><input data-person="${escapeHtml(name)}" type="number" step="any" min="0" placeholder="${ph}" value="${escapeHtml(val)}" /></div>`;
      }).join("");
  }

  function collectAllocations(detail) {
    const alloc = {};
    detail.querySelectorAll(".allocationBox input[data-person]").forEach((input) => {
      if (input.value !== "") alloc[input.dataset.person] = Number(input.value);
    });
    return alloc;
  }

  function detailRow(tx) {
    return sheetBody.querySelector(`tr.sheetDetail[data-id="${tx}"]`)
      || sheetBody.querySelector(`tr.sheetDetail[data-id="${String(tx)}"]`);
  }

  function mainRow(id) {
    return sheetBody.querySelector(`tr.sheetMain[data-id="${id}"]`);
  }

  function setExpanded(id, open) {
    const key = String(id);
    const detail = detailRow(key);
    const main = mainRow(key);
    const btn = main?.querySelector(".detailsBtn");
    if (!detail || !main) return;
    detail.hidden = !open;
    main.classList.toggle("expanded", open);
    if (btn) btn.textContent = open ? "Hide" : "Details";
    if (open) expandedIds.add(key);
    else expandedIds.delete(key);
  }

  function renderSheet() {
    const filtered = filteredItems();
    listEmpty.hidden = filtered.length > 0;
    updateFilterCount(filtered.length, items.length);
    const accounts = meta.accounts || [];
    sheetBody.innerHTML = filtered.map((tx) => {
      const type = tx.transactionType || "Expense";
      const transfer = isTransferType(type);
      const categories = categoryChoices(type);
      const subs = (!transfer && type === "Expense" && meta.expenseCategories)
        ? (meta.expenseCategories[tx.category] || [])
        : [];
      const sms = tx.originalSms || "";
      const open = expandedIds.has(String(tx.id));
      const checked = selectedIds.has(String(tx.id)) ? "checked" : "";
      const st = tx.reviewStatus || "needs_review";
      return `
      <tr class="sheetMain${open ? " expanded" : ""}${checked ? " selected" : ""}" data-id="${tx.id}" data-status="${escapeHtml(st)}">
        <td class="checkCol"><input type="checkbox" class="rowCheck" ${checked} aria-label="Select row" /></td>
        <td><input class="dateInput" data-field="timestamp" type="datetime-local" value="${ymdHm(tx.timestamp)}" /></td>
        <td><input data-field="merchantName" value="${escapeHtml(tx.merchantName || "")}" placeholder="${transfer ? "Description (optional)" : ""}" /></td>
        <td><input class="amountInput" data-field="amount" type="number" step="0.01" min="0" value="${tx.amount}" /></td>
        <td><select data-field="transactionType" class="typeSelect">${typeOptions(tx.transactionType)}</select></td>
        <td><select data-field="account" title="${transfer ? "From" : "Account"}">${optionList(accounts, tx.account || "", false)}</select></td>
        <td><select data-field="category" title="${transfer ? "To account" : "Category"}">${optionList(categories, tx.category || "", true)}</select></td>
        <td><select data-field="subcategory" ${transfer ? "disabled" : ""}>${optionList(subs, transfer ? "" : (tx.subcategory || ""), true)}</select></td>
        <td>
          <select data-field="reviewStatus">
            <option value="needs_review" ${st === "needs_review" ? "selected" : ""}>needs_review</option>
            <option value="reviewed" ${st === "reviewed" ? "selected" : ""}>reviewed</option>
            <option value="exported" ${st === "exported" ? "selected" : ""}>exported</option>
          </select>
        </td>
        <td class="notesCell"><input data-field="notes" value="${escapeHtml(tx.notes || "")}" /></td>
        <td>
          <div class="actions">
            <button type="button" class="ghost detailsBtn">${open ? "Hide" : "Details"}</button>
            <button type="button" class="primary saveRowBtn">Save</button>
          </div>
        </td>
      </tr>
      <tr class="sheetDetail" data-id="${tx.id}" data-alloc="${escapeHtml(tx.splitAllocations || "")}" ${open ? "" : "hidden"}>
        <td colspan="${COLSPAN}">
          <div class="detailPanel">
            <div class="detailStack">
              <div class="detailBlock">
                <h3>Original SMS</h3>
                <pre class="smsFull">${escapeHtml(sms || "No original SMS stored.")}</pre>
              </div>
              <div class="detailBlock splitBlock" ${transfer ? "hidden" : ""}>
                <h3>Split</h3>
                <p class="splitMeta">${escapeHtml(splitLabel(tx))}</p>
                <p class="splitLiveError error" hidden></p>
                ${(() => {
                  const sw = splitwiseMeta();
                  if (!(sw.groups || []).length) {
                    return `<p class="muted detailHint">Sync Splitwise groups in the app Admin first.</p>`;
                  }
                  const selectedGroup = Number(tx.splitwiseGroupId) > 0
                    ? tx.splitwiseGroupId
                    : (sw.defaultGroupId || 0);
                  const options = [`<option value="0">No Splitwise group</option>`]
                    .concat((sw.groups || []).map((g) =>
                      `<option value="${g.id}" ${String(g.id) === String(selectedGroup) ? "selected" : ""}>${escapeHtml(g.name)}</option>`
                    )).join("");
                  return `
                <label class="detailLabel tight">Splitwise group
                  <select data-field="splitwiseGroupId" class="groupSelect">${options}</select>
                </label>
                <p class="detailLabel tight">Members</p>
                <div class="memberChipsHost">${memberChipsHtml(selectedGroup, tx.participants || [], parseParticipantIdMap(tx.splitwiseParticipantIds))}</div>`;
                })()}
                <div class="modeRow">
                  <span class="detailLabel">Mode</span>
                  <select data-field="splitMode" class="modeSelectCompact">
                    <option value="equal" ${(tx.splitMode || "equal") === "equal" ? "selected" : ""}>equal</option>
                    <option value="exact" ${tx.splitMode === "exact" ? "selected" : ""}>exact</option>
                    <option value="percent" ${tx.splitMode === "percent" ? "selected" : ""}>percent</option>
                    <option value="weight" ${tx.splitMode === "weight" ? "selected" : ""}>weight</option>
                  </select>
                </div>
                <div class="allocationBox alloc" hidden></div>
              </div>
            </div>
          </div>
        </td>
      </tr>`;
    }).join("");

    sheetBody.querySelectorAll("tr.sheetMain").forEach((row) => {
      const id = row.dataset.id;
      const detail = detailRow(id);
      const markDirty = () => row.classList.add("dirty");
      const tx = items.find((item) => String(item.id) === String(id));

      row.querySelectorAll("input:not(.rowCheck), select").forEach((el) => {
        el.addEventListener("input", () => {
          markDirty();
          if (detail && el.dataset.field === "amount") refreshSplitMeta(row, detail);
        });
        el.addEventListener("change", () => {
          markDirty();
          if (el.dataset.field === "transactionType") {
            applyRowTypeUi(row, "");
          }
          if (el.dataset.field === "category") {
            const type = row.querySelector('[data-field="transactionType"]')?.value || "Expense";
            if (type === "Expense") {
              const subs = (meta.expenseCategories && meta.expenseCategories[el.value]) || [];
              row.querySelector('select[data-field="subcategory"]').innerHTML = optionList(subs, "", true);
            }
          }
          if (el.dataset.field === "reviewStatus") {
            row.dataset.status = el.value;
          }
          if (detail && el.dataset.field === "amount") refreshSplitMeta(row, detail);
        });
      });

      if (detail) {
        const syncSplitUi = () => {
          rebuildDetailAllocations(detail, tx?.splitAllocations);
          wireAllocInputs(detail, row, markDirty);
          refreshSplitMeta(row, detail);
        };
        const wireMemberChips = () => {
          detail.querySelectorAll(".partChip").forEach((chip) => {
            chip.addEventListener("click", () => {
              chip.classList.toggle("active");
              markDirty();
              syncSplitUi();
            });
          });
        };
        const groupSelect = detail.querySelector('[data-field="splitwiseGroupId"]');
        groupSelect?.addEventListener("change", () => {
          const host = detail.querySelector(".memberChipsHost");
          if (host) {
            host.innerHTML = memberChipsHtml(groupSelect.value, [], {});
            wireMemberChips();
          }
          markDirty();
          syncSplitUi();
        });
        wireMemberChips();
        const modeSelect = detail.querySelector('[data-field="splitMode"]');
        modeSelect?.addEventListener("change", () => {
          markDirty();
          syncSplitUi();
        });
        syncSplitUi();
      }

      row.querySelector(".detailsBtn").addEventListener("click", () => {
        setExpanded(id, !expandedIds.has(String(id)));
      });
      row.querySelector(".saveRowBtn").addEventListener("click", () => saveRow(row));
      const check = row.querySelector(".rowCheck");
      check?.addEventListener("click", (e) => e.stopPropagation());
      check?.addEventListener("change", () => {
        if (check.checked) selectedIds.add(String(id));
        else selectedIds.delete(String(id));
        row.classList.toggle("selected", check.checked);
        refreshBulkBar();
      });
    });
    refreshBulkBar();
  }

  function wireAllocInputs(detail, row, markDirty) {
    detail.querySelectorAll(".allocationBox input").forEach((el) => {
      el.addEventListener("input", () => {
        markDirty();
        refreshSplitMeta(row, detail);
      });
      el.addEventListener("change", () => {
        markDirty();
        refreshSplitMeta(row, detail);
      });
    });
  }

  function collectRow(row) {
    const get = (field) => {
      const fromMain = row.querySelector(`[data-field="${field}"]`);
      if (fromMain) return fromMain;
      return detailRow(row.dataset.id)?.querySelector(`[data-field="${field}"]`);
    };
    const original = items.find((tx) => String(tx.id) === String(row.dataset.id));
    const detail = detailRow(row.dataset.id);
    const d = get("timestamp").value ? new Date(get("timestamp").value) : new Date();
    const transactionType = get("transactionType")?.value || original?.transactionType || "Expense";
    const transfer = isTransferType(transactionType);
    const people = transfer
      ? []
      : (detail ? selectedParticipants(detail) : (original?.participants || []));
    const mode = people.length ? (get("splitMode")?.value || "equal") : "equal";
    const alloc = transfer
      ? {}
      : (detail && mode !== "equal"
        ? collectAllocations(detail)
        : (original?.splitAllocations ? parseAllocations(original.splitAllocations) : {}));
    const groupId = transfer
      ? 0
      : Number(get("splitwiseGroupId")?.value || original?.splitwiseGroupId || 0);
    const idMap = transfer
      ? {}
      : (detail ? selectedParticipantIdMap(detail) : parseParticipantIdMap(original?.splitwiseParticipantIds));
    let merchantName = get("merchantName").value.trim();
    const toAccount = get("category").value;
    if (transfer && !merchantName && toAccount) {
      merchantName = `Transfer to ${toAccount}`;
    }
    return {
      amount: Number(get("amount").value),
      merchantName,
      account: get("account").value,
      category: get("category").value,
      subcategory: transfer ? "" : get("subcategory").value,
      notes: get("notes").value.trim(),
      reviewStatus: get("reviewStatus").value,
      timestamp: Number.isNaN(d.getTime()) ? Date.now() : d.getTime(),
      transactionType,
      participants: people,
      splitMode: mode,
      splitAllocations: alloc,
      splitwiseGroupId: people.length ? groupId : 0,
      splitwiseParticipantIds: idMap
    };
  }

  async function saveRow(row, options = {}) {
    const quiet = !!options.quiet;
    const id = Number(row.dataset.id);
    const btn = row.querySelector(".saveRowBtn");
    const merchant = row.querySelector('[data-field="merchantName"]')?.value?.trim() || "transaction";
    if (btn && !quiet) {
      btn.disabled = true;
      btn.textContent = "Saving…";
    }
    try {
      const body = collectRow(row);
      if (!(body.amount > 0)) {
        throw new Error("Amount must be greater than 0");
      }
      if (body.transactionType === "Transfer-Out") {
        if (!body.account || !body.category) {
          throw new Error("Choose From and To accounts");
        }
        if (body.account.toLowerCase() === body.category.toLowerCase()) {
          throw new Error("From and To must be different");
        }
      } else if (!body.merchantName) {
        throw new Error("Amount and merchant are required");
      }
      const preview = resolveSplitPreview(
        body.amount,
        body.participants,
        body.splitMode,
        body.splitAllocations || {}
      );
      if (body.participants.length && body.splitMode !== "equal" && !preview.ok) {
        throw new Error(preview.error || "Fix split values before saving");
      }
      const saved = await api(`/api/transactions/${id}`, {
        method: "PUT",
        body: JSON.stringify(body)
      });
      const idx = items.findIndex((tx) => tx.id === id);
      if (idx >= 0) items[idx] = saved;
      if (detailRow(id)) {
        detailRow(id).dataset.alloc = saved.splitAllocations || "";
      }
      row.classList.remove("dirty");
      const metaEl = detailRow(id)?.querySelector(".splitMeta");
      if (metaEl) {
        metaEl.textContent = splitLabel(saved);
        metaEl.classList.remove("invalid");
      }
      const errEl = detailRow(id)?.querySelector(".splitLiveError");
      if (errEl) {
        errEl.hidden = true;
        errEl.textContent = "";
      }
      if (!quiet) showToast(`Saved · ${merchant}`, "ok");
      return saved;
    } catch (e) {
      if (!quiet) showToast(e.message || "Save failed", "err", 4200);
      throw e;
    } finally {
      if (btn && !quiet) {
        btn.disabled = false;
        btn.textContent = "Save";
      }
    }
  }

  async function bulkSaveSelected() {
    const rows = Array.from(selectedIds)
      .map((id) => mainRow(id))
      .filter(Boolean);
    if (!rows.length) {
      showToast("Nothing selected", "err");
      return;
    }
    let ok = 0;
    let fail = 0;
    for (const row of rows) {
      try {
        await saveRow(row, { quiet: true });
        ok += 1;
      } catch (_) {
        fail += 1;
      }
    }
    if (fail) showToast(`Saved ${ok}, failed ${fail}`, "err", 4200);
    else showToast(`Saved ${ok} selected`, "ok");
  }

  async function bulkReviewSelected() {
    const ids = Array.from(selectedIds).map(Number).filter((n) => n > 0);
    if (!ids.length) {
      showToast("Nothing selected", "err");
      return;
    }
    try {
      // Persist any pending edits first so status change isn't lost
      for (const id of selectedIds) {
        const row = mainRow(id);
        if (row?.classList.contains("dirty")) {
          await saveRow(row, { quiet: true });
        }
      }
      const res = await api("/api/transactions/bulk", {
        method: "POST",
        body: JSON.stringify({ action: "review", ids })
      });
      selectedIds.clear();
      showToast(`Marked ${res.count || ids.length} as reviewed`, "ok");
      await loadList();
    } catch (e) {
      showToast(e.message || "Mark reviewed failed", "err", 4200);
    }
  }

  async function bulkDeleteSelected() {
    const ids = Array.from(selectedIds).map(Number).filter((n) => n > 0);
    if (!ids.length) {
      showToast("Nothing selected", "err");
      return;
    }
    if (!window.confirm(`Delete ${ids.length} selected transaction(s)? This cannot be undone.`)) {
      return;
    }
    try {
      const res = await api("/api/transactions/bulk", {
        method: "POST",
        body: JSON.stringify({ action: "delete", ids })
      });
      selectedIds.clear();
      showToast(`Deleted ${res.count || ids.length}`, "ok");
      await loadList();
    } catch (e) {
      showToast(e.message || "Delete failed", "err", 4200);
    }
  }

  async function refreshStatus() {
    try {
      const st = await api("/api/status");
      const mins = Math.ceil((st.remainingMs || 0) / 60000);
      document.getElementById("serverMeta").textContent =
        st.running
          ? `Excel-style table · Details for SMS & split · Save per row · ~${mins} min left`
          : "Server stopped";
    } catch (_) {
      document.getElementById("serverMeta").textContent = "Server unavailable";
    }
  }

  async function loadList() {
    const qs = currentFilter ? `?status=${encodeURIComponent(currentFilter)}` : "";
    const data = await api(`/api/transactions${qs}`);
    items = data.items || [];
    const alive = new Set(items.map((tx) => String(tx.id)));
    Array.from(selectedIds).forEach((id) => {
      if (!alive.has(id)) selectedIds.delete(id);
    });
    renderSheet();
    refreshStatus();
  }

  async function enterApp() {
    meta = await api("/api/meta");
    fillFilterControls();
    showLoggedIn(true);
    await loadList();
  }

  async function boot() {
    document.getElementById("loginBtn").onclick = async () => {
      loginError.hidden = true;
      try {
        const code = document.getElementById("codeInput").value.trim();
        const data = await api("/api/login", { method: "POST", body: JSON.stringify({ code }) });
        token = data.token;
        sessionStorage.setItem(TOKEN_KEY, token);
        await enterApp();
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

    document.getElementById("searchInput").addEventListener("input", () => {
      renderSheet();
    });
    document.getElementById("filterAccount").addEventListener("change", () => renderSheet());
    document.getElementById("filterCategory").addEventListener("change", () => {
      fillFilterSubcategories("");
      renderSheet();
    });
    document.getElementById("filterSubcategory").addEventListener("change", () => renderSheet());
    document.getElementById("filterType").addEventListener("change", () => renderSheet());
    document.getElementById("clearFiltersBtn").onclick = () => {
      document.getElementById("searchInput").value = "";
      document.getElementById("filterAccount").value = "";
      document.getElementById("filterCategory").value = "";
      document.getElementById("filterType").value = "";
      fillFilterSubcategories("");
      renderSheet();
    };
    document.getElementById("refreshBtn").onclick = () => loadList();
    document.getElementById("logoutBtn").onclick = () => {
      if (openAccess) return;
      token = "";
      sessionStorage.removeItem(TOKEN_KEY);
      showLoggedIn(false);
    };
    selectAll?.addEventListener("change", () => {
      const visible = filteredItems();
      if (selectAll.checked) {
        visible.forEach((tx) => selectedIds.add(String(tx.id)));
      } else {
        visible.forEach((tx) => selectedIds.delete(String(tx.id)));
      }
      renderSheet();
    });
    document.getElementById("bulkSaveBtn").onclick = () => bulkSaveSelected();
    document.getElementById("bulkReviewBtn").onclick = () => bulkReviewSelected();
    document.getElementById("bulkDeleteBtn").onclick = () => bulkDeleteSelected();
    document.getElementById("bulkClearBtn").onclick = () => {
      selectedIds.clear();
      renderSheet();
    };

    if (token) {
      try {
        await enterApp();
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
        await enterApp();
        return;
      }
    } catch (_) { /* fall through to login */ }
    openAccess = false;
    document.getElementById("logoutBtn").hidden = false;
    showLoggedIn(false);
  }

  boot();
})();
