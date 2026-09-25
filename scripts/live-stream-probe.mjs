#!/usr/bin/env node
/**
 * Live event stream probe for ledger-service.
 *
 * Subscribes to the STOMP endpoint at ws://localhost:8085/ws/events and prints
 * every event that arrives on /topic/events.
 *
 * Deliberately dependency-free: STOMP over a WebSocket is a handful of frames,
 * and a probe that needs `npm install` first is a probe that does not get run
 * when something is broken. Node 21+ has a global WebSocket, so plain `node`
 * is the whole requirement.
 *
 *   node scripts/live-stream-probe.mjs
 *   ENDPOINT=ws://localhost:8085/ws/events DURATION_MS=30000 node scripts/live-stream-probe.mjs
 *   RAW=1 node scripts/live-stream-probe.mjs      # print full event JSON, not a summary line
 *
 * Requires the service to have been started with the gate on:
 *   java -jar ledger-service/target/ledger-service-0.0.1-SNAPSHOT.jar --ledger.live-stream.enabled=true
 */
const ENDPOINT = process.env.ENDPOINT ?? 'ws://localhost:8085/ws/events';
const DESTINATION = process.env.DESTINATION ?? '/topic/events';
const DURATION_MS = Number(process.env.DURATION_MS ?? 60000);
const RAW = process.env.RAW === '1';

const NUL = '\u0000';

/**
 * STOMP 1.2 frame: command, headers, blank line, body, NUL.
 *
 * The blank line is what terminates the header block, so a frame with no headers
 * is command + ONE newline, not two. Building it as `command + '\n' + headers +
 * '\n\n'` — which is what this did — emits an extra empty header line for a
 * header-less frame, and Spring's decoder rejects the whole frame: the DISCONNECT
 * at the end of every run produced a "Failed to parse TextMessage" ERROR in the
 * service log. Joining command and headers into one list keeps the count right
 * whether or not there are any.
 */
const frame = (command, headers = {}, body = '') => {
  const head = [command, ...Object.entries(headers).map(([k, v]) => `${k}:${v}`)].join('\n');
  return head + '\n\n' + body + NUL;
};

/**
 * Splits a received chunk into complete frames.
 *
 * The body is taken verbatim and never unescaped: STOMP escapes headers, not
 * bodies, and unescaping a JSON body would turn an escaped `\n` inside a string
 * literal into a real newline and make the JSON unparseable.
 */
function takeFrames(buffer) {
  const frames = [];
  let rest = buffer;
  for (;;) {
    const end = rest.indexOf(NUL);
    if (end === -1) break;
    const raw = rest.slice(0, end).replace(/^\n+/, ''); // leading newlines are heartbeats
    rest = rest.slice(end + 1);
    if (raw.length === 0) continue;

    const split = raw.indexOf('\n\n');
    const head = split === -1 ? raw : raw.slice(0, split);
    const body = split === -1 ? '' : raw.slice(split + 2);
    const lines = head.split('\n');
    const command = lines.shift();
    const headers = {};
    for (const line of lines) {
      const colon = line.indexOf(':');
      if (colon > 0) headers[line.slice(0, colon)] = line.slice(colon + 1);
    }
    frames.push({ command, headers, body });
  }
  return { frames, rest };
}

const counts = new Map();
let events = 0;
let firstEventAt = null;
let lastEventAt = null;
let buffer = '';
let ws;

function report() {
  const perSource = [...counts.entries()].map(([source, n]) => `${source}=${n}`).join(' ') || 'none';
  console.log(`\n--- ${events} event(s) received (${perSource}) ---`);
  if (firstEventAt) {
    console.log(`--- first event at ${firstEventAt.toISOString()}, last at ${lastEventAt.toISOString()} ---`);
  }
}

function shutdown(code = 0) {
  report();
  try {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(frame('DISCONNECT'));
      ws.close();
    }
  } catch { /* closing anyway */ }
  process.exit(code);
}

console.log(`connecting to ${ENDPOINT} ...`);
const connectedAt = new Date();
ws = new WebSocket(ENDPOINT);

ws.addEventListener('open', () => {
  console.log(`socket open at ${connectedAt.toISOString()}, sending CONNECT`);
  ws.send(frame('CONNECT', { 'accept-version': '1.2', host: 'localhost', 'heart-beat': '0,0' }));
});

ws.addEventListener('message', (message) => {
  buffer += typeof message.data === 'string' ? message.data : message.data.toString();
  const { frames, rest } = takeFrames(buffer);
  buffer = rest;

  for (const received of frames) {
    const receivedAt = new Date();
    if (received.command === 'CONNECTED') {
      console.log(`CONNECTED (version=${received.headers.version}) at ${receivedAt.toISOString()}`);
      ws.send(frame('SUBSCRIBE', { id: 'sub-0', destination: DESTINATION, ack: 'auto' }));
      console.log(`SUBSCRIBE ${DESTINATION} sent at ${receivedAt.toISOString()} - waiting for events\n`);
    } else if (received.command === 'MESSAGE') {
      let batch;
      try {
        batch = JSON.parse(received.body);
      } catch (e) {
        console.log(`!! unparseable body on ${received.headers.destination}: ${e.message}`);
        console.log(received.body.slice(0, 500));
        continue;
      }
      for (const event of batch) {
        events += 1;
        counts.set(event.source, (counts.get(event.source) ?? 0) + 1);
        firstEventAt ??= receivedAt;
        lastEventAt = receivedAt;
        if (RAW) {
          console.log(JSON.stringify(event));
        } else {
          const title = JSON.stringify(event.description ?? '');
          const meta = JSON.stringify(event.metadata ?? {});
          console.log(
            `#${String(events).padStart(4, '0')} recv=${receivedAt.toISOString()} ` +
            `src=${event.source} type=${event.type} tx=${event.transactionId ?? '-'} ${title} meta=${meta}`
          );
        }
      }
    } else if (received.command === 'ERROR') {
      console.log(`!! ERROR frame: ${received.headers.message ?? ''}\n${received.body}`);
    } else {
      console.log(`?? ${received.command} frame: ${JSON.stringify(received.headers)}`);
    }
  }
});

ws.addEventListener('error', (event) => {
  console.log(`!! websocket error: ${event.message ?? event.error?.message ?? 'unknown'}`);
});

ws.addEventListener('close', (event) => {
  console.log(`\nsocket closed (code=${event.code}${event.reason ? `, ${event.reason}` : ''})`);
  shutdown(0);
});

process.on('SIGINT', () => shutdown(0));
setTimeout(() => shutdown(0), DURATION_MS);
