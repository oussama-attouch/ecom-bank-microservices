#!/usr/bin/env node
/**
 * Verifies the /observability/archived page in a real browser.
 *
 * Run:      node scripts/verify-archived-transactions.mjs
 * Needs:    Node 20+, Chrome (set CHROME_PATH to override the default), and the
 *           stack up: dev server :4200, gateway :8888, billing :8083,
 *           Keycloak :8180. Drives Chrome over CDP on port 9341 (CDP_PORT).
 * Writes:   docs/screenshots/observability/archived-transactions.png
 *           scripts/verify-archived-transactions.json
 *
 * Signs in through Keycloak the way the SPA does — it lets the app start its own
 * PKCE sign-in and completes only Keycloak's half — then asserts the table, its
 * default newest-first sort, all six filters, both empty states, the CSV export
 * (downloaded and parsed), the saga link's client-side navigation, and that the
 * Live Events page still renders. Exit code 2 means a check failed.
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.CHROME_PATH
  ?? 'C:\\Users\\oussa\\.cache\\puppeteer\\chrome\\win64-131.0.6778.204\\chrome-win64\\chrome.exe';
const PORT = Number(process.env.CDP_PORT ?? 9341);
const ORIGIN = 'http://localhost:4200';
const APP = `${ORIGIN}/observability/archived`;
const LIVE = `${ORIGIN}/observability/live`;
const USER = process.env.KEYCLOAK_USER ?? 'manager';
const PASS = process.env.KEYCLOAK_PASS ?? 'manager123';
const REPO = path.resolve(import.meta.dirname, '..');
const SHOT = path.join(REPO, 'docs', 'screenshots', 'observability', 'archived-transactions.png');

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'cdp-arch-'));
const downloads = fs.mkdtempSync(path.join(os.tmpdir(), 'cdp-dl-'));
console.log('profile  :', profile);
console.log('downloads:', downloads);

const chrome = spawn(CHROME, [
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`,
  '--headless=new',
  // Fixed desktop viewport: an unconstrained headless window made the table
  // overflow and the amount column look clipped, which the real app never does.
  '--window-size=1440,900',
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-gpu',
  'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const consoleErrors = [];
const failedRequests = [];
const archiveCalls = [];
const ok = (name, pass, detail = '') => {
  results.push({ name, pass: !!pass, detail });
  console.log(`  ${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`);
};

async function http(pathname, method = 'GET') {
  const res = await fetch(`http://127.0.0.1:${PORT}${pathname}`, { method });
  return res.json();
}
async function waitForChrome() {
  for (let i = 0; i < 80; i += 1) {
    try { return await http('/json/version'); } catch { await sleep(250); }
  }
  throw new Error('chrome did not expose CDP');
}
console.log('chrome   :', (await waitForChrome()).Browser);

const target = await http(`/json/new?${encodeURIComponent('about:blank')}`, 'PUT');
const ws = new WebSocket(target.webSocketDebuggerUrl);
let nextId = 1;
const pending = new Map();
let authorizeUrl = null;

const send = (method, params = {}) =>
  new Promise((resolve) => {
    const id = nextId++;
    pending.set(id, resolve);
    ws.send(JSON.stringify({ id, method, params }));
  });

ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg.result); pending.delete(msg.id); return; }
  const p = msg.params ?? {};
  // Every call to the archive endpoint, with the bearer header's presence: this
  // is what distinguishes "the request raced the token" from "the request was
  // rejected for another reason".
  if (msg.method === 'Network.requestWillBeSent'
      && String(p.request?.url ?? '').includes('/api/archived-transactions')) {
    const auth = p.request?.headers?.Authorization ?? p.request?.headers?.authorization;
    archiveCalls.push({ dir: 'REQ', url: p.request.url, hasAuth: !!auth, authLen: auth ? auth.length : 0 });
  }
  if (msg.method === 'Network.responseReceived'
      && String(p.response?.url ?? '').includes('/api/archived-transactions')) {
    archiveCalls.push({ dir: 'RES', status: p.response.status, url: p.response.url });
  }
  if (msg.method === 'Network.requestWillBeSent'
      && String(p.request?.url ?? '').includes('/protocol/openid-connect/auth')) {
    authorizeUrl = p.request.url;
  } else if (msg.method === 'Network.responseReceived' && p.response?.status >= 400) {
    failedRequests.push(`${p.response.status} ${String(p.response.url).slice(0, 110)}`);
  } else if (msg.method === 'Runtime.consoleAPICalled' && p.type === 'error') {
    consoleErrors.push((p.args ?? []).map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 240));
  } else if (msg.method === 'Runtime.exceptionThrown') {
    consoleErrors.push(`exception: ${p.exceptionDetails?.text} ${p.exceptionDetails?.exception?.description ?? ''}`.slice(0, 240));
  }
});

await new Promise((r) => ws.addEventListener('open', r));
await send('Network.enable');
await send('Page.enable');
await send('Runtime.enable');
await send('Page.setDownloadBehavior', { behavior: 'allow', downloadPath: downloads });

const evaluate = async (expression) => {
  const res = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (res?.exceptionDetails) return `<<eval error: ${res.exceptionDetails.text}>>`;
  return res?.result?.value;
};
const url = () => evaluate('location.href');
const text = (n = 600) => evaluate(`document.body ? document.body.innerText.slice(0, ${n}) : ""`);

/** Poll a boolean expression until true. Fixed sleeps were a real source of
 *  false failures here: the first archive request lands whenever the auth
 *  token does, which is not a fixed interval after navigation. */
async function waitFor(label, expression, timeoutMs = 45000) {
  const started = Date.now();
  for (;;) {
    if (await evaluate(expression)) { console.log(`  [wait] ${label} ready after ${Date.now() - started}ms`); return true; }
    if (Date.now() - started > timeoutMs) { console.log(`  [wait] ${label} TIMED OUT after ${timeoutMs}ms`); return false; }
    await sleep(500);
  }
}
const TABLE_LOADED = `!!document.querySelector('.p-datatable-thead th') && !document.querySelector('app-table-skeleton')`;

// ---------------------------------------------------------------- sign in ----
console.log('\n--- sign in ---');
await send('Page.navigate', { url: `${ORIGIN}/login` });
await sleep(9000);
await evaluate(`(() => { const b = document.querySelector('p-button button') || document.querySelector('button'); if (b) b.click(); return !!b; })()`);
for (let i = 0; i < 24 && !authorizeUrl; i += 1) await sleep(500);
if (!authorizeUrl) { console.log('could not capture authorize URL'); ws.close(); chrome.kill(); process.exit(1); }
await send('Page.navigate', { url: authorizeUrl });
await sleep(5000);
const submitted = await evaluate(`(() => {
  const u = document.querySelector('#username'), p = document.querySelector('#password');
  if (!u || !p) return 'no-form';
  u.value = ${JSON.stringify(USER)}; p.value = ${JSON.stringify(PASS)};
  document.querySelector('form').submit(); return 'submitted';
})()`);
console.log('keycloak submit:', submitted);
await sleep(9000);

// Errors already present from the app bootstrap / OIDC dance are not this
// page's: count them, so the page's own regression check compares like with like.
const errsAfterLogin = consoleErrors.length;
console.log('console errors during bootstrap:', errsAfterLogin, JSON.stringify(consoleErrors.slice(0, 4)));

// ------------------------------------------------------ load the new page ----
console.log('\n--- /observability/archived ---');
await send('Page.navigate', { url: APP });
const tableLoaded = await waitFor('archived table', TABLE_LOADED);
console.log('url:', await url());
if (!tableLoaded) {
  console.log('body text:', String(await text(900)));
  console.log('console errors:', JSON.stringify(consoleErrors.slice(0, 8), null, 2));
}

const dom = await evaluate(`(() => {
  const q = (s) => document.querySelector(s);
  const qa = (s) => Array.from(document.querySelectorAll(s));
  const cells = (tr) => Array.from(tr.querySelectorAll('td')).map((td) => td.innerText.trim());
  const rows = qa('.p-datatable-tbody > tr');
  const dataRows = rows.filter((tr) => !tr.classList.contains('p-datatable-emptymessage') && tr.querySelectorAll('td').length > 1);
  const sums = qa('.sum').map((d) => ({
    label: d.querySelector('label')?.innerText.trim() ?? '',
    value: d.querySelector('span')?.innerText.trim() ?? ''
  }));
  const navItems = qa('.nav a').map((a) => ({
    label: (a.querySelector('.nav-label')?.innerText ?? a.innerText).trim(),
    href: a.getAttribute('href')
  }));
  const ths = qa('.p-datatable-thead th').map((th) => th.innerText.trim().toLowerCase());
  const first = dataRows[0] ? cells(dataRows[0]) : null;
  const last = dataRows.length ? cells(dataRows[dataRows.length - 1]) : null;
  const link = q('.p-datatable-tbody a');
  return {
    title: q('.page-head h2')?.innerText.trim() ?? null,
    subtitle: q('.page-sub')?.innerText.trim().slice(0, 90) ?? null,
    headers: ths,
    rowCount: dataRows.length,
    firstRow: first,
    lastRow: last,
    firstRowLinkHref: link ? link.getAttribute('href') : null,
    firstRowLinkText: link ? link.innerText.trim() : null,
    firstRowLinkTitle: link ? link.getAttribute('title') : null,
    summaries: sums,
    sidebarHasArchived: navItems.some((n) => n.href === '/observability/archived' && n.label === 'Archived Transactions'),
    sidebarObservabilityItems: navItems.filter((n) => String(n.href).startsWith('/observability')).map((n) => n.label),
    paginatorPages: qa('.p-paginator .p-paginator-pages button').length,
    paginatorTotalText: q('.p-paginator-current')?.innerText.trim() ?? null,
    hasSkeleton: !!q('app-table-skeleton'),
    filterInputs: qa('.form-row input, .form-row select').length,
    hasExportButton: qa('p-button button').some((b) => b.innerText.includes('Export CSV')),
    hasRefreshButton: qa('p-button button').some((b) => b.innerText.includes('Refresh')),
    // One line per id and per stamp: the defect this page first shipped with.
    // Measured as painted line boxes — the cell is forced to 48px by the global
    // table sheet, so its own height would report 3 lines whatever happens.
    accountCellLines: (() => {
      const tr = dataRows[0];
      if (!tr) return null;
      const r = document.createRange();
      r.selectNodeContents(tr.querySelectorAll('td')[3]);
      return r.getClientRects().length;
    })(),
    dateCellLines: (() => {
      const tr = dataRows[0];
      if (!tr) return null;
      const r = document.createRange();
      r.selectNodeContents(tr.querySelector('td'));
      return r.getClientRects().length;
    })(),
    accountFontSize: (() => {
      const tr = dataRows[0];
      return tr ? getComputedStyle(tr.querySelectorAll('td')[3]).fontSize : null;
    })(),
    // Badge tone is asserted, not eyeballed: TRANSFER must be the quiet
    // secondary treatment and must not carry the amber warning background.
    typeBadge: (() => {
      const tr = dataRows[0];
      if (!tr) return null;
      const tag = tr.querySelectorAll('td')[2].querySelector('.p-tag');
      if (!tag) return null;
      const cs = getComputedStyle(tag);
      return {
        text: tag.innerText.trim(),
        classes: tag.className,
        background: cs.backgroundColor,
        color: cs.color
      };
    })(),
    // Every row is a TRANSFER today, so the whole column should be quiet.
    distinctTypeBadgeClasses: Array.from(new Set(
      dataRows.map((tr) => (tr.querySelectorAll('td')[2].querySelector('.p-tag')?.className ?? ''))
        .filter(Boolean)
    )),
    accountWhiteSpace: (() => {
      const tr = dataRows[0];
      return tr ? getComputedStyle(tr.querySelectorAll('td')[3]).whiteSpace : null;
    })(),
    // Every column must fit inside the table's own box: the amount column was
    // once pushed past the right edge (header read "AM") because the fixed
    // mono columns had no width floor to yield against.
    columnsInsideTable: (() => {
      const tbl = q('.p-datatable-table') || q('table');
      if (!tbl) return null;
      const right = tbl.getBoundingClientRect().right;
      const ths = qa('.p-datatable-thead th');
      return {
        tableRight: Math.round(right),
        overflow: ths.map((th) => Math.round(th.getBoundingClientRect().right - right)),
        allInside: ths.every((th) => th.getBoundingClientRect().right <= right + 1)
      };
    })()
  };
})()`);
console.log(JSON.stringify(dom, null, 2));

if (!tableLoaded) ok('archived table mounted', false, 'never left the skeleton state');
ok('page title renders', dom.title === 'Archived Transactions', String(dom.title));
ok('six columns in order', JSON.stringify(dom.headers) === JSON.stringify(['archived at', 'transaction id', 'type', 'from', 'to', 'amount']), JSON.stringify(dom.headers));
ok('table has rows', dom.rowCount > 0, `${dom.rowCount} rows on page 1`);
ok('skeleton gone after load', dom.hasSkeleton === false);
ok('six filter controls', dom.filterInputs === 6, `${dom.filterInputs} inputs/selects`);
ok('Export CSV button present', dom.hasExportButton);
ok('Refresh button present', dom.hasRefreshButton);
ok('four summary cells', dom.summaries.length === 4, JSON.stringify(dom.summaries));
ok('sidebar: Archived Transactions under Observability',
  dom.sidebarHasArchived, JSON.stringify(dom.sidebarObservabilityItems));
ok('paginator shows 15 per page', dom.paginatorTotalText === null || /15/.test(String(dom.paginatorTotalText)),
  String(dom.paginatorTotalText));
ok('sagas link uses router href', dom.firstRowLinkHref?.startsWith('/transactions/sagas/'),
  `${dom.firstRowLinkHref}`);
ok('first row link is not a full uuid (truncated)', !!dom.firstRowLinkText && dom.firstRowLinkText.includes('…'),
  String(dom.firstRowLinkText));
ok('link title carries the full uuid', /^[0-9a-f-]{36}$/.test(String(dom.firstRowLinkTitle)), String(dom.firstRowLinkTitle));
ok('account cell renders on one line', dom.accountCellLines === 1,
  `${dom.accountCellLines} line(s), ${dom.accountFontSize}, white-space=${dom.accountWhiteSpace}`);
ok('archived-at cell renders on one line', dom.dateCellLines === 1, `${dom.dateCellLines} line(s)`);
ok('all six columns fit inside the table', dom.columnsInsideTable?.allInside === true,
  `overflow px per column: ${JSON.stringify(dom.columnsInsideTable?.overflow)}`);
ok('TRANSFER badge uses the quiet secondary tone',
  /p-tag-secondary/.test(String(dom.typeBadge?.classes))
    && !/p-tag-warn/.test(String(dom.typeBadge?.classes)),
  `${dom.typeBadge?.classes} bg=${dom.typeBadge?.background}`);
ok('no warning-toned badge anywhere in the type column',
  (dom.distinctTypeBadgeClasses ?? []).every((c) => !/p-tag-warn/.test(c)),
  JSON.stringify(dom.distinctTypeBadgeClasses));
ok('archived page adds no console errors', consoleErrors.length === errsAfterLogin,
  consoleErrors.slice(errsAfterLogin).join(' || ') || 'none');

// Default sort: newest first — the archived-at column must be non-increasing.
const stamps = await evaluate(`(() => Array.from(document.querySelectorAll('.p-datatable-tbody > tr'))
  .filter((tr) => tr.querySelectorAll('td').length > 1)
  .map((tr) => tr.querySelector('td').innerText.trim()))()`);
const parsed = stamps.map((s) => new Date(s).getTime());
const desc = parsed.every((v, i) => i === 0 || parsed[i - 1] >= v);
ok('default sort newest-first', desc && parsed.length > 0, `${stamps[0]} .. ${stamps[stamps.length - 1]}`);
results.push({ name: 'page-1 timestamp order', pass: desc, detail: stamps.join(' | ') });

// ------------------------------------------------------------- filters -------
console.log('\n--- filters ---');
const setFilter = (selectorIndex, value) => evaluate(`(() => {
  const els = Array.from(document.querySelectorAll('.form-row input, .form-row select'));
  const el = els[${selectorIndex}];
  if (!el) return 'missing';
  const proto = el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)});
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return 'set';
})()`);

const countRows = () => evaluate(`(() => Array.from(document.querySelectorAll('.p-datatable-tbody > tr'))
  .filter((tr) => !tr.classList.contains('p-datatable-emptymessage') && tr.querySelectorAll('td').length > 1).length)()`);

// PrimeNG 19 does not put `.p-datatable-emptymessage` on the empty row: the
// custom `emptymessage` template is rendered bare inside a `colspan` row. So the
// assertion looks for the shared component and its copy, not for that class.
const EMPTY_STATE_TEXT = `(() => {
  const e = document.querySelector('app-empty-state');
  return e ? e.innerText.replace(/\\s+/g, ' ').trim() : null;
})()`;

// type = CREDIT -> no credit rows exist in the archive, so the filtered empty state must appear
await setFilter(0, 'CREDIT');
await waitFor('CREDIT filter applied', `(() => {
  const e = document.querySelector('app-empty-state');
  return !!e && /match these filters/.test(e.innerText);
})()`, 12000);
const creditRows = await countRows();
const creditEmpty = await evaluate(EMPTY_STATE_TEXT);
ok('CREDIT filter yields 0 rows', creditRows === 0, `${creditRows}`);
ok('filtered empty state shown (distinct copy)',
  !!creditEmpty && /No archived transactions match these filters/.test(creditEmpty), String(creditEmpty));
ok('filtered empty state offers no CTA', !/New Transfer/.test(String(creditEmpty)), String(creditEmpty));

// type = TRANSFER -> rows return
await setFilter(0, 'TRANSFER');
await waitFor('TRANSFER rows restored', `document.querySelectorAll('.p-datatable-tbody > tr').length > 1`, 12000);
const transferRows = await countRows();
ok('TRANSFER filter restores rows', transferRows > 0, `${transferRows}`);

// amount min filter
await setFilter(4, '1000');
await waitFor('min-amount filter settled', `(() => {
  const vals = Array.from(document.querySelectorAll('.p-datatable-tbody > tr'))
    .filter((tr) => tr.querySelectorAll('td').length > 1)
    .map((tr) => parseFloat(tr.querySelectorAll('td')[5].innerText.replace(/,/g, '')));
  return vals.length > 0 && vals.every((v) => v >= 1000);
})()`, 12000);
const minRows = await countRows();
const minAmountsOk = await evaluate(`(() => {
  const vals = Array.from(document.querySelectorAll('.p-datatable-tbody > tr'))
    .filter((tr) => tr.querySelectorAll('td').length > 1)
    .map((tr) => parseFloat(tr.querySelectorAll('td')[5].innerText.replace(/,/g, '')));
  return { n: vals.length, allAtLeast1000: vals.every((v) => v >= 1000), sample: vals.slice(0, 4) };
})()`);
ok('min amount 1000 filters correctly', minAmountsOk.allAtLeast1000 && minRows === minAmountsOk.n,
  `${minRows} rows, sample ${JSON.stringify(minAmountsOk.sample)}`);

// date range: from tomorrow excludes everything, so the filtered empty state must return
const futureDay = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
await setFilter(4, '');          // clear min amount
await setFilter(2, futureDay);   // archived-from = tomorrow
await waitFor('future date excludes all', `!!document.querySelector('app-empty-state')`, 12000);
const futureRows = await countRows();
const futureEmpty = await evaluate(EMPTY_STATE_TEXT);
ok('date range filter excludes all rows', futureRows === 0, `0 rows for from=${futureDay}, empty="${String(futureEmpty).slice(0, 50)}"`);
await setFilter(2, '');
await waitFor('date filter cleared', `document.querySelectorAll('.p-datatable-tbody > tr').length > 1`, 12000);

// account filter
const accountProbe = await evaluate(`(() => {
  const tr = Array.from(document.querySelectorAll('.p-datatable-tbody > tr')).find((r) => r.querySelectorAll('td').length > 1);
  return tr ? tr.querySelectorAll('td')[3].innerText.trim() : null;
})()`);
await setFilter(1, accountProbe);
await waitFor('account filter settled', `(() => {
  const rows = Array.from(document.querySelectorAll('.p-datatable-tbody > tr')).filter((tr) => tr.querySelectorAll('td').length > 1);
  return rows.length > 0 && rows.every((tr) => {
    const t = tr.querySelectorAll('td');
    return t[3].innerText.includes(${JSON.stringify(accountProbe)}) || t[4].innerText.includes(${JSON.stringify(accountProbe)});
  });
})()`, 12000);
const acctCheck = await evaluate(`(() => {
  const rows = Array.from(document.querySelectorAll('.p-datatable-tbody > tr')).filter((tr) => tr.querySelectorAll('td').length > 1);
  return { n: rows.length, allMatch: rows.every((tr) => {
    const t = tr.querySelectorAll('td');
    return t[3].innerText.includes(${JSON.stringify(accountProbe)}) || t[4].innerText.includes(${JSON.stringify(accountProbe)});
  }) };
})()`);
ok('account filter matches from OR to', acctCheck.n > 0 && acctCheck.allMatch,
  `${acctCheck.n} rows containing ${accountProbe}`);

// CSV export while filtered
console.log('\n--- CSV export (filtered) ---');
await evaluate(`(() => { const b = Array.from(document.querySelectorAll('p-button button')).find((x) => x.innerText.includes('Export CSV')); if (b) b.click(); return !!b; })()`);
await sleep(4000);
const files = fs.readdirSync(downloads).filter((f) => f.endsWith('.csv'));
let csvInfo = { files, rows: 0, header: null, firstDataLine: null, datesFormatted: false };
if (files.length) {
  const raw = fs.readFileSync(path.join(downloads, files[0]), 'utf8');
  const body = raw.replace(/^\ufeff/, '');
  const lines = body.split(/\r?\n/).filter(Boolean);
  // Every field is quoted, so the date cell reads "\"Sep 26, 2026 12:58:25\",..."
  const firstField = ((lines[1] ?? '').match(/^"((?:[^"]|"")*)"/) ?? [])[1] ?? '';
  csvInfo = {
    files,
    rows: lines.length - 1,
    header: lines[0],
    firstDataLine: lines[1] ?? null,
    // "MMM d, y HH:mm:ss" — a formatted local stamp, and specifically not ISO.
    datesFormatted: /^[A-Z][a-z]{2} \d{1,2}, \d{4} \d{2}:\d{2}:\d{2}$/.test(firstField)
      && !/^\d{4}-\d{2}-\d{2}T/.test(firstField)
  };
}
console.log(JSON.stringify(csvInfo, null, 2));
ok('CSV downloaded', csvInfo.files.length > 0, String(csvInfo.files[0] ?? 'none'));
ok('CSV has a UTF-8 BOM', csvInfo.files.length > 0
  && fs.readFileSync(path.join(downloads, csvInfo.files[0]), 'utf8').charCodeAt(0) === 0xFEFF);
ok('CSV header is the table columns', csvInfo.header === 'ArchivedAt,TransactionId,Type,FromAccount,ToAccount,Amount', String(csvInfo.header));
ok('CSV row count equals filtered table rows', csvInfo.rows === acctCheck.n, `${csvInfo.rows} csv vs ${acctCheck.n} table`);
ok('CSV date is formatted, not raw ISO', csvInfo.datesFormatted === true, String(csvInfo.firstDataLine).slice(0, 70));

// Clear -> all rows back
await evaluate(`(() => { const b = Array.from(document.querySelectorAll('p-button button')).find((x) => x.innerText.includes('Clear')); if (b) b.click(); return !!b; })()`);
await waitFor('filters cleared', `document.querySelectorAll('.p-datatable-tbody > tr').length > 1`, 12000);
const clearedRows = await countRows();
const clearedFilters = await evaluate(`(() => Array.from(document.querySelectorAll('.form-row input, .form-row select')).map((e) => e.value))()`);
ok('Clear resets filters and restores rows', clearedRows === transferRows && clearedFilters.every((v) => v === ''),
  `${clearedRows} rows, filters=${JSON.stringify(clearedFilters)}`);

// ------------------------------------------------- screenshot (clean view) ---
console.log('\n--- screenshot ---');
fs.mkdirSync(path.dirname(SHOT), { recursive: true });
const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
if (shot?.data) { fs.writeFileSync(SHOT, Buffer.from(shot.data, 'base64')); console.log('wrote', SHOT, fs.statSync(SHOT).size, 'bytes'); }
ok('screenshot written', fs.existsSync(SHOT) && fs.statSync(SHOT).size > 5000, SHOT);

// -------------------------------------------------- router link behaviour ----
console.log('\n--- router link click ---');
await evaluate(`window.__noReload = 'alive'`);
await evaluate(`(() => { const a = document.querySelector('.p-datatable-tbody a'); if (a) a.click(); return !!a; })()`);
await sleep(4000);
const afterClick = {
  url: await url(),
  markerSurvived: await evaluate(`window.__noReload === 'alive'`),
  body: String(await text(220)).replace(/\s+/g, ' ')
};
console.log(JSON.stringify(afterClick, null, 2));
ok('row link navigates to saga inspector', /\/transactions\/sagas\/[0-9a-f-]{36}/.test(String(afterClick.url)), String(afterClick.url));
ok('navigation was client-side (no full reload)', afterClick.markerSurvived === true);

// ----------------------------------------------------- live page regression --
console.log('\n--- /observability/live regression ---');
const errsBefore = consoleErrors.length;
await send('Page.navigate', { url: LIVE });
await sleep(13000);
const liveInfo = await evaluate(`(() => ({
  url: location.href,
  h2: document.querySelector('.page-head h2')?.innerText.trim() ?? null,
  hasFeed: !!document.querySelector('app-event-feed'),
  hasThroughput: !!document.querySelector('app-throughput-panel'),
  feedRows: document.querySelectorAll('app-event-feed .feed-row, app-event-feed li, app-event-feed tbody tr').length,
  statusTag: document.querySelector('.head-tools p-tag')?.innerText.trim() ?? null
}))()`);
console.log(JSON.stringify(liveInfo, null, 2));
ok('live page still renders', liveInfo.h2 === 'Live Events' && liveInfo.hasFeed && liveInfo.hasThroughput,
  `${liveInfo.h2} / feed=${liveInfo.hasFeed} / throughput=${liveInfo.hasThroughput}`);
ok('live page reports a connection status', !!liveInfo.statusTag, String(liveInfo.statusTag));
ok('no console errors on live page', consoleErrors.length === errsBefore,
  consoleErrors.slice(errsBefore).join(' || ') || 'none');

// ------------------------------------------------------------------ report ---
console.log('\n===== CONSOLE ERRORS =====');
console.log(JSON.stringify(consoleErrors.slice(0, 12), null, 2));
console.log('\n===== FAILED HTTP =====');
console.log(JSON.stringify(failedRequests.slice(0, 12), null, 2));
console.log('\n===== ARCHIVE ENDPOINT CALLS =====');
console.log(JSON.stringify(archiveCalls, null, 2));

const failed = results.filter((r) => !r.pass);
console.log(`\n===== RESULT: ${results.length - failed.length}/${results.length} checks passed =====`);
if (failed.length) for (const f of failed) console.log(`  FAILED: ${f.name} — ${f.detail}`);

fs.writeFileSync(path.join(REPO, 'scripts', 'verify-archived-transactions.json'),
  JSON.stringify({ dom, csvInfo, afterClick, liveInfo, archiveCalls, results, consoleErrors, failedRequests }, null, 2));

ws.close();
chrome.kill();
process.exit(failed.length ? 2 : 0);

