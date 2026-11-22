// UI smoke test + screenshot capture. Usage: node e2e/screenshots.js [baseUrl]
const { chromium } = require('playwright');
const path = require('path');
const base = process.argv[2] || 'http://localhost:8080';
const out = path.join(__dirname, '..', 'docs', 'screenshots');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  // 401 = signed-out probes / the deliberate bad password; 422 = the deliberate oversell below
  page.on('console', m => { if (m.type() === 'error' && !/status of (401|422)/.test(m.text())) errors.push(m.text()); });
  const check = (cond, msg) => { if (!cond) throw new Error('UI check failed: ' + msg); console.log('  ok -', msg); };

  async function login(p, user) {
    await p.goto(base + '/#dashboard');
    await p.waitForSelector('#login:not([hidden])');
    await p.fill('#loginForm input[name=username]', user);
    await p.fill('#loginForm input[name=password]', 'demo1234');
    await p.click('#loginForm button[type=submit]');
    await p.waitForSelector('#kpiTiles .tile');
    await p.waitForTimeout(500);
  }
  async function logout(p) {
    await p.click('#logoutBtn');
    await p.waitForSelector('#login:not([hidden])');
  }

  // ---- sign-in screen
  await page.goto(base + '/');
  await page.waitForSelector('#demoList button');
  check(await page.locator('#demoList button').count() === 5, 'sign-in lists the 5 demo accounts');
  await page.fill('#loginForm input[name=username]', 'hq.admin');
  await page.fill('#loginForm input[name=password]', 'wrong-password');
  await page.click('#loginForm button[type=submit]');
  await page.waitForSelector('#loginResult .err');
  check((await page.textContent('#loginResult')).includes('BAD_CREDENTIALS'), 'wrong password is rejected');
  await page.screenshot({ path: `${out}/00-sign-in.png` });

  // ---- shop manager: own shop only
  await login(page, 'shop.sg01');
  check((await page.textContent('#dashTitle')).includes('SG-01'), 'shop manager lands on own-shop dashboard');
  check(await page.locator('#shopTable tr').count() === 2, 'shop manager sees exactly one shop');
  check(!(await page.isVisible('#nav a[data-page=reports]')) && !(await page.isVisible('#nav a[data-page=regions]')), 'shop manager has no Reports/Regions tabs');
  await page.goto(base + '/#skus');
  await page.waitForSelector('#skuTable tr:nth-child(2)');
  check(!(await page.isVisible('#page-skus button[data-roles]')), 'shop manager cannot add SKUs');
  await page.goto(base + '/#dashboard');
  await page.waitForSelector('#kpiTiles .tile');
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${out}/10-dashboard-shop-manager.png` });
  await logout(page);

  // ---- regional HQ: own region only
  await login(page, 'rhq.mainland');
  check((await page.textContent('#dashTitle')).includes('SEA-MAINLAND'), 'regional HQ lands on its region dashboard');
  check(await page.locator('#regionTable tr').count() === 2, 'regional HQ sees only its own region');
  check(await page.locator('#shopTable tr').count() === 9, 'regional HQ sees its 8 shops');
  check(!(await page.isVisible('#genForm')), 'only group HQ can run the monthly batch');
  await page.screenshot({ path: `${out}/11-dashboard-regional-hq.png` });
  await page.goto(base + '/#reports');
  await page.waitForSelector('#reportRegions tr:nth-child(2)');
  check(await page.locator('#reportRegions tr').count() === 2, 'regional HQ report shows only its region');
  await logout(page);

  // ---- group HQ: everything
  await login(page, 'hq.admin');
  check((await page.textContent('#dashTitle')).includes('Group HQ'), 'group HQ sees the group dashboard');
  check(await page.locator('#regionTable tr').count() === 3, 'group HQ sees both regional HQs');
  check(await page.locator('#kpiTiles .tile').count() === 7, 'dashboard shows 7 KPI tiles');
  check(await page.locator('#shopTable tr').count() > 20, 'shop breakdown lists all shops');
  await page.screenshot({ path: `${out}/01-dashboard.png`, fullPage: true });

  await page.goto(base + '/#inventory');
  await page.waitForSelector('#batchTable tr:nth-child(2)');
  await page.selectOption('#invTier', 'MODERATE');
  check(await page.locator('#batchTable .t-MODERATE').count() > 0, 'inventory filters batches by risk tier');
  await page.selectOption('#invTier', '');
  await page.screenshot({ path: `${out}/02-inventory-batches.png` });

  // Stock-in happy path
  await page.goto(base + '/#movements');
  await page.waitForSelector('#headroom:not(:empty)');
  await page.fill('#stockInForm input[name=quantity]', '5');
  await page.click('#stockInForm button[type=submit]');
  await page.waitForSelector('#stockInResult .ok');
  // Stock-out rejected: more than available
  await page.fill('#stockOutForm input[name=quantity]', '999999');
  await page.click('#stockOutForm button[type=submit]');
  await page.waitForSelector('#stockOutResult .err');
  check((await page.textContent('#stockOutResult')).includes('INSUFFICIENT_STOCK'), 'oversell is rejected with INSUFFICIENT_STOCK');
  await page.screenshot({ path: `${out}/03-stock-movements.png` });

  await page.goto(base + '/#ledger');
  await page.waitForSelector('#ledgerTable tr:nth-child(2)');
  check((await page.textContent('#ledgerTable tr:nth-child(2)')).includes('+5'), 'newest ledger entry is the +5 stock-in just made');
  await page.screenshot({ path: `${out}/04-ledger.png` });

  await page.goto(base + '/#skus');
  await page.waitForSelector('#skuTable tr:nth-child(2)');
  await page.screenshot({ path: `${out}/05-skus.png` });

  await page.goto(base + '/#shops');
  await page.waitForSelector('#shopMasterTable tr:nth-child(2)');
  await page.click('#shopMasterTable tr[data-id] td:first-child');
  await page.waitForSelector('#velocityTable tr:nth-child(2)');
  await page.screenshot({ path: `${out}/06-shops-velocity.png`, fullPage: true });

  await page.goto(base + '/#regions');
  await page.waitForSelector('#regionMasterTable tr:nth-child(2)');
  check(await page.locator('#regionMasterTable tr').count() === 3, 'regions page lists both regions');
  await page.screenshot({ path: `${out}/12-regions.png` });

  await page.goto(base + '/#reports');
  await page.waitForSelector('#reportLines tr:nth-child(2)');
  await page.waitForTimeout(600);
  check(await page.locator('#reportTable tr[data-p]').count() >= 6, 'report history has >= 6 months of trend');
  await page.screenshot({ path: `${out}/07-monthly-reports.png`, fullPage: true });

  await page.goto(base + '/#dashboard');
  await page.evaluate(() => localStorage.setItem('theme', 'dark'));
  await page.reload();
  await page.waitForSelector('#kpiTiles .tile');
  await page.waitForTimeout(600);
  check(await page.evaluate(() => document.documentElement.dataset.theme) === 'dark', 'dark theme applies');
  await page.screenshot({ path: `${out}/08-dashboard-dark.png` });
  await page.evaluate(() => localStorage.removeItem('theme'));

  const mobile = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  await login(mobile, 'hq.admin');
  const overflow = await mobile.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  check(!overflow, 'no horizontal page scroll at phone width');
  await mobile.screenshot({ path: `${out}/09-dashboard-mobile.png` });

  check(errors.length === 0, 'no browser console errors' + (errors.length ? ': ' + errors.join(' | ') : ''));
  await browser.close();
  console.log('UI e2e passed; screenshots in docs/screenshots');
})().catch(e => { console.error(e); process.exit(1); });
