package att.ossama.ledgerservice.admin;

import java.time.Instant;
import java.util.List;

/**
 * What a projection replay did, and what it found.
 *
 * <p>The first three fields are the operation's own accounting: how much of the
 * log was folded, how long it took, and when it ran. The rest are the
 * verification, and they exist because of what this service turned out to be —
 * see {@link ProjectionRebuilder} for the long version. In short: there are no
 * materialized read-model tables here to drop and refill, so the useful thing a
 * "rebuild" can do is replay the log independently and check the answer against
 * the read models the dashboard actually serves.
 *
 * <p>A report that says {@code consistent: false} is not a failure of the
 * operation — the replay ran fine. It is a statement about the ledger: the
 * independent fold and the SQL aggregate disagree, which means one of them is
 * wrong and the dashboard's numbers should not be trusted until it is settled.
 *
 * @param eventsProcessed how many events were folded, in log order
 * @param elapsedMs       wall time for the replay and the comparison together
 * @param rebuiltAt       when the replay started, from the injected {@link java.time.Clock}
 * @param accountsRebuilt accounts the replay derived from the log
 * @param accountsVerified accounts the live read models returned to compare against
 * @param consistent      whether every account matched on identity and balance
 * @param mismatchCount   how many disagreements were found, capped list or not
 * @param mismatches      up to twenty of them, as text, for an operator to read
 */
public record ProjectionRebuildReport(
        long eventsProcessed,
        long elapsedMs,
        Instant rebuiltAt,
        int accountsRebuilt,
        int accountsVerified,
        boolean consistent,
        long mismatchCount,
        List<String> mismatches
) {
}
