package att.ossama.gatewayservice.systemhealth;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Every circuit breaker in the fleet, wherever it lives.
 *
 * <h2>Why this does not just read the local registry</h2>
 * {@code CircuitBreakerRegistry} is per-JVM. The gateway's own registry is
 * empty — it has no annotated call sites (see the note in its {@code pom.xml}:
 * the annotations do not work on a reactive {@code Mono}) — while the breakers
 * that actually matter are in {@code order-service}
 * ({@code orderEnrichment}) and {@code billing-service}
 * ({@code ledgerLiveStream}). An endpoint that returned only the local registry
 * would answer "nothing is open" forever, which is worse than no endpoint: it is
 * a green light that was never connected to anything.
 *
 * <p>So the local registry is reported <em>and</em> each service's
 * {@code /actuator/circuitbreakers} is read, then the two are merged. A service
 * that does not expose the endpoint (404) is skipped rather than shown as
 * broken, because exposing it is per-service configuration
 * ({@code management.endpoints.web.exposure.include}).
 */
@RestController
@RequestMapping("/api/admin")
public class CircuitBreakerController {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerController.class);

    private final DiscoveryClient discoveryClient;
    private final WebClient healthProbeWebClient;
    private final CircuitBreakerRegistry localRegistry;
    private final int managementPortOffset;

    public CircuitBreakerController(
            DiscoveryClient discoveryClient,
            WebClient healthProbeWebClient,
            CircuitBreakerRegistry localRegistry,
            @Value("${gateway.health.management-port-offset:10000}") int managementPortOffset) {
        this.discoveryClient = discoveryClient;
        this.healthProbeWebClient = healthProbeWebClient;
        this.localRegistry = localRegistry;
        this.managementPortOffset = managementPortOffset;
    }

    @GetMapping("/circuit-breakers")
    public Mono<CircuitBreakersReport> circuitBreakers() {
        Mono<List<CircuitBreakerView>> local = Mono.fromCallable(this::localBreakers)
                .subscribeOn(Schedulers.boundedElastic());

        Mono<List<CircuitBreakerView>> remote = Mono.fromCallable(() -> {
                    List<ServiceInstance> instances = new ArrayList<>();
                    for (String serviceId : discoveryClient.getServices()) {
                        instances.addAll(discoveryClient.getInstances(serviceId));
                    }
                    return instances;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .flatMap(this::remoteBreakers)
                .flatMapIterable(list -> list)
                .collectList();

        return Mono.zip(local, remote)
                .map(both -> {
                    List<CircuitBreakerView> all = new ArrayList<>(both.getT1());
                    all.addAll(both.getT2());
                    all.sort(Comparator.comparing(CircuitBreakerView::service)
                            .thenComparing(CircuitBreakerView::name));
                    return new CircuitBreakersReport(Instant.now(), all);
                });
    }

    /** Breakers this JVM owns. Empty in the gateway today; correct to report anyway. */
    private List<CircuitBreakerView> localBreakers() {
        return localRegistry.getAllCircuitBreakers().stream()
                .map(breaker -> toView("gateway-service", breaker))
                .toList();
    }

    private CircuitBreakerView toView(String service, CircuitBreaker breaker) {
        return new CircuitBreakerView(
                service,
                breaker.getName(),
                breaker.getState().name(),
                rate(breaker.getMetrics().getFailureRate()),
                rate(breaker.getMetrics().getSlowCallRate()),
                breaker.getMetrics().getNumberOfBufferedCalls(),
                breaker.getMetrics().getNumberOfFailedCalls(),
                breaker.getMetrics().getNumberOfNotPermittedCalls());
    }

    /** Resilience4j reports -1 for "no calls recorded"; null keeps that distinct from 0%. */
    private static Double rate(float value) {
        return value < 0 ? null : (double) value;
    }

    private Mono<List<CircuitBreakerView>> remoteBreakers(ServiceInstance instance) {
        String service = instance.getServiceId().toLowerCase(Locale.ROOT);
        String url = "http://" + instance.getHost() + ":"
                + SystemHealthController.managementPort(instance, managementPortOffset)
                + "/actuator/circuitbreakers";

        return healthProbeWebClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(3))
                .map(body -> parse(service, body))
                .onErrorResume(error -> {
                    // Expected for any service that does not expose the endpoint.
                    log.debug("CIRCUIT_BREAKERS: {} not readable: {}", url, error.toString());
                    return Mono.just(List.of());
                });
    }

    private static List<CircuitBreakerView> parse(String service, JsonNode body) {
        JsonNode breakers = body == null ? null : body.get("circuitBreakers");
        if (breakers == null || !breakers.isObject()) {
            return List.of();
        }
        List<CircuitBreakerView> views = new ArrayList<>();
        breakers.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            views.add(new CircuitBreakerView(
                    service,
                    entry.getKey(),
                    text(value, "state"),
                    percentage(value, "failureRate"),
                    percentage(value, "slowCallRate"),
                    number(value, "bufferedCalls"),
                    number(value, "failedCalls"),
                    number(value, "notPermittedCalls")));
        });
        return views;
    }

    /**
     * Resilience4j publishes these as strings — {@code "-1.0%"} when no calls
     * have been recorded yet. Negative means "no measurement", which is not the
     * same as 0% and is normalised to null so the table can render a dash instead
     * of implying the breaker is failing nothing.
     */
    private static Double percentage(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.asText().replace("%", "").trim();
        try {
            double parsed = Double.parseDouble(raw);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? 0L : value.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "UNKNOWN" : value.asText("UNKNOWN");
    }

    /** One row of the middle table. {@code failureRate} is null until the breaker has calls. */
    public record CircuitBreakerView(
            String service,
            String name,
            String state,
            Double failureRate,
            Double slowCallRate,
            long bufferedCalls,
            long failedCalls,
            long notPermittedCalls) {
    }

    public record CircuitBreakersReport(Instant timestamp, List<CircuitBreakerView> circuitBreakers) {
    }
}
