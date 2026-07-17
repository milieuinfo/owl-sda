(() => {
  const POLL_MS = 2500;
  const LONG_CONTENT_CHARS = 400;

  const RUNNING_THRESHOLD_MIN_MS = 30000;

  const state = {
    activeTab: "messages",
    activeRole: null,
    roles: [],
    liveIntervalSeconds: 15,
    autoScroll: true,
    expandedByRole: {},
    lastMessageCountByRole: {},
  };

  function expandedSetFor(role) {
    if (!state.expandedByRole[role]) {
      state.expandedByRole[role] = new Set();
    }
    return state.expandedByRole[role];
  }

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

  function fmtViolations(value) {
    if (value === undefined || value === null) return "--";
    const n = Number(value);
    return n < 0 ? "--" : n;
  }

  function renderStatRow(metadata, tokens) {
    const stats = [
      ["Shapes Processed", metadata.shapes_processed ?? "--"],
      ["Violations", fmtViolations(metadata.current_violations)],
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

  // metadata.timestamp is formatted yyyyMMdd_HHmmss_SSS in the server's local time zone.
  function parseSnapshotTimestamp(ts) {
    const m = /^(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})_(\d{3})$/.exec(ts || "");
    if (!m) return null;
    const [, y, mo, d, h, mi, s, ms] = m.map(Number);
    return new Date(y, mo - 1, d, h, mi, s, ms).getTime();
  }

  function updateRunIndicator(metadata) {
    const snapshotMs = parseSnapshotTimestamp(metadata.timestamp);
    const threshold = Math.max(RUNNING_THRESHOLD_MIN_MS, state.liveIntervalSeconds * 1000 * 3);
    const isRunning = snapshotMs !== null && Date.now() - snapshotMs < threshold;

    const badge = el("run-indicator");
    if (isRunning) {
      badge.className = "badge badge-running";
      badge.innerHTML = `<span class="running-dot"></span>running`;
    } else {
      badge.className = "badge badge-muted";
      badge.textContent = "idle";
    }
  }

  // Consecutive LIVE snapshots are just periodic ticks of the same wait, not distinct stages --
  // collapse them into one entry (keeping the latest values) so the sidebar doesn't fill up with
  // near-duplicate rows.
  function collapseHistory(history) {
    const collapsed = [];
    for (const snap of history) {
      const prev = collapsed[collapsed.length - 1];
      if (prev && prev.stage === "LIVE" && snap.stage === "LIVE") {
        prev.snap = snap;
        prev.count += 1;
      } else {
        collapsed.push({ stage: snap.stage, snap, count: 1 });
      }
    }
    return collapsed;
  }

  function renderHistory(history) {
    const items = collapseHistory(history).reverse();
    el("stage-list").innerHTML =
      items
        .map(
          (item) => `
        <div class="stage-item">
          <div class="stage-name">
            <span>${item.snap.stage || "?"}</span>
            ${item.count > 1 ? `<span class="stage-count">&times;${item.count}</span>` : ""}
          </div>
          <div class="stage-meta">
            <span>shapes ${item.snap.shapesProcessed ?? 0}</span>
            <span>viol ${fmtViolations(item.snap.currentViolations)}</span>
            <span>${fmtDuration(item.snap.durationMs || 0)}</span>
          </div>
        </div>`
        )
        .join("") || `<div class="empty-hint">No stages recorded yet.</div>`;
  }

  function updateActiveRoleTab() {
    el("role-tabs")
      .querySelectorAll(".role-tab")
      .forEach((btn) => btn.classList.toggle("active", btn.dataset.role === state.activeRole));
  }

  function renderRoleTabs(roles) {
    const changed = JSON.stringify(roles) !== JSON.stringify(state.roles);
    if (changed) {
      state.roles = roles;
      if (!state.activeRole || !roles.includes(state.activeRole)) {
        state.activeRole = roles[0] || null;
      }
      el("role-tabs").innerHTML = roles
        .map((role) => `<button class="role-tab" data-role="${role}">${role}</button>`)
        .join("");
      el("role-tabs")
        .querySelectorAll(".role-tab")
        .forEach((btn) =>
          btn.addEventListener("click", () => {
            state.activeRole = btn.dataset.role;
            updateActiveRoleTab();
            loadMessages();
          })
        );
    }
    updateActiveRoleTab();
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

  function renderMessages(entries, role) {
    if (!entries.length) {
      el("messages").innerHTML = `<div class="empty-hint">No messages logged for this role yet.</div>`;
      return;
    }

    const expanded = expandedSetFor(role);

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
        const isCollapsed = collapsible && !expanded.has(idx);
        return `
        <div class="msg">
          <div class="msg-head">
            <span class="msg-direction ${dirClass}">${entry.direction || "?"}</span>
            <span>${entry.timestamp || ""}</span>
          </div>
          <div class="msg-content ${isCollapsed ? "collapsed" : ""}" data-idx="${idx}">${body}</div>
          ${collapsible ? `<button class="msg-toggle" data-idx="${idx}">${isCollapsed ? "Show more" : "Show less"}</button>` : ""}
        </div>`;
      })
      .join("");

    el("messages")
      .querySelectorAll(".msg-toggle")
      .forEach((btn) =>
        btn.addEventListener("click", () => {
          const idx = Number(btn.dataset.idx);
          const content = el("messages").querySelector(`.msg-content[data-idx="${idx}"]`);
          const nowCollapsed = content.classList.toggle("collapsed");
          btn.textContent = nowCollapsed ? "Show more" : "Show less";
          if (nowCollapsed) {
            expanded.delete(idx);
          } else {
            expanded.add(idx);
          }
        })
      );
  }

  const TURTLE_TOKEN_RE =
    /(#[^\n]*)|("""[\s\S]*?"""|'''[\s\S]*?'''|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(<[^<>\s]*>)|(_:[A-Za-z0-9_-]+)|(@prefix\b|@base\b)|(\^\^[A-Za-z][\w-]*:[A-Za-z0-9_][\w-]*)|(\ba\b)|([A-Za-z][\w-]*:[A-Za-z0-9_][\w-]*|:[A-Za-z0-9_][\w-]*)|(-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|([.;,{}()[\]])/g;

  function classifyTurtleMatch(match) {
    if (match[1]) return "comment";
    if (match[2]) return "string";
    if (match[3]) return "iri";
    if (match[4]) return "bnode";
    if (match[5]) return "keyword";
    if (match[6]) return "datatype";
    if (match[7]) return "keyword";
    if (match[8]) return "pname";
    if (match[9]) return "number";
    return "punct";
  }

  function highlightTurtle(text) {
    let result = "";
    let lastIndex = 0;
    let match;
    TURTLE_TOKEN_RE.lastIndex = 0;
    while ((match = TURTLE_TOKEN_RE.exec(text)) !== null) {
      result += escapeHtml(text.slice(lastIndex, match.index));
      result += `<span class="ttl-${classifyTurtleMatch(match)}">${escapeHtml(match[0])}</span>`;
      lastIndex = TURTLE_TOKEN_RE.lastIndex;
    }
    result += escapeHtml(text.slice(lastIndex));
    return result;
  }

  function renderFile(prefix, file) {
    el(`${prefix}-meta`).textContent = file.exists
      ? `${file.path} -- ${fmtNumber(file.sizeBytes)} bytes${file.truncated ? " (truncated for display)" : ""}`
      : `${file.path || "(not configured)"} -- not written yet`;
    el(`${prefix}-view`).innerHTML = highlightTurtle(file.content || "");
  }

  async function loadState() {
    const data = await getJson("/api/state");
    const hasData = data.benchmarkDirExists;
    el("empty-state").classList.toggle("hidden", hasData);
    el("main-content").classList.toggle("hidden", !hasData);
    if (!hasData) {
      return data;
    }

    state.liveIntervalSeconds = data.liveIntervalSeconds || state.liveIntervalSeconds;
    renderStageBadge(data.metadata.stage);
    updateRunIndicator(data.metadata);
    renderStatRow(data.metadata, metadataTokens(data.metadata));
    renderRoleTabs(data.roles || []);
    el("last-updated").textContent = "updated " + new Date().toLocaleTimeString();
    return data;
  }

  async function loadHistory() {
    const history = await getJson("/api/history");
    renderHistory(Array.isArray(history) ? history : []);
  }

  function scrollMessagesToBottom() {
    const container = el("messages");
    container.scrollTop = container.scrollHeight;
  }

  async function loadMessages() {
    if (!state.activeRole) {
      el("messages").innerHTML = `<div class="empty-hint">No session roles yet.</div>`;
      return;
    }
    const role = state.activeRole;
    const entries = await getJson(`/api/messages?role=${encodeURIComponent(role)}`);
    const grewSinceLastLoad = entries.length > (state.lastMessageCountByRole[role] ?? 0);
    state.lastMessageCountByRole[role] = entries.length;

    renderMessages(entries, role);

    if (state.autoScroll && grewSinceLastLoad) {
      scrollMessagesToBottom();
    }
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

  function setupAutoScrollToggle() {
    const checkbox = el("autoscroll-toggle");
    state.autoScroll = checkbox.checked;
    checkbox.addEventListener("change", () => {
      state.autoScroll = checkbox.checked;
      if (state.autoScroll) {
        scrollMessagesToBottom();
      }
    });
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
  setupAutoScrollToggle();
  tick();
  setInterval(tick, POLL_MS);
})();
