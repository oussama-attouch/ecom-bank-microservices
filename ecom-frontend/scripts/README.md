# Verification harness

`kpi-trends-screenshots.mjs` renders the Command Center in a real headless Chrome
and reports what the KPI cards actually produced: the trend text, its semantic
colour, the arrow class, and the sparkline's SVG geometry — in both themes. It
exists because the trend rules are the kind of thing that compiles cleanly and
still reads wrong on screen (a rise in problem sagas painted green, say).

It is not part of the app build, and it has no dependencies: it drives Chrome
over the DevTools Protocol using Node's built-in `WebSocket` (Node 22+).

Start a browser it can attach to, then run it:

```bash
chrome --headless=new --remote-debugging-port=9444 \
       --user-data-dir=/tmp/dsh-chrome --no-first-run about:blank

node scripts/kpi-trends-screenshots.mjs <outputDir> 9444
```

It needs the full stack up: Keycloak (8180), the gateway (8888), ledger-service
(8085) and the dev server (4200). It signs in as the `manager` demo user from
`keycloak/realm-export.json`.

Output: `dashboard-{light,dark}.png`, `kpi-closeup-{light,dark}.png` and a
`report.json` holding the per-card readings. The PNGs committed under
`docs/screenshots/pr6/` came from this script.

One caveat about those committed shots: every event in the demo ledger was
written the same afternoon, so all four KPIs have a zero baseline a week ago and
the cards correctly show a dash. The non-zero (green/red) states were verified by
temporarily inserting two backdated rows, capturing, and deleting them again —
the screenshots in the repo are from the clean state.
