package att.ossama.ledgerservice.dashboard;

import att.ossama.ledgerservice.dashboard.KpiTrendsTestDoubles.FakeEventStore;
import att.ossama.ledgerservice.dashboard.KpiTrendsTestDoubles.FakeJournalRepository;
import att.ossama.ledgerservice.dashboard.KpiTrendsTestDoubles.FakeSagaRepository;
import att.ossama.ledgerservice.domain.AccountCreatedEvent;
import att.ossama.ledgerservice.domain.JournalEntry;
import att.ossama.ledgerservice.domain.MoneyCreditedEvent;
import att.ossama.ledgerservice.domain.MoneyDebitedEvent;
import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.SagaStatus;
import att.ossama.ledgerservice.journal.JournalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trend math: which events land in which window, and what happens when the
 * week-ago value is zero (the case that must not divide by zero).
 *
 * <p>Reads real seam implementations backed by lists — see
 * {@link KpiTrendsTestDoubles} — so the assertions are about the service's own
 * windowing rather than about stub wiring.
 *
 * <h2>Reading the timestamps in these tests</h2>
 * "Now" is pinned mid-afternoon, which splits the fixtures into three bands:
 * <ul>
 *   <li>{@code OLD} — before the cutoff, so it is part of the baseline only;</li>
 *   <li>{@code IN_WINDOW} — inside the last 7 days, current side only;</li>
 *   <li>before {@code OLD} — outside both windows, i.e. history the trend
 *       deliberately does not look at.</li>
 * </ul>
 * Each test asserts both sides of the comparison so a fixture that silently
 * falls into the wrong band fails loudly instead of skewing one number.
 */
class KpiTrendsServiceTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    /** Mid-afternoon, so "today so far" is a partial day and the window edges matter. */
    private static final Instant NOW = Instant.parse("2026-02-11T15:00:00Z");
    /** Start of the 7-day window: anything at or before this is baseline. */
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(7));
    private static final Instant OLD = NOW.minus(Duration.ofDays(30));
    private static final Instant ANCIENT = NOW.minus(Duration.ofDays(90));
    private static final Instant IN_WINDOW = NOW.minus(Duration.ofDays(2));
    /** Inside the 7 days before the cutoff: the baseline side of a flow KPI. */
    private static final Instant PREVIOUS_WINDOW = NOW.minus(Duration.ofDays(9));
    private static final Instant MIDNIGHT = LocalDate.of(2026, 2, 11).atStartOfDay(ZONE).toInstant();
    private static final Instant WEEK_AGO_MIDNIGHT = LocalDate.of(2026, 2, 4).atStartOfDay(ZONE).toInstant();

    private FakeEventStore eventStore;
    private FakeJournalRepository journal;
    private FakeSagaRepository sagaStore;
    private SlaMetricsService sla;
    private KpiTrendsService service;

    @BeforeEach
    void setUp() {
        eventStore = new FakeEventStore();
        journal = new FakeJournalRepository();
        sagaStore = new FakeSagaRepository();
        sla = new SlaMetricsService(Clock.fixed(NOW, ZONE));
        // The replay aggregates are the same four questions the SQL ones answer,
        // so these tests still pin the windowing rules rather than the query.
        service = new KpiTrendsService(
                new ReplayLedgerAggregates(eventStore, new JournalService(journal, Clock.fixed(NOW, ZONE)),
                        sagaStore),
                sla,
                Clock.fixed(NOW, ZONE));
    }

    // -----------------------------------------------------------------------
    // Assets under management — a level, replayed from the event log
    // -----------------------------------------------------------------------

    @Test
    void assetsUnderManagementComparesTheReplayedBalanceNowAgainstAWeekAgo() {
        account("ACC-1", ANCIENT);
        credit("ACC-1", 4000, ANCIENT);
        credit("ACC-1", 1250, IN_WINDOW);
        debit("ACC-1", 250, IN_WINDOW);

        KpiTrend trend = service.trends().assetsUnderManagement();

        assertThat(trend.current()).isEqualByComparingTo("5000.00");
        assertThat(trend.previous()).isEqualByComparingTo("4000.00");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("25.00");
    }

    @Test
    void aDebitPostedInsideTheWindowLowersTheTrend() {
        account("ACC-1", ANCIENT);
        credit("ACC-1", 1000, OLD);
        debit("ACC-1", 250, IN_WINDOW);

        KpiTrend trend = service.trends().assetsUnderManagement();

        assertThat(trend.current()).isEqualByComparingTo("750.00");
        assertThat(trend.previous()).isEqualByComparingTo("1000.00");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("-25.00");
    }

    /** The baseline is a snapshot, not a sum: a week-old balance counts once, not per event. */
    @Test
    void theBaselineIsTheBalanceAsItStoodAWeekAgo() {
        account("ACC-1", ANCIENT);
        credit("ACC-1", 900, OLD);
        debit("ACC-1", 100, OLD);
        credit("ACC-1", 700, IN_WINDOW);

        KpiTrend trend = service.trends().assetsUnderManagement();

        assertThat(trend.previous()).isEqualByComparingTo("800.00");
        assertThat(trend.current()).isEqualByComparingTo("1500.00");
    }

    // -----------------------------------------------------------------------
    // Active accounts — a level, counted from ACCOUNT_CREATED events
    // -----------------------------------------------------------------------

    @Test
    void activeAccountsCountsOnlyTheAccountsThatExistedAWeekAgo() {
        account("ACC-OLD", ANCIENT);
        account("ACC-NEW", IN_WINDOW);

        KpiTrend trend = service.trends().activeAccounts();

        assertThat(trend.current()).isEqualByComparingTo("2.00");
        assertThat(trend.previous()).isEqualByComparingTo("1.00");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("100.00");
    }

    @Test
    void anAccountCreatedExactlyOnTheCutoffCountsAsExistingForTheBaseline() {
        // A level KPI's cutoff is an inclusive upper bound: an account created
        // exactly on it already existed a week ago, so it is part of the
        // baseline rather than a new arrival. No percentage is possible yet
        // because there is nothing else to compare against.
        account("ACC-EDGE", CUTOFF);

        KpiTrend trend = service.trends().activeAccounts();

        assertThat(trend.current()).isEqualByComparingTo("1.00");
        assertThat(trend.previous()).isEqualByComparingTo("1.00");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("0.00");
    }

    // -----------------------------------------------------------------------
    // Volume — a flow over the selected range
    // -----------------------------------------------------------------------

    /**
     * The card is "Volume (7d)", not "Today's volume": the number is the
     * window's turnover, so a posting from the middle of the week counts and one
     * from just before the window starts does not.
     */
    @Test
    void volumeCoversTheSelectedWindowRatherThanTodaySoFar() {
        entry(500, MIDNIGHT.plus(Duration.ofHours(1)));
        entry(300, MIDNIGHT.plus(Duration.ofHours(8)));
        // Straddles the window's start: 02:00 is before the cutoff and belongs to
        // the previous window, 20:00 is after it and belongs to this one.
        entry(8830, WEEK_AGO_MIDNIGHT.plus(Duration.ofHours(2)));
        entry(9999, WEEK_AGO_MIDNIGHT.plus(Duration.ofHours(20)));

        KpiTrend trend = service.trends().todayVolume();

        assertThat(trend.current()).isEqualByComparingTo("10799.00");
        assertThat(trend.previous()).isEqualByComparingTo("8830.00");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("22.30");
    }

    @Test
    void volumeCountsEveryDayOfTheWindowNotOnlyToday() {
        entry(400, MIDNIGHT.minus(Duration.ofHours(6)));
        entry(400, MIDNIGHT.plus(Duration.ofHours(6)));

        KpiTrend trend = service.trends().todayVolume();

        assertThat(trend.current()).isEqualByComparingTo("800.00");
        assertThat(trend.previous()).isEqualByComparingTo("0.00");
    }

    // -----------------------------------------------------------------------
    // The sparkline series
    // -----------------------------------------------------------------------

    @Test
    void volumeHistoryHoldsOneWholeDayPerPointOldestFirst() {
        entry(100, MIDNIGHT.plus(Duration.ofHours(1)));
        entry(700, MIDNIGHT.minus(Duration.ofDays(3)).plus(Duration.ofHours(9)));
        entry(500, MIDNIGHT.minus(Duration.ofDays(7)).plus(Duration.ofHours(2)));

        List<BigDecimal> history = service.trends().todayVolume().history();

        assertThat(history).hasSize(7);
        // Each point covers one calendar day, and the last one is today so far —
        // the same window `current` measures, which is what makes the last point
        // of the series agree with the number printed on the card. The entry from
        // a week ago sits outside the series entirely.
        assertThat(history).containsExactly(
                new BigDecimal("0.00"),   // 6 days ago
                new BigDecimal("0.00"),   // 5 days ago
                new BigDecimal("0.00"),   // 4 days ago
                new BigDecimal("700.00"), // 3 days ago
                new BigDecimal("0.00"),   // 2 days ago
                new BigDecimal("0.00"),   // yesterday
                new BigDecimal("100.00")); // today, so far
    }

    @Test
    void accountHistoryRisesAsAccountsAppear() {
        account("ACC-1", MIDNIGHT.minus(Duration.ofDays(3)).plus(Duration.ofHours(4)));
        account("ACC-2", MIDNIGHT.minus(Duration.ofDays(1)).plus(Duration.ofHours(4)));

        List<BigDecimal> history = service.trends().activeAccounts().history();

        assertThat(history).hasSize(7);
        assertThat(history.get(0)).isEqualByComparingTo("0.00");
        assertThat(history.get(3)).isEqualByComparingTo("1.00");
        assertThat(history.get(6)).isEqualByComparingTo("2.00");
    }

    @Test
    void historyIsZeroFilledRatherThanMissingOnAnEmptyLedger() {
        List<BigDecimal> history = service.trends().assetsUnderManagement().history();

        assertThat(history).hasSize(7);
        assertThat(history).allSatisfy(value -> assertThat(value).isEqualByComparingTo("0.00"));
    }

    // -----------------------------------------------------------------------
    // Problem sagas — a flow of problem sagas started in each window
    // -----------------------------------------------------------------------

    @Test
    void problemSagasCountsSagasStartedInTheWindowThatAreStillFailing() {
        saga(SagaStatus.COMPENSATING, IN_WINDOW);
        saga(SagaStatus.FAILED, NOW.minus(Duration.ofDays(3)));
        saga(SagaStatus.COMPLETED, IN_WINDOW);
        // Started in the previous 7-day window only.
        saga(SagaStatus.FAILED, NOW.minus(Duration.ofDays(9)));
        // Ancient but still FAILED today: a flow metric counts what started in
        // the window, so history older than both windows stays out of the delta.
        saga(SagaStatus.FAILED, ANCIENT);

        KpiTrend trend = service.trends().problemSagas();

        assertThat(trend.current()).isEqualByComparingTo("2.00");
        assertThat(trend.previous()).isEqualByComparingTo("1.00");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("100.00");
    }

    // -----------------------------------------------------------------------
    // Net cash flow — a flow, money into the customer ledger minus money out
    // -----------------------------------------------------------------------

    @Test
    void netCashFlowAddsDepositsAndTakesOffWithdrawals() {
        entry(1000, IN_WINDOW);              // money in
        withdrawal(250, IN_WINDOW);          // money out
        entry(4000, PREVIOUS_WINDOW);

        KpiTrend trend = service.trends().netCashFlow();

        assertThat(trend.current()).isEqualByComparingTo("750.00");
        assertThat(trend.previous()).isEqualByComparingTo("4000.00");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("-81.25");
    }

    @Test
    void aTransferBetweenTwoCustomersIsNotCashFlow() {
        // Both legs touch customer accounts with opposite signs: the money moved
        // inside the ledger rather than into it, so it nets to nothing. Counting
        // the cash or clearing legs instead would report the same transfer twice.
        journal.add(new JournalEntry("t-1", "txn", "ACC-1", "TRANSFER_CLEARING",
                500, "USD", "TRANSFER debit", IN_WINDOW, "SYSTEM"));
        journal.add(new JournalEntry("t-2", "txn", "TRANSFER_CLEARING", "ACC-2",
                500, "USD", "TRANSFER credit", IN_WINDOW, "SYSTEM"));

        KpiTrend trend = service.trends().netCashFlow();

        assertThat(trend.current()).isEqualByComparingTo("0.00");
    }

    // -----------------------------------------------------------------------
    // Average transaction value — a flow, averaged over the window's postings
    // -----------------------------------------------------------------------

    @Test
    void averageTransactionValueAveragesTheWindowsPostings() {
        entry(100, IN_WINDOW);
        entry(300, IN_WINDOW);
        withdrawal(200, IN_WINDOW);
        entry(60, PREVIOUS_WINDOW);
        entry(40, PREVIOUS_WINDOW);

        KpiTrend trend = service.trends().avgTransactionValue();

        // Sign does not matter here: this is the size of a posting, not the
        // direction of the money.
        assertThat(trend.current()).isEqualByComparingTo("200.00");
        assertThat(trend.previous()).isEqualByComparingTo("50.00");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("300.00");
    }

    // -----------------------------------------------------------------------
    // Balance concentration — a level, and a ratio rather than a total
    // -----------------------------------------------------------------------

    @Test
    void balanceConcentrationIsTheShareOfTheLedgerTheLargestAccountsHold() {
        evenlyHeldLedger();
        // One of the largest accounts grows during the window.
        credit("ACC-WHALE-0", 110, IN_WINDOW);

        KpiTrend trend = service.trends().balanceConcentration();

        // 900 of 1000 before the window, 1010 of 1110 after it: the ten largest
        // accounts hold more of the ledger than they did, which is the risk this
        // card exists to surface.
        assertThat(trend.previous()).isEqualByComparingTo("90.00");
        assertThat(trend.current()).isEqualByComparingTo("90.99");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("1.10");
    }

    @Test
    void balanceConcentrationHistoryReadsOneSnapshotPerDay() {
        evenlyHeldLedger();
        credit("ACC-WHALE-0", 110, MIDNIGHT.minus(Duration.ofDays(3)).plus(Duration.ofHours(5)));

        List<BigDecimal> history = service.trends().balanceConcentration().history();

        // A share cannot be recovered by subtracting the flows that followed it,
        // so each point is the concentration as that day ended.
        assertThat(history).hasSize(7);
        assertThat(history.get(0)).isEqualByComparingTo("90.00");   // 6 days ago
        assertThat(history.get(2)).isEqualByComparingTo("90.00");   // 4 days ago
        assertThat(history.get(3)).isEqualByComparingTo("90.99");   // 3 days ago, after the top-up
        assertThat(history.get(6)).isEqualByComparingTo("90.99");   // today
    }

    // -----------------------------------------------------------------------
    // Large transfers — a flow, counted from the journal
    // -----------------------------------------------------------------------

    @Test
    void largeTransfersCountsOnlyPostingsAboveTheThreshold() {
        entry(10_000, IN_WINDOW);       // exactly at the threshold is not above it
        entry(10_000.01, IN_WINDOW);
        entry(25_000, IN_WINDOW);
        entry(50_000, PREVIOUS_WINDOW);

        KpiTrend trend = service.trends().largeTransfers();

        assertThat(trend.current()).isEqualByComparingTo("2.00");
        assertThat(trend.previous()).isEqualByComparingTo("1.00");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("100.00");
    }

    // -----------------------------------------------------------------------
    // The four saga KPIs — flows over the sagas started in each window
    // -----------------------------------------------------------------------

    @Test
    void sagaSuccessRateIsTheShareOfStartedSagasThatCompleted() {
        saga(SagaStatus.COMPLETED, IN_WINDOW, IN_WINDOW.plusMillis(400));
        saga(SagaStatus.COMPLETED, NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(3)).plusMillis(600));
        saga(SagaStatus.COMPENSATING, IN_WINDOW, IN_WINDOW.plusMillis(800));
        saga(SagaStatus.FAILED, IN_WINDOW, null);
        saga(SagaStatus.COMPLETED, PREVIOUS_WINDOW, PREVIOUS_WINDOW.plusMillis(500));

        KpiTrend trend = service.trends().sagaSuccessRate();

        assertThat(trend.current()).isEqualByComparingTo("50.00");    // 2 of 4
        assertThat(trend.previous()).isEqualByComparingTo("100.00");  // 1 of 1
        assertThat(trend.deltaPercent()).isEqualByComparingTo("-50.00");
    }

    @Test
    void averageSagaDurationAveragesTheFinishedSagasStartedInTheWindow() {
        saga(SagaStatus.COMPLETED, IN_WINDOW, IN_WINDOW.plusMillis(300));
        saga(SagaStatus.COMPLETED, IN_WINDOW, IN_WINDOW.plusMillis(700));
        // Still running: there is no duration to average, and it must not read
        // as a saga that took no time at all.
        saga(SagaStatus.COMPENSATING, IN_WINDOW, null);
        saga(SagaStatus.COMPLETED, PREVIOUS_WINDOW, PREVIOUS_WINDOW.plusMillis(100));

        KpiTrend trend = service.trends().avgSagaDuration();

        assertThat(trend.current()).isEqualByComparingTo("500.00");
        assertThat(trend.previous()).isEqualByComparingTo("100.00");
        assertThat(trend.deltaPercent()).isEqualByComparingTo("400.00");
    }

    @Test
    void p95SagaDurationInterpolatesTheSlowTail() {
        // Twenty finished sagas 100 ms apart: p95 sits just inside the slowest
        // one (1900 + 5% of the gap to 2000) rather than snapping to it, which is
        // what makes the card meaningful on a window this small.
        for (int i = 1; i <= 20; i++) {
            saga(SagaStatus.COMPLETED, IN_WINDOW, IN_WINDOW.plusMillis(i * 100L));
        }

        KpiTrend trend = service.trends().p95SagaDuration();

        assertThat(trend.current()).isEqualByComparingTo("1905.00");
        // No baseline: nothing had started in the week before the cutoff.
        assertThat(trend.previous()).isEqualByComparingTo("0.00");
        assertThat(trend.deltaPercent()).isNull();
    }

    @Test
    void compensationRateIsTheShareOfStartedSagasBeingUnwound() {
        saga(SagaStatus.COMPENSATING, IN_WINDOW, IN_WINDOW.plusMillis(400));
        saga(SagaStatus.COMPLETED, IN_WINDOW, IN_WINDOW.plusMillis(300));
        saga(SagaStatus.COMPLETED, IN_WINDOW, IN_WINDOW.plusMillis(300));
        saga(SagaStatus.FAILED, IN_WINDOW, null);
        saga(SagaStatus.COMPENSATING, PREVIOUS_WINDOW, null);

        KpiTrend trend = service.trends().compensationRate();

        assertThat(trend.current()).isEqualByComparingTo("25.00");    // 1 of 4
        assertThat(trend.previous()).isEqualByComparingTo("100.00");  // 1 of 1
        assertThat(trend.deltaPercent()).isEqualByComparingTo("-75.00");
    }

    // -----------------------------------------------------------------------
    // Saga-to-journal reconciliation — the governance row's referential check
    // -----------------------------------------------------------------------

    @Test
    void reconciliationCountsOnlyTransferPostingsThatHaveASagaRow() {
        // Matched: a transfer leg whose transaction the saga store knows about.
        transfer("txn-matched", 500, IN_WINDOW);
        sagaStore.add(new SagaState("txn-matched", "ACC-1", "ACC-2", 500, IN_WINDOW));
        // Unmatched: the same posting with no saga record — the leak this card
        // exists to surface.
        transfer("txn-orphan", 500, IN_WINDOW);
        // A deposit has no saga by design, so it must not be counted against
        // the rate: the denominator is transfers, not postings.
        entry(900, IN_WINDOW);

        KpiTrend trend = service.trends().sagaJournalReconciliation();

        assertThat(trend.current()).isEqualByComparingTo("50.00");
        // Nothing was reconciled in the week before the cutoff.
        assertThat(trend.previous()).isEqualByComparingTo("0.00");
        assertThat(trend.deltaPercent()).isNull();
    }

    @Test
    void reconciliationCountsCompensationLegsAsTransfers() {
        // A compensation reversal carries its own prose description, not
        // "TRANSFER ...". Matching on the description instead of the clearing
        // account would drop these — the legs most worth reconciling.
        journal.add(new JournalEntry("c-1", "txn-comp", JournalService.TRANSFER_CLEARING, "ACC-1",
                400, "USD", "COMPENSATION (reverse debit)", IN_WINDOW, "SYSTEM"));
        sagaStore.add(new SagaState("txn-comp", "ACC-1", "ACC-2", 400, IN_WINDOW));

        KpiTrend trend = service.trends().sagaJournalReconciliation();

        assertThat(trend.current()).isEqualByComparingTo("100.00");
    }

    @Test
    void reconciliationReportsZeroRatherThanFailingOnAWindowWithNoTransfers() {
        entry(500, IN_WINDOW);

        KpiTrend trend = service.trends().sagaJournalReconciliation();

        assertThat(trend.current()).isEqualByComparingTo("0.00");
    }

    // -----------------------------------------------------------------------
    // Anomaly rate — three sigma against the trailing 30 days
    // -----------------------------------------------------------------------

    @Test
    void anomalyRateFlagsTheAmountsBeyondThreeSigma() {
        // Twenty ordinary postings, all older than the comparison window: they
        // shape the baseline without contributing to the window's rate.
        for (int i = 0; i < 20; i++) {
            entry(100, NOW.minus(Duration.ofDays(20)).plus(Duration.ofHours(i)));
        }
        // Ten ordinary postings and one enormous one inside the window.
        for (int i = 0; i < 10; i++) {
            entry(100, IN_WINDOW.plus(Duration.ofMinutes(i)));
        }
        entry(100_000, IN_WINDOW);

        KpiTrend trend = service.trends().anomalyRate();

        // One of the window's eleven postings is an outlier.
        assertThat(trend.current()).isEqualByComparingTo("9.09");
    }

    @Test
    void anomalyRateIsZeroWhenTheBaselineHasNoSpread() {
        // Every amount identical: the standard deviation is zero, so nothing can
        // be three of them away from the mean. Reports 0 rather than dividing by
        // zero and rather than calling all of them outliers.
        entry(100, IN_WINDOW);
        entry(100, OLD.minus(Duration.ofDays(1)));
        entry(100, NOW.minus(Duration.ofDays(3)));

        KpiTrend trend = service.trends().anomalyRate();

        assertThat(trend.current()).isEqualByComparingTo("0.00");
    }

    @Test
    void anomalyRateIsZeroOnAnEmptyLedgerRatherThanAnError() {
        KpiTrend trend = service.trends().anomalyRate();

        assertThat(trend.current()).isEqualByComparingTo("0.00");
        assertThat(trend.deltaPercent()).isNull();
    }

    // -----------------------------------------------------------------------
    // Audit trail completeness — is every posting attributable?
    // -----------------------------------------------------------------------

    @Test
    void auditTrailCompletenessCountsThePostingsMissingAnyAuditField() {
        entry(100, IN_WINDOW);                          // SYSTEM
        entry(200, IN_WINDOW);                          // SYSTEM
        entry(300, IN_WINDOW);                          // SYSTEM
        // The row a backfill wrote without recording who posted it. Reported as
        // it stands: three of four is 75%, and that is the governance finding.
        entryBy(400, IN_WINDOW, null);

        KpiTrend trend = service.trends().auditTrailCompleteness();

        assertThat(trend.current()).isEqualByComparingTo("75.00");
    }

    @Test
    void auditTrailCompletenessIsZeroForAWindowWithNoPostings() {
        // The denominator is empty, so there is no completeness to report. Zero
        // rather than 100 keeps the card from reading as a clean bill of health
        // for a window nothing was written to.
        KpiTrend trend = service.trends().auditTrailCompleteness();

        assertThat(trend.current()).isEqualByComparingTo("0.00");
    }

    // -----------------------------------------------------------------------
    // Dashboard SLA — the one KPI read from memory rather than from SQL
    // -----------------------------------------------------------------------

    @Test
    void dashboardSlaReadsTheTrailingDayAndTreatsUntimedDaysAsGaps() {
        // Four requests in the last 24 hours, one of them over the 500ms line.
        sla.record("kpi-trends", Duration.ofMillis(80));
        sla.record("kpi-trends", Duration.ofMillis(120));
        sla.record("chart-series", Duration.ofMillis(240));
        sla.record("chart-series", Duration.ofMillis(900));

        KpiTrend trend = service.trends().dashboardSlaCompliance();

        assertThat(trend.current()).isEqualByComparingTo("75.00");
        // Nothing was recorded in the 24 hours before that, so there is no
        // baseline to divide by — a dash, not a 0% collapse.
        assertThat(trend.previous()).isEqualByComparingTo("0.00");
        assertThat(trend.deltaPercent()).isNull();

        // The sparkline's earlier days are gaps rather than zeroes: the process
        // was not running, which is a different statement from "answered
        // nothing in time". A zero-fill here would draw a week-long outage.
        assertThat(trend.history()).hasSize(7);
        assertThat(trend.history().subList(0, 6)).containsOnlyNulls();
        assertThat(trend.history().get(6)).isEqualByComparingTo("75.00");
    }

    @Test
    void dashboardSlaCountsARequestExactlyOnTheFiveHundredMillisecondLine() {
        // The threshold is "under 500ms", so 500 itself is a breach.
        sla.record("kpi-trends", Duration.ofMillis(499));
        sla.record("kpi-trends", Duration.ofMillis(500));

        KpiTrend trend = service.trends().dashboardSlaCompliance();

        assertThat(trend.current()).isEqualByComparingTo("50.00");
    }

    // -----------------------------------------------------------------------
    // The zero-baseline rule
    // -----------------------------------------------------------------------

    @Test
    void aZeroBaselineReportsNoPercentageInsteadOfDividingByZero() {
        account("ACC-1", NOW.minus(Duration.ofHours(1)));
        credit("ACC-1", 5000, NOW.minus(Duration.ofHours(1)));
        entry(8830, MIDNIGHT.plus(Duration.ofHours(2)));
        saga(SagaStatus.FAILED, NOW.minus(Duration.ofHours(3)));

        KpiTrendsResponse trends = service.trends();

        assertThat(trends.assetsUnderManagement().deltaPercent()).isNull();
        assertThat(trends.activeAccounts().deltaPercent()).isNull();
        assertThat(trends.todayVolume().deltaPercent()).isNull();
        assertThat(trends.problemSagas().deltaPercent()).isNull();
        // The value itself is still reported — only the percentage is withheld.
        assertThat(trends.assetsUnderManagement().current()).isEqualByComparingTo("5000.00");
        assertThat(trends.assetsUnderManagement().previous()).isEqualByComparingTo("0.00");
    }

    @Test
    void anUnchangedKpiReportsZeroPercentRatherThanNoBaseline() {
        account("ACC-1", ANCIENT);
        credit("ACC-1", 1000, OLD);

        KpiTrend trend = service.trends().assetsUnderManagement();

        assertThat(trend.deltaPercent()).isNotNull();
        assertThat(trend.deltaPercent()).isEqualByComparingTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void anEmptyLedgerReportsZeroesWithNoPercentages() {
        KpiTrendsResponse trends = service.trends();

        assertThat(trends.assetsUnderManagement().current()).isEqualByComparingTo("0.00");
        assertThat(trends.activeAccounts().current()).isEqualByComparingTo("0.00");
        assertThat(trends.todayVolume().current()).isEqualByComparingTo("0.00");
        assertThat(trends.problemSagas().current()).isEqualByComparingTo("0.00");
        assertThat(trends.netCashFlow().current()).isEqualByComparingTo("0.00");
        assertThat(trends.avgTransactionValue().current()).isEqualByComparingTo("0.00");
        assertThat(trends.balanceConcentration().current()).isEqualByComparingTo("0.00");
        assertThat(trends.largeTransfers().current()).isEqualByComparingTo("0.00");
        assertThat(trends.sagaSuccessRate().current()).isEqualByComparingTo("0.00");
        assertThat(trends.avgSagaDuration().current()).isEqualByComparingTo("0.00");
        assertThat(trends.p95SagaDuration().current()).isEqualByComparingTo("0.00");
        assertThat(trends.compensationRate().current()).isEqualByComparingTo("0.00");
        assertThat(trends.sagaJournalReconciliation().current()).isEqualByComparingTo("0.00");
        assertThat(trends.anomalyRate().current()).isEqualByComparingTo("0.00");
        assertThat(trends.auditTrailCompleteness().current()).isEqualByComparingTo("0.00");
        assertThat(trends.dashboardSlaCompliance().current()).isEqualByComparingTo("0.00");
        assertThat(trends.assetsUnderManagement().deltaPercent()).isNull();
        assertThat(trends.activeAccounts().deltaPercent()).isNull();
        assertThat(trends.todayVolume().deltaPercent()).isNull();
        assertThat(trends.problemSagas().deltaPercent()).isNull();
        assertThat(trends.netCashFlow().deltaPercent()).isNull();
        assertThat(trends.avgTransactionValue().deltaPercent()).isNull();
        assertThat(trends.balanceConcentration().deltaPercent()).isNull();
        assertThat(trends.largeTransfers().deltaPercent()).isNull();
        assertThat(trends.sagaSuccessRate().deltaPercent()).isNull();
        assertThat(trends.avgSagaDuration().deltaPercent()).isNull();
        assertThat(trends.p95SagaDuration().deltaPercent()).isNull();
        assertThat(trends.compensationRate().deltaPercent()).isNull();
    }

    @Test
    void moneyIsNotAccumulatedInBinaryFloatingPoint() {
        account("ACC-1", ANCIENT);
        credit("ACC-1", 0.1, IN_WINDOW);
        credit("ACC-1", 0.2, IN_WINDOW);

        KpiTrend trend = service.trends().assetsUnderManagement();

        assertThat(trend.current()).isEqualByComparingTo("0.30");
    }

    // -----------------------------------------------------------------------
    // The range selector
    // -----------------------------------------------------------------------

    /** Three weeks back: inside a 30-day window, outside a 7-day one. */
    private static final Instant THREE_WEEKS_AGO = NOW.minus(Duration.ofDays(21));
    /** Six weeks back: the 30-day range's own comparison window. */
    private static final Instant SIX_WEEKS_AGO = NOW.minus(Duration.ofDays(42));

    @Test
    void aLongerRangeWidensTheWindowAndMovesTheComparisonWithIt() {
        entry(100, IN_WINDOW);
        entry(700, THREE_WEEKS_AGO);
        entry(500, SIX_WEEKS_AGO);

        KpiTrend week = service.trends(KpiRange.SEVEN_DAYS).todayVolume();
        KpiTrend month = service.trends(KpiRange.THIRTY_DAYS).todayVolume();

        // The week sees yesterday's posting and none of the older ones; the month
        // sees three weeks back, and compares against the six weeks before today.
        assertThat(week.current()).isEqualByComparingTo("100.00");
        assertThat(week.previous()).isEqualByComparingTo("0.00");
        assertThat(month.current()).isEqualByComparingTo("800.00");
        assertThat(month.previous()).isEqualByComparingTo("500.00");
        assertThat(month.deltaPercent()).isEqualByComparingTo("60.00");
    }

    /**
     * {@code all} has no earlier period to compare against, and says so rather
     * than inventing one: a zero baseline, and a delta the client draws as "—".
     */
    @Test
    void theAllRangeReportsNoComparisonForAnyKpi() {
        account("ACC-1", ANCIENT);
        credit("ACC-1", 100, IN_WINDOW);
        entry(700, THREE_WEEKS_AGO);
        saga(SagaStatus.COMPLETED, IN_WINDOW, IN_WINDOW.plusMillis(300));

        KpiTrendsResponse all = service.trends(KpiRange.ALL);

        assertThat(all.todayVolume().current()).isEqualByComparingTo("700.00");
        assertThat(all.todayVolume().previous()).isEqualByComparingTo("0.00");
        assertThat(all.todayVolume().deltaPercent()).isNull();
        assertThat(all.assetsUnderManagement().deltaPercent()).isNull();
        assertThat(all.sagaSuccessRate().deltaPercent()).isNull();
        assertThat(all.p95SagaDuration().deltaPercent()).isNull();
        // The cards still report what they measured — only the percentage is
        // withheld, because there is nothing to measure it against.
        assertThat(all.assetsUnderManagement().current()).isEqualByComparingTo("100.00");
        assertThat(all.sagaSuccessRate().current()).isEqualByComparingTo("100.00");
    }

    /** Seven daily points for a week, thirty for a month, thirteen weeks, twelve months. */
    @Test
    void theSparklineTakesTheShapeOfTheRange() {
        entry(100, IN_WINDOW);

        assertThat(service.trends(KpiRange.SEVEN_DAYS).todayVolume().history()).hasSize(7);
        assertThat(service.trends(KpiRange.THIRTY_DAYS).todayVolume().history()).hasSize(30);
        assertThat(service.trends(KpiRange.NINETY_DAYS).todayVolume().history()).hasSize(13);
        assertThat(service.trends(KpiRange.ONE_YEAR).todayVolume().history()).hasSize(12);
        assertThat(service.trends(KpiRange.ALL).todayVolume().history()).hasSize(24);
        // The percentile card is built from its own series rather than a sum, and
        // takes the same shape.
        assertThat(service.trends(KpiRange.ONE_YEAR).p95SagaDuration().history()).hasSize(12);
    }

    /**
     * For a flow, the points of the series are the parts of the window it is the
     * total of: they sum back to the number printed on the card.
     */
    @Test
    void aFlowSeriesSumsToTheWindowItBelongsTo() {
        entry(120, NOW.minus(Duration.ofDays(1)));
        entry(240, NOW.minus(Duration.ofDays(2)));
        entry(999, NOW.minus(Duration.ofDays(20)));

        KpiTrend month = service.trends(KpiRange.THIRTY_DAYS).todayVolume();

        BigDecimal total = month.history().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("1359.00");
        assertThat(month.current()).isEqualByComparingTo("1359.00");
    }

    /**
     * A rate is the window's two totals divided once, not the mean of its days:
     * a quiet day that failed everything must not weigh as much as a busy day
     * that failed nothing.
     */
    @Test
    void aRateDividesTheWindowsTotalsRatherThanAveragingItsDays() {
        for (int i = 0; i < 10; i++) {
            saga(SagaStatus.COMPLETED, NOW.minus(Duration.ofDays(2)).plus(Duration.ofMinutes(i)),
                    NOW.minus(Duration.ofDays(2)).plus(Duration.ofMinutes(i)).plusMillis(200));
        }
        saga(SagaStatus.FAILED, NOW.minus(Duration.ofDays(1)));
        saga(SagaStatus.FAILED, NOW.minus(Duration.ofDays(1)).plus(Duration.ofMinutes(1)));

        KpiTrend trend = service.trends(KpiRange.SEVEN_DAYS).sagaSuccessRate();

        // Ten of twelve completed. The two days' own rates are 100% and 0%, whose
        // mean would be 50% — a number no window in this ledger ever had.
        assertThat(trend.current()).isEqualByComparingTo("83.33");
    }

    /** A level's points are the standing total as each of them ended. */
    @Test
    void aLevelHistoryIsTheStandingTotalAtTheEndOfEachPoint() {
        account("ACC-1", ANCIENT);
        credit("ACC-1", 300, NOW.minus(Duration.ofDays(40)));
        credit("ACC-1", 100, NOW.minus(Duration.ofDays(1)));

        List<BigDecimal> history = service.trends(KpiRange.ONE_YEAR).assetsUnderManagement().history();

        assertThat(history).hasSize(12);
        assertThat(history.get(11)).isEqualByComparingTo("400.00"); // this month, so far
        assertThat(history.get(10)).isEqualByComparingTo("300.00"); // before the last credit
        assertThat(history.get(9)).isEqualByComparingTo("0.00");    // before either of them
        assertThat(history.get(0)).isEqualByComparingTo("0.00");
    }

    /** An unknown token is refused rather than served as the default. */
    @Test
    void anUnknownRangeIsRejectedWithTheValueThatWasWrong() {
        assertThatThrownBy(() -> KpiRange.parse("13w"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown range: 13w");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private void account(String accountId, Instant at) {
        eventStore.append(new AccountCreatedEvent("evt-" + accountId, at, accountId, 1L, "Holder"));
    }

    private void credit(String accountId, double amount, Instant at) {
        eventStore.append(new MoneyCreditedEvent("evt-c-" + accountId + at, at, "txn", accountId, amount, "CREDIT"));
    }

    private void debit(String accountId, double amount, Instant at) {
        eventStore.append(new MoneyDebitedEvent("evt-d-" + accountId + at, at, "txn", accountId, amount, "DEBIT"));
    }

    private void entry(double amount, Instant at) {
        entryBy(amount, at, "SYSTEM");
    }

    /** A posting with an explicit author, for the audit-completeness fixtures. */
    private void entryBy(double amount, Instant at, String postedBy) {
        journal.add(new JournalEntry("j-" + journal.findAll().size(), "txn", "CASH_ACCOUNT", "ACC-1",
                amount, "USD", "CREDIT", at, postedBy));
    }

    /**
     * One leg of a transfer: the shape {@code JournalService.postTransferDebit}
     * writes, recognised by the clearing account rather than by its description.
     */
    private void transfer(String transactionId, double amount, Instant at) {
        journal.add(new JournalEntry("t-" + transactionId, transactionId, "ACC-1",
                JournalService.TRANSFER_CLEARING, amount, "USD", "TRANSFER debit", at, "SYSTEM"));
    }

    /** A posting the other way round: money leaving the customer ledger. */
    private void withdrawal(double amount, Instant at) {
        journal.add(new JournalEntry("w-" + journal.findAll().size(), "txn", "ACC-1", "CASH_ACCOUNT",
                amount, "USD", "DEBIT", at, "SYSTEM"));
    }

    /**
     * Twenty accounts holding 1000 between them: ten of 90 and ten of 10. The top
     * ten therefore hold 90% of the ledger, which is a share a top-up can move —
     * with ten accounts or fewer the card would read 100% by definition.
     */
    private void evenlyHeldLedger() {
        for (int i = 0; i < 10; i++) {
            account("ACC-WHALE-" + i, ANCIENT);
            credit("ACC-WHALE-" + i, 90, ANCIENT);
        }
        for (int i = 0; i < 10; i++) {
            account("ACC-SMALL-" + i, ANCIENT);
            credit("ACC-SMALL-" + i, 10, ANCIENT);
        }
    }

    private void saga(SagaStatus status, Instant startedAt) {
        saga(status, startedAt, null);
    }

    /**
     * A saga that started — and, for the duration KPIs, finished — at the given
     * instants. {@code null} completion is a saga still in flight.
     */
    private void saga(SagaStatus status, Instant startedAt, Instant completedAt) {
        SagaState saga = new SagaState("txn-" + startedAt + "-" + status + "-" + completedAt,
                "ACC-1", "ACC-2", 100, startedAt);
        saga.restoreTerminalState(status, null, completedAt);
        sagaStore.add(saga);
    }
}
