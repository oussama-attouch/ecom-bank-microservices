package att.ossama.billingservice.observability;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Wiring for {@link KafkaLagHealthIndicator}: one shared {@link AdminClient} and
 * the properties it reads.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaLagProperties.class)
public class KafkaLagConfiguration {

    /**
     * One long-lived client, not one per health call.
     *
     * <p>The health endpoint is polled by at least three callers in this stack —
     * a Kubernetes probe, Prometheus, and {@code ServiceHealthChecker} every five
     * minutes — so constructing a client per call would be a connection storm
     * against the broker. {@code AdminClient} is thread-safe and pooled
     * internally, which is exactly the lifetime this needs.
     *
     * <p>{@code AdminClient.create} does not open a connection, so an unreachable
     * broker fails the <em>sample</em> rather than the <em>context</em>. That
     * matters concretely: every service here has a context-load smoke test that
     * runs with no Kafka, and a health indicator that breaks startup would take
     * the test suite with it.
     */
    @Bean(destroyMethod = "close")
    public AdminClient kafkaLagAdminClient(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            KafkaLagProperties properties) {

        long timeoutMs = properties.adminTimeout().toMillis();

        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(AdminClientConfig.CLIENT_ID_CONFIG, "billing-service-kafka-lag-health");
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeoutMs);
        // Must not be shorter than the wait the sandboxed caller performs, or the
        // client times out "successfully" and the sample reports a broker failure
        // that never happened.
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) Math.max(timeoutMs, 1_000L));
        // A probe wants a fresh answer or a fast failure, not a retry tail. The
        // next sample is one cache TTL away, so retrying here only makes the
        // reported state older.
        config.put(AdminClientConfig.RETRIES_CONFIG, 1);
        return AdminClient.create(config);
    }
}
