package att.ossama.ledgerservice.dashboard;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Times every Command Center request and hands the measurement to
 * {@link SlaMetricsService}.
 *
 * <p>A filter rather than an aspect so that a route added to the dashboard later
 * is measured without anyone remembering to annotate it, and registered against
 * the dashboard's path prefix alone so the ledger's ordinary read endpoints are
 * not dragged in — the KPI is about the analytics product, not the service.
 *
 * <p>Registered innermost (see {@code DashboardSlaConfiguration}), so the number
 * is the time this service spent handling the request. That is the part that can
 * actually regress — a KPI aggregate that stops using its index shows up here —
 * and it deliberately excludes upstream proxy and authentication time, which
 * this service cannot influence.
 */
public class DashboardSlaFilter extends OncePerRequestFilter {

    private final SlaMetricsService metrics;

    public DashboardSlaFilter(SlaMetricsService metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // In a finally block so a request that failed is still counted: a
            // 500 that took four seconds is a worse SLA breach than a 200 that
            // took six hundred milliseconds, and dropping it would let the card
            // read healthy while the endpoint is falling over.
            metrics.record(request.getRequestURI(), Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }
}
