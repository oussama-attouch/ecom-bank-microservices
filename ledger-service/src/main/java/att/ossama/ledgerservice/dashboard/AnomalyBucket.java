package att.ossama.ledgerservice.dashboard;

import java.time.LocalDate;

/**
 * One bucket of the anomaly card's measure, from a grouped pass of
 * {@code journal_entries}.
 *
 * <p>The anomaly rate asks a question about a window's <em>shape</em> rather than
 * its size — how many of its postings are far from what the ledger normally
 * looks like — so it needs the distribution behind the window, not just its
 * total, and cannot be read alongside the other journal measures. The baseline
 * it judges against is the trailing month ending where the span ends, one
 * baseline for the whole series rather than one per bucket: that is the rule the
 * card's own tooltip states, and the one the single-window aggregate has always
 * applied, so a point of the sparkline and the number printed beside it are
 * answering the same question.
 *
 * <p>{@link #postings()} is the denominator, and it counts only the postings
 * that had a spread to be judged against: a baseline whose amounts were all
 * identical has a standard deviation of zero, and with no spread nothing can be
 * an outlier. Those postings are left out of both sides of the ratio rather than
 * counted as ordinary, which is the same rule the single-window aggregate always
 * applied.
 *
 * @param start    the bucket's first day
 * @param current  true when the postings counted here fall inside the selected window
 * @param outliers postings more than three standard deviations from their day's baseline
 * @param postings postings that had a baseline with a spread to be measured against
 */
public record AnomalyBucket(
        LocalDate start,
        boolean current,
        long outliers,
        long postings) {
}
