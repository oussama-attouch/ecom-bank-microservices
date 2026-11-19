package att.ossama.billingservice.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Consumer-group lag for the two groups this service owns, as a health signal.
 *
 * <p>Kafka is the one external dependency here that Spring Boot does not cover:
 * Actuator auto-configures {@code db}, {@code diskSpace}, {@code ping},
 * {@code livenessState} and {@code readinessState}, and none of them asks the
 * question an operator actually has about a consumer — "are we behind?". The
 * DLT group is the sharp end of that: {@code specs/001-dlt-consumer.md} chose a
 * separate group id specifically so its lag "becomes separately observable", and
 * until now "observable" meant running {@code kafka-consumer-groups} by hand.
 *
 * <h2>Why this does not block</h2>
 * A health indicator is invoked on the request thread, and this stack polls
 * {@code /actuator/health} from at least three directions. A blocking broker
 * round trip inside {@link #health()} would mean a Kafka outage turns healthy
 * endpoints slow, which is how a metrics/health surface becomes an outage of its
 * own. So {@code health()} only ever reads the last cached sample and asks for a
 * refresh — it never waits for one. The sampling thread is single, daemon, and
 * guarded by a compare-and-set, so overlapping polls collapse into one sweep.
 *
 * <h2>Why LAGGING is not DOWN</h2>
 * A backlog is not an outage: the service still archives, and the queue is
 * draining. Reporting {@code DOWN} would turn {@code /actuator/health} into a
 * 503 for a condition redundancy cannot fix, and — once step 2 adds probes — a
 * readiness check that pulls a pod out of rotation for a backlog it will drain on
 * its own. {@code LAGGING} is a distinct status, declared in
 * {@code management.endpoint.health.status.order} and mapped to HTTP 200 in
 * {@code http-mapping}, so it alerts without claiming the service is broken. The
 * same number is also published as a gauge ({@code kafka.consumer.group.lag}) so
 * Prometheus can alert on it without depending on the health endpoint at all.
 *
 * <p>Registered under the id {@code kafkaLag}, which means the standard Actuator
 * switch applies with no bespoke property:
 * {@code management.health.kafkaLag.enabled=false}.
 */
@Component
public class KafkaLagHealthIndicator implements HealthIndicator {

    /** Behind its threshold but still serving — deliberately not {@link Status#DOWN}. */
    static final Status LAGGING = new Status("LAGGING", "Consumer group is behind its lag threshold");

    private static final Logger log = LoggerFactory.getLogger(KafkaLagHealthIndicator.class);
    private static final String GAUGE_NAME = "kafka.consumer.group.lag";

    private final AdminClient adminClient;
    private final KafkaLagProperties properties;
    private final MeterRegistry meterRegistry;
    private final ExecutorService sampler;

    private final AtomicBoolean sampling = new AtomicBoolean();
    private final AtomicReference<Sample> lastSample = new AtomicReference<>(Sample.beforeFirstSample());
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private volatile boolean shutDown;

    KafkaLagHealthIndicator(AdminClient kafkaLagAdminClient,
                            KafkaLagProperties properties,
                            MeterRegistry meterRegistry) {
        this.adminClient = kafkaLagAdminClient;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.sampler = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kafka-lag-sampler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Fill the cache before anything asks. Without this the first poll after boot
     * would report {@code UNKNOWN} and start a sample it cannot wait for.
     */
    @EventListener(ApplicationReadyEvent.class)
    void sampleOnStartup() {
        requestSample();
    }

    @Override
    public Health health() {
        Sample sample = lastSample.get();
        long ageMillis = ageOf(sample);
        if (ageMillis < 0 || ageMillis > properties.cacheTtl().toMillis()) {
            requestSample();
        }
        return describe(sample, ageMillis);
    }

    /**
     * Starts a refresh if one is not already running. Never waits, never throws:
     * a saturated or shut-down sampler must not turn a health poll into a 500.
     */
    void requestSample() {
        if (shutDown || !sampling.compareAndSet(false, true)) {
            return;
        }
        try {
            sampler.execute(this::sampleSafely);
        } catch (RejectedExecutionException e) {
            sampling.set(false);
            log.debug("KAFKA_LAG: sampler rejected a run during shutdown; keeping the last sample");
        }
    }

    private void sampleSafely() {
        try {
            Sample sample = takeSample();
            lastSample.set(sample);
            log.debug("KAFKA_LAG: sampled {}", sample.groups());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastSample.set(Sample.failed("interrupted while sampling: " + e.getMessage()));
        } catch (RuntimeException e) {
            // One WARN per failed sweep, not per poll: the sample itself is cached
            // for cache-ttl, so a dead broker produces at most one line per TTL
            // instead of one per interested caller.
            log.warn("KAFKA_LAG: sample failed, reporting DOWN — {}", e.toString());
            lastSample.set(Sample.failed(e.toString()));
        } finally {
            sampling.set(false);
        }
    }

    private Sample takeSample() throws InterruptedException {
        Instant now = Instant.now();
        long timeoutMillis = properties.adminTimeout().toMillis();
        Map<String, GroupLag> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Long> configured : properties.groups().entrySet()) {
            groups.put(configured.getKey(), lagOf(configured.getKey(), configured.getValue(), timeoutMillis));
        }
        return new Sample(now, groups, null);
    }

    /**
     * Committed offset versus log-end offset, summed over the group's partitions.
     *
     * <p>Only partitions with a committed offset are listed. That is not a
     * limitation worth working around: a group can only be behind on partitions it
     * has consumed from, and both groups here run with
     * {@code auto-offset-reset=earliest}, so a partition with no commit is one the
     * consumer has not reached yet rather than one it is stuck on.
     */
    private GroupLag lagOf(String group, long maxLag, long timeoutMillis) throws InterruptedException {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = adminClient
                    .listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);

            if (committed == null || committed.isEmpty()) {
                return new GroupLag(0L, maxLag, false);
            }

            Map<TopicPartition, OffsetSpec> logEndRequests = new HashMap<>();
            for (TopicPartition partition : committed.keySet()) {
                logEndRequests.put(partition, OffsetSpec.latest());
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> logEnds = adminClient
                    .listOffsets(logEndRequests)
                    .all()
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);

            long lag = 0L;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> partition : committed.entrySet()) {
                ListOffsetsResult.ListOffsetsResultInfo logEnd = logEnds.get(partition.getKey());
                if (logEnd == null) {
                    continue;
                }
                // -1 is Kafka's "no committed offset". Clamping to 0 matches what
                // auto-offset-reset=earliest will actually do; treating it as the
                // full log length would alarm on every brand-new group.
                long committedOffset = Math.max(0L, partition.getValue().offset());
                lag += Math.max(0L, logEnd.offset() - committedOffset);
            }

            publishGauge(group, lag);
            return new GroupLag(lag, maxLag, true);

        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("group '" + group + "': " + cause, cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "group '" + group + "': no answer within " + timeoutMillis + "ms", e);
        }
    }

    /**
     * Same number, exposed as a Prometheus gauge, so alerting does not have to
     * scrape the health endpoint. Created on the first observation and kept alive
     * by the strong reference in {@link #gauges}, which is what Micrometer needs —
     * a garbage-collected holder silently stops being exported.
     */
    private void publishGauge(String group, long lag) {
        gauges.computeIfAbsent(group, name -> {
            AtomicLong holder = new AtomicLong(lag);
            AtomicLong registered = meterRegistry.gauge(GAUGE_NAME, Tags.of("group", name), holder);
            return registered == null ? holder : registered;
        }).set(lag);
    }

    private static long ageOf(Sample sample) {
        if (sample.sampledAt() == null) {
            return -1L;
        }
        return Duration.between(sample.sampledAt(), Instant.now()).toMillis();
    }

    private Health describe(Sample sample, long ageMillis) {
        if (sample.failure() != null) {
            return Health.down()
                    .withDetail("error", sample.failure())
                    .withDetail("sampledAt", sample.sampledAt() == null ? "never" : sample.sampledAt().toString())
                    .build();
        }
        if (sample.sampledAt() == null) {
            // No evidence yet, and saying UP would be an assertion nothing has
            // checked. UNKNOWN maps to HTTP 200, so it does not trip a probe.
            return Health.unknown()
                    .withDetail("sample", "not taken yet")
                    .build();
        }

        boolean lagging = false;
        Map<String, Object> groups = new LinkedHashMap<>();
        for (Map.Entry<String, GroupLag> entry : sample.groups().entrySet()) {
            GroupLag lag = entry.getValue();
            boolean behind = lag.hasCommittedOffsets() && lag.lag() > lag.maxLag();
            lagging |= behind;

            Map<String, Object> detail = new LinkedHashMap<>();
            if (lag.hasCommittedOffsets()) {
                detail.put("lag", lag.lag());
                detail.put("threshold", lag.maxLag());
                detail.put("state", behind ? "LAGGING" : "OK");
            } else {
                detail.put("lag", "none yet");
                detail.put("threshold", lag.maxLag());
                detail.put("state", "NO_COMMITTED_OFFSETS");
            }
            groups.put(entry.getKey(), detail);
        }

        Health.Builder builder = lagging ? Health.status(LAGGING) : Health.up();
        return builder
                .withDetail("groups", groups)
                .withDetail("sampledAt", sample.sampledAt().toString())
                .withDetail("sampleAgeMs", ageMillis)
                .build();
    }

    @PreDestroy
    void shutdown() {
        shutDown = true;
        sampler.shutdownNow();
    }

    /** One sweep over every configured group. A null {@code sampledAt} means "never sampled". */
    private record Sample(Instant sampledAt, Map<String, GroupLag> groups, String failure) {

        static Sample beforeFirstSample() {
            return new Sample(null, Map.of(), null);
        }

        /**
         * A failure still carries a timestamp: it is cached like any other sample,
         * so a dead broker is retried once per cache TTL rather than once per poll.
         */
        static Sample failed(String reason) {
            return new Sample(Instant.now(), Map.of(), reason);
        }
    }

    private record GroupLag(long lag, long maxLag, boolean hasCommittedOffsets) {
    }
}
