#!/usr/bin/env node
/**
 * Cross-checks that a brand-new transfer reaches /observability/archived.
 *
 * Run:      node scripts/crosscheck-archived-transactions.mjs
 * Needs:    Node 20+, Chrome (set CHROME_PATH to override the default), and the
 *           stack up: dev server :4200, gateway :8888, ledger :8085,
 *           billing :8083, Keycloak :8180. Drives Chrome over CDP on port 9363
 *           (CDP_PORT). Transfer amount/source overridable via TRANSFER_AMOUNT,
 *           SOURCE_ACCOUNT, DEST_ACCOUNT.
 * Writes:   docs/screenshots/observability/archived-transactions-new-row.png
 *           scripts/crosscheck-archived-transactions.json
 *
 * Proves the chain the page exists to demonstrate: the ledger commits, publishes
 * to 'ledger-events', Kafka delivers, billing consumes and archives, and the open
 * page lists the row at the top on refresh. It creates a real transfer (the same
 * POST the transfer wizard's Confirm button makes) and moves real balances.
 * Exit code 2 means a check failed.
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.CHROME_PATH
  ?? 'C:\\Users\\oussa\\.cache\\puppeteer\\chrome\\win64-131.0.6778.204\\chrome-win64\\chrome.exe';
const PORT = Number(process.env.CDP_PORT ?? 9363);
const ORIGIN = 'http://localhost:4200';
const APP = `${ORIGIN}/observability/archived`;
const REPO = path.resolve(import.meta.dirname, '..');
const SHOT = path.join(REPO, 'docs', 'screenshots', 'observability', 'archived-transactions-new-row.png');

// A distinctive amount, so the new row is unmistakable in the table and the CSV.
const AMOUNT = Number(process.env.TRANSFER_AMOUNT ?? 321.09);
// Accounts are resolved from the ledger rather than hard-coded: a fixed source
// account is a fixture that rots (the first attempt used one holding 2,872.79
// and the ledger correctly refused the transfer).
let SOURCE = process.env.SOURCE_ACCOUNT ?? '';
let DEST = process.env.DEST_ACCOUNT ?? '';

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'cdp-cross-'));
const chrome = spawn(CHROME, [`--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--headless=new',
  // Fixed desktop viewport: an unconstrained headless window made the table
  // overflow and the amount column look clipped, which the real app never does.
  '--window-size=1440,900',
  '--no-first-run', '--disable-gpu', 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const ok = (n, p, d = '') => { results.push({ name: n, pass: !!p, detail: d }); console.log(`  ${p ? 'PASS' : 'FAIL'}  ${n}${d ? `  — ${d}` : ''}`); };

async function http(p, m = 'GET') { return (await fetch(`http://127.0.0.1:${PORT}${p}`, { method: m })).json(); }
for (let i = 0; i < 80; i++) { try { await http('/json/version'); break; } catch { await sleep(250); } }
const t = await http(`/json/new?${encodeURIComponent('about:blank')}`, 'PUT');
const ws = new WebSocket(t.webSocketDebuggerUrl);
let id = 1; const pending = new Map(); let authorizeUrl = null;
const send = (m, p = {}) => new Promise((r) => { const i = id++; pending.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); return; }
  const p = m.params ?? {};
  if (m.method === 'Network.requestWillBeSent' && String(p.request?.url ?? '').includes('/protocol/openid-connect/auth')) authorizeUrl = p.request.url;
});
await new Promise((r) => ws.addEventListener('open', r));
await send('Network.enable'); await send('Page.enable'); await send('Runtime.enable');
const ev = async (e) => { const r = await send('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); return r?.exceptionDetails ? `<<err ${r.exceptionDetails.text}>>` : r?.result?.value; };

async function topRow() {
  return ev(`(() => {
    const tr = Array.from(document.querySelectorAll('.p-datatable-tbody > tr')).find((r) => r.querySelectorAll('td').length > 1);
    return tr ? Array.from(tr.querySelectorAll('td')).map((td) => td.innerText.replace(/\\s+/g, ' ').trim()) : null;
  })()`);
}
async function summaries() {
  return ev(`(() => Object.fromEntries(Array.from(document.querySelectorAll('.sum')).map((d) =>
    [(d.querySelector('label')?.innerText || '').trim(), (d.querySelector('span')?.innerText || '').trim()])))()`);
}
async function refreshPage() {
  await ev(`(() => { const b = Array.from(document.querySelectorAll('p-button button')).find((x) => x.innerText.includes('Refresh')); if (b) b.click(); return !!b; })()`);
}

// ---------------------------------------------------------------- sign in ----
await send('Page.navigate', { url: `${ORIGIN}/login` });
await sleep(9000);
await ev(`(() => { const b = document.querySelector('p-button button') || document.querySelector('button'); if (b) b.click(); return !!b; })()`);
for (let i = 0; i < 24 && !authorizeUrl; i++) await sleep(500);
await send('Page.navigate', { url: authorizeUrl });
await sleep(5000);
await ev(`(() => { const u = document.querySelector('#username'), p = document.querySelector('#password');
  u.value='manager'; p.value='manager123'; document.querySelector('form').submit(); return 1; })()`);
await sleep(9000);

console.log('\n--- baseline ---');
await send('Page.navigate', { url: APP });
for (let i = 0; i < 60; i++) {
  if (await ev(`!!document.querySelector('.p-datatable-thead th')`)) break;
  await sleep(500);
}
const before = await topRow();
const beforeSums = await summaries();
console.log('top row :', JSON.stringify(before));
console.log('summary :', JSON.stringify(beforeSums));
ok('baseline page loaded', !!before, JSON.stringify(before));

// ------------------------------------------------------- create a transfer ---
console.log('\n--- creating a transfer (same call the wizard makes) ---');
const token = (await (await fetch('http://localhost:8180/realms/ecom-bank/protocol/openid-connect/token', {
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: 'client_id=ecom-frontend&username=manager&password=manager123&grant_type=password'
})).json()).access_token;
ok('service token acquired', !!token, token ? `${token.length} chars` : 'none');

const accounts = await (await fetch('http://localhost:8888/ledger-service/api/accounts', {
  headers: { Authorization: `Bearer ${token}` }
})).json();
if (!SOURCE) {
  // Richest account, so the transfer cannot be refused for insufficient funds.
  const richest = accounts.slice().sort((a, b) => (b.balance ?? 0) - (a.balance ?? 0))[0];
  SOURCE = richest?.accountId;
  DEST = DEST || accounts.find((a) => a.accountId !== SOURCE)?.accountId;
}
const sourceBalance = accounts.find((a) => a.accountId === SOURCE)?.balance ?? 0;
console.log(`source ${SOURCE} (balance ${sourceBalance}) -> ${DEST}, amount ${AMOUNT}`);
ok('source account is funded for the transfer', sourceBalance >= AMOUNT,
  `balance ${sourceBalance} >= ${AMOUNT}`);

const txId = crypto.randomUUID();
const created = await fetch(`http://localhost:8888/ledger-service/api/transfers`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  body: JSON.stringify({ sourceAccountId: SOURCE, destinationAccountId: DEST, amount: AMOUNT, transactionId: txId })
});
const createdBody = await created.json().catch(() => ({}));
console.log('transfer response:', created.status, JSON.stringify(createdBody).slice(0, 200));
ok('transfer accepted by the ledger', created.ok, `HTTP ${created.status}, saga=${createdBody.status ?? '?'}`);
ok('saga reached COMPLETED', createdBody.status === 'COMPLETED', String(createdBody.status));

// ------------------------------------------------- wait for the archive -----
console.log('\n--- waiting for Kafka -> billing -> archive ---');
let archived = null;
const deadline = Date.now() + 60000;
while (Date.now() < deadline) {
  const res = await fetch('http://localhost:8888/billing-service/api/archived-transactions', {
    headers: { Authorization: `Bearer ${token}` }
  });
  const rows = await res.json();
  archived = rows.find((r) => r.transactionId === txId) ?? null;
  if (archived) { console.log(`  archived after ${Math.round((Date.now() - (deadline - 60000)) / 1000)}s`); break; }
  await sleep(1500);
}
ok('transfer archived by billing-service (Kafka consumed)', !!archived,
  archived ? `id=${archived.id} amount=${archived.amount}` : 'not seen within 60s');

// -------------------------------------- the page must show it, at the top ---
console.log('\n--- refreshing the open page ---');
await refreshPage();
await sleep(4000);
let after = await topRow();
// The Refresh button flips the skeleton in and out; wait for rows to be back.
for (let i = 0; i < 40 && !after; i++) { await sleep(500); after = await topRow(); }
const afterSums = await summaries();
console.log('top row :', JSON.stringify(after));
console.log('summary :', JSON.stringify(afterSums));

ok('new transaction is the newest row (top of a newest-first table)',
  Array.isArray(after) && after[1] === txId.slice(0, 12) + '…',
  `top id=${after ? after[1] : 'none'} expected=${txId.slice(0, 12)}…`);
ok('new row shows the transfer amount', Array.isArray(after) && after[5] === AMOUNT.toFixed(2),
  `${after ? after[5] : 'none'} vs ${AMOUNT.toFixed(2)}`);
ok('new row shows the right direction', Array.isArray(after) && after[3] === SOURCE && after[4] === DEST,
  after ? `${after[3]} -> ${after[4]}` : 'none');
ok('archived count grew by one', Number(afterSums.ARCHIVED) === Number(beforeSums.ARCHIVED) + 1,
  `${beforeSums.ARCHIVED} -> ${afterSums.ARCHIVED}`);
ok('total volume grew by the transfer amount',
  Math.abs(Number(String(afterSums['TOTAL VOLUME']).replace(/,/g, ''))
    - (Number(String(beforeSums['TOTAL VOLUME']).replace(/,/g, '')) + AMOUNT)) < 0.01,
  `${beforeSums['TOTAL VOLUME']} -> ${afterSums['TOTAL VOLUME']}`);

fs.mkdirSync(path.dirname(SHOT), { recursive: true });
const shot = await send('Page.captureScreenshot', { format: 'png' });
if (shot?.data) { fs.writeFileSync(SHOT, Buffer.from(shot.data, 'base64')); console.log('\nwrote', SHOT); }
ok('cross-check screenshot written', fs.existsSync(SHOT));

const failed = results.filter((r) => !r.pass);
console.log(`\n===== CROSS-CHECK: ${results.length - failed.length}/${results.length} checks passed =====`);
for (const f of failed) console.log(`  FAILED: ${f.name} — ${f.detail}`);
fs.writeFileSync(path.join(REPO, 'scripts', 'crosscheck-archived-transactions.json'),
  JSON.stringify({ txId, amount: AMOUNT, source: SOURCE, dest: DEST, before, after, beforeSums, afterSums, archived, results }, null, 2));
ws.close(); chrome.kill();
process.exit(failed.length ? 2 : 0);

