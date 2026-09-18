package att.ossama.ledgerservice.dashboard;

import java.time.LocalDate;

/**
 * One bucket of the event log's measures, from a single grouped pass of
 * {@code event_store}.
 *
 * <p>Unlike the journal's measures these are read over the whole log rather than
 * over the two comparison windows, because the two KPIs they feed are levels:
 * "assets under management" and "accounts that exist" are cumulative positions,
 * and the position now is the sum of every movement ever recorded. The same rows
 * also carry the movement inside each bucket, which is what turns the level now
 * into the level as each point of the sparkline ended — the buckets partition
 * the log, so subtracting the movements that followed a point is arithmetic
 * rather than an approximation.
 *
 * @param start           the bucket's first day
 * @param current         true when the movements counted here fall inside the selected window
 * @param netFlow         credits minus debits, in money
 * @param accountsCreated accounts opened
 */
public record EventBucket(
        LocalDate start,
        boolean current,
        double netFlow,
        long accountsCreated) {
}
