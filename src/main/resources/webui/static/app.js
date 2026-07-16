(() => {
  const POLL_MS = 2500;
  const LONG_CONTENT_CHARS = 400;

  const state = {
    activeTab: "messages",
    activeRole: null,
    roles: [],
  };

  const el = (id) => document.getElementById(id);

  async function getJson(url) {
    const res = await fetch(url, { cache: "no-store" });
    if (!res.ok) {
      throw new Error(`${url} -> ${res.status}`);
    }
    return res.json();
  }

  function setConnIndicator(ok) {
    const dot = el("conn-indicator");
    dot.classList.toggle("down", !ok);
  }

  function fmtNumber(n) {
    if (n === undefined || n === null) return "--";
    return new Intl.NumberFormat().format(n);
  }

  function fmtDuration(ms) {
    if (!ms) return "0s";
    const s = ms / 1000;
    if (s < 60) return `${s.toFixed(1)}s`;
    const m = Math.floor(s / 60);
    return `${m}m ${Math.round(s % 60)}s`;
  }

  function tokensTotalFrom(tokens) {
    if (!tokens) return 0;
    let total = (tokens.supervisor?.total || 0) + (tokens.reviewer?.total || 0);
    const workers = tokens.workers || {};
    for (const key of Object.keys(workers)) {
      total += workers[key]?.total || 0;
    }
    return total;
  }

  function renderStatRow(metadata, tokens) {
    const stats = [
      ["Shapes Processed", metadata.shapes_processed ?? "--"],
      ["Violations", metadata.current_violations ?? "--"],
      ["Triples", fmtNumber(metadata.triplestore_size)],
      ["Duration", fmtDuration(Number(metadata.duration_ms || 0))],
      ["Total Tokens", fmtNumber(tokensTotalFrom(tokens))],
    ];
    el("stat-row").innerHTML = stats
      .map(
        ([label, value]) => `
        <div class="stat-card">
          <div class="stat-label">${label}</div>
          <div class="stat-value">${value}</div>
        </div>`
      )
      .join("");
  }

  function metadataTokens(metadata) {
    const tokens = { supervisor: {}, reviewer: {}, workers: {} };
    for (const key of Object.keys(metadata)) {
      const m = key.match(/^tokens\.(supervisor|reviewer)\.(input|output|total)$/);
      if (m) {
        tokens[m[1]][m[2]] = Number(metadata[key]);
        continue;
      }
      const wm = key.match(/^tokens\.worker\.(.+)\.(input|output|total)$/);
      if (wm) {
        tokens.workers[wm[1]] = tokens.workers[wm[1]] || {};
        tokens.workers[wm[1]][wm[2]] = Number(metadata[key]);
      }
    }
    return tokens;
  }

  function renderStageBadge(stage) {
    const badge = el("stage-badge");
    badge.textContent = stage || "no data yet";
    badge.className = "badge " + (stage ? "badge-good" : "badge-muted");
  }

  function renderHistory(history) {
    const items = [...history].reverse();
    el("stage-list").innerHTML =
      items
        .map(
          (snap) => `
        <div class="stage-item">
          <div class="stage-name">${snap.stage || "?"}</div>
          <div class="stage-meta">
            <span>shapes ${snap.shapesProcessed ?? 0}</span>
            <span>viol ${snap.currentViolations ?? "?"}</span>
            <span>${fmtDuration(snap.durationMs || 0)}</span>
          </div>
        </div>`
        )
        .join("") || `<div class="empty-hint">No stages recorded yet.</div>`;
  }

  function renderRoleTabs(roles) {
    if (JSON.stringify(roles) === JSON.stringify(state.roles) && state.activeRole) {
      return;
    }
    state.roles = roles;
    if (!state.activeRole || !roles.includes(state.activeRole)) {
      state.activeRole = roles[0] || null;
    }
    el("role-tabs").innerHTML = roles
      .map(
        (role) =>
          `<button class="role-tab ${role === state.activeRole ? "active" : ""}" data-role="${role}">${role}</button>`
      )
      .join("");
    el("role-tabs")
      .querySelectorAll(".role-tab")
      .forEach((btn) =>
        btn.addEventListener("click", () => {
          state.activeRole = btn.dataset.role;
          renderRoleTabs(state.roles);
          loadMessages();
        })
      );
  }

  function escapeHtml(str) {
    return str
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function prettyIfJson(text, isJson) {
    if (!isJson) return escapeHtml(text);
    try {
      return escapeHtml(JSON.stringify(JSON.parse(text), null, 2));
    } catch (e) {
      return escapeHtml(text);
    }
  }

  function renderMessages(entries) {
    if (!entries.length) {
      el("messages").innerHTML = `<div class="empty-hint">No messages logged for this role yet.</div>`;
      return;
    }

    el("messages").innerHTML = entries
      .map((entry, idx) => {
        const dirClass = `dir-${entry.direction || "UNKNOWN"}`;
        let body;
        if (entry.tool) {
          body = `<span class="tool-name">${escapeHtml(entry.tool.name)}</span>(${prettyIfJson(
            entry.tool.arguments,
            entry.tool.argumentsAreJson
          )})`;
        } else {
          body = escapeHtml(entry.content || "");
        }
        const collapsible = (entry.content || "").length > LONG_CONTENT_CHARS;
        return `
        <div class="msg">
          <div class="msg-head">
            <span class="msg-direction ${dirClass}">${entry.direction || "?"}</span>
            <span>${entry.timestamp || ""}</span>
          </div>
          <div class="msg-content ${collapsible ? "collapsed" : ""}" data-idx="${idx}">${body}</div>
          ${collapsible ? `<button class="msg-toggle" data-idx="${idx}">Show more</button>` : ""}
        </div>`;
      })
      .join("");

    el("messages")
      .querySelectorAll(".msg-toggle")
      .forEach((btn) =>
        btn.addEventListener("click", () => {
          const content = el("messages").querySelector(
            `.msg-content[data-idx="${btn.dataset.idx}"]`
          );
          const collapsed = content.classList.toggle("collapsed");
          btn.textContent = collapsed ? "Show more" : "Show less";
        })
      );
  }

  function renderFile(prefix, file) {
    el(`${prefix}-meta`).textContent = file.exists
      ? `${file.path} -- ${fmtNumber(file.sizeBytes)} bytes${file.truncated ? " (truncated for display)" : ""}`
      : `${file.path || "(not configured)"} -- not written yet`;
    el(`${prefix}-view`).textContent = file.content || "";
  }

  async function loadState() {
    const data = await getJson("/api/state");
    const hasData = data.benchmarkDirExists;
    el("empty-state").classList.toggle("hidden", hasData);
    el("main-content").classList.toggle("hidden", !hasData);
    if (!hasData) {
      return data;
    }

    renderStageBadge(data.metadata.stage);
    renderStatRow(data.metadata, metadataTokens(data.metadata));
    renderRoleTabs(data.roles || []);
    el("last-updated").textContent = "updated " + new Date().toLocaleTimeString();
    return data;
  }

  async function loadHistory() {
    const history = await getJson("/api/history");
    renderHistory(Array.isArray(history) ? history : []);
  }

  async function loadMessages() {
    if (!state.activeRole) {
      el("messages").innerHTML = `<div class="empty-hint">No session roles yet.</div>`;
      return;
    }
    const entries = await getJson(`/api/messages?role=${encodeURIComponent(state.activeRole)}`);
    renderMessages(entries);
  }

  async function loadOutput() {
    renderFile("output", await getJson("/api/output"));
  }

  async function loadTriplestore() {
    renderFile("triplestore", await getJson("/api/triplestore"));
  }

  async function loadActiveTabData() {
    if (state.activeTab === "messages") await loadMessages();
    else if (state.activeTab === "output") await loadOutput();
    else if (state.activeTab === "triplestore") await loadTriplestore();
  }

  async function tick() {
    try {
      const stateData = await loadState();
      if (stateData.benchmarkDirExists) {
        await loadHistory();
        await loadActiveTabData();
      }
      setConnIndicator(true);
    } catch (e) {
      setConnIndicator(false);
    }
  }

  function setupTabs() {
    document.querySelectorAll(".tab-btn").forEach((btn) =>
      btn.addEventListener("click", () => {
        document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
        document.querySelectorAll(".tab-panel").forEach((p) => p.classList.remove("active"));
        btn.classList.add("active");
        el(`tab-${btn.dataset.tab}`).classList.add("active");
        state.activeTab = btn.dataset.tab;
        loadActiveTabData();
      })
    );
  }

  setupTabs();
  tick();
  setInterval(tick, POLL_MS);
})();
