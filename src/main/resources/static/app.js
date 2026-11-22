/* Regional Inventory HQ - single page front end (vanilla JS + Chart.js) */
const App = (() => {
  const state = { me: null, regions: [], shops: [], skus: [], alerts: [], alertFilter: 'ALL', ledgerPage: 0, charts: {}, velocityShop: null };
  const TIERS = ['NORMAL', 'MODERATE', 'HIGH', 'EXPIRED'];
  const TIER_VAR = { NORMAL: '--good', MODERATE: '--warning', HIGH: '--serious', EXPIRED: '--critical' };
  const TIER_RATE = { NORMAL: '0%', MODERATE: '25%', HIGH: '60%', EXPIRED: '100%' };

  // ---------- helpers ----------
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const money = v => v == null ? '–' : '¥' + Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  // Chinese convention for axis labels: 万 = 10,000, 亿 = 100,000,000
  const moneyShort = v => { v = Number(v); return Math.abs(v) >= 1e8 ? '¥' + (v / 1e8).toFixed(2) + '亿' : Math.abs(v) >= 1e4 ? '¥' + (v / 1e4).toFixed(1) + '万' : '¥' + v.toFixed(0); };
  const num = (v, d = 0) => Number(v).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d });
  // Dates in the viewer's local calendar (toISOString() is UTC and is a day behind in China before 08:00)
  const localDate = (d = new Date()) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  // turnover = COGS / (V_cost + ε): with no stock ε makes it ~10^8, which is meaningless to show
  const turn = (rate, cost) => Number(cost) > 0 ? num(rate, 2) + '×' : '– <span class="hint">no stock</span>';
  const pct = v => (v * 100).toFixed(1) + '%';
  const cssVar = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  const badge = (t, cls) => `<span class="badge ${cls}">${esc(t)}</span>`;
  const meter = v => `<span class="meter ${v >= 0.9 ? 'hot' : ''}"><i style="width:${Math.min(100, v * 100)}%"></i></span>${pct(v)}`;

  const ROLE_LABEL = { HQ_ADMIN: 'HQ', SHOP_MANAGER: 'Shop manager' };
  const csrfToken = () => (document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1];
  async function api(path, opts = {}) {
    const headers = { 'Content-Type': 'application/json' };
    const method = (opts.method || 'GET').toUpperCase();
    if (method !== 'GET' && csrfToken()) headers['X-XSRF-TOKEN'] = decodeURIComponent(csrfToken());
    const res = await fetch('/api' + path, { ...opts, headers, credentials: 'same-origin' });
    if (res.status === 401 && !path.startsWith('/auth/')) { showLogin(); throw new Error('Please sign in'); }
    const text = await res.text();
    const body = text && (res.headers.get('content-type') || '').includes('json') ? JSON.parse(text) : text;
    if (!res.ok) { const err = new Error(body?.detail || res.statusText); err.body = body; throw err; }
    return body;
  }
  function toast(msg) { const t = $('#toast'); t.textContent = msg; t.classList.add('show'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('show'), 2600); }
  function errorHtml(e) {
    const b = e.body || {};
    let extra = '';
    if (b.details) extra = '<ul>' + Object.entries(b.details).map(([k, v]) => `<li>${esc(k)}: <b>${esc(v)}</b></li>`).join('') + '</ul>';
    if (b.errors) extra = '<ul>' + Object.entries(b.errors).map(([k, v]) => `<li>${esc(k)}: ${esc(v)}</li>`).join('') + '</ul>';
    return `<div class="err"><b>${esc(b.code || 'Error')}</b> — ${esc(e.message)}${extra}</div>`;
  }
  const formData = form => Object.fromEntries([...new FormData(form)].map(([k, v]) => [k, v === '' ? null : v]));

  // ---------- chart theming ----------
  function baseOptions(extra = {}) {
    const ink2 = cssVar('--ink-2'), grid = cssVar('--grid'), muted = cssVar('--muted');
    Chart.defaults.font.family = 'system-ui, -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif';
    Chart.defaults.color = ink2;
    return {
      responsive: true, maintainAspectRatio: false, animation: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { position: 'top', align: 'start', labels: { boxWidth: 10, boxHeight: 10, useBorderRadius: true, borderRadius: 3 } },
        tooltip: { backgroundColor: cssVar('--surface'), titleColor: cssVar('--ink'), bodyColor: ink2, borderColor: cssVar('--axis'), borderWidth: 1, padding: 10, boxPadding: 4 }
      },
      scales: {
        x: { grid: { display: false }, border: { color: cssVar('--axis') }, ticks: { color: muted } },
        y: { grid: { color: grid }, border: { display: false }, ticks: { color: muted }, beginAtZero: true }
      },
      ...extra
    };
  }
  function chart(id, config) {
    state.charts[id]?.destroy();
    state.charts[id] = new Chart(document.getElementById(id), config);
  }

  // ---------- routing ----------
  const loaders = {};
  const can = (...roles) => state.me && roles.includes(state.me.role);
  function applyRoles() {
    $$('[data-roles]').forEach(el => {
      const ok = state.me && el.dataset.roles.split(',').includes(state.me.role);
      if (ok) el.removeAttribute('data-hidden-by-role'); else el.setAttribute('data-hidden-by-role', '');
    });
  }
  function route() {
    let page = (location.hash || '#dashboard').slice(1);
    const link = $(`#nav a[data-page="${page}"]`);
    if (!link || link.hasAttribute('data-hidden-by-role')) page = 'dashboard';
    $$('.page').forEach(p => p.classList.toggle('active', p.id === 'page-' + page));
    $$('#nav a').forEach(a => a.classList.toggle('active', a.dataset.page === page));
    if (!state.me) return;                       // signed out: the login overlay is showing
    Promise.resolve((loaders[page] || (() => {}))()).catch(e => { if (state.me) toast(e.message); });
  }

  async function loadMaster() {
    [state.regions, state.shops, state.skus] = await Promise.all([api('/regions'), api('/shops'), api('/skus')]);
    const rf = $('#regionFilter'), rv = rf.value;
    rf.innerHTML = '<option value="">All regions</option>' + state.regions.map(r => `<option value="${r.id}">${esc(r.code)} — ${esc(r.name)}</option>`).join('');
    rf.value = rv;
    $('#regionFilterWrap').hidden = state.regions.length < 2;
    const shopOpts = state.shops.filter(s => s.active).map(s => `<option value="${s.id}">${esc(s.code)} — ${esc(s.name)}</option>`).join('');
    const skuOpts = state.skus.filter(s => s.active).map(s => `<option value="${s.id}">${esc(s.code)} — ${esc(s.name)}</option>`).join('');
    $$('.shopSelect').forEach(el => { const v = el.value; el.innerHTML = shopOpts; if (v) el.value = v; });
    $$('.skuSelect').forEach(el => { const v = el.value; el.innerHTML = skuOpts; if (v) el.value = v; });
    for (const id of ['invShop', 'ledShop']) { const el = $('#' + id); const v = el.value; el.innerHTML = '<option value="">All shops</option>' + shopOpts; el.value = v; }
    const ls = $('#ledSku'); const v = ls.value; ls.innerHTML = '<option value="">All SKUs</option>' + skuOpts; ls.value = v;
  }

  // ---------- dashboard ----------
  loaders.dashboard = async function loadDashboard() {
    const rq = $('#regionFilter').value ? '?regionId=' + $('#regionFilter').value : '';
    const [pm, alerts] = await Promise.all([api('/analytics/portfolio' + rq), api('/analytics/alerts' + rq)]);
    const me = state.me;
    $('#dashTitle').textContent = me.role === 'SHOP_MANAGER' ? `Shop overview · ${me.shopCode}`
      : `${me.hqName} · all shops`;
    $('#asOf').textContent = `Live valuation as of ${pm.asOfDate} · ${pm.regions.length} region${pm.regions.length === 1 ? '' : 's'} · ${pm.shops.length} shop${pm.shops.length === 1 ? '' : 's'} · ${num(pm.units)} units on hand` + (state.me.hqCity ? ` · HQ: ${state.me.hqCity}` : '');
    $('#regionCard').hidden = me.role === 'SHOP_MANAGER';
    $('#regionTable').innerHTML = regionRows(pm.regions);
    const tiles = [
      ['Purchase cost value (V<sub>cost</sub>)', money(pm.costValue), 'Σ on-hand × purchase price'],
      ['Retail potential (V<sub>retail</sub>)', money(pm.retailValue), 'Σ on-hand × sale price'],
      ['Potential gross margin', money(pm.potentialGrossMargin), pct(pm.potentialGrossMargin / (pm.retailValue || 1)) + ' of retail'],
      ['COGS (30 days)', money(pm.cogs30d), 'velocity × 30 × purchase price'],
      ['Turnover rate (monthly)', Number(pm.costValue) > 0 ? num(pm.turnoverRate, 2) + '×' : '–', '≈ ' + num(30 / (pm.turnoverRate || 1e-6), 1) + ' days of stock'],
      ['Depreciation provision', money(pm.provision), pct(pm.provision / (pm.costValue || 1)) + ' of cost'],
      ['Net realisable cost', money(pm.netRealisableCost), 'V<sub>cost</sub> − provision']
    ];
    $('#kpiTiles').innerHTML = tiles.map(([l, v, n]) => `<div class="tile"><div class="label">${l}</div><div class="value">${v}</div><div class="note">${n}</div></div>`).join('');

    // tier composition (status colours, always labelled)
    const total = TIERS.reduce((a, t) => a + Number(pm.costByTier[t] || 0), 0) || 1;
    $('#tierBar').innerHTML = TIERS.map(t => {
      const v = Number(pm.costByTier[t] || 0);
      return v > 0 ? `<div title="${t}: ${money(v)} (${pct(v / total)})" style="flex:${v};background:var(${TIER_VAR[t]})"></div>` : '';
    }).join('');
    $('#tierTable').innerHTML = '<tr><th>Tier</th><th>R<sub>dep</sub></th><th class="num">Cost value</th><th class="num">Share</th><th class="num">Provision rate</th></tr>' +
      TIERS.map((t, i) => `<tr><td><span class="swatch" style="background:var(${TIER_VAR[t]})"></span>${t}</td><td>${['&lt; 0.60', '0.60 – 0.85', '0.85 – 1.00', '&gt; 1.00'][i]}</td>
      <td class="num">${money(pm.costByTier[t])}</td><td class="num">${pct(Number(pm.costByTier[t]) / total)}</td><td class="num">${TIER_RATE[t]}</td></tr>`).join('');

    state.alerts = alerts;
    renderAlerts();

    const byTurnover = pm.shops.filter(s => Number(s.costValue) > 0).sort((a, b) => b.turnoverRate - a.turnoverRate);
    chart('turnoverChart', {
      type: 'bar',
      data: { labels: byTurnover.map(s => s.shopCode), datasets: [{ label: 'Turnover rate', data: byTurnover.map(s => +s.turnoverRate.toFixed(3)), backgroundColor: cssVar('--s1'), borderRadius: 4, borderSkipped: 'bottom', maxBarThickness: 28 }] },
      options: baseOptions({ plugins: { ...baseOptions().plugins, legend: { display: false }, tooltip: { ...baseOptions().plugins.tooltip, callbacks: { label: c => { const s = byTurnover[c.dataIndex]; return [`Turnover ${c.raw.toFixed(2)}×`, `COGS30d ${money(s.cogs30d)}`, `V_cost ${money(s.costValue)}`]; } } } } })
    });
    const shops = [...pm.shops].sort((a, b) => a.shopCode.localeCompare(b.shopCode));
    const opts = baseOptions();
    opts.scales.y.max = 1; opts.scales.y.ticks.callback = v => (v * 100) + '%';
    opts.plugins.tooltip.callbacks = { label: c => `${c.dataset.label}: ${pct(c.raw)}` };
    chart('utilChart', {
      type: 'bar',
      data: { labels: shops.map(s => s.shopCode), datasets: [
        { label: 'Storage used', data: shops.map(s => s.storageUtilisation), backgroundColor: cssVar('--s1'), borderRadius: 4, borderSkipped: 'bottom', maxBarThickness: 16 },
        { label: 'Capital budget used', data: shops.map(s => s.budgetUtilisation), backgroundColor: cssVar('--s2'), borderRadius: 4, borderSkipped: 'bottom', maxBarThickness: 16 }
      ] },
      options: opts
    });

    $('#shopTable').innerHTML = `<tr><th>Shop</th><th>City</th><th class="num">Units</th><th class="num">V<sub>cost</sub></th><th class="num">V<sub>retail</sub></th><th class="num">COGS 30d</th><th class="num">Turnover</th><th class="num">Provision</th><th class="num">Net realisable</th><th>Storage</th><th>Budget</th></tr>` +
      shops.map(s => `<tr><td><b>${esc(s.shopCode)}</b> ${esc(s.shopName)}</td><td>${esc(s.city)}</td><td class="num">${num(s.units)}</td><td class="num">${money(s.costValue)}</td><td class="num">${money(s.retailValue)}</td>
        <td class="num">${money(s.cogs30d)}</td><td class="num">${turn(s.turnoverRate, s.costValue)}</td><td class="num">${money(s.provision)}</td><td class="num">${money(s.netRealisableCost)}</td>
        <td>${meter(s.storageUtilisation)}</td><td>${meter(s.budgetUtilisation)}</td></tr>`).join('');
  };

  function regionRows(regions) {
    return `<tr><th>Region</th><th class="num">Shops</th><th class="num">Units</th><th class="num">V<sub>cost</sub></th><th class="num">V<sub>retail</sub></th><th class="num">COGS 30d</th><th class="num">Turnover</th><th class="num">Provision</th><th class="num">Net realisable</th><th>Storage</th><th>Budget</th></tr>` +
      regions.map(r => `<tr><td><b>${esc(r.regionCode)}</b> ${esc(r.regionName)}</td><td class="num">${r.shopCount}</td><td class="num">${num(r.units)}</td><td class="num">${money(r.costValue)}</td><td class="num">${money(r.retailValue)}</td>
      <td class="num">${money(r.cogs30d)}</td><td class="num">${turn(r.turnoverRate, r.costValue)}</td><td class="num">${money(r.provision)}</td><td class="num">${money(r.netRealisableCost)}</td><td>${meter(r.storageUtilisation)}</td><td>${meter(r.budgetUtilisation)}</td></tr>`).join('');
  }

  // ---------- festivals (节日备货) ----------
  loaders.festivals = async () => {
    const plan = await api('/analytics/festival-plan?horizonDays=' + $('#festHorizon').value);
    state.fest = plan;
    $('#lessonCard').hidden = !plan.lessons.length;
    $('#lessonTable').innerHTML = `<tr><th>Region</th><th class="num">Received</th><th class="num">Sold</th><th class="num">Sell-through</th><th class="num">Returned</th><th class="num">Written off</th><th class="num">Still on hand</th><th class="num">Unsold at cost</th></tr>` +
      plan.lessons.map(l => `<tr><td><b>${esc(l.regionCode)}</b> ${esc(l.regionName)}</td><td class="num">${num(l.received)}</td><td class="num">${num(l.sold)}</td>
      <td class="num"><b>${pct(l.sellThrough)}</b></td><td class="num">${num(l.returned)}</td><td class="num">${num(l.writtenOff)}</td><td class="num">${num(l.stillOnHand)}</td><td class="num">${money(l.unsoldCost)}</td></tr>`).join('');
    if (!plan.events.some(e => e.code === state.festCode)) state.festCode = plan.events[0] ? plan.events[0].code : null;
    $('#festCards').innerHTML = plan.events.map(e => `<button class="fest-card ${e.code === state.festCode ? 'on' : ''}" data-code="${esc(e.code)}">
      <div class="fname">${esc(e.name)}</div><div class="fdate">${e.date} · in ${e.daysAway} day${e.daysAway === 1 ? '' : 's'}</div>
      <div class="fval">${money(e.totalCost)}</div><div class="fnote">${num(e.totalUnits)} extra units · ${e.lines.length} shop/SKU lines</div></button>`).join('')
      || '<p class="hint">No festivals in this window.</p>';
    document.querySelectorAll('#festCards .fest-card').forEach(b => b.onclick = () => { state.festCode = b.dataset.code; loaders.festivals(); });
    renderFestLines();
  };
  function renderFestLines() {
    const e = state.fest ? state.fest.events.find(x => x.code === state.festCode) : null;
    $('#festDetail').hidden = !e;
    if (!e) return;
    $('#festTitle').textContent = `${e.name} · ${e.date} · sales window ${e.windowStart} → ${e.windowEnd}`;
    $('#festStory').textContent = e.story;
    const q = $('#festSearch').value.trim().toLowerCase();
    const rows = e.lines.filter(l => !q || (l.shopCode + l.shopName + l.skuCode + l.skuName).toLowerCase().includes(q));
    const md = d => d.slice(5);
    $('#festLines').innerHTML = `<tr><th>Shop</th><th>Region</th><th>SKU</th><th class="num">Normal / day</th><th class="num">Uplift</th><th class="num">Festival demand</th><th class="num">Pre-stock</th><th class="num">At cost</th><th>Receive between</th><th>Why</th><th></th></tr>` +
      rows.slice(0, 300).map(l => `<tr><td><b>${esc(l.shopCode)}</b> ${esc(l.shopName)}</td><td>${esc(l.regionCode)}</td><td><b>${esc(l.skuCode)}</b> ${esc(l.skuName)}</td>
      <td class="num">${l.factor ? num(l.baselineDaily, 1) : "–"}</td><td class="num">${l.factor ? num(l.factor, 1) + '×' : 'seasonal'}</td><td class="num">${num(l.expectedUnits)}</td>
      <td class="num"><b>${num(l.recommendedQty)}</b>${l.cappedBy ? `<span class="cap">${esc(l.cappedBy.toLowerCase())} cap</span>` : ''}</td><td class="num">${money(l.cost)}</td>
      <td>${md(l.orderFrom)} → ${md(l.orderBy)}</td><td class="hint why">${esc(l.note)}</td>
      <td><button class="small ghost" data-shop="${l.shopId}" data-sku="${l.skuId}" data-qty="${l.recommendedQty}">Stock-in</button></td></tr>`).join('') +
      (rows.length > 300 ? `<tr><td colspan="11" class="hint">Showing the 300 largest of ${rows.length} lines. Search to narrow down.</td></tr>` : '');
    document.querySelectorAll('#festLines button[data-qty]').forEach(b => b.onclick = () => {
      location.hash = '#movements';
      setTimeout(() => { const f = $('#stockInForm'); f.shopId.value = b.dataset.shop; f.skuId.value = b.dataset.sku; f.quantity.value = b.dataset.qty;
        f.batchProductionDate.value = localDate(); f.reference.value = 'FEST-' + state.festCode; updateHeadroom(); }, 50);
    });
  }

  // ---------- regions ----------
  loaders.regions = async () => {
    await loadMaster();
    const pm = await api('/analytics/portfolio');
    const metrics = Object.fromEntries(pm.regions.map(r => [r.regionId, r]));
    $('#regionMasterTable').innerHTML = `<tr><th>Code</th><th>Name</th><th class="num">Shops</th><th class="num">V<sub>cost</sub></th><th class="num">Turnover</th><th class="num">Provision</th><th>Storage</th><th>Budget</th><th>Status</th><th></th></tr>` +
      state.regions.map(g => { const m = metrics[g.id]; return `<tr class="${g.active ? '' : 'inactive'}"><td><b>${esc(g.code)}</b></td><td>${esc(g.name)}</td><td class="num">${g.shopCount}</td>
      <td class="num">${m ? money(m.costValue) : '–'}</td><td class="num">${m ? num(m.turnoverRate, 2) + '×' : '–'}</td><td class="num">${m ? money(m.provision) : '–'}</td><td>${m ? meter(m.storageUtilisation) : '–'}</td><td>${m ? meter(m.budgetUtilisation) : '–'}</td>
      <td>${g.active ? 'Active' : 'Inactive'}</td><td>${can('HQ_ADMIN') ? `<button class="small ghost" onclick="App.editRegion(${g.id})">Edit</button> <button class="small ghost" onclick="App.toggleRegion(${g.id})">${g.active ? 'Deactivate' : 'Activate'}</button>` : ''}</td></tr>`; }).join('');
  };
  function editRegion(id) {
    const g = state.regions.find(x => x.id === id);
    openModal(g ? 'Edit region ' + g.code : 'New region', [['code', 'Code', 'text'], ['name', 'Name', 'text']], g, async body => {
      await api(g ? '/regions/' + id : '/regions', { method: g ? 'PUT' : 'POST', body: JSON.stringify(body) });
      toast('Region saved'); loaders.regions();
    });
  }
  async function toggleRegion(id) {
    const g = state.regions.find(x => x.id === id);
    try { await api(g.active ? `/regions/${id}` : `/regions/${id}/activate`, { method: g.active ? 'DELETE' : 'POST' }); toast('Region updated'); loaders.regions(); }
    catch (e) { toast(e.message); }
  }

  function renderAlerts() {
    const types = ['ALL', ...new Set(state.alerts.map(a => a.type))];
    $('#alertFilter').innerHTML = types.map(t => `<button class="chip ${t === state.alertFilter ? 'on' : ''}" data-t="${esc(t)}">${esc(t.replace(/_/g, ' ').toLowerCase())}</button>`).join('');
    $$('#alertFilter .chip').forEach(b => b.onclick = () => { state.alertFilter = b.dataset.t; renderAlerts(); });
    const list = state.alerts.filter(a => state.alertFilter === 'ALL' || a.type === state.alertFilter);
    $('#alertCount').textContent = `(${list.length})`;
    $('#alerts').innerHTML = list.map(a => `<div class="alert">${badge(a.severity, 'sev-' + a.severity)}
      <div><div class="who">${esc(a.shopCode)}${a.skuCode ? ' · ' + esc(a.skuCode) : ''} <span class="count">${esc(a.type.replace(/_/g, ' '))}</span></div><div class="msg">${esc(a.message)}</div></div>
      <div>${a.suggestedQty ? `<button class="small ghost" data-shop="${a.shopId}" data-sku="${a.skuId}" data-qty="${a.suggestedQty}">Reorder ${a.suggestedQty}</button>` : ''}</div></div>`).join('') || '<p class="hint">No alerts.</p>';
    $$('#alerts button[data-qty]').forEach(b => b.onclick = () => {
      location.hash = '#movements';
      setTimeout(() => {
        const f = $('#stockInForm'); f.shopId.value = b.dataset.shop; f.skuId.value = b.dataset.sku; f.quantity.value = b.dataset.qty;
        f.batchProductionDate.value = localDate(); updateHeadroom();
      }, 50);
    });
  }

  // ---------- inventory ----------
  let batchCache = [];
  loaders.inventory = async () => {
    const shopId = $('#invShop').value;
    batchCache = await api('/inventory/batches' + (shopId ? '?shopId=' + shopId : ''));
    renderBatches();
  };
  function renderBatches() {
    const tier = $('#invTier').value, q = $('#invSearch').value.trim().toLowerCase();
    const rows = batchCache.filter(b => (!tier || b.tier === tier) && (!q || (b.skuCode + b.skuName + b.batchNo).toLowerCase().includes(q)));
    $('#batchTable').innerHTML = `<tr><th>Shop</th><th>SKU</th><th>Batch</th><th>Produced</th><th>Expires</th><th class="num">Elapsed / life</th><th class="num">R<sub>dep</sub></th><th>Tier</th><th class="num">On hand</th><th class="num">Cost value</th><th class="num">Retail value</th><th class="num">Provision</th></tr>` +
      rows.slice(0, 1000).map(b => `<tr><td>${esc(b.shopCode)}</td><td><b>${esc(b.skuCode)}</b> ${esc(b.skuName)}</td><td>${esc(b.batchNo)}</td><td>${b.productionDate}</td><td>${b.expiryDate}</td>
      <td class="num">${b.elapsedDays} / ${b.shelfLifeDays}d</td><td class="num">${b.rDep.toFixed(3)}</td><td>${badge(b.tier, 't-' + b.tier)}</td><td class="num">${num(b.onHandQty)}</td>
      <td class="num">${money(b.costValue)}</td><td class="num">${money(b.retailValue)}</td><td class="num">${money(b.provision)}</td></tr>`).join('') +
      (rows.length > 1000 ? `<tr><td colspan="12" class="hint">Showing first 1000 of ${rows.length} batches. Filter to narrow down.</td></tr>` : '');
  }

  // ---------- movements ----------
  async function updateHeadroom() {
    const f = $('#stockInForm');
    if (!f.shopId.value || !f.skuId.value) return;
    try {
      const h = await api(`/inventory/headroom?shopId=${f.shopId.value}&skuId=${f.skuId.value}`);
      $('#headroom').textContent = `This shop can receive up to ${num(h.maxReceivableQty)} more units of this SKU within its storage and budget limits.`;
    } catch (e) { $('#headroom').textContent = ''; }
  }
  async function updateOutBatches() {
    const f = $('#stockOutForm');
    if (!f.shopId.value) return;
    const list = (await api('/inventory/batches?shopId=' + f.shopId.value)).filter(b => String(b.skuId) === f.skuId.value);
    $('#outBatch').innerHTML = '<option value="">FEFO auto-allocate</option>' + list.map(b => `<option value="${b.batchId}">${esc(b.batchNo)} · ${b.onHandQty} units · ${b.tier}</option>`).join('');
  }
  function movementResult(r, verb) {
    return `<div class="ok"><b>${verb}</b>. On-hand balance is now <b>${num(r.onHandAfter)}</b>.<ul>` +
      r.transactions.map(t => `<li>#${t.id} ${esc(t.batchNo)}: ${t.quantityDelta > 0 ? '+' : ''}${t.quantityDelta} → ${t.balanceAfter}</li>`).join('') +
      r.warnings.map(w => `<li>⚠ ${esc(w)}</li>`).join('') + '</ul></div>';
  }
  loaders.movements = () => { updateHeadroom(); updateOutBatches(); };

  // ---------- ledger ----------
  loaders.ledger = async () => {
    const p = new URLSearchParams({ page: state.ledgerPage, size: 50 });
    if ($('#ledShop').value) p.set('shopId', $('#ledShop').value);
    if ($('#ledSku').value) p.set('skuId', $('#ledSku').value);
    if ($('#ledType').value) p.set('type', $('#ledType').value);
    const page = await api('/inventory/transactions?' + p);
    $('#ledgerTable').innerHTML = `<tr><th>#</th><th>Time (CST)</th><th>Type</th><th>Reason</th><th>Shop</th><th>SKU</th><th>Batch</th><th class="num">Qty</th><th class="num">Balance</th><th class="num">Cost</th><th>Reference</th><th>Correlation id</th></tr>` +
      page.content.map(t => `<tr><td>${t.id}</td><td>${new Date(t.occurredAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })}</td><td>${t.type === 'STOCK_IN' ? '<span class="in">IN</span>' : '<span class="out">OUT</span>'}</td>
      <td>${esc(t.reason || '')}</td><td>${esc(t.shopCode)}</td><td>${esc(t.skuCode)}</td><td>${esc(t.batchNo)}</td><td class="num">${t.quantityDelta > 0 ? '+' : ''}${t.quantityDelta}</td><td class="num">${t.balanceAfter}</td>
      <td class="num">${money(t.costAmount)}</td><td>${esc(t.reference || '')}</td><td class="hint">${esc((t.correlationId || '').slice(0, 8))}</td></tr>`).join('');
    $('#ledPage').textContent = `Page ${page.number + 1} of ${Math.max(1, page.totalPages)} · ${num(page.totalElements)} entries`;
    $('#ledPrev').disabled = page.first; $('#ledNext').disabled = page.last;
  };

  // ---------- SKUs ----------
  loaders.skus = async () => {
    await loadMaster();
    $('#skuTable').innerHTML = `<tr><th>Code</th><th>Name</th><th>Category</th><th class="num">Purchase</th><th class="num">Sale</th><th class="num">Margin</th><th class="num">Volume m³</th><th class="num">Shelf life</th><th>Status</th><th></th></tr>` +
      state.skus.map(s => `<tr class="${s.active ? '' : 'inactive'}"><td><b>${esc(s.code)}</b></td><td>${esc(s.name)}</td><td>${esc(s.category)}</td><td class="num">${money(s.purchasePrice)}</td><td class="num">${money(s.salePrice)}</td>
      <td class="num">${pct((s.salePrice - s.purchasePrice) / (s.salePrice || 1))}</td><td class="num">${s.unitVolumeM3}</td><td class="num">${s.shelfLifeDays}d</td><td>${s.active ? 'Active' : 'Inactive'}</td>
      <td>${!can('HQ_ADMIN') ? '' : `<button class="small ghost" onclick="App.editSku(${s.id})">Edit</button>`} ${!can('HQ_ADMIN') ? '' : s.active ? `<button class="small ghost" onclick="App.toggleSku(${s.id}, false)">Deactivate</button>` : `<button class="small ghost" onclick="App.toggleSku(${s.id}, true)">Activate</button>`}</td></tr>`).join('');
  };
  function openModal(title, fields, values, onSubmit) {
    const f = $('#modalForm');
    f.innerHTML = `<h3>${esc(title)}</h3>` + fields.map(([name, label, type, extra]) => type === 'select'
      ? `<label>${esc(label)} <select name="${name}" required>${extra}</select></label>`
      : `<label>${esc(label)} <input name="${name}" type="${type}" ${extra || ''} value="${esc(values?.[name] ?? '')}" required></label>`).join('') +
      `<div class="result" id="modalResult"></div><div class="actions"><button type="button" class="ghost" id="modalCancel">Cancel</button><button type="submit">Save</button></div>`;
    fields.filter(f => f[2] === 'select' && values?.[f[0]] != null).forEach(f => { f.value = values[f[0]]; $(`#modalForm select[name="${f[0]}"]`).value = values[f[0]]; });
    $('#modalCancel').onclick = () => $('#modal').close();
    f.onsubmit = async ev => {
      ev.preventDefault();
      try { await onSubmit(formData(f)); $('#modal').close(); }
      catch (e) { $('#modalResult').innerHTML = errorHtml(e); }
    };
    $('#modal').showModal();
  }
  function editSku(id) {
    const s = state.skus.find(x => x.id === id);
    openModal(s ? 'Edit SKU ' + s.code : 'New SKU', [
      ['code', 'Code', 'text'], ['name', 'Name', 'text'], ['category', 'Category', 'text'],
      ['purchasePrice', 'Purchase price (¥)', 'number', 'step="0.01" min="0"'], ['salePrice', 'Sale price (¥)', 'number', 'step="0.01" min="0"'],
      ['unitVolumeM3', 'Unit storage volume (m³)', 'number', 'step="0.00001" min="0.00001"'], ['shelfLifeDays', 'Shelf life (days)', 'number', 'min="1"']
    ], s, async body => {
      await api(s ? '/skus/' + id : '/skus', { method: s ? 'PUT' : 'POST', body: JSON.stringify(body) });
      toast('SKU saved'); loaders.skus();
    });
  }
  async function toggleSku(id, on) {
    try { await api(on ? `/skus/${id}/activate` : `/skus/${id}`, { method: on ? 'POST' : 'DELETE' }); toast('SKU updated'); loaders.skus(); }
    catch (e) { toast(e.message); }
  }

  // ---------- Shops ----------
  loaders.shops = async () => {
    await loadMaster();
    const manage = can('HQ_ADMIN');
    $('#shopMasterTable').innerHTML = `<tr><th>Code</th><th>Name</th><th>Region</th><th>City</th><th class="num">Capacity m³</th><th class="num">Budget</th><th>Storage used</th><th>Budget used</th><th>Status</th><th></th></tr>` +
      state.shops.map(s => `<tr class="clickable ${s.active ? '' : 'inactive'}" data-id="${s.id}"><td><b>${esc(s.code)}</b></td><td>${esc(s.name)}</td><td>${esc(s.regionCode)}</td><td>${esc(s.city)}</td><td class="num">${num(s.storageCapacityM3, 3)}</td><td class="num">${money(s.capitalBudget)}</td>
      <td>${meter(s.storageUtilisation)}</td><td>${meter(s.budgetUtilisation)}</td><td>${s.active ? 'Active' : 'Inactive'}</td>
      <td>${manage ? `<button class="small ghost" data-edit="${s.id}">Edit</button> <button class="small ghost" data-toggle="${s.id}">${s.active ? 'Deactivate' : 'Activate'}</button>` : ''}</td></tr>`).join('');
    $$('#shopMasterTable tr[data-id]').forEach(tr => tr.onclick = ev => {
      if (ev.target.dataset.edit) return editShop(+ev.target.dataset.edit);
      if (ev.target.dataset.toggle) return toggleShop(+ev.target.dataset.toggle);
      showVelocities(+tr.dataset.id);
    });
    if (state.velocityShop) showVelocities(state.velocityShop);
  };
  function editShop(id) {
    const s = state.shops.find(x => x.id === id);
    const regionOpts = state.regions.filter(g => g.active || g.id === s?.regionId).map(g => `<option value="${g.id}">${esc(g.code)} — ${esc(g.name)}</option>`).join('');
    openModal(s ? 'Edit shop ' + s.code : 'New shop', [
      ['regionId', 'Region', 'select', regionOpts], ['code', 'Code', 'text'], ['name', 'Name', 'text'], ['city', 'City', 'text'],
      ['storageCapacityM3', 'Storage capacity (m³)', 'number', 'step="0.001" min="0.001"'], ['capitalBudget', 'Capital budget (¥)', 'number', 'step="0.01" min="0"']
    ], s, async body => {
      await api(s ? '/shops/' + id : '/shops', { method: s ? 'PUT' : 'POST', body: JSON.stringify(body) });
      toast('Shop saved'); loaders.shops();
    });
  }
  async function toggleShop(id) {
    const s = state.shops.find(x => x.id === id);
    try { await api(s.active ? `/shops/${id}` : `/shops/${id}/activate`, { method: s.active ? 'DELETE' : 'POST' }); toast('Shop updated'); loaders.shops(); }
    catch (e) { toast(e.message); }
  }
  async function showVelocities(shopId) {
    state.velocityShop = shopId;
    const s = state.shops.find(x => x.id === shopId);
    const list = await api(`/shops/${shopId}/velocities`);
    $('#velocityCard').hidden = false;
    $('#velocityTitle').textContent = `Sales velocity · ${s.code} ${s.name}`;
    $('#velocityTable').innerHTML = `<tr><th>SKU</th><th class="num">Daily velocity</th><th class="num">On hand</th><th class="num">Days of cover</th></tr>` +
      list.map(v => `<tr><td><b>${esc(v.skuCode)}</b> ${esc(v.skuName)}</td><td class="num">${num(v.dailySalesVelocity, 1)}</td><td class="num">${num(v.onHandQty)}</td><td class="num">${v.daysOfCover == null ? '–' : num(v.daysOfCover, 1)}</td></tr>`).join('');
  }

  // ---------- Reports ----------
  loaders.reports = async () => {
    const reports = await api('/reports');
    const labels = reports.map(r => r.period);
    const o1 = baseOptions();
    o1.scales.y.ticks.callback = v => moneyShort(v);
    o1.plugins.tooltip.callbacks = { label: c => `${c.dataset.label}: ${money(c.raw)}` };
    const line = (label, data, color) => ({ label, data, borderColor: color, backgroundColor: color, borderWidth: 2, pointRadius: 4, pointHoverRadius: 6, tension: 0.25 });
    chart('valTrend', { type: 'line', data: { labels, datasets: [
      line('Retail potential', reports.map(r => r.totalRetailValue), cssVar('--s2')),
      line('Purchase cost', reports.map(r => r.totalCostValue), cssVar('--s1')),
      line('Net realisable cost', reports.map(r => r.netRealisableCost), cssVar('--s3'))
    ] }, options: o1 });
    const o2 = baseOptions({}); o2.plugins.legend = { display: false };
    o2.plugins.tooltip.callbacks = { label: c => `Turnover ${c.raw.toFixed(2)}×` };
    chart('turnTrend', { type: 'line', data: { labels, datasets: [line('Turnover rate', reports.map(r => +r.turnoverRate.toFixed(3)), cssVar('--s1'))] }, options: o2 });
    const o3 = baseOptions(); o3.plugins.legend = { display: false };
    o3.scales.y.ticks.callback = v => moneyShort(v);
    o3.plugins.tooltip.callbacks = { label: c => { const r = reports[c.dataIndex]; return [`Provision ${money(c.raw)}`, `High-risk cost ${money(r.highCost)}`, `Expired cost ${money(r.expiredCost)}`]; } };
    const codes = [...new Set(reports.flatMap(r => r.regions.map(g => g.regionCode)))].sort();
    const series = ['--s1', '--s2', '--s3', '--s4'];   // fixed palette order; >4 regions would fold into 'Other'
    const o4 = baseOptions(); o4.scales.y.ticks.callback = v => moneyShort(v);
    o4.plugins.tooltip.callbacks = { label: c => `${c.dataset.label}: ${money(c.raw)}` };
    chart('regionTrend', { type: 'line', data: { labels, datasets: codes.map((code, i) => line(code,
      reports.map(r => r.regions.find(g => g.regionCode === code)?.costValue ?? null), cssVar(series[i] || '--muted'))) }, options: o4 });
    chart('provTrend', { type: 'bar', data: { labels, datasets: [{ label: 'Provision', data: reports.map(r => r.totalProvision), backgroundColor: cssVar('--s1'), borderRadius: 4, borderSkipped: 'bottom', maxBarThickness: 40 }] }, options: o3 });

    const rev = [...reports].reverse();
    $('#reportTable').innerHTML = `<tr><th>Period</th><th>As of</th><th>Trigger</th><th class="num">V<sub>cost</sub></th><th class="num">MoM</th><th class="num">V<sub>retail</sub></th><th class="num">COGS 30d</th><th class="num">Turnover</th><th class="num">Provision</th><th class="num">Net realisable</th><th>Generated</th></tr>` +
      rev.map((r, i) => {
        const prev = rev[i + 1];
        const mom = prev ? (r.totalCostValue - prev.totalCostValue) / (prev.totalCostValue || 1) : null;
        return `<tr class="clickable" data-p="${r.period}"><td><b>${r.period}</b></td><td>${r.asOfDate}</td><td>${esc(r.trigger)}</td><td class="num">${money(r.totalCostValue)}</td>
        <td class="num">${mom == null ? '–' : (mom >= 0 ? '▲ ' : '▼ ') + pct(Math.abs(mom))}</td><td class="num">${money(r.totalRetailValue)}</td><td class="num">${money(r.totalCogs30d)}</td>
        <td class="num">${num(r.turnoverRate, 2)}×</td><td class="num">${money(r.totalProvision)}</td><td class="num">${money(r.netRealisableCost)}</td><td>${new Date(r.generatedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })}</td></tr>`;
      }).join('');
    $$('#reportTable tr[data-p]').forEach(tr => tr.onclick = () => showReport(tr.dataset.p));
    if (rev.length && $('#reportDetail').hidden) showReport(rev[0].period);
  };
  async function showReport(period) {
    const { report, lines } = await api('/reports/' + period);
    $('#reportDetail').hidden = false;
    $('#reportDetailTitle').textContent = `Report ${report.period} · as of ${report.asOfDate} · ${report.shopCount} shops`;
    $('#csvLink').href = `/api/reports/${period}/csv`;
    $('#reportRegions').innerHTML = `<tr><th>Region</th><th class="num">Shops</th><th class="num">V<sub>cost</sub></th><th class="num">V<sub>retail</sub></th><th class="num">COGS 30d</th><th class="num">Turnover</th><th class="num">Provision</th><th class="num">Net realisable</th></tr>` +
      report.regions.map(g => `<tr><td><b>${esc(g.regionCode)}</b> ${esc(g.regionName)}</td><td class="num">${g.shopCount}</td><td class="num">${money(g.costValue)}</td><td class="num">${money(g.retailValue)}</td><td class="num">${money(g.cogs30d)}</td>
      <td class="num">${turn(g.turnoverRate, g.costValue)}</td><td class="num">${money(g.provision)}</td><td class="num">${money(g.netRealisableCost)}</td></tr>`).join('');
    $('#reportLines').innerHTML = `<tr><th>Shop</th><th>Region</th><th>City</th><th class="num">V<sub>cost</sub></th><th class="num">V<sub>retail</sub></th><th class="num">COGS 30d</th><th class="num">Turnover</th><th class="num">Provision</th><th class="num">High-risk cost</th><th class="num">Expired cost</th><th>Storage</th><th>Budget</th></tr>` +
      lines.map(l => `<tr><td><b>${esc(l.shopCode)}</b> ${esc(l.shopName)}</td><td>${esc(l.regionCode)}</td><td>${esc(l.city)}</td><td class="num">${money(l.costValue)}</td><td class="num">${money(l.retailValue)}</td><td class="num">${money(l.cogs30d)}</td>
      <td class="num">${turn(l.turnoverRate, l.costValue)}</td><td class="num">${money(l.provision)}</td><td class="num">${money(l.highRiskCost)}</td><td class="num">${money(l.expiredCost)}</td><td>${meter(l.storageUtilisation)}</td><td>${meter(l.budgetUtilisation)}</td></tr>`).join('');
  }

  // ---------- wiring ----------
  function wire() {
    $('#invShop').onchange = loaders.inventory;
    $('#invTier').onchange = renderBatches;
    $('#invSearch').oninput = renderBatches;
    for (const id of ['ledShop', 'ledSku', 'ledType']) $('#' + id).onchange = () => { state.ledgerPage = 0; loaders.ledger(); };
    $('#ledPrev').onclick = () => { state.ledgerPage--; loaders.ledger(); };
    $('#ledNext').onclick = () => { state.ledgerPage++; loaders.ledger(); };

    const inF = $('#stockInForm'), outF = $('#stockOutForm');
    inF.shopId.onchange = inF.skuId.onchange = updateHeadroom;
    outF.shopId.onchange = outF.skuId.onchange = updateOutBatches;
    inF.batchProductionDate.max = localDate();
    inF.onsubmit = async ev => {
      ev.preventDefault();
      try { const r = await api('/inventory/stock-in', { method: 'POST', body: JSON.stringify(formData(inF)) }); $('#stockInResult').innerHTML = movementResult(r, 'Stock received'); updateHeadroom(); updateOutBatches(); }
      catch (e) { $('#stockInResult').innerHTML = errorHtml(e); }
    };
    outF.onsubmit = async ev => {
      ev.preventDefault();
      try { const r = await api('/inventory/stock-out', { method: 'POST', body: JSON.stringify(formData(outF)) }); $('#stockOutResult').innerHTML = movementResult(r, 'Stock issued'); updateOutBatches(); updateHeadroom(); }
      catch (e) { $('#stockOutResult').innerHTML = errorHtml(e); }
    };
    $('#velocityForm').onsubmit = async ev => {
      ev.preventDefault();
      const d = formData(ev.target);
      try { await api(`/shops/${state.velocityShop}/velocities/${d.skuId}`, { method: 'PUT', body: JSON.stringify({ dailySalesVelocity: d.dailySalesVelocity }) }); toast('Velocity saved'); showVelocities(state.velocityShop); }
      catch (e) { toast(e.message); }
    };
    const gen = $('#genForm');
    const lastMonth = new Date(); lastMonth.setDate(1); lastMonth.setMonth(lastMonth.getMonth() - 1);
    gen.period.value = localDate(lastMonth).slice(0, 7);
    gen.onsubmit = async ev => {
      ev.preventDefault();
      try { await api('/reports/generate?period=' + gen.period.value, { method: 'POST' }); toast('Report ' + gen.period.value + ' generated'); $('#reportDetail').hidden = true; loaders.reports(); }
      catch (e) { toast(e.message); }
    };
    $('#themeToggle').onclick = () => {
      const dark = document.documentElement.dataset.theme ? document.documentElement.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
      document.documentElement.dataset.theme = dark ? 'light' : 'dark';
      try { localStorage.setItem('theme', document.documentElement.dataset.theme); } catch (e) {}
      route();
    };
    try { const t = localStorage.getItem('theme'); if (t) document.documentElement.dataset.theme = t; } catch (e) {}
    window.addEventListener('hashchange', route);
    $('#regionFilter').onchange = () => loaders.dashboard();
    $('#festHorizon').onchange = () => loaders.festivals();
    $('#festSearch').oninput = renderFestLines;
    $('#loginForm').onsubmit = async ev => {
      ev.preventDefault();
      try {
        state.me = await api('/auth/login', { method: 'POST', body: JSON.stringify(formData(ev.target)) });
        ev.target.reset(); $('#loginResult').innerHTML = '';
        await startSession();
      } catch (e) { $('#loginResult').innerHTML = errorHtml(e); }
    };
    $('#logoutBtn').onclick = async () => {
      try { await api('/auth/logout', { method: 'POST' }); } catch (e) {}
      // full reload: no rendered data from this user may remain in the DOM for the next person at a shared till
      state.me = null;
      location.replace(location.pathname);
    };
  }

  async function showLogin() {
    $('#login').hidden = false; $('#userChip').hidden = true;
    try {
      const demo = await api('/auth/demo-accounts');
      $('#demoAccounts').hidden = !demo.length;
      $('#demoList').innerHTML = demo.map(d => `<button type="button" data-u="${esc(d.username)}"><span><b>${esc(d.username)}</b> · ${esc(d.displayName)}</span><span class="r">${esc(ROLE_LABEL[d.role])} · ${esc(d.scope)}</span></button>`).join('');
      $$('#demoList button').forEach(b => b.onclick = () => { const f = $('#loginForm'); f.username.value = b.dataset.u; f.password.value = 'demo1234'; f.password.focus(); });
    } catch (e) {}
    $('#loginForm').username.focus();
  }

  async function startSession() {
    $('#login').hidden = true;
    const me = state.me;
    $('#userChip').hidden = false;
    $('#userName').textContent = me.displayName;
    $('#userRole').textContent = ROLE_LABEL[me.role] + (me.shopCode ? ' · ' + me.shopCode : ' · all shops');
    $('#hqBrand').textContent = me.hqName || 'Regional Inventory HQ';
    document.body.dataset.role = me.role;
    applyRoles();
    $('#regionFilter').value = '';
    await loadMaster();
    route();
  }

  async function init() {
    wire();
    const today = localDate();
    $('#stockInForm').batchProductionDate.value = today;
    try { state.me = await api('/auth/me'); await startSession(); }
    catch (e) { showLogin(); }
  }
  document.addEventListener('DOMContentLoaded', init);

  return { loadDashboard: () => loaders.dashboard(), editSku, toggleSku, editShop, editRegion, toggleRegion };
})();
