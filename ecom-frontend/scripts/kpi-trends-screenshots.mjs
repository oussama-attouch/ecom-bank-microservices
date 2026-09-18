/**
 * Verification harness: signs in through Keycloak in a real headless Chrome and
 * captures the Command Center, so the KPI trend indicators and sparklines can be
 * checked as rendered rather than merely as compiled.
 *
 * Not part of the app build — `ng build` never sees this file, and it needs no
 * test dependencies: it speaks the DevTools Protocol over Node's built-in
 * WebSocket (Node 22+).
 *
 * It attaches to a Chrome already listening for debugging rather than spawning
 * one, so the browser can be started and inspected independently:
 *
 *   chrome --headless=new --remote-debugging-port=9444 --user-data-dir=<tmp> about:blank
 *   node scripts/kpi-trends-screenshots.mjs <outputDir> [port]
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const OUTPUT_DIR = process.argv[2] ?? join(process.cwd(), 'kpi-trends-shots');
const PORT = Number(process.argv[3] ?? 9444);
const APP_URL = 'http://localhost:4200/dashboard';

const KEYCLOAK_USER = 'manager';
const KEYCLOAK_PASSWORD = 'manager123';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Minimal DevTools Protocol client bound to one page target. */
class Page {
  #socket;
  #nextId = 1;
  #pending = new Map();
  /**
   * Everything the page logged as an error or threw, so "no console errors" can
   * be asserted from a capture run rather than taken on trust. Filled from the
   * CDP event stream: responses carry an `id`, events do not.
   */
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

  /** Keeps the errors worth failing a run over; everything else is ignored. */
  #recordEvent(message) {
    const describe = (arg) => arg?.value ?? arg?.description ?? arg?.type ?? '';
    if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
      this.consoleErrors.push('console.error: ' + message.params.args.map(describe).join(' ').slice(0, 300));
    } else if (message.method === 'Runtime.exceptionThrown') {
      const details = message.params.exceptionDetails;
      this.consoleErrors.push('uncaught: ' + (details.exception?.description ?? details.text).slice(0, 300));
    } else if (message.method === 'Log.entryAdded' && message.params.entry.level === 'error') {
      const entry = message.params.entry;
      // A missing favicon is not a dashboard fault; the app never references one.
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

  /** Evaluates an expression in the page and returns its value. */
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

  /** Polls a page-side predicate until it is true. */
  async waitFor(expression, { timeoutMs = 45_000, label = expression } = {}) {
    const deadline = Date.now() + timeoutMs;
    let lastError = null;
    while (Date.now() < deadline) {
      try {
        if (await this.eval(expression)) return true;
      } catch (error) {
        lastError = error; // navigation in flight: the execution context is briefly gone
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

async function fetchJson(path, init) {
  const response = await fetch(`http://127.0.0.1:${PORT}${path}`, init);
  return response.json();
}

/**
 * Forces a theme by the same switch the app itself uses, and waits until the
 * computed colours have actually changed, so a capture cannot race the repaint.
 */
async function setTheme(page, dark) {
  const before = await page.eval(`getComputedStyle(document.body).backgroundColor`);
  await page.eval(`
    (() => {
      localStorage.setItem('app-dark', ${dark ? "'true'" : "'false'"});
      document.documentElement.classList.toggle('app-dark', ${dark});
      return true;
    })()
  `);
  await page.waitFor(
    `getComputedStyle(document.body).backgroundColor !== ${JSON.stringify(before)}`,
    { label: `${dark ? 'dark' : 'light'} theme repaint`, timeoutMs: 10_000 }
  ).catch(() => console.error(`  (theme did not change background colour; continuing)`));
  await sleep(1200);
  return page.eval(`getComputedStyle(document.body).backgroundColor`);
}

/** Reads back what the KPI cards actually rendered. */
const READ_CARDS = `
  Array.from(document.querySelectorAll('.kpi')).map((card) => {
    const trendEl = card.querySelector('.kpi-trend');
    const sparkEl = card.querySelector('app-kpi-sparkline');
    const line = card.querySelector('app-kpi-sparkline .spark-line');
    const svg = card.querySelector('app-kpi-sparkline svg');
    const dot = card.querySelector('.kpi-dot');
    return {
      label: card.querySelector('.kpi-label')?.textContent?.trim(),
      value: card.querySelector('.kpi-value')?.textContent?.trim(),
      trendText: trendEl?.textContent?.replace(/\\s+/g, ' ').trim(),
      trendTone: trendEl ? trendEl.className.replace('kpi-trend', '').replace('ng-star-inserted', '').trim() : null,
      trendColor: trendEl ? getComputedStyle(trendEl).color : null,
      arrowClass: trendEl?.querySelector('i')?.className ?? null,
      trendTitle: trendEl?.getAttribute('title'),
      // Threshold dot: absent on the cards with no target, which is itself the
      // assertion — a dot on all sixteen would mean the target rule is ignored.
      dot: dot ? dot.className.replace('kpi-dot', '').replace('ng-star-inserted', '').trim() : null,
      dotTitle: dot?.getAttribute('title') ?? null,
      dotColor: dot ? getComputedStyle(dot).backgroundColor : null,
      sparkTone: sparkEl ? sparkEl.className.replace('ng-star-inserted', '').trim() : null,
      sparkColor: sparkEl ? getComputedStyle(sparkEl).color : null,
      sparkStroke: line ? getComputedStyle(line).stroke : null,
      sparkPoints: line ? line.getAttribute('d').split('C').length : 0,
      sparkSize: svg ? Math.round(svg.getBoundingClientRect().width) + 'x' + Math.round(svg.getBoundingClientRect().height) : null
    };
  })
`;

/**
 * Stamps every card with its index, so a later read can tell whether the poll
 * replaced the DOM nodes.
 *
 * This is how "the cards do not re-animate on the 5s poll" is actually checked:
 * the entrance animation is bound to node creation (brief 6), so a card that
 * still carries its own stamp is the same card, and a card that lost it was
 * rebuilt and has just replayed its animation. Counting running animations
 * confirms the same thing from the other side.
 */
const STAMP_CARDS = `
  (() => {
    const cards = Array.from(document.querySelectorAll('.kpi'));
    cards.forEach((card, i) => { card.dataset.probe = String(i); });
    return cards.map((card) => card.dataset.probe).join(',');
  })()
`;

/** The same cards, read back after a poll has landed. */
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

/**
 * How the grid actually laid out: the number of distinct rows and the number of
 * cards in each. Sixteen cards in a `repeat(4, 1fr)` grid should read [4,4,4,4],
 * and anything else means the cards wrapped rather than filling their rows.
 */
const GRID_SHAPE = `
  (() => {
    const cards = Array.from(document.querySelectorAll('.kpi'));
    const rows = new Map();
    for (const card of cards) {
      const top = Math.round(card.getBoundingClientRect().top);
      rows.set(top, (rows.get(top) ?? 0) + 1);
    }
    const counts = Array.from(rows.entries()).sort((a, b) => a[0] - b[0]).map(([, n]) => n);
    return { rows: counts.length, perRow: counts, columns: getComputedStyle(document.querySelector('.kpi-grid')).gridTemplateColumns.split(' ').length };
  })()
`;

async function main() {
  mkdirSync(OUTPUT_DIR, { recursive: true });

  const version = await fetchJson('/json/version');
  const target = await fetchJson('/json/new?about:blank', { method: 'PUT' });
  const page = await Page.attach(target.webSocketDebuggerUrl);
  await page.send('Page.enable');
  await page.send('Runtime.enable');
  // Browser-level errors — a failed request, a 500 — arrive as Log entries, not
  // as Runtime console calls, so both streams have to be on to see them all.
  await page.send('Log.enable');
  await page.send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 1000,
    deviceScaleFactor: 2,
    mobile: false
  });

  // 1. Start from a clean theme so the light capture really is light.
  await page.eval(`localStorage.setItem('app-dark', 'false'); true`).catch(() => {});

  // 2. The auth guard parks an unauthenticated visit on the app's login page;
  //    its CTA starts the OIDC flow. Whether Keycloak still has a session (the
  //    code flow bounces straight back) or asks for credentials, both are handled.
  await page.send('Page.navigate', { url: APP_URL });
  await page.waitFor(`!!document.querySelector('app-root')`, { label: 'app bootstrapped' });

  //    Three states this has to tell apart, and the reason each wait below is on
  //    an element rather than on a URL:
  //      - the app's own login page, whose CTA starts the code flow (it paints
  //        only once the OIDC client has configured itself, which on a cold
  //        dev-server load outlasts any fixed sleep);
  //      - Keycloak's hosted form, a separate navigation;
  //      - the dashboard, whose route the guard also *flashes* on the way to
  //        bouncing an unauthenticated visit back to /login. Waiting on
  //        `pathname === '/dashboard'` therefore succeeds too early and the run
  //        then sits on the login page forever — waiting on the cards cannot.
  const SIGN_IN_STATE = `(() => {
    if (document.querySelectorAll('.kpi').length) return 'dashboard';
    if (document.querySelector('#username')) return 'credentials';
    if (document.querySelector('.hero-cta, p-button button')) return 'login';
    return 'idle';
  })()`;

  let loginUrl = await page.eval('location.href');
  let needsCredentials = false;
  let onDashboard = false;

  for (let attempt = 0; attempt < 4 && !onDashboard; attempt++) {
    await page.waitFor(`${SIGN_IN_STATE} !== 'idle'`, { label: 'a sign-in page or the dashboard' });
    const state = await page.eval(SIGN_IN_STATE);

    if (state === 'credentials') {
      needsCredentials = true;
      await page.eval(`
        (() => {
          document.querySelector('#username').value = ${JSON.stringify(KEYCLOAK_USER)};
          document.querySelector('#password').value = ${JSON.stringify(KEYCLOAK_PASSWORD)};
          document.querySelector('#kc-login').click();
          return true;
        })()
      `);
    } else if (state === 'login') {
      loginUrl = await page.eval('location.href');
      await page.eval(`
        (() => {
          const cta = document.querySelector('.hero-cta') ?? document.querySelector('p-button button');
          cta.click();
          return true;
        })()
      `);
    }

    // 3. The callback exchanges the code and the dashboard renders; a flash of
    //    the route before the token is stored lands back on /login, which the
    //    next attempt signs in again.
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
  // One 5s poll plus the count-up, so the capture shows settled numbers.
  await sleep(6500);

  const background = await setTheme(page, false);
  const light = await page.eval(READ_CARDS);
  await page.screenshot(join(OUTPUT_DIR, 'dashboard-light.png'));

  // 3b. Poll stability. The Command Center refreshes every 5s, and the KPI
  //     entrance animation must not replay with it: a card that re-animates
  //     every tick reads as a page that keeps reloading. Stamping the nodes and
  //     reading the stamps back after a poll has landed is the direct test —
  //     the stamp only survives if the node did.
  const stamps = await page.eval(STAMP_CARDS);
  await sleep(6500);
  const afterPoll = await page.eval(READ_AFTER_POLL);
  const pollStable = afterPoll.count === light.length
    && afterPoll.probes === stamps
    && afterPoll.runningAnimations === 0;

  const clip = await page.eval(`
    (() => {
      const r = document.querySelector('.kpi-grid').getBoundingClientRect();
      return { x: Math.floor(r.x), y: Math.floor(r.y), width: Math.ceil(r.width), height: Math.ceil(r.height), scale: 2 };
    })()
  `);
  await page.screenshot(join(OUTPUT_DIR, 'kpi-closeup-light.png'), clip);

  // 4. Dark-mode parity (brief 7): the tokens repaint, the component does not change.
  const darkBackground = await setTheme(page, true);
  const dark = await page.eval(READ_CARDS);
  await page.screenshot(join(OUTPUT_DIR, 'dashboard-dark.png'));
  await page.screenshot(join(OUTPUT_DIR, 'kpi-closeup-dark.png'), clip);

  // 5. Semantic direction (brief 5.3) — "up" is not always good — is covered by
  //    the unit tests in kpi-trends.spec.ts, which can drive the card with a
  //    rising problem-saga trend. The live ledger cannot produce that shape:
  //    every saga it holds started today, so the baseline is zero and the card
  //    correctly shows a dash.

  const report = {
    browser: version.Browser,
    loginUrl,
    needsCredentials,
    lightBackground: background,
    darkBackground,
    outputDir: OUTPUT_DIR,
    cardCount: light.length,
    grid: await page.eval(GRID_SHAPE),
    thresholdDots: light.filter((c) => c.dot).map((c) => `${c.label}=${c.dot}`),
    pollStable,
    afterPoll,
    consoleErrors: page.consoleErrors,
    light,
    dark
  };
  writeFileSync(join(OUTPUT_DIR, 'report.json'), JSON.stringify(report, null, 2));
  page.close();

  // Compact console summary; report.json holds the full detail.
  console.log(`browser=${version.Browser} needsCredentials=${needsCredentials}`);
  console.log(`light bg=${background} dark bg=${darkBackground}`);
  console.log(`cards=${light.length} thresholdDots=${report.thresholdDots.length} pollStable=${pollStable}`);
  console.log(`grid rows=${report.grid.rows} perRow=${report.grid.perRow.join(',')} columns=${report.grid.columns}`);
  console.log(`consoleErrors=${page.consoleErrors.length}`);
  for (const error of page.consoleErrors) console.log(`  ERROR ${error}`);
  for (const card of light) {
    console.log(
      `LIGHT ${card.label}: value=${card.value} | dot=${card.dot ?? '-'} (${card.dotTitle ?? 'no target'}) | ` +
      `trend="${card.trendText}" tone=${card.trendTone} colour=${card.trendColor} arrow=${card.arrowClass}`
    );
    console.log(`      spark tone=${card.sparkTone} stroke=${card.sparkStroke} pts=${card.sparkPoints} size=${card.sparkSize}`);
  }
  for (const card of dark) {
    console.log(`DARK  ${card.label}: trend colour=${card.trendColor} spark stroke=${card.sparkStroke}`);
  }
  console.log('done');
  process.exit(0);
}

main().catch((error) => {
  console.error('FAILED:', error.message);
  process.exit(1);
});
