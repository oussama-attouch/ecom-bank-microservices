package att.ossama.ledgerservice.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import att.ossama.ledgerservice.web.ApiExceptionHandler;
import att.ossama.ledgerservice.web.AtParam;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the wire contract the Command Center parses.
 *
 * <p>The JSON field names come from the record components and the client's KPI
 * card keys off them by name, so renaming one is a breaking change that still
 * compiles. The nullable {@code deltaPercent} is asserted explicitly: "no
 * baseline" must arrive as JSON {@code null}, not be omitted.
 */
class DashboardControllerTest {

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

    /** Seven points, the shape the sparkline expects. */
    private static final List<BigDecimal> SERIES = List.of(
            new BigDecimal("4100.00"), new BigDecimal("4200.00"), new BigDecimal("4300.00"),
            new BigDecimal("4400.00"), new BigDecimal("4500.00"), new BigDecimal("4700.00"),
            new BigDecimal("5000.00"));

    /**
     * The SLA card's series, with the leading gap a restarted service produces:
     * the null has to arrive as JSON {@code null}. {@code List.of} would reject
     * it, which is why this one is built differently from {@link #SERIES}.
     */
    private static final List<BigDecimal> SLA_SERIES = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(null, null, null, null, null, null, new BigDecimal("95.20")));

    private static MockMvc mockMvc(KpiTrendsResponse response) {
        return MockMvcBuilders.standaloneSetup(new DashboardController(new StubKpiTrendsService(response), null, new AtParam(Clock.systemUTC()), Clock.systemUTC())).build();
    }

    @Test
    void servesTheSixteenKpiTrendsUnderTheirContractNames() throws Exception {
        KpiTrendsResponse response = new KpiTrendsResponse(
                new KpiTrend(new BigDecimal("5000.00"), new BigDecimal("4200.00"), new BigDecimal("19.05"), SERIES),
                new KpiTrend(new BigDecimal("2.00"), new BigDecimal("1.00"), new BigDecimal("100.00"), SERIES),
                new KpiTrend(new BigDecimal("8830.00"), new BigDecimal("0.00"), null, SERIES),
                new KpiTrend(new BigDecimal("2.00"), new BigDecimal("0.00"), null, SERIES),
                new KpiTrend(new BigDecimal("12500.00"), new BigDecimal("10000.00"), new BigDecimal("25.00"), SERIES),
                new KpiTrend(new BigDecimal("250.00"), new BigDecimal("200.00"), new BigDecimal("25.00"), SERIES),
                new KpiTrend(new BigDecimal("91.89"), new BigDecimal("90.00"), new BigDecimal("2.10"), SERIES),
                new KpiTrend(new BigDecimal("6.00"), new BigDecimal("4.00"), new BigDecimal("50.00"), SERIES),
                new KpiTrend(new BigDecimal("94.00"), new BigDecimal("95.00"), new BigDecimal("-1.05"), SERIES),
                new KpiTrend(new BigDecimal("412.50"), new BigDecimal("380.00"), new BigDecimal("8.55"), SERIES),
                new KpiTrend(new BigDecimal("980.00"), new BigDecimal("900.00"), new BigDecimal("8.89"), SERIES),
                new KpiTrend(new BigDecimal("5.00"), new BigDecimal("4.00"), new BigDecimal("25.00"), SERIES),
                new KpiTrend(new BigDecimal("31.05"), new BigDecimal("100.00"), new BigDecimal("-68.95"), SERIES),
                new KpiTrend(new BigDecimal("0.88"), new BigDecimal("1.10"), new BigDecimal("-20.00"), SERIES),
                new KpiTrend(new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("0.00"), SERIES),
                new KpiTrend(new BigDecimal("95.20"), new BigDecimal("93.40"), new BigDecimal("1.93"), SLA_SERIES));

        mockMvc(response).perform(get("/api/dashboard/kpi-trends"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.assetsUnderManagement.current").value(5000.00))
                .andExpect(jsonPath("$.assetsUnderManagement.previous").value(4200.00))
                .andExpect(jsonPath("$.assetsUnderManagement.deltaPercent").value(19.05))
                .andExpect(jsonPath("$.assetsUnderManagement.history.length()").value(7))
                .andExpect(jsonPath("$.activeAccounts.current").value(2))
                .andExpect(jsonPath("$.activeAccounts.deltaPercent").value(100.0))
                .andExpect(jsonPath("$.todayVolume.current").value(8830.00))
                .andExpect(jsonPath("$.todayVolume.deltaPercent").value(nullValue()))
                .andExpect(jsonPath("$.problemSagas.current").value(2))
                .andExpect(jsonPath("$.problemSagas.deltaPercent").value(nullValue()))
                .andExpect(jsonPath("$.problemSagas.history.length()").value(7))
                // Row 2, financial.
                .andExpect(jsonPath("$.netCashFlow.current").value(12500.00))
                .andExpect(jsonPath("$.netCashFlow.deltaPercent").value(25.00))
                .andExpect(jsonPath("$.netCashFlow.history.length()").value(7))
                .andExpect(jsonPath("$.avgTransactionValue.current").value(250.00))
                .andExpect(jsonPath("$.balanceConcentration.current").value(91.89))
                .andExpect(jsonPath("$.largeTransfers.current").value(6))
                // Row 3, operational.
                .andExpect(jsonPath("$.sagaSuccessRate.current").value(94.00))
                .andExpect(jsonPath("$.avgSagaDuration.current").value(412.50))
                .andExpect(jsonPath("$.p95SagaDuration.current").value(980.00))
                .andExpect(jsonPath("$.compensationRate.current").value(5.00))
                .andExpect(jsonPath("$.compensationRate.history.length()").value(7))
                // Row 4, governance.
                .andExpect(jsonPath("$.sagaJournalReconciliation.current").value(31.05))
                .andExpect(jsonPath("$.sagaJournalReconciliation.deltaPercent").value(-68.95))
                .andExpect(jsonPath("$.anomalyRate.current").value(0.88))
                .andExpect(jsonPath("$.anomalyRate.history.length()").value(7))
                .andExpect(jsonPath("$.auditTrailCompleteness.current").value(100.00))
                .andExpect(jsonPath("$.dashboardSlaCompliance.current").value(95.20))
                // The one series that may hold a gap, and it must survive the
                // round trip as JSON null rather than as an omitted array slot
                // or a zero the sparkline would draw as a cliff.
                .andExpect(jsonPath("$.dashboardSlaCompliance.history.length()").value(7))
                .andExpect(jsonPath("$.dashboardSlaCompliance.history[0]").value(nullValue()))
                .andExpect(jsonPath("$.dashboardSlaCompliance.history[6]").value(95.20));
    }

    @Test
    void aTrendSerializesAsCurrentPreviousDeltaPercentAndHistory() throws Exception {
        String json = MAPPER.writeValueAsString(new KpiTrend(
                new BigDecimal("750.00"), new BigDecimal("1000.00"), new BigDecimal("-25.00"), SERIES));

        assertThat(json).isEqualTo("{\"current\":750.00,\"previous\":1000.00,\"deltaPercent\":-25.00,"
                + "\"history\":[4100.00,4200.00,4300.00,4400.00,4500.00,4700.00,5000.00]}");
    }

    /**
     * The selector's five tokens, and the default for a client that sends none.
     *
     * <p>The range is asserted to reach the service rather than merely to be
     * accepted: a controller that parsed the parameter and then ignored it would
     * still answer 200 to every one of these.
     */
    @Test
    void passesTheRequestedRangeToTheServiceAndDefaultsToSevenDays() throws Exception {
        KpiTrend trend = new KpiTrend(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, SERIES);
        KpiTrendsResponse response = new KpiTrendsResponse(trend, trend, trend, trend, trend, trend, trend, trend,
                trend, trend, trend, trend, trend, trend, trend, trend);
        StubKpiTrendsService service = new StubKpiTrendsService(response);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service, null, new AtParam(Clock.systemUTC()), Clock.systemUTC()))
                .build();

        for (String token : List.of("7d", "30d", "90d", "1y", "all")) {
            mvc.perform(get("/api/dashboard/kpi-trends?range=" + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assetsUnderManagement.current").value(1));
            assertThat(service.lastRange().token()).isEqualTo(token);
        }

        // No parameter at all, and an empty one: both are the window this
        // endpoint had before the selector existed.
        mvc.perform(get("/api/dashboard/kpi-trends")).andExpect(status().isOk());
        assertThat(service.lastRange()).isEqualTo(KpiRange.SEVEN_DAYS);
        mvc.perform(get("/api/dashboard/kpi-trends?range=")).andExpect(status().isOk());
        assertThat(service.lastRange()).isEqualTo(KpiRange.SEVEN_DAYS);
    }

    /**
     * An unknown token is refused with the value that was wrong, rather than
     * quietly served as the default: a misspelled range would otherwise show a
     * week of data under a year's heading.
     */
    @Test
    void rejectsAnUnknownRangeWithTheOffendingValue() throws Exception {
        KpiTrendsResponse response = new KpiTrendsResponse(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new DashboardController(new StubKpiTrendsService(response), null, new AtParam(Clock.systemUTC()), Clock.systemUTC()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/dashboard/kpi-trends?range=13w"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown range: 13w"));
    }

    /**
     * The range-aware entry point is the one exercised; the real implementation
     * needs three live stores behind it.
     */
    private static final class StubKpiTrendsService extends KpiTrendsService {
        private final KpiTrendsResponse response;
        private KpiRange lastRange;

        private StubKpiTrendsService(KpiTrendsResponse response) {
            super(null, null, null);
            this.response = response;
        }

        @Override
        public KpiTrendsResponse trends(KpiRange range) {
            this.lastRange = range;
            return response;
        }

        private KpiRange lastRange() {
            return lastRange;
        }
    }
}
