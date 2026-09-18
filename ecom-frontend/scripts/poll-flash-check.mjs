/**
 * Live check: does the dashboard flash its loading overlay while polling?
 *
 * Signs in, waits for the Command Center to render, then samples the DOM every
 * 200ms for 35 seconds — long enough for 7 poll cycles at 5s. Records whether
 * the global overlay ever appears, whether skeletons come back, and whether the
 * KPI numbers are being re-animated. Also reads the Resource Timing entries to
 * confirm the polls are actually firing and how long they take.
 *
 * Attaches to a Chrome already listening for debugging:
 *   chrome --headless=new --remote-debugging-port=9555 --user-data-dir=<tmp> about:blank
 *   node scripts/poll-flash-check.mjs [port] [seconds]
 */
const PORT = Number(process.argv[2] ?? 9555);
const SECONDS = Number(process.argv[3] ?? 35);
const APP_URL = 'http://localhost:4200/dashboard';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class Page {
  #socket; #id = 1; #pending = new Map();
  constructor(socket) {
    this.#socket = socket;
    socket.addEventListener('message', (e) => {
      const m = JSON.parse(e.data);
      const p = this.#pending.get(m.id);
      if (!p) return;
      this.#pending.delete(m.id);
      m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result);
    });
  }
  static async attach(url) {
    const socket = new WebSocket(url);
    await new Promise((res, rej) => {
      socket.addEventListener('open', res, { once: true });
      socket.addEventListener('error', () => rej(new Error('ws refused')), { once: true });
    });
    return new Page(socket);
  }
  send(method, params = {}) {
    const id = this.#id++;
    this.#socket.send(JSON.stringify({ id, method, params }));
    return new Promise((res, rej) => {
      this.#pending.set(id, { resolve: res, reject: rej });
      setTimeout(() => this.#pending.delete(id) && rej(new Error(method + ' timeout')), 25000);
    });
  }
  async eval(expression) {
    const { result, exceptionDetails } = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true
    });
    if (exceptionDetails) throw new Error(exceptionDetails.text);
    return result.value;
  }
  async waitFor(expression, label, timeoutMs = 60000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      try { if (await this.eval(expression)) return true; } catch { /* navigating */ }
      await sleep(400);
    }
    throw new Error('timed out waiting for: ' + label);
  }
}

const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json();
const page = await Page.attach(target.webSocketDebuggerUrl);
await page.send('Page.enable');
await page.send('Runtime.enable');
await page.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });

// Sign in.
await page.send('Page.navigate', { url: APP_URL });
await page.waitFor(`!!document.querySelector('app-root')`, 'app bootstrap');
await sleep(2500);
if (await page.eval(`!!document.querySelector('.hero-cta, p-button button')`)) {
  await page.eval(`(() => { const c = document.querySelector('.hero-cta') ?? document.querySelector('p-button button'); c.click(); return true; })()`);
}
await sleep(2000);
if (await page.eval(`!!document.querySelector('#username')`)) {
  await page.eval(`(() => {
    document.querySelector('#username').value = 'manager';
    document.querySelector('#password').value = 'manager123';
    document.querySelector('#kc-login').click();
    return true;
  })()`);
}
await page.waitFor(`location.pathname === '/dashboard'`, 'dashboard route');
await page.waitFor(`document.querySelectorAll('.kpi').length >= 4`, 'KPI cards');

// Let the first load settle so we measure polls, not mount.
await sleep(7000);

const SAMPLER = `
  (() => {
    const overlay = document.querySelector('.loading-overlay');
    const overlayVisible = !!overlay && overlay.getClientRects().length > 0;
    return {
      overlayVisible,
      skeletons: document.querySelectorAll('.skeleton').length,
      firstKpi: (document.querySelector('.kpi .kpi-value')?.textContent ?? '').trim()
    };
  })()
`;

const samples = [];
const started = Date.now();
while (Date.now() - started < SECONDS * 1000) {
  samples.push(await page.eval(SAMPLER));
  await sleep(200);
}

const resources = await page.eval(`
  (() => {
    const wanted = ['/accounts','/journal/entries','/journal/trial-balance','/sagas','/dashboard/kpi-trends'];
    return performance.getEntriesByType('resource')
      .filter(e => wanted.some(w => e.name.includes(w)))
      .map(e => ({ name: e.name.split('/api/')[1] ?? e.name, start: Math.round(e.startTime), ms: Math.round(e.duration) }));
  })()
`);

const overlaySamples = samples.filter(s => s.overlayVisible).length;
const skeletonSamples = samples.filter(s => s.skeletons > 0).length;
const values = [...new Set(samples.map(s => s.firstKpi))];

console.log(JSON.stringify({
  samples: samples.length,
  seconds: SECONDS,
  overlayVisibleSamples: overlaySamples,
  skeletonVisibleSamples: skeletonSamples,
  distinctFirstKpiValues: values,
  kpiValueChangeCount: samples.reduce((n, s, i) => i && s.firstKpi !== samples[i - 1].firstKpi ? n + 1 : n, 0),
  requests: resources.length,
  byEndpoint: resources.reduce((acc, r) => {
    const key = r.name.split('?')[0];
    acc[key] = acc[key] ?? { count: 0, maxMs: 0, minMs: 1e9 };
    acc[key].count++;
    acc[key].maxMs = Math.max(acc[key].maxMs, r.ms);
    acc[key].minMs = Math.min(acc[key].minMs, r.ms);
    return acc;
  }, {}),
  requestGapsSeconds: (() => {
    const t = resources.map(r => r.start).sort((a, b) => a - b);
    const gaps = [];
    for (let i = 1; i < t.length; i++) if (t[i] - t[i - 1] > 500) gaps.push(Math.round((t[i] - t[i - 1]) / 100) / 10);
    return gaps.slice(-8);
  })()
}, null, 2));
