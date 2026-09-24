package att.ossama.ledgerservice.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The Command Center's chart data for one selected time range.
 *
 * <p>Exists because the charts used to be aggregated in the browser from a single
 * page of 200 journal rows: that is a few days at the portfolio ledger's volume,
 * so any longer range would have drawn a chart from a fraction of its own window.
 * The series are built where the rows are, and the client just draws them.
 *
 * @param range              the range that was applied, echoed back
 * @param dailyVolume        journal entries per day, oldest first
 * @param dailyFlow          money in and out per day, by account leg, oldest first
 * @param balanceDistribution the ten largest account balances
 * @param sagaBreakdown      saga counts by terminal status
 * @param historyStart       the earliest instant the ledger holds, or null for an
 *                           empty log
 */
public record ChartSeries(
        String range,
        List<DailyVolume> dailyVolume,
        List<DailyFlow> dailyFlow,
        List<AccountBalance> balanceDistribution,
        SagaBreakdown sagaBreakdown,
        Instant historyStart) {

    /** One point of the volume chart. */
    public record DailyVolume(String date, long count) {
    }

    /**
     * One point of the cash-flow chart. Only legs touching a customer account
     * count, so internal clearing transfers are not reported as customer flow.
     */
    public record DailyFlow(String date, BigDecimal in, BigDecimal out) {
    }

    /** One slice of the distribution chart. */
    public record AccountBalance(String accountId, BigDecimal balance) {
    }

    /** The saga status panel. */
    public record SagaBreakdown(long completed, long compensating, long failed) {
    }
}
