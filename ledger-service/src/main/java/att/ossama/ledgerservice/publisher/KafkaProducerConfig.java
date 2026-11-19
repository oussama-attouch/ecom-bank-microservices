package att.ossama.ledgerservice.publisher;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "ledger.publisher", havingValue = "kafka")
public class KafkaProducerConfig {

    @Value("${ledger.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * Boot's {@code KafkaMetricsAutoConfiguration} only customises the factories
     * {@code KafkaAutoConfiguration} builds; this one is hand-built, so its
     * {@code kafka.producer.*} meters would not exist without the listener
     * attached below. Only active under {@code ledger.publisher=kafka}, which is
     * also the only configuration in which the saga publishes through it.
     */
    @Bean
    public ProducerFactory<String, String> producerFactory(MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Exactly-once (idempotent) producer
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        // Bound total retry time so a Kafka outage fails fast for the saga to compensate.
        // request.timeout.ms must be <= delivery.timeout.ms for the idempotent producer.
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 8000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        // Observation is what writes the W3C `traceparent` header onto the
        // record; billing-service's consumer then joins this trace instead of
        // starting a second one, which is the Gateway → Kafka → billing path this
        // milestone exists to make traceable. Boot's
        // spring.kafka.template.observation-enabled only reaches the KafkaTemplate
        // KafkaAutoConfiguration builds, and this one is hand-built, so the switch
        // belongs here. KafkaTemplate resolves the ObservationRegistry from the
        // application context in afterSingletonsInstantiated().
        //
        // The outbox relay is a different case and is not covered by this
        // comment: a row published by OutboxPublisher starts a new root trace,
        // because the original request's trace context is not persisted in
        // outbox_events. See specs/006-distributed-tracing-log-aggregation.md.
        template.setObservationEnabled(true);
        return template;
    }
}
