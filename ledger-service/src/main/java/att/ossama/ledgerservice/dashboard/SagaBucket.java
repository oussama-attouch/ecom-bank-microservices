package att.ossama.ledgerservice.dashboard;

import java.time.LocalDate;

/**
 * One bucket of the saga store's measures, from a single grouped pass of
 * {@code saga_states}.
 *
 * <p>All four operational cards are attributed to the window a saga
 * <em>started</em> in, whatever it did afterwards, which is what lets one
 * grouping answer them together: a rate and the durations behind it describe the
 * same population rather than two different ones. A saga store keeps only each
 * saga's current status, so "compensating" is what started in the window and is
 * still being unwound — a saga whose compensation has since completed no longer
 * counts, and no past status could be reconstructed for it either.
 *
 * <p>The percentile is computed per bucket in SQL rather than derived from the
 * other columns: a p95 is not a function of a bucket's sum and count, and
 * averaging the buckets' percentiles would average a measurement rather than
 * compute one.
 *
 * @param start             the bucket's first day
 * @param current           true when the sagas counted here started inside the selected window
 * @param started           sagas started
 * @param completed         of those, the ones that reached COMPLETED
 * @param compensating      of those, the ones still being unwound
 * @param problem           of those, the ones in a problem state — COMPENSATING or FAILED
 * @param durationMillisSum total duration of the finished ones, in milliseconds
 * @param durationCount     how many finished, the denominator of a mean
 * @param p95Millis         the 95th percentile of those durations, zero when none finished
 */
public record SagaBucket(
        LocalDate start,
        boolean current,
        long started,
        long completed,
        long compensating,
        long problem,
        double durationMillisSum,
        long durationCount,
        double p95Millis) {
}
