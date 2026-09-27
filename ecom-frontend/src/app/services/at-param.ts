/**
 * The timeline scrubber's instant, spelled once for every read the Command
 * Center makes.
 *
 * A shared helper rather than a private method on each service for the same
 * reason the server has one `AtParam`: the dashboard fans out to six endpoints
 * on a single drag, and they must all be asked about the same instant. Two
 * spellings of "the instant" would eventually differ, and the difference would
 * show up as one card quietly describing a different moment from the rest.
 */

/**
 * The `at=` query part for an instant, or null for the live read.
 *
 * Null and undefined both mean live, and the parameter is then omitted entirely
 * rather than sent empty: an absent `at` is what the server reads as "now", and
 * an empty one is a value it would have to reject or ignore.
 *
 * `toISOString()` is deliberate. The server accepts only ISO-8601 instants
 * carrying a UTC offset, and rejects a zone-less timestamp with a 400 — which is
 * exactly what a naive `toLocaleString()` or a hand-rolled `YYYY-MM-DD HH:mm`
 * would send.
 */
export function atParam(at: Date | null | undefined): string | null {
  return at ? `at=${encodeURIComponent(at.toISOString())}` : null;
}

/** Joins the query parts of a URL, dropping the ones that are absent. */
export function query(...parts: (string | null | undefined)[]): string {
  const present = parts.filter((p): p is string => !!p);
  return present.length ? `?${present.join('&')}` : '';
}
