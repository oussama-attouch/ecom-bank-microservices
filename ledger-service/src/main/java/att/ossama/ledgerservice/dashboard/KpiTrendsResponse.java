package att.ossama.ledgerservice.dashboard;

/**
 * Payload of {@code GET /api/dashboard/kpi-trends?range=…}.
 *
 * <p>One trend per Command Center KPI card (brief 5.3), in the same order the
 * cards are rendered: the four cumulative KPIs on the first row, the financial
 * four on the second, the operational four on the third, and the governance four
 * on the fourth.
 *
 * <p>Every trend in the payload covers the range the request asked for, so the
 * shape here is the same whatever that range is — only the windows behind the
 * numbers and the length of each history change. Adding the parameter therefore
 * changed no field name and no type, which is what keeps a client that predates
 * the selector working: it asks for nothing and gets the seven-day payload it was
 * written against.
 *
 * <p>The fourth row is meta-metrics about the analytics pipeline rather than
 * about the bank: they describe whether the ledger log and the saga store agree,
 * whether the window's amounts are statistically ordinary, whether every posting
 * is attributable, and whether this very endpoint answers fast enough. They are
 * reported as measured, including when the answer is unflattering — a governance
 * card that has been tuned to look green carries no information.
 *
 * <p>Additive by design: the client picks each card's trend out of this payload
 * by name, so a field added here cannot silently pair a card with another KPI's
 * history, and a client that does not know a field simply ignores it.
 */
public record KpiTrendsResponse(
        KpiTrend assetsUnderManagement,
        KpiTrend activeAccounts,
        KpiTrend todayVolume,
        KpiTrend problemSagas,
        KpiTrend netCashFlow,
        KpiTrend avgTransactionValue,
        KpiTrend balanceConcentration,
        KpiTrend largeTransfers,
        KpiTrend sagaSuccessRate,
        KpiTrend avgSagaDuration,
        KpiTrend p95SagaDuration,
        KpiTrend compensationRate,
        KpiTrend sagaJournalReconciliation,
        KpiTrend anomalyRate,
        KpiTrend auditTrailCompleteness,
        KpiTrend dashboardSlaCompliance) {
}
