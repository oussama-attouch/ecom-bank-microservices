package att.ossama.ledgerservice.dashboard;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link DashboardSlaFilter} against the Command Center's routes.
 *
 * <p>A {@link FilterRegistrationBean} rather than {@code @Component} so the
 * filter is scoped to {@code /api/dashboard/*} by URL pattern instead of
 * inspecting the path on every request in the application, and so its position
 * in the chain is stated rather than inherited from bean ordering.
 */
@Configuration
public class DashboardSlaConfiguration {

    @Bean
    public FilterRegistrationBean<DashboardSlaFilter> dashboardSlaFilter(SlaMetricsService metrics) {
        FilterRegistrationBean<DashboardSlaFilter> registration =
                new FilterRegistrationBean<>(new DashboardSlaFilter(metrics));
        registration.addUrlPatterns("/api/dashboard/*");
        // Innermost of the chain: measures the controller and its queries, which
        // is the work this service is responsible for.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setName("dashboardSlaFilter");
        return registration;
    }
}
