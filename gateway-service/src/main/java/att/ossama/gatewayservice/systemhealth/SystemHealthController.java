package att.ossama.gatewayservice.systemhealth;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One call that answers "is anything wrong right now" for the whole fleet.
 *
 * <h2>The management port, and why the offset is configuration</h2>
 * Step 2 moved Actuator off the application port, so a probe cannot use the port
 * Eureka registered — {@code instance.getPort()} is business traffic, and
 * {@code /actuator/health} there is a 404. The management port is
 * {@code applicationPort + offset}.
 *
 * <p>The offset is a property, not a constant, because {@code +10000} is only
 * true on the shared developer host. The Kubernetes manifest sets
 * {@code MGMT_PORT=8081} in every pod, which the offset cannot express. Two ways
 * to make that exact, both cheap when wanted:
 * <ul>
 *   <li>publish the real port as Eureka metadata —
 *       {@code eureka.instance.metadata-map.management-port=${MGMT_PORT}} — and
 *       this class already prefers it over the offset;</li>
 *   <li>or set {@code MGMT_PORT_OFFSET} per environment and keep one scheme.</li>
 * </ul>
 *
 * <h2>Blocking discovery on a reactive controller</h2>
 * {@code DiscoveryClient} here is the Netflix-backed implementation: a blocking
 * HTTP client. Calling it directly would block a Netty event-loop thread, and
 * with 8 services that is 8 blocking lookups per page refresh — the classic way
 * to make a reactive service behave like a slow threaded one. The lookups are
 * therefore moved to {@code boundedElastic}; only the probes themselves stay on
 * the reactive path.
 *
 * <h2>{@code details} is normally empty, on purpose</h2>
 * {@code management.endpoint.health.show-details=when-authorized} resolves to
 * "never" on every downstream service (no Spring Security on their classpath by
 * design, per AGENTS.md), so this aggregator receives {@code {"status":"UP"}}
 * and nothing else. That is the correct trade — those details carry the JDBC URL
 * and broker list — and the field is nullable so a caller can tell "no details
 * offered" from "healthy with nothing to report". Setting
 * {@code HEALTH_SHOW_DETAILS=always} on a management network that only the
 * gateway can reach is what turns the field on.
 */
@RestController
@RequestMapping("/api/admin")
public class SystemHealthController {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthController.class);

    /** Eureka metadata key a service may use to publish its true management port. */
    static final String MANAGEMENT_PORT_METADATA = "management.port";

    /**
     * The same key after relaxed binding. {@code metadata-map[management.port]}
     * preserves the dot, but a plain {@code metadata-map.management-port} binds a
     * key with a dash — same intent, different string. Both are read rather than
     * betting on one, because the failure mode of guessing wrong is a probe
     * against appPort + 10000 that returns a 404 and reads as "service DOWN".
     */
    static final String MANAGEMENT_PORT_METADATA_ALT = "management-port";

    private final DiscoveryClient discoveryClient;
    private final WebClient healthProbeWebClient;
    private final int managementPortOffset;

    public SystemHealthController(
            DiscoveryClient discoveryClient,
            WebClient healthProbeWebClient,
            @Value("${gateway.health.management-port-offset:10000}") int managementPortOffset) {
        this.discoveryClient = discoveryClient;
        this.healthProbeWebClient = healthProbeWebClient;
        this.managementPortOffset = managementPortOffset;
    }

    @GetMapping("/system-health")
    public Mono<SystemHealthReport> systemHealth() {
        return discoveredInstances()
                // flatMap, not concatMap: the probes are independent, so one slow
                // service must not queue the other seven behind it.
                .flatMap(this::probe)
                .sort(Comparator.comparing(ServiceHealth::name)
                        .thenComparingInt(ServiceHealth::appPort))
                .collectList()
                .map(services -> new SystemHealthReport(Instant.now(), overallStatus(services), services));
    }

    private Flux<ServiceInstance> discoveredInstances() {
        return Mono.fromCallable(() -> {
                    List<ServiceInstance> instances = new ArrayList<>();
                    for (String serviceId : discoveryClient.getServices()) {
                        instances.addAll(discoveryClient.getInstances(serviceId));
                    }
                    return instances;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * Never fails the aggregate. A probe that throws, times out or returns a
     * non-2xx becomes a DOWN row carrying the reason — one broken service must
     * not blank the page for the other seven.
     */
    private Mono<ServiceHealth> probe(ServiceInstance instance) {
        String serviceId = instance.getServiceId().toLowerCase(Locale.ROOT);
        // Both ports are reported, not just the one probed. Eureka registers the
        // APPLICATION port; the probe goes to the MANAGEMENT port. Showing only
        // one of them made every card look like it was probing the port it
        // advertised, which is exactly wrong and exactly what an operator would
        // assume while debugging a 404.
        String host = instance.getHost();
        int appPort = instance.getPort();
        int managementPort = managementPort(instance, managementPortOffset);
        String url = "http://" + host + ":" + managementPort + "/actuator/health";
        long startedAt = System.nanoTime();

        return healthProbeWebClient.get()
                .uri(url)
                .retrieve()
                // 503 is not an error here: it is the readiness endpoint saying
                // "do not send me traffic", and the body is still a status.
                .onStatus(status -> true, response -> Mono.empty())
                .bodyToMono(JsonNode.class)
                .timeout(java.time.Duration.ofSeconds(3))
                .map(body -> new ServiceHealth(
                        serviceId,
                        host,
                        appPort,
                        managementPort,
                        text(body, "status"),
                        elapsedMillis(startedAt),
                        body.has("components") ? body.get("components") : null,
                        null))
                .onErrorResume(error -> {
                    log.debug("SYSTEM_HEALTH: probe of {} failed: {}", url, error.toString());
                    return Mono.just(new ServiceHealth(
                            serviceId,
                            host,
                            appPort,
                            managementPort,
                            "DOWN",
                            elapsedMillis(startedAt),
                            null,
                            describe(error)));
                });
    }

    /**
     * Public and static because {@link CircuitBreakerController} resolves the
     * same port for the same instances: two copies of this rule would drift the
     * moment the metadata key or the offset changes, and the symptom would be one
     * panel silently probing a 404.
     */
    public static int managementPort(ServiceInstance instance, int managementPortOffset) {
        String published = instance.getMetadata().get(MANAGEMENT_PORT_METADATA);
        if (published == null || published.isBlank()) {
            published = instance.getMetadata().get(MANAGEMENT_PORT_METADATA_ALT);
        }
        if (published != null && !published.isBlank()) {
            try {
                return Integer.parseInt(published.trim());
            } catch (NumberFormatException ignored) {
                log.warn("SYSTEM_HEALTH: {} published {}={}, which is not a port; using the offset",
                        instance.getServiceId(), MANAGEMENT_PORT_METADATA, published);
            }
        }
        return instance.getPort() + managementPortOffset;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.round((System.nanoTime() - startedAtNanos) / 1_000_000.0);
    }

    private static String describe(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        return node == null || node.isNull() ? "UNKNOWN" : node.asText("UNKNOWN");
    }

    /**
     * The fleet's status is the worst status present.
     *
     * <p>LAGGING is carried through rather than folded into DOWN or UP: on
     * billing-service it means "the Kafka group is behind but the service is
     * serving", and the dashboard's yellow card is the whole reason the status
     * exists. An empty fleet is UNKNOWN, not UP — a gateway that can see no
     * services has not established that anything is healthy.
     */
    static String overallStatus(List<ServiceHealth> services) {
        if (services.isEmpty()) {
            return "UNKNOWN";
        }
        boolean down = services.stream().anyMatch(s -> "DOWN".equals(s.status()));
        if (down) {
            return "DOWN";
        }
        boolean lagging = services.stream().anyMatch(s -> "LAGGING".equals(s.status()));
        if (lagging) {
            return "LAGGING";
        }
        boolean up = services.stream().allMatch(s -> "UP".equals(s.status()));
        return up ? "UP" : "UNKNOWN";
    }

    /**
     * One row of the top row of cards.
     *
     * <p>{@code appPort} is what Eureka registered; {@code managementPort} is
     * where the probe actually went. Both are reported because they differ by
     * construction, and a card showing one number while probing another is how a
     * 404 on the wrong port gets diagnosed as a dead service.
     *
     * <p>{@code details} and {@code error} are mutually exclusive.
     */
    public record ServiceHealth(
            String name,
            String host,
            int appPort,
            int managementPort,
            String status,
            long latencyMs,
            JsonNode details,
            String error) {
    }

    public record SystemHealthReport(
            Instant timestamp,
            String overallStatus,
            List<ServiceHealth> services) {
    }
}
