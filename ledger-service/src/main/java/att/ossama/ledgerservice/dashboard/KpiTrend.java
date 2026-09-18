package att.ossama.ledgerservice.dashboard;

import java.math.BigDecimal;
import java.util.List;

/**
 * One KPI's trend: its value over the selected range against its value over the
 * range of the same length immediately before it.
 *
 * @param current       the KPI's value over the selected window
 * @param previous      the KPI's value over the comparison window, or zero for
 *                      the {@code all} range — nothing precedes the whole log, so
 *                      there is no earlier period to measure
 * @param deltaPercent  {@code (current - previous) / previous * 100}, or
 *                      {@code null} when there is no baseline to divide by
 *                      (a zero or non-existent value in the previous window, and
 *                      every KPI of the {@code all} range). The client renders
 *                      "—" instead of a percentage in that case, which is why
 *                      this is nullable rather than zero.
 * @param history       one point per bucket of the selected range, oldest first,
 *                      the last being the period in progress, for the card's
 *                      inline sparkline. Seven daily points for {@code 7d}, 30
 *                      for {@code 30d}, 13 weekly for {@code 90d}, and 12 or up
 *                      to 24 monthly for {@code 1y} and {@code all}. A count for
 *                      accounts and sagas, an amount for money.
 */
public record KpiTrend(BigDecimal current, BigDecimal previous, BigDecimal deltaPercent, List<BigDecimal> history) {
}
