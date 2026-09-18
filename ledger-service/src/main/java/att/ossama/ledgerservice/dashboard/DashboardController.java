package att.ossama.ledgerservice.dashboard;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

/**
 * Historical half of the Command Center KPIs (brief 5.3).
 *
 * <p>A separate route from the existing read endpoints on purpose: the KPI
 * cards refresh on their own cadence and a trend is a derived aggregate over
 * the whole log, not a page of rows.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final KpiTrendsService kpiTrendsService;
    private final ObjectProvider<ChartSeriesService> chartSeriesService;
    private final Clock clock;

    public DashboardController(KpiTrendsService kpiTrendsService,
                               ObjectProvider<ChartSeriesService> chartSeriesService,
                               Clock clock) {
        this.kpiTrendsService = kpiTrendsService;
        this.chartSeriesService = chartSeriesService;
        this.clock = clock;
    }

    /**
     * Current vs previous value for each KPI card, with the percentage delta and
     * the sparkline history behind each one.
     *
     * <p>The range is the header selector's token — {@code 7d}, {@code 30d},
     * {@code 90d}, {@code 1y} or {@code all} — and it moves every card, not only
     * the four charts: the same period the operator picked for the charts is the
     * period the KPIs are computed over. It defaults to {@code 7d}, the window
     * this endpoint was written against, so a client that predates the selector
     * keeps the behaviour it was written for.
     *
     * <p>An unknown token is a 400 rather than a silent fallback to the default:
     * a typo would otherwise show a week of data under a year's heading.
     */
    @GetMapping("/kpi-trends")
    public KpiTrendsResponse kpiTrends(@RequestParam(name = "range", defaultValue = "7d") String range) {
        return kpiTrendsService.trends(range);
    }

    /**
     * Chart data for one time range: daily volume, daily cash flow, the largest
     * balances and the saga breakdown.
     *
     * <p>Resolved through a provider so the in-memory profile — which has no
     * database to aggregate in — still starts and simply does not offer the route.
     */
    @GetMapping("/chart-series")
    public ChartSeries chartSeries(@RequestParam(name = "range", defaultValue = "7d") String range) {
        ChartSeriesService service = chartSeriesService.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Chart series need the durable store");
        }
        return service.series(range, clock.instant());
    }
}
