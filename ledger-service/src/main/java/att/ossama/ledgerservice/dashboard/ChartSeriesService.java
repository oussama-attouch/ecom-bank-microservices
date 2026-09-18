package att.ossama.ledgerservice.dashboard;

import att.ossama.ledgerservice.persistence.repository.AccountSummaryRow;
import att.ossama.ledgerservice.persistence.repository.EventJpaRepository;
import att.ossama.ledgerservice.persistence.repository.JournalEntryJpaRepository;
import att.ossama.ledgerservice.persistence.repository.SagaStateJpaRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds the Command Center's chart series for a selected range.
 *
 * <p>Aggregated in SQL and grouped by day, so the cost depends on the range the
 * user picked and not on how many rows the ledger holds: the previous approach
 * shipped one page of 200 journal rows to the browser and let it draw whatever
 * fell inside the window, which is a few days at portfolio volume.
 */
@Service
@Profile("!inmem")
public class ChartSeriesService {

    /** The ranges the selector offers, and how far back each reaches. */
    public enum Range {
        SEVEN_DAYS("7d", Duration.ofDays(7)),
        THIRTY_DAYS("30d", Duration.ofDays(30)),
        NINETY_DAYS("90d", Duration.ofDays(90)),
        ONE_YEAR("1y", Duration.ofDays(365)),
        ALL("all", null);

        private final String token;
        private final Duration lookback;

        Range(String token, Duration lookback) {
            this.token = token;
            this.lookback = lookback;
        }

        String token() {
            return token;
        }

        /** Null lookback means the whole log. */
        Duration lookback() {
            return lookback;
        }

        static Range parse(String requested) {
            if (requested == null || requested.isBlank()) {
                return SEVEN_DAYS;
            }
            String normalised = requested.trim().toLowerCase(Locale.ROOT);
            for (Range range : values()) {
                if (range.token.equals(normalised)) {
                    return range;
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown range: '" + requested + "' (expected one of 7d, 30d, 90d, 1y, all)");
        }
    }

    /** How many slices the distribution chart keeps. */
    private static final int DISTRIBUTION_SIZE = 10;

    private final JournalEntryJpaRepository journal;
    private final SagaStateJpaRepository sagas;
    private final EventJpaRepository events;

    public ChartSeriesService(JournalEntryJpaRepository journal,
                              SagaStateJpaRepository sagas,
                              EventJpaRepository events) {
        this.journal = journal;
        this.sagas = sagas;
        this.events = events;
    }

    /** The series for {@code requested}, which must be one of the known tokens. */
    @Transactional(readOnly = true)
    public ChartSeries series(String requested, Instant now) {
        Range range = Range.parse(requested);
        Instant from = range.lookback() == null ? Instant.EPOCH : now.minus(range.lookback());

        List<ChartSeries.DailyVolume> volume = new ArrayList<>();
        for (Object[] row : journal.dailyCounts(from, now)) {
            volume.add(new ChartSeries.DailyVolume((String) row[0], ((Number) row[1]).longValue()));
        }

        List<ChartSeries.DailyFlow> flow = new ArrayList<>();
        for (Object[] row : journal.dailyFlow(from, now)) {
            flow.add(new ChartSeries.DailyFlow((String) row[0], (BigDecimal) row[1], (BigDecimal) row[2]));
        }

        return new ChartSeries(range.token(), volume, flow, topAccounts(), breakdown());
    }

    /**
     * The ten largest balances.
     *
     * <p>Reuses the account summary aggregate the accounts list already runs, and
     * sorts in Java: the list is one row per account, so ordering it here costs
     * nothing next to a second pass over the event log.
     */
    private List<ChartSeries.AccountBalance> topAccounts() {
        List<AccountSummaryRow> rows = events.accountSummaries();
        return rows.stream()
                .filter(row -> row.getBalance() != null)
                .sorted(Comparator.comparing(AccountSummaryRow::getBalance).reversed())
                .limit(DISTRIBUTION_SIZE)
                .map(row -> new ChartSeries.AccountBalance(row.getAccountId(), row.getBalance()))
                .toList();
    }

    private ChartSeries.SagaBreakdown breakdown() {
        long completed = 0;
        long compensating = 0;
        long failed = 0;
        for (Object[] row : sagas.countByStatus()) {
            String status = (String) row[0];
            long count = ((Number) row[1]).longValue();
            switch (status == null ? "" : status) {
                case "COMPLETED" -> completed = count;
                case "COMPENSATING" -> compensating = count;
                case "FAILED" -> failed = count;
                default -> { }
            }
        }
        return new ChartSeries.SagaBreakdown(completed, compensating, failed);
    }
}
