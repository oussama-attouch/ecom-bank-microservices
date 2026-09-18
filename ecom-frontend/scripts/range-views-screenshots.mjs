/**
 * Verification harness: captures the Command Center at three period-selector
 * settings, so "the KPI cards follow the range" can be checked as rendered
 * rather than merely as compiled.
 *
 * It clicks the selector the way an operator does — not by reloading with a
 * stored range — because the interesting path is the one the click starts:
 * `onRangeChange` drops both range-dependent payloads and re-fetches them for
 * the new window.
 *
 * Not part of the app build, like its sibling kpi-trends-screenshots.mjs, and it
 * speaks the DevTools Protocol over Node's built-in WebSocket (Node 22+):
 *
 *   chrome --headless=new --remote-debugging-port=9444 --user-data-dir=<tmp> about:blank
 *   node scripts/range-views-screenshots.mjs <outputDir> [port]
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const OUTPUT_DIR = process.argv[2] ?? join(process.cwd(), 'range-views-shots');
const PORT = Number(process.argv[3] ?? 9444);
const APP_URL = 'http://localhost:4200/dashboard';

const KEYCLOAK_USER = 'manager';
const KEYCLOAK_PASSWORD = 'manager123';

/** The three views the brief asks for. */
const VIEWS = [
  { button: '7D', token: '7d', over: 'Over 7 days', previous: 'From previous 7d' },
  { button: '30D', token: '30d', over: 'Over 30 days', previous: 'From previous 30d' },
  { button: '1Y', token: '1y', over: 'Over 1 year', previous: 'From previous 1y' }
];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Minimal DevTools Protocol client bound to one page target. */
class Page {
  #socket;
  #nextId = 1;
  #pending = new Map();
  /** Everything the page logged as an error or threw, so "no console errors" is asserted, not assumed. */
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
      }, 20_000);
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

  async waitFor(expression, { timeoutMs = 60_000, label = expression } = {}) {
    const deadline = Date.now() + timeoutMs;
    let lastError = null;
    while (Date.now() < deadline) {
      try {
        if (await this.eval(expression)) return true;
      } catch (error) {
        lastError = error; // navigation in flight: the context is briefly gone
      }
      await sleep(400);
    }
    throw new Error(`timed out waiting for: ${label}${lastError ? ` (${lastError.message})` : ''}`);
  }

  async screenshot(path, clip) {
    const params = { format: 'png' };
    if (clip) params.clip = clip;
    const { data } = await this.send('Page.captureScreenshot', params);
    writeFileSync(path, Buffer.from(data, 'base64'));
  }

  close() {
    this.#socket.close();
  }
}

/** What each card rendered, including the two new range-facing fields. */
const READ_CARDS = `
  Array.from(document.querySelectorAll('.kpi')).map((card) => {
    const cardRect = card.getBoundingClientRect();
    const valueRect = card.querySelector('.kpi-value')?.getBoundingClientRect();
    const svg = card.querySelector('app-kpi-sparkline svg');
    const sparkRect = svg?.getBoundingClientRect();
    const box = svg ?? card.querySelector('.spark-empty');
    const emptyRect = box?.getBoundingClientRect();
    const spark = sparkRect ?? emptyRect;
    return {
      label: card.querySelector('.kpi-label')?.textContent?.trim(),
      subtitle: card.querySelector('.kpi-sub')?.textContent?.trim() ?? null,
      value: card.querySelector('.kpi-value')?.textContent?.trim(),
      trend: card.querySelector('.kpi-trend')?.textContent?.replace(/\\s+/g, ' ').trim(),
      points: (() => {
        const line = card.querySelector('app-kpi-sparkline .spark-line');
        return line ? line.getAttribute('d').split('C').length : 0;
      })(),
      // Geometry, so "the chart is not clipped and the number does not run into
      // it" is measured rather than eyeballed: both must sit inside the card,
      // and the number must end before the chart begins.
      clipped: spark ? Math.round(spark.right) > Math.round(cardRect.right) - 4 : false,
      overlap: spark && valueRect ? Math.round(valueRect.right) > Math.round(spark.left) : false,
      cardRight: Math.round(cardRect.right),
      valueRight: valueRect ? Math.round(valueRect.right) : null,
      sparkLeft: spark ? Math.round(spark.left) : null,
      sparkRight: spark ? Math.round(spark.right) : null,
      valueFont: card.querySelector('.kpi-value') ? getComputedStyle(card.querySelector('.kpi-value')).fontSize : null
    };
  })
`;

/** Stamps the cards so a later read can tell whether the poll replaced them. */
const STAMP_CARDS = `
  (() => {
    const cards = Array.from(document.querySelectorAll('.kpi'));
    cards.forEach((card, i) => { card.dataset.probe = String(i); });
    return cards.map((card) => card.dataset.probe).join(',');
  })()
`;

const READ_AFTER_POLL = `
  (() => {
    const cards = Array.from(document.querySelectorAll('.kpi'));
    return {
      count: cards.length,
      probes: cards.map((card) => card.dataset.probe ?? 'RECREATED').join(','),
      runningAnimations: cards.reduce(
        (n, card) => n + card.getAnimations().filter((a) => a.playState === 'running').length, 0)
    };
  })()
`;

async function fetchJson(path, init) {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, init);
  return response.json();
}

/** Every card's rendered value in one string, so two reads can be compared. */
const READ_VALUES = `
  Array.from(document.querySelectorAll('.kpi-value')).map((e) => e.textContent.trim()).join('|')
`;

/**
 * Waits until the rendered numbers stop moving.
 *
 * The card labels follow the selector the instant it is clicked, before the
 * payload has been fetched, so waiting on a label does not mean the round trip
 * has landed — and the count-up directive animates a re-created card's value
 * over ~800ms after it does. Reading in between captures a number the dashboard
 * never held, so the capture waits for two identical reads instead.
 */
async function waitForSettledValues(page, { timeoutMs = 30_000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let previous = await page.eval(READ_VALUES);
  while (Date.now() < deadline) {
    await sleep(1200);
    const current = await page.eval(READ_VALUES);
    if (current === previous && !current.includes('No data')) return current;
    previous = current;
  }
  return previous;
}

/** Signs in the way the sibling harness does: Keycloak, or the app's own CTA first. */
async function signIn(page) {
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
}

/** Clicks a period button, as an operator would. */
const clickRange = (button) => `
  (() => {
    const buttons = Array.from(document.querySelectorAll('.range-picker .p-togglebutton'));
    const target = buttons.find((b) => b.textContent.trim() === ${JSON.stringify(button)});
    if (!target) return 'missing';
    target.querySelector('button')?.click();
    target.click();
    return 'clicked';
  })()
`;

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

  // A deterministic starting point: the first view is 7D whatever the browser had.
  await page.eval(`localStorage.setItem('app-dark', 'false'); localStorage.setItem('dashboard.range', '7D'); true`)
    .catch(() => {});

  await page.send('Page.navigate', { url: APP_URL });
  await signIn(page);
  await page.waitFor(`document.querySelectorAll('app-kpi-sparkline svg').length >= 8`, {
    label: 'rendered sparklines',
    timeoutMs: 60_000
  });
  await sleep(6500); // one poll plus the count-up, so the numbers are settled

  const views = [];
  for (const view of VIEWS) {
    const clicked = await page.eval(clickRange(view.button));
    if (clicked !== 'clicked') throw new Error(`no ${view.button} button in the period selector`);

    // The label is written from the same range the fetch is made for, so waiting
    // on it is waiting on the round trip that the click started.
    await page.waitFor(
      `Array.from(document.querySelectorAll('.kpi-label')).some((l) => l.textContent.trim() === 'Volume (${view.token})')`,
      { label: `the ${view.token} payload to land`, timeoutMs: 60_000 }
    );
    await page.waitFor(`document.querySelectorAll('.kpi-sub').length >= 10`, { label: 'card subtitles' });
    await waitForSettledValues(page);

    const cards = await page.eval(READ_CARDS);
    const volume = cards.find((card) => card.label === `Volume (${view.token})`);
    await page.screenshot(join(OUTPUT_DIR, `dashboard-${view.token}.png`));
    const clip = await page.eval(`
      (() => {
        const r = document.querySelector('.kpi-grid').getBoundingClientRect();
        return { x: Math.floor(r.x), y: Math.floor(r.y), width: Math.ceil(r.width), height: Math.ceil(r.height), scale: 2 };
      })()
    `);
    await page.screenshot(join(OUTPUT_DIR, `kpis-${view.token}.png`), clip);

    views.push({
      ...view,
      cards: cards.length,
      volumeValue: volume?.value ?? null,
      volumeTrend: volume?.trend ?? null,
      // The sparkline's own point count, read out of the rendered path: 7 points
      // is six smoothing segments, thirty is twenty-nine, twelve is eleven.
      volumePoints: volume?.points ?? 0,
      subtitles: cards.filter((card) => card.subtitle).length,
      // A card whose chart runs off its own edge, or whose number runs into it.
      clipped: cards.filter((card) => card.clipped).map((card) => card.label),
      overlapping: cards.filter((card) => card.overlap).map((card) => card.label),
      labels: cards.map((card) => card.label)
    });
    console.log(`VIEW ${view.button}: cards=${cards.length} volume=${volume?.value} sparkPoints=${volume?.points}`);
    console.log(`     trend="${volume?.trend}"`);
    console.log(`     clipped=${JSON.stringify(views.at(-1).clipped)} overlapping=${JSON.stringify(views.at(-1).overlapping)}`);
    for (const card of cards.filter((c) => c.clipped || c.overlap)) {
      console.log(`     GEOM ${card.label}: font=${card.valueFont} valueRight=${card.valueRight} ` +
        `sparkLeft=${card.sparkLeft} sparkRight=${card.sparkRight} cardRight=${card.cardRight}`);
    }
  }

  // The 5s poll must not be rebuilding the cards: a stamp that survives is a node
  // that survived, which is what "no infinite loop" looks like from the outside.
  const stamps = await page.eval(STAMP_CARDS);
  await sleep(6500);
  const afterPoll = await page.eval(READ_AFTER_POLL);
  const pollStable = afterPoll.probes === stamps && afterPoll.runningAnimations === 0;

  const report = {
    browser: version.Browser,
    pollStable,
    afterPoll,
    consoleErrors: page.consoleErrors,
    views
  };
  writeFileSync(join(OUTPUT_DIR, 'report.json'), JSON.stringify(report, null, 2));

  console.log(`browser=${version.Browser} pollStable=${pollStable} consoleErrors=${page.consoleErrors.length}`);
  for (const error of page.consoleErrors) console.log(`  ERROR ${error}`);
  console.log('done');
  process.exit(0);
}

main().catch((error) => {
  console.error('FAILED:', error.message);
  process.exit(1);
});
