package att.ossama.billingservice.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for {@link KafkaLagHealthIndicator}.
 *
 * <p>Bound from {@code billing.kafka-lag.*}. The {@code groups} map is the whole
 * selector: its <em>keys</em> are the consumer groups to sample and its
 * <em>values</em> are the lag, in messages, above which that group is reported as
 * {@code LAGGING}. Sampling a group means listing its committed offsets, so an
 * unlisted group costs nothing.
 *
 * <p>Defaults exist so the indicator is meaningful without any configuration at
 * all — a monitoring feature that silently does nothing when a property file is
 * incomplete is worse than one that reports a slightly wrong threshold. The two
 * defaults are deliberately different: the archive group is a pipeline where
 * several hundred messages in flight is normal throughput, while
 * {@code billing-service-dlt} should be empty, because every message on it is a
 * poison message an operator has to read.
 */
@ConfigurationProperties(prefix = "billing.kafka-lag")
public record KafkaLagProperties(Map<String, Long> groups, Duration cacheTtl, Duration adminTimeout) {

    private static final Map<String, Long> DEFAULT_GROUPS = Map.of(
            "billing-service", 500L,
            "billing-service-dlt", 0L);

    public KafkaLagProperties {
        // LinkedHashMap copy rather than Map.copyOf: preserves the order the
        // groups were declared in, so the health JSON reads the same way twice.
        groups = (groups == null || groups.isEmpty())
                ? Collections.unmodifiableMap(new LinkedHashMap<>(DEFAULT_GROUPS))
                : Collections.unmodifiableMap(new LinkedHashMap<>(groups));
        cacheTtl = (cacheTtl == null || cacheTtl.isNegative()) ? Duration.ofSeconds(30) : cacheTtl;
        adminTimeout = (adminTimeout == null || adminTimeout.isNegative()) ? Duration.ofSeconds(2) : adminTimeout;
    }
}
