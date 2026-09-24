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

## `feature-screenshots.mjs`

Captures the two screenshots the time-travel and projection-consistency features
are documented with, and reads the page back so a run cannot exit 0 having
captured the wrong state: it fails if the snapshot banner never appeared, or if
the rebuild toast came back as anything but a success.

```bash
node scripts/feature-screenshots.mjs <outputDir> 9444 [scrubber|rebuild|both]
```

The two modes are separable because only `rebuild` needs a ledger-service started
with the gate open, which is off by default:

```bash
java -jar ledger-service/target/ledger-service-0.0.1-SNAPSHOT.jar \
     --ledger.projection-rebuild.enabled=true
```

| Mode | Output | What it drives |
|---|---|---|
| `scrubber` | `time-travel-scrubber.png` | Drags `#ledger-scrubber` to a past instant and waits for the snapshot banner |
| `rebuild` | `projection-rebuild-success.png` | Clicks Rebuild Projections in the header and waits for the result toast |

Output goes to `docs/screenshots/dashboard/`, alongside a `report.json` holding
the live and snapshot KPI readings, the toast text, and any console errors — so a
capture carries its own evidence of what rendered.

One implementation note, because it is the fiddly part: the scrubber is a native
`<input type="range">` whose value is a *step index* in minutes from the ledger's
first event, and the left end is not exposed to the page. The script recovers it
from the input's `max` and the dashboard's "now", which lands within an hour
across a two-year range — fine for a screenshot, and the handle's label is read
back afterwards so the run reports where it actually landed rather than where it
aimed.
