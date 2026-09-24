/**
 * Verification harness for the time-travel and projection-consistency features.
 * Captures the two screenshots those features are documented with, reading the
 * page back as it goes so a capture cannot be committed for a state that never
 * rendered.
 *
 * Not part of the app build — `ng build` never sees this file, and it needs no
 * test dependencies: it speaks the DevTools Protocol over Node's built-in
 * WebSocket (Node 22+), exactly as `kpi-trends-screenshots.mjs` does.
 *
 *   chrome --headless=new --remote-debugging-port=9444 --user-data-dir=<tmp> about:blank
 *   node scripts/feature-screenshots.mjs <outputDir> [port] [mode]
 *
 * mode is `scrubber`, `rebuild` or `both` (default `both`). The two are
 * separable because `rebuild` needs a ledger-service started with
 * `--ledger.projection-rebuild.enabled=true`, and the scrubber does not.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const OUTPUT_DIR = process.argv[2] ?? join(process.cwd(), 'feature-shots');
const PORT = Number(process.argv[3] ?? 9444);
const MODE = (process.argv[4] ?? 'both').toLowerCase();
const APP_URL = 'http://localhost:4200/dashboard';

const KEYCLOAK_USER = 'manager';
const KEYCLOAK_PASSWORD = 'manager123';

/**
 * The instant to scrub to.
 *
 * Chosen because the seeded portfolio ledger has real history around it, so the
 * snapshot shows a populated dashboard rather than a ledger that had barely
 * started. The harness reads the handle's label back after the drag and fails
 * the run if the resulting instant is not in the past — the screenshot is
 * supposed to prove snapshot mode, and a capture that silently stayed live
 * would prove the opposite.
 */
const TARGET_INSTANT = Date.parse('2025-05-18T10:27:00Z');

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Minimal DevTools Protocol client bound to one page target. */
class Page {
  #socket;
  #nextId = 1;
  #pending = new Map();
  consoleErrors = [];

  constructor(socket) {
    this.#socket = socket;
    socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.method) {
        this.#recordEvent(message);
        return;
      }
      const resolver = this.#pending.get(message.id);
      if (!resolver) return;
      this.#pending.delete(message.id);
      message.error ? resolver.reject(new Error(JSON.stringify(message.error))) : resolver.resolve(message.result);
    });
  }

  #recordEvent(message) {
    const describe = (arg) => arg?.value ?? arg?.description ?? arg?.type ?? '';
    if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
      this.consoleErrors.push('console.error: ' + message.params.args.map(describe).join(' ').slice(0, 300));
    } else if (message.method === 'Runtime.exceptionThrown') {
      const details = message.params.exceptionDetails;
      this.consoleErrors.push('uncaught: ' + (details.exception?.description ?? details.text).slice(0, 300));
    } else if (message.method === 'Log.entryAdded' && message.params.entry.level === 'error') {
      const entry = message.params.entry;
      if (!/favicon/.test(entry.url ?? '')) {
        this.consoleErrors.push('log: ' + (entry.text ?? '').slice(0, 300) + ' ' + (entry.url ?? ''));
      }
    }
  }

  static async attach(wsUrl) {
    const socket = new WebSocket(wsUrl);
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true });
      socket.addEventListener('error', () => reject(new Error('DevTools WebSocket refused')), { once: true });
    });
    return new Page(socket);
  }

  send(method, params = {}) {
    const id = this.#nextId++;
    this.#socket.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject });
      setTimeout(() => {
        if (this.#pending.delete(id)) reject(new Error(`${method} timed out`));
      }, 30_000);
    });
  }

  async eval(expression) {
    const { result, exceptionDetails } = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true
    });
    if (exceptionDetails) {
      throw new Error(`${exceptionDetails.text} ${exceptionDetails.exception?.description ?? ''}`);
    }
    return result.value;
  }

  async waitFor(expression, { timeoutMs = 45_000, label = expression } = {}) {
    const deadline = Date.now() + timeoutMs;
    let lastError = null;
    while (Date.now() < deadline) {
      try {
        if (await this.eval(expression)) return true;
      } catch (error) {
        lastError = error;
      }
      await sleep(400);
    }
    throw new Error(`timed out waiting for: ${label}${lastError ? ` (${lastError.message})` : ''}`);
  }

  async screenshot(path) {
    const { data } = await this.send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(path, Buffer.from(data, 'base64'));
  }

  close() {
    this.#socket.close();
  }
}

async function fetchJson(path, init) {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, init);
  return response.json();
}

/** The KPI cards' labels and rendered values, so the capture can be described. */
const READ_KPIS = `
  Array.from(document.querySelectorAll('.kpi')).map((card) => ({
    label: card.querySelector('.kpi-label')?.textContent?.trim(),
    value: card.querySelector('.kpi-value')?.textContent?.trim(),
    trend: card.querySelector('.kpi-trend')?.textContent?.replace(/\\s+/g, ' ').trim(),
    dot: card.querySelector('.kpi-dot')?.className.replace('kpi-dot', '').replace('ng-star-inserted', '').trim() ?? null
  }))
`;

/** Signs in, tolerating both a live Keycloak session and a credentials form. */
async function signIn(page) {
  await page.send('Page.navigate', { url: APP_URL });
  await page.waitFor(`!!document.querySelector('app-root')`, { label: 'app bootstrapped' });

  const SIGN_IN_STATE = `(() => {
    if (document.querySelectorAll('.kpi').length) return 'dashboard';
    if (document.querySelector('#username')) return 'credentials';
    if (document.querySelector('.hero-cta, p-button button')) return 'login';
    return 'idle';
  })()`;

  let onDashboard = false;
  for (let attempt = 0; attempt < 4 && !onDashboard; attempt++) {
    await page.waitFor(`${SIGN_IN_STATE} !== 'idle'`, { label: 'a sign-in page or the dashboard' });
    const state = await page.eval(SIGN_IN_STATE);

    if (state === 'credentials') {
      await page.eval(`
        (() => {
          document.querySelector('#username').value = ${JSON.stringify(KEYCLOAK_USER)};
          document.querySelector('#password').value = ${JSON.stringify(KEYCLOAK_PASSWORD)};
          document.querySelector('#kc-login').click();
          return true;
        })()
      `);
    } else if (state === 'login') {
      await page.eval(`
        (() => {
          const cta = document.querySelector('.hero-cta') ?? document.querySelector('p-button button');
          cta.click();
          return true;
        })()
      `);
    }

    onDashboard = await page
      .waitFor(`document.querySelectorAll('.kpi').length > 0`, { label: 'KPI cards', timeoutMs: 45_000 })
      .then(() => true)
      .catch(() => false);
  }
  if (!onDashboard) throw new Error('could not reach the Command Center');

  await page.waitFor(`document.querySelectorAll('app-kpi-sparkline svg').length >= 1`, {
    label: 'rendered sparklines',
    timeoutMs: 60_000
  });
  await sleep(6500);
}

/**
 * Drags the scrubber to {@link TARGET_INSTANT}.
 *
 * The control is a native range input whose value is a step index in minutes
 * from the ledger's earliest event, so the target has to be converted to a step.
 * The left end is not exposed to the page, but it is recoverable: the input's
 * `max` is the whole range in minutes and the right end is the dashboard's "now",
 * captured when the component was built — seconds ago, against a range of two
 * years. That is close enough to land on the intended day, and the label is read
 * back afterwards so the run reports where it actually landed rather than where
 * it aimed.
 */
async function scrubTo(page, targetMs) {
  const geometry = await page.eval(`
    (() => {
      const input = document.querySelector('#ledger-scrubber');
      if (!input) return null;
      return { max: Number(input.max), min: Number(input.min), disabled: input.disabled,
               value: Number(input.value) };
    })()
  `);
  if (!geometry) throw new Error('the scrubber input was not found');
  if (geometry.disabled) throw new Error('the scrubber is disabled: the ledger history never loaded');

  const rangeMinutes = geometry.max - geometry.min;
  const earliestMs = Date.now() - rangeMinutes * 60_000;
  const rawStep = Math.round((targetMs - earliestMs) / 60_000);
  const step = Math.min(Math.max(rawStep, geometry.min), geometry.max);

  await page.eval(`
    (() => {
      const input = document.querySelector('#ledger-scrubber');
      // The native setter, then an input event: Angular's value accessor listens
      // for the event, and assigning through the prototype setter is what makes
      // the assignment visible to it.
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(input, ${JSON.stringify(String(step))});
      input.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()
  `);

  // The drag is debounced by 300ms and then fans out to six reads.
  await page.waitFor(`!!document.querySelector('.snapshot-banner')`, {
    label: 'the snapshot banner',
    timeoutMs: 30_000
  });
  // Let the six snapshot reads land and the count-up animations settle.
  await sleep(7000);

  return {
    rangeMinutes,
    earliestMs,
    requestedStep: step,
    max: geometry.max,
    label: await page.eval(`document.querySelector('.scrubber-value')?.textContent?.replace(/\\s+/g,' ').trim()`),
    banner: await page.eval(`document.querySelector('.snapshot-banner')?.textContent?.replace(/\\s+/g,' ').trim()`)
  };
}

/** Clicks Rebuild Projections and waits for the result toast. */
async function clickRebuild(page) {
  const found = await page.eval(`
    (() => {
      const buttons = Array.from(document.querySelectorAll('.head-tags p-button button'));
      const button = buttons.find((b) => /Rebuild Projections/i.test(b.textContent ?? ''));
      if (!button) return false;
      button.click();
      return true;
    })()
  `);
  if (!found) throw new Error('the Rebuild Projections button was not found in the header');

  // The POST folds the whole log before it answers, so this waits on the toast
  // rather than on a fixed delay.
  await page.waitFor(`!!document.querySelector('.p-toast-message')`, {
    label: 'the rebuild result toast',
    timeoutMs: 90_000
  });
  await sleep(600); // let the toast's entrance animation finish before the capture

  const toast = await page.eval(`
    (() => {
      const el = document.querySelector('.p-toast-message');
      if (!el) return null;
      const cls = el.className;
      return {
        text: el.textContent?.replace(/\\s+/g, ' ').trim(),
        severity: /success/.test(cls) ? 'success' : /error/.test(cls) ? 'error' : /warn/.test(cls) ? 'warn' : 'info'
      };
    })()
  `);
  return toast;
}

async function main() {
  mkdirSync(OUTPUT_DIR, { recursive: true });

  const version = await fetchJson('/json/version');
  const target = await fetchJson('/json/new?about:blank', { method: 'PUT' });
  const page = await Page.attach(target.webSocketDebuggerUrl);
  await page.send('Page.enable');
  await page.send('Runtime.enable');
  await page.send('Log.enable');
  await page.send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 1000,
    deviceScaleFactor: 2,
    mobile: false
  });

  await page.eval(`localStorage.setItem('app-dark', 'false'); true`).catch(() => {});

  const report = { browser: version.Browser, mode: MODE, outputDir: OUTPUT_DIR, consoleErrors: [] };

  await signIn(page);
  report.liveKpis = await page.eval(READ_KPIS);
  report.rebuildButtonPresent = await page.eval(
    `Array.from(document.querySelectorAll('.head-tags p-button button'))
       .some((b) => /Rebuild Projections/i.test(b.textContent ?? ''))`
  );

  if (MODE === 'scrubber' || MODE === 'both') {
    console.log('--- scrubbing to a past instant ---');
    report.scrub = await scrubTo(page, TARGET_INSTANT);
    report.snapshotKpis = await page.eval(READ_KPIS);
    await page.screenshot(join(OUTPUT_DIR, 'time-travel-scrubber.png'));
    console.log(`scrub label="${report.scrub.label}" banner="${report.scrub.banner}"`);
  }

  if (MODE === 'rebuild' || MODE === 'both') {
    console.log('--- clicking Rebuild Projections ---');
    try {
      report.rebuildToast = await clickRebuild(page);
      await page.screenshot(join(OUTPUT_DIR, 'projection-rebuild-success.png'));
      console.log(`toast severity=${report.rebuildToast?.severity} text="${report.rebuildToast?.text}"`);
    } catch (error) {
      report.rebuildError = error.message;
      console.error(`rebuild capture failed: ${error.message}`);
    }
  }

  report.consoleErrors = page.consoleErrors;
  // Named per mode rather than a bare `report.json`: the dashboard screenshot
  // directory already holds the kpi-trends harness's `report.json`, and writing
  // a second artefact to that path would silently replace committed evidence
  // rather than add to it.
  writeFileSync(join(OUTPUT_DIR, `feature-report-${MODE}.json`), JSON.stringify(report, null, 2));
  page.close();

  // Assertions, so a run cannot exit 0 having captured the wrong state.
  const problems = [];
  if ((MODE === 'scrubber' || MODE === 'both') && !report.scrub?.banner) {
    problems.push('no snapshot banner: the capture is not of snapshot mode');
  }
  if (MODE === 'rebuild' || MODE === 'both') {
    if (!report.rebuildToast) problems.push(`no rebuild toast: ${report.rebuildError ?? 'unknown'}`);
    else if (report.rebuildToast.severity !== 'success') {
      problems.push(`rebuild toast was "${report.rebuildToast.severity}", not success`);
    }
  }

  console.log(`consoleErrors=${page.consoleErrors.length}`);
  for (const error of page.consoleErrors) console.log(`  ERROR ${error}`);
  for (const problem of problems) console.log(`  PROBLEM ${problem}`);
  console.log(problems.length ? 'FAILED' : 'done');
  process.exit(problems.length ? 1 : 0);
}

main().catch((error) => {
  console.error('FAILED:', error.message);
  process.exit(1);
});
