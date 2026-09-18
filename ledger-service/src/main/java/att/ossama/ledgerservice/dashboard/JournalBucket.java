package att.ossama.ledgerservice.dashboard;

import java.time.LocalDate;

/**
 * One bucket of the journal's measures, from a single grouped pass of
 * {@code journal_entries}.
 *
 * <p>Six of the sixteen cards ask six different questions of the same table, and
 * each of them is an aggregate over the postings of a window. Reading them as
 * six queries means six passes over the largest table in the ledger; one pass
 * that carries a column per question costs a single scan, and the per-KPI rules
 * are then arithmetic on these rows.
 *
 * <p>Every measure is a value for the bucket as a whole. Which of the two
 * comparison windows it belongs to is {@link #current()}: the rows are split in
 * SQL, so a bucket that straddles the boundary — the one the selected window
 * starts inside — comes back once per side and is summed again where the
 * history needs the whole bucket.
 *
 * @param start             the bucket's first day
 * @param current           true when the rows counted here fall inside the selected window
 * @param volume            total amount posted
 * @param postings          number of postings, the denominator of an average
 * @param customerCashFlow  money into customer accounts minus money out of them
 * @param largeTransfers    postings above the large-transfer threshold
 * @param transferPostings  postings with a leg against the transfer clearing account
 * @param reconciledTransfers  of those, the ones with a matching saga record
 * @param auditedPostings   postings carrying every audit field
 */
public record JournalBucket(
        LocalDate start,
        boolean current,
        double volume,
        long postings,
        double customerCashFlow,
        long largeTransfers,
        long transferPostings,
        long reconciledTransfers,
        long auditedPostings) {
}
