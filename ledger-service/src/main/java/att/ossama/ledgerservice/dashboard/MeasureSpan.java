package att.ossama.ledgerservice.dashboard;

import java.time.Instant;

/**
 * The interval one KPI read spans, and how the rows behind it are grouped.
 *
 * <p>One read answers three questions at once, which is what keeps a refresh to
 * a handful of queries rather than one per KPI per day of sparkline:
 * <ul>
 *   <li><b>current</b> — the selected window, {@code (splitAt, to]};</li>
 *   <li><b>previous</b> — the comparison window, {@code (spanFrom, splitAt]};</li>
 *   <li><b>history</b> — one point per bucket, each covering whole calendar
 *       buckets so a sparkline point is a period rather than a fragment.</li>
 * </ul>
 *
 * <p>The split is applied in SQL, per row, rather than by cutting the grouped
 * rows in Java: the selected window starts mid-bucket (at "this time, thirty
 * days ago"), so the bucket that holds it belongs to both windows, and only the
 * rows themselves can say which side of the boundary they fall on. Grouped rows
 * that straddle it are returned once per side and merged again for the history,
 * which is what makes a point whole while the two window totals stay exact.
 *
 * @param spanFrom the oldest instant read; {@code null} means the beginning of the log
 * @param splitAt  the boundary between the previous and the current window; a
 *                 {@code null} means there is no previous window, and is read as
 *                 the beginning of the log so that every row is current
 * @param to       the newest instant read — the "now" of the request
 * @param bucket   how wide one history point is
 */
public record MeasureSpan(Instant spanFrom, Instant splitAt, Instant to, KpiRange.Bucket bucket) {

    public MeasureSpan {
        // A null split means "nothing precedes this window" — the all range.
        // Sent as the beginning of the log rather than as SQL null, because a
        // comparison against null is null in SQL: every row would fall into a
        // nameless third group instead of onto the current side of the split.
        splitAt = splitAt == null ? Instant.EPOCH : splitAt;
    }
}
