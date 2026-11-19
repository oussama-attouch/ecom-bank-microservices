package att.ossama.ledgerservice.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Scheduled watchdog that pings every service's /actuator/health endpoint every
 * five minutes and logs a warning when any service reports unhealthy or is
 * unreachable. This catches slow in-memory / connection-pool degradation before
 * it surfaces as 500s on data endpoints.
 *
 * <p>The ports below are the <em>management</em> ports, not the application
 * ports. Since specs/005-observability-resilience.md step 2 each service listens
 * for /actuator/** on its own listener (app port + 10000 by default), so polling
 * 8081–8085 here would get a 404 from all eight services and report the whole
 * stack as DOWN — a watchdog that fails open is worse than none, because it
 * trains its reader to ignore it.
 */
@Component
public class ServiceHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(ServiceHealthChecker.class);

    private final RestTemplate restTemplate;
    private final List<Target> targets = new ArrayList<>();

    public ServiceHealthChecker(RestTemplateBuilder builder,
                                @Value("${ledger.health-check.base-url:http://localhost}") String baseUrl) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();

        targets.add(new Target("discovery-service", baseUrl, 18761));
        targets.add(new Target("config-service", baseUrl, 19999));
        targets.add(new Target("gateway-service", baseUrl, 18888));
        targets.add(new Target("customer-service", baseUrl, 18081));
        targets.add(new Target("inventory-service", baseUrl, 18082));
        targets.add(new Target("billing-service", baseUrl, 18083));
        targets.add(new Target("order-service", baseUrl, 18084));
        targets.add(new Target("ledger-service", baseUrl, 18085));
    }

    @Scheduled(fixedRateString = "${ledger.health-check.interval-ms:300000}", initialDelayString = "${ledger.health-check.initial-delay-ms:30000}")
    public void checkAllServices() {
        int unhealthy = 0;
        for (Target t : targets) {
            boolean up = isUp(t);
            if (!up) {
                unhealthy++;
                log.warn("[health-check] {} is UNHEALTHY ({}:{}/actuator/health)", t.name, t.baseUrl, t.port);
            }
        }
        if (unhealthy == 0) {
            log.info("[health-check] all {} services are UP", targets.size());
        } else {
            log.warn("[health-check] {} of {} services are unhealthy", unhealthy, targets.size());
        }
    }

    private boolean isUp(Target t) {
        try {
            String url = t.baseUrl + ":" + t.port + "/actuator/health";
            @SuppressWarnings("unchecked")
            var body = restTemplate.getForObject(url, java.util.Map.class);
            if (body == null) return false;
            Object status = body.get("status");
            return "UP".equalsIgnoreCase(String.valueOf(status));
        } catch (Exception e) {
            return false;
        }
    }

    private record Target(String name, String baseUrl, int port) {}
}
