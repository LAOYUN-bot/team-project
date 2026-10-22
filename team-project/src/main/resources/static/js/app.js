/* =============================================================================
   灯塔 · 职业成长
   面向使用者的产品界面。
   ========================================================================== */
(() => {
  const { api } = Api;
  const esc = Charts.esc;
  const fmt = Charts.fmt;

  /* ---------------------------------------------------------------- 图标 */
  const ICONS = {
    home: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V21h13V9.5"/><path d="M9.5 21v-6h5v6"/></svg>',
    ability: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"/><circle cx="12" cy="12" r="3.6"/></svg>',
    path: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="19" r="2.4"/><circle cx="18" cy="5" r="2.4"/><path d="M8.4 19H14a3.6 3.6 0 0 0 0-7.2H9.6a3.6 3.6 0 0 1 0-7.2h5.9"/></svg>',
    plan: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 5h11M9 12h11M9 19h11"/><path d="M4 5.2 5 6.4 7 4M4 12.2l1 1.2L7 11M4 19.2l1 1.2L7 18"/></svg>',
    practice: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5.5h16v10H9l-4 3.5v-13.5Z"/><path d="M8.5 9.5h7M8.5 12.5h4"/></svg>',
    partner: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5 13.7 9l5.5 1.7-5.5 1.7L12 18l-1.7-5.6L4.8 10.7 10.3 9 12 3.5Z"/><path d="M18.5 16.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8.8-2.2Z"/></svg>',
    library: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5.2A2.2 2.2 0 0 1 6.2 3H19v15.5H6.2A2.2 2.2 0 0 0 4 20.7V5.2Z"/><path d="M4 18.5A2.2 2.2 0 0 1 6.2 20.7H19"/><path d="M8 7.5h7M8 11h5"/></svg>',
    settings: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3.2"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"/></svg>',
  };

  const NAV = [
    { key: 'home', label: '今日', icon: 'home' },
    { key: 'ability', label: '我的能力', icon: 'ability' },
    { key: 'path', label: '职业路径', icon: 'path' },
    { key: 'plan', label: '学习计划', icon: 'plan' },
    { group: '练习与交流' },
    { key: 'practice', label: '情景练习', icon: 'practice' },
    { key: 'partner', label: '职业伙伴', icon: 'partner' },
    { key: 'library', label: '资料问答', icon: 'library' },
    { key: 'settings', label: '设置', icon: 'settings' },
  ];

  const state = {
    me: null,
    route: 'home',
    authMode: 'login',
    practice: null,
    partnerSessionId: null,
    partnerMessages: [],
  };

  /* ---------------------------------------------------------------- 基础 */
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  function toast(message, kind = '', ms = 2800) {
    const box = $('#toast');
    if (!box) return;
    const item = document.createElement('div');
    item.className = `toast ${kind}`;
    item.textContent = message;
    box.appendChild(item);
    setTimeout(() => item.remove(), ms);
  }

  const ok = (m) => toast(m, 'ok');
  const err = (m) => toast(m, 'err', 4000);
  const spin = '<span class="spin"></span>';

  /** 把服务端错误翻译成人话，不展示错误码与堆栈 */
  function friendlyMessage(e) {
    if (!e) return '操作没有完成，请稍后再试';
    if (e.status === 401 || e.code === 20001) return '登录已过期，请重新登录';
    if (e.code === 20002) return '用户名或密码不正确';
    if (e.code === 20004) return '这个用户名已经被使用了';
    if (e.code === 30002) return '请先完善你的基本资料';
    if (e.code === 30004) return '还没有职业路径，先制定一个吧';
    if (e.code === 30007) return '操作有点频繁，稍等一下再试';
    if (e.code === 50001 || e.code === 50002) return 'AI 服务暂时不可用，已切换为简化结果';
    if (e.code === 40001 || e.code === 40002) return '服务暂时不可用，请稍后重试';
    return e.message || '操作没有完成，请稍后再试';
  }

  async function guard(fn, successMessage) {
    try {
      const result = await fn();
      if (successMessage) ok(successMessage);
      return result;
    } catch (e) {
      if (e.status === 401 || e.code === 20001) {
        state.me = null;
        renderAuth();
        return null;
      }
      err(friendlyMessage(e));
      return null;
    }
  }

  const badge = (text, kind = '') => `<span class="badge ${kind}">${esc(text)}</span>`;
  const tag = (text, kind = '') => `<span class="tag ${kind}">${esc(text)}</span>`;
  const emptyBlock = (text) => `<p class="empty">${esc(text)}</p>`;
  const loadingBlock = (text = '正在加载…') => `<div class="loading-block">${spin} ${esc(text)}</div>`;

  function pageHead(title, desc, actions = '') {
    return `
      <div class="page-head">
        <div>
          <h1 class="page-title">${esc(title)}</h1>
          ${desc ? `<div class="page-desc">${esc(desc)}</div>` : ''}
        </div>
        ${actions ? `<div class="btn-row">${actions}</div>` : ''}
      </div>`;
  }

  function levelBadge(level) {
    const kind = ['优秀', '高度匹配'].includes(level) ? 'accent'
      : ['良好', '较匹配'].includes(level) ? 'primary'
      : ['合格', '部分匹配'].includes(level) ? 'warn' : 'danger';
    return badge(level || '—', kind);
  }

  const greeting = () => {
    const h = new Date().getHours();
    if (h < 6) return '夜深了';
    if (h < 12) return '早上好';
    if (h < 14) return '中午好';
    if (h < 19) return '下午好';
    return '晚上好';
  };

  /* ---------------------------------------------------------------- 登录 */
  function renderAuth() {
    const isLogin = state.authMode === 'login';
    $('#root').innerHTML = `
      <div class="auth-wrap">
        <div class="auth-card">
          <div class="auth-side">
            <div class="brand">
              <div class="brand-logo" style="background:rgba(255,255,255,.18)">灯</div>
              <div>
                <div class="brand-title" style="color:#fff">灯塔</div>
                <div class="brand-sub" style="color:rgba(255,255,255,.78)">职业成长</div>
              </div>
            </div>
            <p style="margin-top:22px">把职业目标拆成今天能做的一件事。</p>
            <div class="points">
              <div class="point">${ICONS.ability}<span>看清自己现在的位置</span></div>
              <div class="point">${ICONS.path}<span>得到一条真正能执行的路线</span></div>
              <div class="point">${ICONS.practice}<span>每一步都有具体的反馈</span></div>
            </div>
          </div>
          <div class="auth-form">
            <div class="tabs">
              <button class="tab ${isLogin ? 'active' : ''}" data-mode="login">登录</button>
              <button class="tab ${isLogin ? '' : 'active'}" data-mode="register">注册</button>
            </div>
            <div class="field">
              <label>用户名</label>
              <input id="auth-username" autocomplete="username" placeholder="请输入用户名" />
            </div>
            <div class="field">
              <label>密码</label>
              <input id="auth-password" type="password" autocomplete="current-password" placeholder="请输入密码" />
            </div>
            ${isLogin ? '' : `
            <div class="field">
              <label>昵称</label>
              <input id="auth-nickname" placeholder="怎么称呼你（可不填）" />
            </div>`}
            <button class="primary" id="auth-submit" style="width:100%;justify-content:center;padding:10px">
              ${isLogin ? '登录' : '创建账号'}
            </button>
            <p class="hint" style="margin-top:14px">
              继续即表示同意我们按最小必要原则处理你的信息，你可以随时导出或删除。
            </p>
          </div>
        </div>
      </div>`;
    $$('.tab').forEach((t) => t.addEventListener('click', () => {
      state.authMode = t.dataset.mode;
      renderAuth();
    }));
    $('#auth-submit').addEventListener('click', submitAuth);
    $('#auth-password').addEventListener('keydown', (e) => { if (e.key === 'Enter') submitAuth(); });
  }

  async function submitAuth() {
    const username = $('#auth-username').value.trim();
    const password = $('#auth-password').value;
    const nickname = $('#auth-nickname') ? $('#auth-nickname').value.trim() : '';
    if (!username || !password) { err('请填写用户名和密码'); return; }
    const btn = $('#auth-submit');
    btn.disabled = true;
    btn.innerHTML = `${spin} 请稍候`;
    try {
      const data = state.authMode === 'login'
        ? await api.login({ username, password })
        : await api.register({ username, password, nickname });
      state.me = data;
      state.practice = null;
      state.partnerSessionId = null;
      state.partnerMessages = [];
      await boot();
    } catch (e) {
      err(friendlyMessage(e));
      btn.disabled = false;
      btn.textContent = state.authMode === 'login' ? '登录' : '创建账号';
    }
  }

  /* ---------------------------------------------------------------- 外壳 */
  function renderShell() {
    const navHtml = NAV.map((item) => {
      if (item.group) return `<div class="nav-group">${esc(item.group)}</div>`;
      const active = state.route === item.key ? 'active' : '';
      return `<button class="nav-item ${active}" data-route="${item.key}">
                ${ICONS[item.icon] || ''}<span>${esc(item.label)}</span>
              </button>`;
    }).join('');

    const name = state.me?.nickname || state.me?.username || '';
    $('#root').innerHTML = `
      <div class="app">
        <aside class="sidebar">
          <div class="brand">
            <div class="brand-logo">灯</div>
            <div>
              <div class="brand-title">灯塔</div>
              <div class="brand-sub">职业成长</div>
            </div>
          </div>
          <nav class="nav">${navHtml}</nav>
          <div class="sidebar-foot">
            <div class="avatar">${esc(name.slice(0, 1) || '我')}</div>
            <div style="min-width:0">
              <div style="font-size:13px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(name)}</div>
              <div class="hint" style="margin:0">我的成长空间</div>
            </div>
          </div>
        </aside>
        <main class="main"><div class="container" id="page"></div></main>
      </div>`;

    $$('.nav-item').forEach((el) => el.addEventListener('click', () => route(el.dataset.route)));
  }

  /* ==================================================================
   * 今日
   * ================================================================== */
  async function viewHome() {
    const [panel, companion] = await Promise.all([api.growthPanel(), api.companionState()]);
    const dims = [...(panel.radar?.dimensions || [])].sort((a, b) => b.score - a.score);
    const progress = panel.progress || {};
    const alerts = panel.alerts || [];
    const milestones = companion.milestones || [];
    const name = state.me?.nickname || state.me?.username || '';
    const strengths = dims.slice(0, 3);
    const focus = dims.slice(-2).reverse();

    $('#page').innerHTML = `
      ${pageHead(`${greeting()}，${name}`, '今天也按自己的节奏来。')}
      <div class="grid bento">
        <div class="span-4 card">
          <div class="flex-between" style="align-items:flex-start">
            <div>
              <div class="card-title">今天的第一件事</div>
              <div class="card-sub">完成一件小事，比计划十件更有用</div>
            </div>
            ${badge(`连续 ${companion.streakDays || 0} 天`, 'accent')}
          </div>
          <div class="callout accent" style="margin-top:14px">${esc(companion.message || '')}</div>
          <div style="margin-top:14px">
            <div class="hint" style="margin-bottom:6px">接下来</div>
            <div style="font-size:14px;font-weight:600">${esc(companion.nextAction || '完成一次能力自评')}</div>
          </div>
          <div class="btn-row" style="margin-top:16px">
            <button class="primary" id="btn-checkin">完成今日打卡</button>
            <button class="ghost" data-go="practice">做一次练习</button>
          </div>
          ${milestones.length ? `
          <div style="margin-top:16px;border-top:1px solid var(--line);padding-top:12px">
            <div class="hint" style="margin-bottom:6px">已达成</div>
            ${milestones.map((m) => tag(m.title, m.achieved ? 'accent' : '')).join('')}
          </div>` : ''}
        </div>

        <div class="span-2 card">
          <div class="card-title">学习进度</div>
          <div class="chart-ring" id="home-ring" style="margin-top:6px"></div>
          <div class="metric-inline" style="margin-top:12px;justify-content:space-between">
            <div class="item"><div class="n">${progress.doneTasks ?? 0}</div><div class="l">已完成</div></div>
            <div class="item"><div class="n">${progress.totalTasks ?? 0}</div><div class="l">总任务</div></div>
            <div class="item"><div class="n">${Math.round((progress.totalMinutes ?? 0) / 60)}</div><div class="l">小时</div></div>
          </div>
        </div>

        <div class="span-3 card">
          <div class="card-head">
            <div>
              <div class="card-title">能力概览</div>
              <div class="card-sub">综合了自评、练习表现与学习情况</div>
            </div>
            <button class="btn ghost sm" data-go="ability" style="border:none;background:none;color:var(--primary);padding:0">查看详情</button>
          </div>
          <div class="chart-host" id="home-radar"></div>
        </div>

        <div class="span-3 card">
          <div class="card-title">你的样子</div>
          <div style="margin-top:12px">
            <div class="hint" style="margin-bottom:6px">比较擅长</div>
            ${strengths.map((d) => `<div class="kv"><span class="k">${esc(d.label)}</span><span class="v">${fmt(d.score)}</span></div>`).join('') || emptyBlock('还没有数据')}
          </div>
          <div style="margin-top:14px">
            <div class="hint" style="margin-bottom:6px">可以加强</div>
            ${focus.map((d) => `<div class="kv"><span class="k">${esc(d.label)}</span><span class="v">${fmt(d.score)}</span></div>`).join('') || ''}
          </div>
        </div>

        ${alerts.map((a) => `
        <div class="span-6 card" style="padding:16px 20px">
          <div class="flex-between">
            <div class="callout warn" style="flex:1;margin-right:14px">${esc(a.message)}</div>
            <button class="btn ghost sm" data-dismiss-alert="${a.id}">知道了</button>
          </div>
        </div>`).join('')}

        <div class="span-6 card">
          <div class="card-title">从这里开始</div>
          <div class="grid cols-3" style="margin-top:14px">
            <button class="list-item" data-go="path" style="text-align:left;cursor:pointer">
              <div class="li-main"><div class="li-title">看看你的路线</div><div class="li-sub">每个阶段要做什么，都写清楚了</div></div>
            </button>
            <button class="list-item" data-go="plan" style="text-align:left;cursor:pointer">
              <div class="li-main"><div class="li-title">完成今天的学习</div><div class="li-sub">从最小的那个单元开始</div></div>
            </button>
            <button class="list-item" data-go="partner" style="text-align:left;cursor:pointer">
              <div class="li-main"><div class="li-title">和伙伴聊聊</div><div class="li-sub">说说最近卡住的地方</div></div>
            </button>
          </div>
        </div>
      </div>`;

    Charts.ring($('#home-ring'), progress.percent || 0, { label: '完成率', size: 168 });
    if (dims.length) Charts.radar($('#home-radar'), dims, { size: 300 });

    $('#btn-checkin').addEventListener('click', () => guard(async () => {
      await api.checkin(30, null);
      await route('home');
    }, '今天的学习已记录'));

    $$('[data-go]').forEach((el) => el.addEventListener('click', () => route(el.dataset.go)));
    $$('[data-dismiss-alert]').forEach((el) => el.addEventListener('click', () => guard(async () => {
      await api.resolveAlert(Number(el.dataset.dismissAlert));
      await route('home');
    })));
  }

  /* ==================================================================
   * 我的能力
   * ================================================================== */
  async function viewAbility() {
    const [panel, versions, match] = await Promise.all([
      api.growthPanel(),
      api.radarVersions().catch(() => []),
      api.match().catch(() => null),
    ]);
    const gap = await api.gap().catch(() => null);
    const radar = panel.radar || { dimensions: [], version: 0 };
    const dims = radar.dimensions || [];

    let previous = null;
    if (radar.previousVersion) {
      const prev = await api.radar(radar.previousVersion).catch(() => null);
      previous = prev ? prev.dimensions : null;
    }

    const versionOptions = (v) => (versions || [])
      .map((x) => `<option value="${x.version}" ${Number(x.version) === v ? 'selected' : ''}>第 ${x.version} 次</option>`)
      .join('');

    $('#page').innerHTML = `
      ${pageHead('我的能力', '看见自己的位置，也知道下一步往哪走。',
        `<button class="btn ghost sm" id="btn-refresh-ability">重新评估</button>`)}
      <div class="grid bento">
        <div class="span-3 card">
          <div class="card-head">
            <div>
              <div class="card-title">能力图谱</div>
              <div class="card-sub">${radar.version ? `第 ${radar.version} 次评估` : '还没有评估'}${radar.previousVersion ? ' · 虚线为上一次' : ''}</div>
            </div>
          </div>
          <div class="chart-host" id="ability-radar"></div>
        </div>

        <div class="span-3 card">
          <div class="card-title">与目标岗位的匹配度</div>
          ${match ? `
            <div class="stat" style="margin-top:8px">
              <div class="stat-value">${fmt(match.matchRate)}<span style="font-size:16px;font-weight:500;color:var(--text-dim)">%</span></div>
              <div class="stat-hint">${esc(match.jobName || '')} · ${esc(match.level || '')}</div>
            </div>
            <div class="progress ${match.matchRate >= 70 ? 'accent' : ''}" style="margin:12px 0 16px"><i style="width:${Math.min(100, match.matchRate)}%"></i></div>
            <div class="hint" style="margin-bottom:8px">按能力维度看</div>
            <div id="match-bars"></div>
          ` : `<div class="callout muted" style="margin-top:10px">在设置里选择目标岗位后，这里会显示匹配度。</div>`}
        </div>

        <div class="span-6 card">
          <div class="card-head">
            <div>
              <div class="card-title">最值得补的几项</div>
              <div class="card-sub">按「对目标岗位的重要程度」和「你目前的差距」排序</div>
            </div>
          </div>
          ${gap && gap.items?.length ? '<div id="gap-bars"></div>' : '<div class="callout muted">完成能力自评后，这里会给出建议。</div>'}
        </div>

        <div class="span-6 card">
          <div class="card-head">
            <div>
              <div class="card-title">能力变化</div>
              <div class="card-sub">对比两次评估，看看哪些方面在进步</div>
            </div>
            <div class="btn-row">
              <select id="cmp-a" style="width:auto">${versionOptions(radar.previousVersion || 1)}</select>
              <span class="muted">对比</span>
              <select id="cmp-b" style="width:auto">${versionOptions(radar.version || 1)}</select>
              <button class="btn sm" id="btn-compare">查看变化</button>
            </div>
          </div>
          <div id="compare-result"><div class="callout muted">选择两次评估，查看每一项的变化。</div></div>
        </div>

        <div class="span-6 card" style="padding:16px 22px">
          <details>
            <summary style="cursor:pointer;font-size:13px;color:var(--text-soft)">这些分数是怎么来的？</summary>
            <div style="margin-top:12px;font-size:13px;color:var(--text-soft);line-height:1.8">
              你的每一项能力分数，来自三个方面的综合：能力自评问卷、情景练习中的表现、以及学习任务的完成情况。
              不同维度侧重的来源不同，例如「沟通表达」主要看练习表现，「学习力」更看重完成率和坚持情况。
              每次评估都会保留一份历史记录，方便你回看变化。
            </div>
          </details>
        </div>
      </div>`;

    if (dims.length) Charts.radar($('#ability-radar'), dims, { size: 360, previous });

    if (match) {
      Charts.bars($('#match-bars'), (match.dimensionContributions || []).slice(0, 5).map((d) => ({
        label: d.dimensionLabel,
        value: d.radarScore,
        display: `${fmt(d.radarScore)}`,
        color: Charts.colors.primary,
      })));
    }
    if (gap?.items?.length) {
      Charts.bars($('#gap-bars'), gap.items.slice(0, 6).map((i) => ({
        label: i.skillName,
        value: i.gap,
        display: `差 ${fmt(i.gap)}`,
        color: i.gap > 30 ? Charts.colors.danger : i.gap > 15 ? Charts.colors.warn : Charts.colors.accent,
      })));
    }

    $('#btn-refresh-ability').addEventListener('click', () => guard(async () => {
      await api.recalculateRadar();
      await route('ability');
    }, '已更新你的能力评估'));

    $('#btn-compare').addEventListener('click', () => guard(async () => {
      const a = Number($('#cmp-a').value);
      const b = Number($('#cmp-b').value);
      if (a === b) { err('请选择两次不同的评估'); return; }
      const r = await api.growthCompare(a, b);
      $('#compare-result').innerHTML = `
        <div class="callout ${r.avgDelta >= 0 ? 'accent' : 'warn'}">${esc(r.summary || '')}</div>
        <div id="cmp-chart" style="margin-top:14px"></div>`;
      Charts.bars($('#cmp-chart'), (r.diff || []).map((d) => ({
        label: d.label,
        value: Math.abs(d.delta),
        display: `${d.delta >= 0 ? '+' : ''}${fmt(d.delta)}`,
        color: d.delta >= 0 ? Charts.colors.accent : Charts.colors.danger,
      })));
    }));
  }

  /* ==================================================================
   * 职业路径
   * ================================================================== */
  async function viewPath() {
    const [path, match] = await Promise.all([
      api.path().catch(() => ({ exists: false })),
      api.match().catch(() => null),
    ]);

    const stageHtml = (path.stages || []).map((s) => `
      <div class="stage">
        <div class="stage-card">
          <div class="stage-head">
            <div>
              <div class="stage-name">${esc(s.name)}</div>
              <div class="stage-meta">第 ${s.startMonth}–${s.endMonth} 个月</div>
            </div>
            <div class="btn-row">
              <button class="btn ghost sm" data-edit-stage="${s.id}">调整目标</button>
            </div>
          </div>
          <div class="stage-goal">${esc(s.goal || '')}</div>
          <div>${(s.skills || []).map((k) => tag(k, 'primary')).join('')}</div>
          ${(s.outputs || []).map((o) => `<div class="output">完成标志：${esc(o)}</div>`).join('')}
          ${(s.units || []).length ? `
          <div class="units">
            <div class="hint" style="margin-bottom:4px">按周安排</div>
            ${s.units.map((u) => `
              <div class="unit-row">
                <div class="unit-week">${esc(u.period || '')}</div>
                <div class="unit-body">
                  <div class="unit-title">${esc(u.title)}</div>
                  <div class="unit-sub">${esc(u.deliverable || '')}${u.estHours ? ` · 约 ${fmt(u.estHours)} 小时` : ''}</div>
                </div>
              </div>`).join('')}
          </div>` : ''}
        </div>
      </div>`).join('');

    $('#page').innerHTML = `
      ${pageHead('职业路径', '一条能走完的路，而不是一句「好好努力」。')}
      <div class="grid bento">
        <div class="span-6 card">
          <div class="card-head">
            <div>
              <div class="card-title">${path.exists && path.stages?.length ? esc(path.targetJobName || '我的发展方向') : '还没有制定路线'}</div>
              <div class="card-sub">${path.exists && path.stages?.length
                ? `预计 ${path.horizonYears} 年 · 共 ${path.stages.length} 个阶段${match ? ` · 匹配度 ${fmt(match.matchRate)}%` : ''}`
                : '告诉我你的目标，我来帮你拆成阶段'}</div>
            </div>
            <div class="btn-row">
              <select id="horizon" style="width:auto">
                <option value="3">3 年</option><option value="4" selected>4 年</option><option value="5">5 年</option>
              </select>
              <button class="primary" id="btn-generate">${path.exists ? '重新制定' : '开始制定'}</button>
              ${path.exists ? '<button class="btn ghost" id="btn-replan">换个思路</button>' : ''}
            </div>
          </div>
          <div id="path-progress"></div>
          ${path.exists && path.stages?.length
            ? `<div class="timeline" style="margin-top:6px">${stageHtml}</div>`
            : '<div class="callout muted">路线会分成几个阶段，每个阶段都有明确的目标、要掌握的技能，以及可以拿出来看的结果。</div>'}
        </div>
      </div>`;

    $('#btn-generate').addEventListener('click', () => generatePath(Number($('#horizon').value)));
    const replan = $('#btn-replan');
    if (replan) replan.addEventListener('click', () => guard(async () => {
      await api.replan();
      await route('path');
    }, '路线已更新'));

    $$('[data-edit-stage]').forEach((el) => el.addEventListener('click', () => {
      const stageId = Number(el.dataset.editStage);
      const stage = (path.stages || []).find((s) => s.id === stageId);
      const goal = prompt('调整这个阶段想达到的状态：', stage?.goal || '');
      if (goal === null) return;
      guard(async () => {
        await api.updateStage(stageId, { goal });
        await route('path');
      }, '已更新');
    }));
  }

  async function generatePath(horizonYears) {
    const host = $('#path-progress');
    const steps = [
      ['ANALYZE', '了解你的情况'],
      ['GENERATE', '安排成长阶段'],
      ['VALIDATE', '检查每个阶段要做的事'],
      ['PERSIST', '保存计划'],
    ];
    host.innerHTML = `<div class="steps" style="margin-bottom:16px">${steps.map(([k, label]) =>
      `<div class="step" data-step="${k}"><span class="dot"></span>${esc(label)}</div>`).join('')}</div>`;
    const setStep = (key, cls) => {
      const el = $(`[data-step="${key}"]`, host);
      if (!el) return;
      el.className = `step ${cls}`;
      el.querySelector('.dot').textContent = cls === 'done' ? '✓' : '';
    };

    const btn = $('#btn-generate');
    btn.disabled = true;
    try {
      await api.generatePathStream({ horizonYears }, (name, data) => {
        if (name === 'progress') {
          if (data.step === 'ANALYZE') setStep('ANALYZE', 'active');
          else if (data.step === 'PROMPT') { setStep('ANALYZE', 'done'); setStep('GENERATE', 'active'); }
          else if (data.step === 'GENERATE') setStep('GENERATE', 'active');
          else if (data.step === 'VALIDATE') setStep('VALIDATE', 'active');
          else if (data.step === 'PERSIST') { setStep('VALIDATE', 'done'); setStep('PERSIST', 'active'); }
        } else if (name === 'done') {
          steps.forEach(([k]) => setStep(k, 'done'));
        } else if (name === 'error') {
          host.innerHTML = `<div class="callout danger">${esc(data.message || '生成没有完成，请稍后重试')}</div>`;
        }
      });
      ok('路线已生成');
      setTimeout(() => route('path'), 500);
    } catch (e) {
      err(friendlyMessage(e));
    } finally {
      btn.disabled = false;
    }
  }

  /* ==================================================================
   * 学习计划
   * ================================================================== */
  async function viewPlan() {
    const [tasks, units, stats] = await Promise.all([
      api.tasks(), api.units().catch(() => []), api.learningStats(),
    ]);

    const taskHtml = tasks.length ? tasks.map((t) => `
      <div class="list-item ${t.status === 'DONE' ? 'done' : ''}">
        <div class="check ${t.status === 'DONE' ? 'on' : ''}" data-task="${t.id}" data-status="${t.status}">✓</div>
        <div class="li-main">
          <div class="li-title">${esc(t.title)}</div>
          <div class="li-sub">${esc(t.stageName || '')}${t.dueDate ? ` · 建议在 ${esc(t.dueDate)} 前完成` : ''}</div>
        </div>
      </div>`).join('') : emptyBlock('还没有学习任务');

    const grouped = {};
    (units || []).forEach((u) => {
      const key = u.stageName || '学习单元';
      (grouped[key] = grouped[key] || []).push(u);
    });
    const unitHtml = Object.keys(grouped).length ? Object.entries(grouped).map(([stage, list]) => `
      <div style="margin-bottom:18px">
        <div class="hint" style="margin-bottom:8px">${esc(stage)}</div>
        <div class="list">
          ${list.map((u) => `
            <div class="list-item">
              <div class="li-main">
                <div class="li-title">${esc(u.period || '')} ${esc(u.title)}</div>
                <div class="li-sub">${esc(u.deliverable || '')}${u.estHours ? ` · 约 ${fmt(u.estHours)} 小时` : ''}</div>
              </div>
            </div>`).join('')}
        </div>
      </div>`).join('') : emptyBlock('制定职业路径后，这里会出现按周安排的学习内容');

    $('#page').innerHTML = `
      ${pageHead('学习计划', '把大目标拆成今天就能完成的小事。',
        '<button class="primary" id="btn-checkin">今日打卡</button>')}
      <div class="grid bento">
        <div class="span-2 card">
          <div class="stat">
            <div class="stat-label">任务完成率</div>
            <div class="stat-value">${fmt(stats.percent)}<span style="font-size:15px;font-weight:500;color:var(--text-dim)">%</span></div>
          </div>
          <div class="progress accent" style="margin:12px 0 16px"><i style="width:${Math.min(100, stats.percent || 0)}%"></i></div>
          <div class="kv"><span class="k">连续学习</span><span class="v">${stats.streakDays || 0} 天</span></div>
          <div class="kv"><span class="k">累计时长</span><span class="v">${Math.round((stats.totalMinutes || 0) / 60)} 小时</span></div>
          <div class="kv"><span class="k">完成任务</span><span class="v">${stats.doneTasks || 0} / ${stats.totalTasks || 0}</span></div>
        </div>

        <div class="span-4 card">
          <div class="card-head">
            <div>
              <div class="card-title">本周任务</div>
              <div class="card-sub">点一下圆圈即可标记完成</div>
            </div>
            ${tasks.length ? '' : '<button class="btn sm" id="btn-create-tasks">生成任务</button>'}
          </div>
          <div class="list">${taskHtml}</div>
        </div>

        <div class="span-6 card">
          <div class="card-head">
            <div>
              <div class="card-title">学习内容</div>
              <div class="card-sub">每一周都有明确要做出来的东西</div>
            </div>
          </div>
          ${unitHtml}
        </div>
      </div>`;

    $('#btn-checkin').addEventListener('click', () => guard(async () => {
      await api.checkin(30, null);
      await route('plan');
    }, '今天的学习已记录'));

    const createBtn = $('#btn-create-tasks');
    if (createBtn) createBtn.addEventListener('click', () => guard(async () => {
      await api.generateTasks(8);
      await route('plan');
    }, '任务已生成'));

    $$('[data-task]').forEach((el) => el.addEventListener('click', () => guard(async () => {
      const next = el.dataset.status === 'DONE' ? 'TODO' : 'DONE';
      await api.updateTask(Number(el.dataset.task), next);
      await route('plan');
    })));
  }

  /* ==================================================================
   * 情景练习
   * ================================================================== */
  async function viewPractice() {
    const scenarios = await api.scenarios();
    const current = state.practice;

    if (!current) {
      $('#page').innerHTML = `
        ${pageHead('情景练习', '在真实的工作场景里，把话说到点上。')}
        <div class="grid cols-3">
          ${scenarios.map((s) => `
            <div class="card">
              <div class="flex-between" style="align-items:flex-start">
                <div class="card-title">${esc(s.name)}</div>
                ${badge(s.difficulty === 'HARD' ? '有难度' : '适中', s.difficulty === 'HARD' ? 'warn' : '')}
              </div>
              <div class="card-sub" style="margin-top:8px;line-height:1.7">${esc(s.description || '')}</div>
              <div style="margin-top:12px">${(s.rubric || []).map((r) => tag(r.name)).join('')}</div>
              <div class="btn-row" style="margin-top:16px">
                <button class="primary sm" data-start="${s.id}">开始练习</button>
              </div>
            </div>`).join('')}
        </div>`;
      $$('[data-start]').forEach((el) => el.addEventListener('click', () => guard(async () => {
        state.practice = await api.startSimulation(Number(el.dataset.start));
        await route('practice');
      })));
      return;
    }

    const ev = current.evaluation && current.evaluation.exists ? current.evaluation : null;
    $('#page').innerHTML = `
      ${pageHead(current.scenarioName || '情景练习', '像真实沟通一样，把话说明白。',
        '<button class="btn ghost" id="btn-quit-practice">结束练习</button>')}
      <div class="grid bento">
        <div class="span-3 card">
          <div class="chat tall" id="practice-chat">
            ${(current.messages || []).map((m) => bubbleHtml(m.role, m.content, m.isAiGenerated)).join('')}
          </div>
          <div class="chat-input">
            <textarea id="practice-input" placeholder="输入你的回应（按 Ctrl+Enter 发送）"></textarea>
            <button class="primary" id="btn-practice-send">发送</button>
          </div>
          <div class="btn-row" style="margin-top:12px">
            <button class="primary" id="btn-practice-evaluate" ${current.canEvaluate ? '' : 'disabled'}>生成反馈</button>
            <span class="muted" id="practice-hint">${current.canEvaluate ? '可以生成反馈了' : `再聊 ${Math.max(0, (current.minTurns || 3) - (current.turnCount || 0))} 轮就能生成反馈`}</span>
          </div>
        </div>
        <div class="span-3 card">
          ${ev ? feedbackHtml(ev) : `
            <div class="card-title">这次练习会看什么</div>
            <div class="card-sub" style="margin-bottom:12px">不是打分工具，是帮你下次说得更好的镜子</div>
            ${(current.rubric || []).map((r) => `
              <div class="kv"><span class="k">${esc(r.name)}</span><span class="v" style="font-weight:400;color:var(--text-dim);max-width:60%;text-align:right">${esc(r.description || '')}</span></div>
            `).join('')}
          `}
        </div>
      </div>`;

    const box = $('#practice-chat');
    box.scrollTop = box.scrollHeight;
    if (ev) {
      Charts.bars($('#feedback-bars'), (ev.dimensions || []).map((d) => ({
        label: d.name,
        value: d.score,
        display: fmt(d.score),
        color: d.score >= 80 ? Charts.colors.accent : d.score >= 60 ? Charts.colors.primary : Charts.colors.warn,
      })));
    }

    const send = async () => {
      const input = $('#practice-input');
      const text = input.value.trim();
      if (!text) return;
      box.insertAdjacentHTML('beforeend', bubbleHtml('user', text, false));
      input.value = '';
      const id = `p-${Date.now()}`;
      box.insertAdjacentHTML('beforeend', `<div class="msg assistant"><div class="avatar">灯</div><div class="bubble cursor" id="${id}"></div></div>`);
      box.scrollTop = box.scrollHeight;
      try {
        await api.simulationMessageStream(current.sessionId, { message: text }, (name, data) => {
          const b = document.getElementById(id);
          if (!b) return;
          if (name === 'token') b.textContent += data.t;
          else if (name === 'done') {
            b.classList.remove('cursor');
            current.turnCount = data.turn;
            current.canEvaluate = data.canEvaluate;
            const btn = $('#btn-practice-evaluate');
            btn.disabled = !current.canEvaluate;
            const hint = $('#practice-hint');
            if (hint) hint.textContent = current.canEvaluate ? '可以生成反馈了' : `再聊 ${Math.max(0, 3 - current.turnCount)} 轮就能生成反馈`;
          } else if (name === 'error') {
            b.classList.remove('cursor');
            b.textContent = friendlyMessage({ message: data.message });
          }
          box.scrollTop = box.scrollHeight;
        });
      } catch (e) {
        err(friendlyMessage(e));
      }
    };

    $('#btn-practice-send').addEventListener('click', send);
    $('#practice-input').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) send();
    });
    $('#btn-practice-evaluate').addEventListener('click', () => guard(async () => {
      const r = await api.evaluateSimulation(current.sessionId);
      state.practice = await api.simulationDetail(current.sessionId);
      await route('practice');
      ok(`反馈已生成：${fmt(r.overallScore)} 分`);
    }));
    $('#btn-quit-practice').addEventListener('click', () => { state.practice = null; route('practice'); });
  }

  function bubbleHtml(role, content, isAi) {
    const isUser = role === 'user';
    return `<div class="msg ${isUser ? 'user' : 'assistant'}">
        <div class="avatar">${isUser ? '我' : '灯'}</div>
        <div class="bubble">${esc(content)}${isAi ? '<div class="meta">AI 生成，仅供参考</div>' : ''}</div>
      </div>`;
  }

  function feedbackHtml(ev) {
    return `
      <div class="stat">
        <div class="stat-label">这次练习</div>
        <div class="stat-value">${fmt(ev.overallScore)}<span style="font-size:15px;font-weight:500;color:var(--text-dim)"> 分</span></div>
        <div class="stat-hint">${esc(ev.level || '')}</div>
      </div>
      <div class="progress ${ev.overallScore >= 80 ? 'accent' : ''}" style="margin:12px 0"><i style="width:${Math.min(100, ev.overallScore)}%"></i></div>
      <div class="callout" style="margin-bottom:14px">${esc(ev.summary || '')}</div>
      <div id="feedback-bars"></div>
      <div style="margin-top:16px">
        <div class="hint" style="margin-bottom:8px">下次可以这样做</div>
        ${(ev.suggestions || []).map((s) => `<div class="output">${esc(s)}</div>`).join('') || '<span class="muted">继续保持</span>'}
      </div>`;
  }

  /* ==================================================================
   * 职业伙伴
   * ================================================================== */
  async function viewPartner() {
    if (state.partnerSessionId && !state.partnerMessages.length) {
      state.partnerMessages = await api.agentMessages(state.partnerSessionId).catch(() => []);
    }
    const messages = state.partnerMessages;

    $('#page').innerHTML = `
      ${pageHead('职业伙伴', '可以聊计划、聊困惑，也可以只是说说最近的进展。',
        '<button class="btn ghost sm" id="btn-new-chat">开始新对话</button>')}
      <div class="card" style="display:flex;flex-direction:column">
        <div class="chat tall" id="partner-chat">
          ${messages.length
            ? messages.map((m) => bubbleHtml(m.role, m.content, m.isAiGenerated)).join('')
            : bubbleHtml('assistant', '你好，我是灯塔。最近在忙什么？需要一起理一理下一步吗？', true)}
        </div>
        <div class="chat-input">
          <textarea id="partner-input" placeholder="说说你现在的情况…（Ctrl+Enter 发送）"></textarea>
          <button class="primary" id="btn-partner-send">发送</button>
        </div>
        <div class="suggestions">
          <button class="suggestion" data-say="我最近有点迷茫，不知道该先学什么">我有点迷茫，不知道先学什么</button>
          <button class="suggestion" data-say="帮我看看这周该做什么">这周该做什么</button>
          <button class="suggestion" data-say="我想转岗，之前的经验能复用吗">想转岗，经验能复用吗</button>
        </div>
        <p class="hint" style="margin-top:14px">AI 生成的内容仅供参考，涉及重要决定时请结合自身情况判断。</p>
      </div>`;

    const box = $('#partner-chat');
    box.scrollTop = box.scrollHeight;

    const send = async (preset) => {
      const input = $('#partner-input');
      const text = (preset || input.value).trim();
      if (!text) return;
      input.value = '';
      box.insertAdjacentHTML('beforeend', bubbleHtml('user', text, false));
      const id = `c-${Date.now()}`;
      box.insertAdjacentHTML('beforeend', `<div class="msg assistant"><div class="avatar">灯</div><div class="bubble cursor" id="${id}"></div></div>`);
      box.scrollTop = box.scrollHeight;
      try {
        await api.agentChatStream({ sessionId: state.partnerSessionId, message: text }, (name, data) => {
          const b = document.getElementById(id);
          if (!b) return;
          if (name === 'meta') state.partnerSessionId = data.sessionId;
          else if (name === 'token') { b.textContent += data.t; box.scrollTop = box.scrollHeight; }
          else if (name === 'done') b.insertAdjacentHTML('beforeend', '<div class="meta">AI 生成，仅供参考</div>');
          else if (name === 'error') { b.classList.remove('cursor'); b.textContent = friendlyMessage({ message: data.message }); }
        });
      } catch (e) {
        err(friendlyMessage(e));
      }
    };

    $('#btn-partner-send').addEventListener('click', () => send());
    $('#partner-input').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) send();
    });
    $$('[data-say]').forEach((el) => el.addEventListener('click', () => send(el.dataset.say)));
    $('#btn-new-chat').addEventListener('click', () => {
      state.partnerSessionId = null;
      state.partnerMessages = [];
      route('partner');
    });
  }

  /* ==================================================================
   * 资料问答
   * ================================================================== */
  async function viewLibrary() {
    const stats = await api.kbStats().catch(() => ({ documents: 0, chunks: 0 }));

    $('#page').innerHTML = `
      ${pageHead('资料问答', '从职业发展资料里找答案，并告诉你出处。')}
      <div class="grid bento">
        <div class="span-4 card">
          <div class="field">
            <textarea id="ask-input" placeholder="想问点什么？例如：Java 后端岗位需要掌握哪些数据库技能？"></textarea>
          </div>
          <div class="btn-row">
            <button class="primary" id="btn-ask">查找答案</button>
          </div>
          <div class="suggestions">
            <button class="suggestion" data-ask="Java 后端岗位需要掌握哪些数据库技能？">Java 后端需要哪些数据库技能</button>
            <button class="suggestion" data-ask="数据分析师日常负责什么？">数据分析师的日常工作</button>
            <button class="suggestion" data-ask="AI 应用开发岗位需要什么能力？">AI 应用开发岗位要求</button>
          </div>
          <div id="ask-result" style="margin-top:20px"></div>
        </div>
        <div class="span-2 card">
          <div class="card-title">资料库</div>
          <div class="card-sub" style="margin-bottom:12px">共收录 ${stats.documents || 0} 份资料</div>
          <div class="callout muted">
            答案只来自这些资料。如果资料里没有相关内容，我会直接告诉你，而不是猜一个。
          </div>
          <div style="margin-top:14px">
            ${(stats.docs || []).map((d) => `
              <div class="list-item">
                <div class="li-main">
                  <div class="li-title" style="font-size:13px">${esc(d.title)}</div>
                  <div class="li-sub">${esc(d.category || '')} · ${esc(d.source || '')}</div>
                </div>
              </div>`).join('') || `<div class="hint">暂无资料</div>`}
          </div>
          <div class="btn-row" style="margin-top:14px">
            <button class="btn ghost" id="btn-kb-check" style="font-size:12.5px">检查问答质量</button>
          </div>
          <div id="kb-check-result" style="margin-top:10px"></div>
        </div>
      </div>`;


    const ask = async (preset) => {
      const input = $('#ask-input');
      const question = (preset || input.value).trim();
      if (!question) { err('先输入想问的问题'); return; }
      if (!preset) input.value = question;
      const host = $('#ask-result');
      host.innerHTML = loadingBlock('正在查找…');
      try {
        const r = await api.kbAsk(question);
        if (r.refused) {
          host.innerHTML = `
            <div class="callout warn">
              <div class="ct">资料里没有相关内容</div>
              我没有在现有资料中找到可以回答这个问题的内容，所以不做推测。可以换个问法，或者问我职业规划方面的其他问题。
            </div>`;
          return;
        }
        host.innerHTML = `
          <div class="hint" style="margin-bottom:8px">回答</div>
          <div style="font-size:14.5px;line-height:1.85">${esc(r.answer)}</div>
          <div style="margin-top:18px">
            <div class="hint" style="margin-bottom:8px">参考来源</div>
            ${(r.citations || []).map((c, i) => `
              <div class="list-item" style="margin-bottom:8px">
                <div class="li-main">
                  <div class="li-title">${i + 1}. ${esc(c.title)}</div>
                  <div class="li-sub">${esc(c.source)}</div>
                  <div class="li-sub" style="margin-top:6px;color:var(--text-soft)">「${esc(c.excerpt)}」</div>
                </div>
              </div>`).join('')}
          </div>
          <p class="hint" style="margin-top:12px">内容由 AI 整理，仅供参考。</p>`;
      } catch (e) {
        host.innerHTML = `<div class="callout danger">${esc(friendlyMessage(e))}</div>`;
      }
    };

    $('#btn-ask').addEventListener('click', () => ask());
    $('#ask-input').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) ask();
    });
    $$('[data-ask]').forEach((el) => el.addEventListener('click', () => ask(el.dataset.ask)));
    $('#btn-kb-check').addEventListener('click', () => guard(async () => {
      const box = $('#kb-check-result');
      box.innerHTML = loadingBlock('正在检查…');
      const r = await api.kbEval();
      box.innerHTML = `
        <div class="metric-inline">
          <div class="item"><div class="n">${Math.round(r.keywordHitRate ?? 0)}%</div><div class="l">命中率</div></div>
          <div class="item"><div class="n">${Math.round(r.recallAt1 ?? 0)}%</div><div class="l">首位命中</div></div>
        </div>
        <div class="hint" style="margin-top:8px">用 ${r.total || 0} 个常见问题逐个提问，看资料能不能被正确找到。</div>`;
    }, '检查完成'));
  }

  /* ==================================================================
   * 设置
   * ================================================================== */
  async function viewSettings() {
    const [{ profile }, jobs, usage] = await Promise.all([
      api.getProfile(),
      api.jobs().catch(() => []),
      api.llmStats(7).catch(() => null),
    ]);
    const p = profile || {};
    const budget = usage?.budget || {};
    const ov = usage?.overview || {};

    $('#page').innerHTML = `
      ${pageHead('设置', '你的资料、偏好与数据。')}
      <div class="grid bento">
        <div class="span-4 card">
          <div class="card-title">我的资料</div>
          <div class="card-sub" style="margin-bottom:16px">这些信息用于给出更贴合你的建议</div>
          <div class="form-grid">
            <div class="field">
              <label>学历</label>
              <select id="s-education">
                ${['专科', '本科', '硕士', '博士', '其他'].map((v) => `<option ${p.education === v ? 'selected' : ''}>${v}</option>`).join('')}
              </select>
            </div>
            <div class="field"><label>专业</label><input id="s-major" value="${esc(p.major || '')}" /></div>
            <div class="field"><label>年级 / 身份</label><input id="s-grade" value="${esc(p.grade || '')}" placeholder="如：大三、应届" /></div>
            <div class="field"><label>工作年限</label><input id="s-workYears" type="number" min="0" max="40" value="${p.workYears ?? 0}" /></div>
            <div class="field full">
              <label>目标岗位</label>
              <select id="s-targetJob">
                <option value="">请选择</option>
                ${(jobs || []).map((j) => `<option value="${j.id}" ${String(p.targetJobId) === String(j.id) ? 'selected' : ''}>${esc(j.name)}</option>`).join('')}
              </select>
            </div>
            <div class="field full">
              <label>每周可用来学习的时间</label>
              <input id="s-weeklyHours" type="number" min="1" max="60" value="${p.weeklyHours ?? 8}" />
              <div class="hint">建议按实际能坚持的时间填，计划会照此安排</div>
            </div>
          </div>
          <div class="btn-row"><button class="primary" id="btn-save-profile">保存</button></div>
        </div>

        <div class="span-2 card">
          <div class="card-title">账户</div>
          <div class="kv"><span class="k">用户名</span><span class="v">${esc(state.me?.username || '')}</span></div>
          <div class="kv"><span class="k">昵称</span><span class="v">${esc(state.me?.nickname || state.me?.username || '')}</span></div>
          <div class="btn-row" style="margin-top:16px">
            <button class="btn ghost" id="btn-logout">退出登录</button>
          </div>
        </div>

        <div class="span-2 card">
          <div class="card-title">我的数据</div>
          <div class="card-sub" style="margin-bottom:14px">数据属于你，随时可以带走或删除</div>
          <div class="btn-row" style="flex-direction:column;align-items:stretch">
            <button class="btn" id="btn-export">导出我的数据</button>
            <button class="btn danger" id="btn-erase">删除全部数据</button>
          </div>
        </div>

        <div class="span-2 card">
          <div class="card-title">使用情况</div>
          <div class="card-sub" style="margin-bottom:12px">智能助手的调用情况</div>
          <div class="kv"><span class="k">近 7 天</span><span class="v">${ov.calls ?? 0} 次</span></div>
          <div class="kv"><span class="k">今日剩余额度</span><span class="v">${Math.max(0, (budget.dailyLimit ?? 0) - (budget.todayCalls ?? 0))} 次</span></div>
          <div class="kv"><span class="k">平均响应</span><span class="v">${Math.round(ov.avgLatency ?? 0)} 毫秒</span></div>
          ${budget.alerting ? '<div class="callout warn" style="margin-top:12px">今天的用量接近上限，稍后会自动改用更快的方式应答。</div>' : ''}
        </div>

        <div class="span-2 card">
          <div class="card-title">关于 AI 建议</div>
          <div style="margin-top:10px;font-size:13px;color:var(--text-soft);line-height:1.85">
            页面中标注「AI 生成」的内容由智能助手整理，可能存在偏差，重要决定请自行核实。
            你的资料在用于生成建议前会先隐去姓名、联系方式等身份信息。
            如果你需要，可以随时导出或删除全部数据。
          </div>
        </div>
      </div>`;

    $('#btn-save-profile').addEventListener('click', () => guard(async () => {
      await api.saveProfile({
        education: $('#s-education').value,
        major: $('#s-major').value,
        grade: $('#s-grade').value,
        workYears: Number($('#s-workYears').value || 0),
        currentJob: p.currentJob || '',
        targetJobId: $('#s-targetJob').value ? Number($('#s-targetJob').value) : null,
        interestTags: p.interestTags || '',
        weeklyHours: Number($('#s-weeklyHours').value || 8),
      });
    }, '已保存'));

    $('#btn-logout').addEventListener('click', async () => {
      await guard(() => api.logout());
      state.me = null;
      state.practice = null;
      state.partnerSessionId = null;
      state.partnerMessages = [];
      renderAuth();
    });

    $('#btn-export').addEventListener('click', () => guard(async () => {
      const data = await api.exportData();
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = '我的数据.json';
      a.click();
      URL.revokeObjectURL(a.href);
      ok('已导出');
    }));

    $('#btn-erase').addEventListener('click', () => {
      if (!confirm('确定删除全部数据吗？此操作不可撤销。')) return;
      guard(async () => {
        await api.eraseData();
        state.me = null;
        renderAuth();
        toast('数据已删除', 'ok', 4000);
      });
    });
  }

  /* ==================================================================
   * 完善资料
   * ================================================================== */
  async function viewOnboarding() {
    const [{ profile }, jobs, dims, questions] = await Promise.all([
      api.getProfile(),
      api.jobs().catch(() => []),
      api.dimensions(),
      api.questions(),
    ]);
    const p = profile || {};
    const latest = await api.latestAssessment();

    const grouped = {};
    questions.forEach((q) => { (grouped[q.dimension] = grouped[q.dimension] || []).push(q); });
    const dimLabel = (key) => (dims.find((d) => d.key === key) || {}).label || key;

    $('#page').innerHTML = `
      ${pageHead('先花几分钟了解你', '信息越具体，后面的建议就越贴合。')}
      <div class="grid bento">
        <div class="span-4 card">
          <div class="card-title">基本信息</div>
          <div class="card-sub" style="margin-bottom:16px">只收集做规划必需的内容</div>
          <div class="form-grid">
            <div class="field">
              <label>学历</label>
              <select id="f-education">
                ${['专科', '本科', '硕士', '博士', '其他'].map((v) => `<option ${p.education === v ? 'selected' : ''}>${v}</option>`).join('')}
              </select>
            </div>
            <div class="field"><label>专业</label><input id="f-major" value="${esc(p.major || '')}" placeholder="如：计算机科学与技术" /></div>
            <div class="field"><label>年级 / 身份</label><input id="f-grade" value="${esc(p.grade || '')}" placeholder="如：大三、应届" /></div>
            <div class="field"><label>工作年限</label><input id="f-workYears" type="number" min="0" max="40" value="${p.workYears ?? 0}" /></div>
            <div class="field full">
              <label>目标岗位</label>
              <select id="f-targetJob">
                <option value="">请选择</option>
                ${(jobs || []).map((j) => `<option value="${j.id}" ${String(p.targetJobId) === String(j.id) ? 'selected' : ''}>${esc(j.name)}</option>`).join('')}
              </select>
            </div>
            <div class="field full">
              <label>每周能用来学习的时间</label>
              <input id="f-weeklyHours" type="number" min="1" max="60" value="${p.weeklyHours ?? 8}" />
            </div>
          </div>
          <div class="btn-row">
            <button class="primary" id="btn-save-basic">保存并继续</button>
            <span class="muted">${latest.exists ? '你已经完成过自评，也可以直接提交新的' : ''}</span>
          </div>
        </div>

        <div class="span-2 card">
          <div class="card-title">能力自评</div>
          <div class="card-sub" style="margin-bottom:14px">根据实际情况选，不必挑最好看的答案</div>
          <div>
            ${Object.entries(grouped).map(([dim, list]) => `
              <div style="margin-bottom:14px">
                <div class="hint" style="margin-bottom:6px">${esc(dimLabel(dim))}</div>
                ${list.map((q) => `
                  <div class="q-item" style="padding:8px 0;border-bottom:1px dashed var(--line-soft)">
                    <div class="q-text" style="font-size:12.5px;color:var(--text-soft);margin-bottom:6px">${q.no}. ${esc(q.text)}</div>
                    <div class="scale" data-q="${q.no}" style="display:flex;gap:5px">
                      ${[1, 2, 3, 4, 5].map((v) => `
                        <label class="choice" style="padding:4px 10px;font-size:12px">
                          <input type="radio" name="q${q.no}" value="${v}" ${v === 3 ? 'checked' : ''} />${v}
                        </label>`).join('')}
                    </div>
                  </div>`).join('')}
              </div>`).join('')}
          </div>
          <button class="primary" id="btn-submit-assessment" style="width:100%;justify-content:center">提交自评</button>
        </div>
      </div>`;

    $('#btn-save-basic').addEventListener('click', () => guard(async () => {
      await api.saveProfile({
        education: $('#f-education').value,
        major: $('#f-major').value,
        grade: $('#f-grade').value,
        workYears: Number($('#f-workYears').value || 0),
        currentJob: p.currentJob || '',
        targetJobId: $('#f-targetJob').value ? Number($('#f-targetJob').value) : null,
        interestTags: p.interestTags || '',
        weeklyHours: Number($('#f-weeklyHours').value || 8),
      });
      if (state.me?.profileComplete) await route('home');
    }, '已保存'));

    $('#btn-submit-assessment').addEventListener('click', () => guard(async () => {
      const answers = $$('.scale').map((box) => ({
        no: Number(box.dataset.q),
        value: Number($('input:checked', box).value),
      }));
      await api.submitAssessment({ answers });
      await route('home');
    }, '自评已完成，你的能力图谱已生成'));
  }

  /* ---------------------------------------------------------------- 路由 */
  const ROUTES = {
    home: viewHome,
    ability: viewAbility,
    path: viewPath,
    plan: viewPlan,
    practice: viewPractice,
    partner: viewPartner,
    library: viewLibrary,
    settings: viewSettings,
    onboarding: viewOnboarding,
  };

  async function route(key) {
    const target = ROUTES[key] ? key : 'home';
    state.route = target;
    renderShell();
    $('#page').innerHTML = loadingBlock();
    try {
      await ROUTES[target]();
    } catch (e) {
      if (e.status === 401 || e.code === 20001) {
        state.me = null;
        renderAuth();
        return;
      }
      $('#page').innerHTML = `
        ${pageHead('出了点问题', '')}
        <div class="card"><div class="callout warn">${esc(friendlyMessage(e))}</div>
        <div class="btn-row" style="margin-top:14px"><button class="primary" id="btn-retry">再试一次</button></div></div>`;
      const retry = $('#btn-retry');
      if (retry) retry.addEventListener('click', () => route(target));
    }
  }

  /* ---------------------------------------------------------------- 启动 */
  async function boot() {
    try {
      state.me = await api.me();
    } catch (e) {
      state.me = null;
    }
    if (!state.me) { renderAuth(); return; }
    await route(state.me.profileComplete ? 'home' : 'onboarding');
  }

  boot();
})();
