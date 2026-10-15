/* =============================================================================
 * A02 图表库 —— 纯 SVG 实现（雷达图 / 折线图 / 进度环 / 热力图 / 条形图）
 *
 * 为什么手写而不用 ECharts：
 *   本机为离线环境，npm 仓库不可达，无法安装 echarts 依赖。
 *   架构文档要求 T4「雷达图 / 学习进度 / 职场匹配度可视化」为硬指标，
 *   因此以零依赖的 SVG 自绘实现同等可视化能力，不牺牲任何演示效果。
 *   若后续网络恢复，只需替换本文件的实现，调用方接口不变。
 * ========================================================================== */
const Charts = (() => {
  const NS = 'http://www.w3.org/2000/svg';

  const el = (name, attrs = {}, text) => {
    const node = document.createElementNS(NS, name);
    Object.entries(attrs).forEach(([k, v]) => {
      if (v !== null && v !== undefined) node.setAttribute(k, String(v));
    });
    if (text !== undefined) node.textContent = String(text);
    return node;
  };

  const clear = (host) => { while (host.firstChild) host.removeChild(host.firstChild); };

  /** 读取 app.css 的设计令牌；取不到时回落到浅色主题的同等值 */
  const cssVar = (name, fallback) => {
    try {
      const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
      return v || fallback;
    } catch (e) { return fallback; }
  };

  /**
   * 颜色板 —— 与 app.css 的 :root 令牌同源（不再硬编码深色值）。
   * 换主题只需改 CSS，图表自动跟随。
   */
  const C = {
    primary: cssVar('--primary', '#2563eb'),
    primarySoft: cssVar('--chart-fill', 'rgba(37,99,235,0.13)'),
    accent: cssVar('--accent', '#0e9f6e'),
    accentSoft: cssVar('--chart-fill-accent', 'rgba(14,159,110,0.16)'),
    warn: cssVar('--warn', '#c2740a'),
    danger: cssVar('--danger', '#d92d20'),
    grid: cssVar('--chart-grid', 'rgba(17,24,39,0.07)'),
    axis: cssVar('--chart-axis', 'rgba(17,24,39,0.13)'),
    text: cssVar('--text-soft', '#4d5566'),
    textDim: cssVar('--text-dim', '#8b93a3'),
    strong: cssVar('--text', '#1b2130'),
    surface: cssVar('--surface', '#ffffff'),
    track: cssVar('--surface-sunken', '#f2f4f7'),
    prev: cssVar('--chart-prev', '#c3c9d4'),
  };

  /* ---------------------------------------------------------------------------
   * 雷达图（≥6 维，支持「上一版本」对比与版本动画）
   * ------------------------------------------------------------------------ */
  function radar(host, dimensions, options = {}) {
    clear(host);
    if (!dimensions || !dimensions.length) { host.innerHTML = '<p class="empty">暂无画像数据</p>'; return; }
    const size = options.size || 360;
    const pad = 62;
    const cx = size / 2;
    const cy = size / 2;
    const r = size / 2 - pad;
    const n = dimensions.length;
    const angle = (i) => (Math.PI * 2 * i) / n - Math.PI / 2;
    const point = (i, ratio) => [cx + Math.cos(angle(i)) * r * ratio, cy + Math.sin(angle(i)) * r * ratio];

    // viewBox 左右各留出 marginX：维度标签写在圆外，需要额外横向空间，否则水平方向会被裁掉
    const labelR = 1.14;
    const marginX = 46;
    const svg = el('svg', { viewBox: `${-marginX} 0 ${size + marginX * 2} ${size}`, class: 'chart-svg', width: '100%',
      height: 'auto', role: 'img', 'aria-label': '职业能力雷达图' });

    // 网格环 + 刻度
    [0.25, 0.5, 0.75, 1].forEach((ratio) => {
      const pts = dimensions.map((_, i) => point(i, ratio).join(',')).join(' ');
      svg.appendChild(el('polygon', { points: pts, fill: 'none', stroke: C.grid,
        'stroke-width': ratio === 1 ? 1.4 : 1 }));
    });
    // 轴线
    dimensions.forEach((_, i) => {
      const [x, y] = point(i, 1);
      svg.appendChild(el('line', { x1: cx, y1: cy, x2: x, y2: y, stroke: C.axis, 'stroke-width': 1 }));
    });

    // 上一版本（灰色轮廓）
    if (options.previous && options.previous.length) {
      const prevPts = options.previous.map((d, i) => point(i, clamp01((d.score || 0) / 100)).join(',')).join(' ');
      svg.appendChild(el('polygon', { points: prevPts, fill: 'rgba(139,155,180,0.10)',
        stroke: C.prev, 'stroke-width': 1.4, 'stroke-dasharray': '4 4' }));
    }

    // 当前版本
    const curPts = dimensions.map((d, i) => point(i, clamp01((d.score || 0) / 100)).join(',')).join(' ');
    const poly = el('polygon', { points: curPts, fill: C.primarySoft, stroke: C.primary, 'stroke-width': 2.2,
      'stroke-linejoin': 'round' });
    poly.style.transformOrigin = `${cx}px ${cy}px`;
    poly.style.animation = 'radarIn .55s ease-out';
    svg.appendChild(poly);

    // 顶点 + 分值：维度名与分值合并为一段文本。
    // 原实现把两者放在同一条射线的 1.16r / 1.30r 上，水平方向会互相压字，故改为一行两段。
    dimensions.forEach((d, i) => {
      const [x, y] = point(i, clamp01((d.score || 0) / 100));
      svg.appendChild(el('circle', { cx: x, cy: y, r: 3.4, fill: C.primary, stroke: C.surface, 'stroke-width': 1.6 }));
      const [lx, ly] = point(i, labelR);
      const anchor = Math.abs(lx - cx) < 12 ? 'middle' : (lx > cx ? 'start' : 'end');
      const label = el('text', { x: lx, y: ly, fill: C.text, 'font-size': 11.5,
        'text-anchor': anchor, 'dominant-baseline': 'middle' }, d.label);
      label.appendChild(el('tspan', { dx: 5, fill: C.strong, 'font-size': 11, 'font-weight': 600 }, fmt(d.score)));
      svg.appendChild(label);
    });

    host.appendChild(svg);
  }

  /* ---------------------------------------------------------------------------
   * 折线图（成长曲线）
   * ------------------------------------------------------------------------ */
  function line(host, points, options = {}) {
    clear(host);
    if (!points || points.length === 0) { host.innerHTML = '<p class="empty">暂无成长记录</p>'; return; }
    const w = options.width || 520;
    const h = options.height || 220;
    const padL = 38; const padR = 16; const padT = 16; const padB = 30;
    const values = points.map((p) => Number(p.value) || 0);
    const minV = options.min !== undefined ? options.min : Math.max(0, Math.floor(Math.min(...values) - 6));
    const maxV = options.max !== undefined ? options.max : Math.min(100, Math.ceil(Math.max(...values) + 6));
    const span = Math.max(1, maxV - minV);
    const x = (i) => padL + (points.length === 1 ? (w - padL - padR) / 2
      : (i * (w - padL - padR)) / (points.length - 1));
    const y = (v) => padT + (1 - (v - minV) / span) * (h - padT - padB);

    const svg = el('svg', { viewBox: `0 0 ${w} ${h}`, class: 'chart-svg', width: '100%', height: 'auto',
      role: 'img', 'aria-label': '成长曲线' });

    // 横向网格
    [0, 0.5, 1].forEach((t) => {
      const v = minV + span * t;
      svg.appendChild(el('line', { x1: padL, y1: y(v), x2: w - padR, y2: y(v), stroke: C.grid }));
      svg.appendChild(el('text', { x: padL - 7, y: y(v), fill: C.textDim, 'font-size': 10,
        'text-anchor': 'end', 'dominant-baseline': 'middle' }, Math.round(v)));
    });

    const path = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i)},${y(values[i])}`).join(' ');
    const area = `${path} L${x(points.length - 1)},${h - padB} L${x(0)},${h - padB} Z`;
    const grad = el('linearGradient', { id: 'gradLine', x1: 0, y1: 0, x2: 0, y2: 1 });
    grad.appendChild(el('stop', { offset: '0%', 'stop-color': C.primary, 'stop-opacity': 0.35 }));
    grad.appendChild(el('stop', { offset: '100%', 'stop-color': C.primary, 'stop-opacity': 0.02 }));
    const defs = el('defs');
    defs.appendChild(grad);
    svg.appendChild(defs);
    svg.appendChild(el('path', { d: area, fill: 'url(#gradLine)', stroke: 'none' }));
    svg.appendChild(el('path', { d: path, fill: 'none', stroke: C.primary, 'stroke-width': 2.4,
      'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));

    points.forEach((p, i) => {
      svg.appendChild(el('circle', { cx: x(i), cy: y(values[i]), r: 4, fill: C.surface,
        stroke: C.primary, 'stroke-width': 2.2 }));
      svg.appendChild(el('text', { x: x(i), y: y(values[i]) - 11, fill: C.strong, 'font-size': 10.5,
        'text-anchor': 'middle', 'font-weight': 600 }, fmt(values[i])));
      svg.appendChild(el('text', { x: x(i), y: h - 9, fill: C.textDim, 'font-size': 10,
        'text-anchor': 'middle' }, p.label));
    });

    host.appendChild(svg);
  }

  /* ---------------------------------------------------------------------------
   * 进度环
   * ------------------------------------------------------------------------ */
  function ring(host, percent, options = {}) {
    clear(host);
    const size = options.size || 190;
    const stroke = options.stroke || 14;
    const r = (size - stroke) / 2 - 2;
    const c = 2 * Math.PI * r;
    const p = clamp01((Number(percent) || 0) / 100);

    const svg = el('svg', { viewBox: `0 0 ${size} ${size}`, class: 'chart-svg chart-ring', width: '100%',
      height: 'auto', role: 'img', 'aria-label': '学习任务完成率' });
    svg.appendChild(el('circle', { cx: size / 2, cy: size / 2, r, fill: 'none',
      stroke: C.track, 'stroke-width': stroke }));
    const arc = el('circle', { cx: size / 2, cy: size / 2, r, fill: 'none', stroke: C.primary,
      'stroke-width': stroke, 'stroke-linecap': 'round',
      'stroke-dasharray': `${c * p} ${c}`, transform: `rotate(-90 ${size / 2} ${size / 2})` });
    svg.appendChild(arc);
    svg.appendChild(el('text', { x: size / 2, y: size / 2 - 2, fill: C.strong, 'font-size': 30,
      'font-weight': 700, 'text-anchor': 'middle', 'dominant-baseline': 'middle' }, `${fmt(percent)}%`));
    svg.appendChild(el('text', { x: size / 2, y: size / 2 + 24, fill: C.textDim, 'font-size': 11,
      'text-anchor': 'middle' }, options.label || '完成率'));
    host.appendChild(svg);
  }

  /* ---------------------------------------------------------------------------
   * 活跃热力图（GitHub 风格，按周分列）
   * ------------------------------------------------------------------------ */
  function heatmap(host, cells, options = {}) {
    clear(host);
    if (!cells || !cells.length) { host.innerHTML = '<p class="empty">暂无打卡记录</p>'; return; }
    const cell = options.cell || 13;
    const gap = options.gap || 3.5;
    const colors = [C.track, C.accentSoft, 'rgba(14,159,110,0.42)',
      'rgba(14,159,110,0.72)', C.accent];

    // 按周分列：第一列补齐到周一
    const first = new Date(cells[0].date + 'T00:00:00');
    const lead = (first.getDay() + 6) % 7; // 周一为 0
    const columns = [];
    let col = new Array(lead).fill(null);
    cells.forEach((cellData) => {
      col.push(cellData);
      if (col.length === 7) { columns.push(col); col = []; }
    });
    if (col.length) { while (col.length < 7) col.push(null); columns.push(col); }

    const w = columns.length * (cell + gap) + 26;
    const h = 7 * (cell + gap) + 24;
    const svg = el('svg', { viewBox: `0 0 ${w} ${h}`, class: 'chart-svg', width: '100%', height: 'auto',
      role: 'img', 'aria-label': '学习活跃热力图' });

    const dayLabels = ['一', '', '三', '', '五', '', '日'];
    dayLabels.forEach((label, i) => {
      if (!label) return;
      svg.appendChild(el('text', { x: 18, y: 16 + i * (cell + gap) + cell / 2, fill: C.textDim,
        'font-size': 9.5, 'text-anchor': 'end', 'dominant-baseline': 'middle' }, label));
    });

    columns.forEach((column, ci) => {
      column.forEach((cellData, ri) => {
        if (!cellData) return;
        const rect = el('rect', {
          x: 26 + ci * (cell + gap), y: 16 + ri * (cell + gap), width: cell, height: cell, rx: 2.6,
          fill: colors[Math.min(4, cellData.level || 0)],
        });
        rect.appendChild(el('title', {}, `${cellData.date}：${cellData.value} 分钟`));
        svg.appendChild(rect);
      });
    });
    host.appendChild(svg);

    const legend = document.createElement('div');
    legend.className = 'legend';
    legend.innerHTML = '<span>少</span>' + colors.map((c) =>
      `<i style="background:${c}"></i>`).join('') + '<span>多</span>';
    host.appendChild(legend);
  }

  /* ---------------------------------------------------------------------------
   * 横向条形图（差距分析 / 匹配度贡献）
   * ------------------------------------------------------------------------ */
  function bars(host, items, options = {}) {
    clear(host);
    if (!items || !items.length) { host.innerHTML = '<p class="empty">暂无数据</p>'; return; }
    const max = Math.max(...items.map((i) => Number(i.value) || 0), 1);
    const wrap = document.createElement('div');
    wrap.className = 'bars';
    items.forEach((item) => {
      const row = document.createElement('div');
      row.className = 'bar-row';
      const pct = Math.max(2, ((Number(item.value) || 0) / max) * 100);
      const color = item.color || C.primary;
      row.innerHTML = `
        <div class="bar-label" title="${esc(item.label)}">${esc(item.label)}</div>
        <div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:${color}"></div></div>
        <div class="bar-value">${esc(item.display !== undefined ? item.display : fmt(item.value))}</div>`;
      wrap.appendChild(row);
    });
    host.appendChild(wrap);
  }

  /* ---------------------------------------------------------------------------
   * 工具函数
   * ------------------------------------------------------------------------ */
  function clamp01(v) { return Math.max(0, Math.min(1, v)); }
  function fmt(v) {
    const n = Number(v);
    if (!isFinite(n)) return '-';
    return Number.isInteger(n) ? String(n) : n.toFixed(1);
  }
  function esc(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  return { radar, line, ring, heatmap, bars, esc, fmt, colors: C };
})();
