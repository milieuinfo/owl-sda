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
    runStartMs: null,
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

  // metadata.duration_ms only covers the CURRENT stage (it resets every time a round/stage
  // boundary snapshot - e.g. GENERATE - restarts the stage clock), so displaying it as "Duration"
  // made a long run look like it had only been going for a few minutes. Total run duration is the
  // latest known snapshot time minus the very first snapshot recorded for this run (state.runStartMs,
  // set from history[0] in loadHistory), falling back to the per-stage value only before history
  // has loaded for the first time.
  function totalRunDurationMs(metadata) {
    const latestSnapshotMs = parseSnapshotTimestamp(metadata.timestamp);
    if (state.runStartMs === null || latestSnapshotMs === null) {
      return Number(metadata.duration_ms || 0);
    }
    return Math.max(0, latestSnapshotMs - state.runStartMs);
  }

  function renderStatRow(metadata, tokens) {
    const stats = [
      ["Shapes Processed", metadata.shapes_processed ?? "--"],
      ["Violations", fmtViolations(metadata.current_violations)],
      ["Triples", fmtNumber(metadata.triplestore_size)],
      ["Duration", fmtDuration(totalRunDurationMs(metadata))],
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

  // LIVE snapshots are just periodic ticks of the wait between real stage transitions (e.g.
  // GENERATE), not distinct stages themselves. A long run accumulates one such wait per round, so
  // keeping every one of them as its own sidebar row would make "LIVE" show up over and over as
  // the run progresses. Only the most recent snapshot can still be an active wait; every earlier
  // LIVE tick is dropped, and consecutive LIVE snapshots at the tail are collapsed into one entry
  // (keeping the latest values) so the sidebar stays bounded to real milestones plus at most one
  // "still working" row.
  function collapseHistory(history) {
    const collapsed = [];
    const lastIndex = history.length - 1;
    for (let i = 0; i < history.length; i++) {
      const snap = history[i];
      if (snap.stage === "LIVE" && i !== lastIndex) {
        continue;
      }
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
        .map(
          (role) => `
        <button class="role-tab" data-role="${role}">
          <span class="role-busy-dot" title="idle"></span>
          <span>${role}</span>
          <span class="role-context"></span>
        </button>`
        )
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

  // Maps a UI role name to the metadata.txt key segment SnapshotWriter uses for it: supervisor
  // and reviewer are written under their own name, but workers are written as "worker.worker_N"
  // (see SnapshotWriter#writeRoleLiveStatus) since "worker_N" alone would collide with the
  // "tokens.worker.worker_N.*" convention already in use.
  function roleMetadataKey(role) {
    const m = /^worker_(\d+)$/.exec(role || "");
    return m ? `worker.worker_${m[1]}` : role;
  }

  // Reflects each role's live status (busy dot + context-window usage) onto the already-rendered
  // role tabs. Kept separate from renderRoleTabs so a plain metadata refresh (every poll tick)
  // doesn't have to rebuild the tab buttons (which would drop the active/click state) just to
  // update these two figures.
  function updateRoleLiveStatus(metadata) {
    el("role-tabs")
      .querySelectorAll(".role-tab")
      .forEach((btn) => {
        const key = roleMetadataKey(btn.dataset.role);
        const busy = metadata[`busy.${key}`] === "true";
        const used = Number(metadata[`context.${key}.used`] || 0);
        const limit = Number(metadata[`context.${key}.limit`] || 0);

        btn.classList.toggle("role-busy", busy);
        const dot = btn.querySelector(".role-busy-dot");
        if (dot) dot.title = busy ? "working" : "idle";

        const ctx = btn.querySelector(".role-context");
        if (ctx) {
          if (limit > 0) {
            const pct = Math.min(100, Math.round((used / limit) * 100));
            ctx.textContent = `${pct}%`;
            ctx.title = `context window: ${fmtNumber(used)} / ${fmtNumber(limit)} tokens (last call)`;
          } else {
            ctx.textContent = "";
            ctx.title = "";
          }
        }
      });
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
    updateRoleLiveStatus(data.metadata);
    el("last-updated").textContent = "updated " + new Date().toLocaleTimeString();
    return data;
  }

  async function loadHistory() {
    const history = await getJson("/api/history");
    const list = Array.isArray(history) ? history : [];
    if (list.length > 0) {
      state.runStartMs = parseSnapshotTimestamp(list[0].timestamp);
    }
    renderHistory(list);
    renderTrends(list);
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

  // --- Trends charts (triples / violations over time) ---------------------

  const CHART_VB_W = 640;
  const CHART_VB_H = 180;
  const CHART_PAD = { top: 12, right: 16, bottom: 24, left: 42 };

  // Each render replaces the container's innerHTML (fresh SVG from the latest history), so the
  // tooltip node is (re)created fresh alongside it rather than persisted across polls.
  function createTooltipEl(container) {
    const tip = document.createElement("div");
    tip.className = "chart-tooltip";
    tip.innerHTML = `<div class="tt-value"></div><div class="tt-time"></div>`;
    container.appendChild(tip);
    return tip;
  }

  // Builds { elapsedMs, value } points from history, using -1/null/undefined as "not measured
  // yet" gaps (e.g. currentViolations before the first SHACL validation of a run) rather than
  // plotting them as real zero/negative values.
  function trendPoints(history, extractValue) {
    return history
      .map((snap) => {
        const ms = parseSnapshotTimestamp(snap.timestamp);
        if (ms === null) return null;
        const raw = extractValue(snap);
        const value = raw === undefined || raw === null || Number(raw) < 0 ? null : Number(raw);
        return { elapsedMs: ms - (state.runStartMs ?? ms), value };
      })
      .filter((p) => p !== null);
  }

  // Splits points into contiguous runs of non-null values, so gaps (unmeasured points) break the
  // line instead of interpolating across them or being drawn as zero.
  function contiguousSegments(points) {
    const segments = [];
    let current = [];
    for (const p of points) {
      if (p.value === null) {
        if (current.length) segments.push(current);
        current = [];
      } else {
        current.push(p);
      }
    }
    if (current.length) segments.push(current);
    return segments;
  }

  function renderLineChart(containerId, points, { color, formatValue }) {
    const container = el(containerId);
    if (!points.length) {
      container.innerHTML = `<div class="chart-empty-hint">No data yet.</div>`;
      return;
    }

    const xs = points.map((p) => p.elapsedMs);
    const values = points.map((p) => p.value).filter((v) => v !== null);
    const xMin = Math.min(...xs);
    const xMax = Math.max(...xs);
    const rawYMin = values.length ? Math.min(...values) : 0;
    const rawYMax = values.length ? Math.max(...values) : 1;
    const yPad = Math.max(1, (rawYMax - rawYMin) * 0.15);
    const yMin = Math.max(0, Math.floor(rawYMin - yPad));
    const yMax = Math.ceil(rawYMax + yPad) || 1;

    const plotW = CHART_VB_W - CHART_PAD.left - CHART_PAD.right;
    const plotH = CHART_VB_H - CHART_PAD.top - CHART_PAD.bottom;

    const px = (ms) => CHART_PAD.left + (xMax === xMin ? plotW / 2 : ((ms - xMin) / (xMax - xMin)) * plotW);
    const py = (v) => CHART_PAD.top + plotH - ((v - yMin) / (yMax - yMin || 1)) * plotH;

    const segments = contiguousSegments(points);
    const pathD = segments
      .map((seg) => (seg.length === 1
        ? `M ${px(seg[0].elapsedMs)} ${py(seg[0].value)} L ${px(seg[0].elapsedMs)} ${py(seg[0].value)}`
        : "M " + seg.map((p) => `${px(p.elapsedMs)} ${py(p.value)}`).join(" L ")))
      .join(" ");

    const gridLines = [yMin, (yMin + yMax) / 2, yMax];
    const gridSvg = gridLines
      .map((v) => {
        const y = py(v);
        return `
          <line class="chart-grid" x1="${CHART_PAD.left}" x2="${CHART_VB_W - CHART_PAD.right}" y1="${y}" y2="${y}" />
          <text class="chart-axis-label" x="${CHART_PAD.left - 6}" y="${y + 3}" text-anchor="end">${formatValue(v)}</text>`;
      })
      .join("");

    const lastValid = [...points].reverse().find((p) => p.value !== null);
    const lastDotSvg = lastValid
      ? `<circle class="chart-dot" cx="${px(lastValid.elapsedMs)}" cy="${py(lastValid.value)}" r="4" fill="${color}" />
         <text class="chart-axis-label" x="${Math.min(px(lastValid.elapsedMs) + 6, CHART_VB_W - CHART_PAD.right - 24)}" y="${py(lastValid.value) - 8}" font-weight="700">${formatValue(lastValid.value)}</text>`
      : "";

    const startLabel = fmtDuration(xMin);
    const endLabel = fmtDuration(xMax);

    container.innerHTML = `
      <svg viewBox="0 0 ${CHART_VB_W} ${CHART_VB_H}" preserveAspectRatio="none">
        ${gridSvg}
        <path class="chart-line" d="${pathD}" stroke="${color}" />
        ${lastDotSvg}
        <text class="chart-axis-label" x="${CHART_PAD.left}" y="${CHART_VB_H - 6}">${startLabel}</text>
        <text class="chart-axis-label" x="${CHART_VB_W - CHART_PAD.right}" y="${CHART_VB_H - 6}" text-anchor="end">${endLabel}</text>
        <line class="chart-crosshair" x1="0" x2="0" y1="${CHART_PAD.top}" y2="${CHART_VB_H - CHART_PAD.bottom}" />
        <rect class="chart-hover-target" x="${CHART_PAD.left}" y="${CHART_PAD.top}" width="${plotW}" height="${plotH}" />
      </svg>`;

    const svg = container.querySelector("svg");
    const crosshair = container.querySelector(".chart-crosshair");
    const hoverTarget = container.querySelector(".chart-hover-target");
    const tip = createTooltipEl(container);

    function nearestPoint(elapsedMs) {
      let best = points[0];
      let bestDist = Infinity;
      for (const p of points) {
        const d = Math.abs(p.elapsedMs - elapsedMs);
        if (d < bestDist) {
          bestDist = d;
          best = p;
        }
      }
      return best;
    }

    hoverTarget.addEventListener("mousemove", (evt) => {
      const rect = svg.getBoundingClientRect();
      const scaleX = CHART_VB_W / rect.width;
      const xInVb = (evt.clientX - rect.left) * scaleX;
      const ms = xMin + ((xInVb - CHART_PAD.left) / plotW) * (xMax - xMin || 1);
      const point = nearestPoint(ms);

      crosshair.setAttribute("x1", px(point.elapsedMs));
      crosshair.setAttribute("x2", px(point.elapsedMs));
      crosshair.style.opacity = "1";

      tip.style.opacity = point.value === null ? "0" : "1";
      tip.querySelector(".tt-value").textContent = point.value === null ? "" : formatValue(point.value);
      tip.querySelector(".tt-time").textContent = fmtDuration(point.elapsedMs) + " into run";

      const containerRect = container.getBoundingClientRect();
      const tipX = rect.left - containerRect.left + px(point.elapsedMs) / scaleX;
      const tipY = rect.top - containerRect.top + (point.value === null ? py(yMax) : py(point.value)) / scaleX;
      tip.style.left = `${tipX}px`;
      tip.style.top = `${Math.max(0, tipY - 8)}px`;
    });
    hoverTarget.addEventListener("mouseleave", () => {
      crosshair.style.opacity = "0";
      tip.style.opacity = "0";
    });
  }

  function renderTrends(history) {
    if (!history.length) {
      el("chart-triples").innerHTML = `<div class="chart-empty-hint">No data yet.</div>`;
      el("chart-violations").innerHTML = `<div class="chart-empty-hint">No data yet.</div>`;
      return;
    }
    const accent = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim();
    const bad = getComputedStyle(document.documentElement).getPropertyValue("--bad").trim();

    renderLineChart(
      "chart-triples",
      trendPoints(history, (s) => s.triplestoreSize),
      { color: accent, formatValue: (v) => fmtNumber(Math.round(v)) }
    );
    renderLineChart(
      "chart-violations",
      trendPoints(history, (s) => s.currentViolations),
      { color: bad, formatValue: (v) => fmtNumber(Math.round(v)) }
    );
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
