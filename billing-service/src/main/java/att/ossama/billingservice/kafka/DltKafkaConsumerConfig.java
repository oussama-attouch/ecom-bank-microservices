package att.ossama.billingservice.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer wiring for the dead-letter topic, kept in its own class so the main
 * listener's factory, error handler and {@code DeadLetterPublishingRecoverer}
 * in {@link KafkaConsumerConfig} are untouched.
 */
@Configuration
public class DltKafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * Deliberately a different property from {@code spring.kafka.consumer.group-id}:
     * the DLT offsets must not mix into the archive consumer's commit history,
     * so a DLT stall cannot slow archive consumption. The literal default keeps
     * the group correct even if the property is absent.
     */
    @Value("${spring.kafka.consumer.dlt-group-id:billing-service-dlt}")
    private String dltGroupId;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> dltListenerContainerFactory(MeterRegistry meterRegistry) {
        // Built inline rather than as a @Bean on purpose: a second
        // ConsumerFactory bean would make the main kafkaListenerContainerFactory's
        // ConsumerFactory injection ambiguous and break the context.
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, dltGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Same isolation level as the main consumer. Safe here: the KafkaTemplate
        // behind DeadLetterPublishingRecoverer has no transactional id, so DLT
        // records are non-transactional and immediately visible to read_committed.
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, String> dltConsumerFactory = new DefaultKafkaConsumerFactory<>(props);
        // Metrics for the DLT group specifically. Without this the poison-message
        // consumer is the one part of the stack with no throughput or error
        // numbers at all — and it is the part an operator asks about. Attaching
        // the listener does not touch the error handler below: no recoverer, no
        // retry budget, no republish path.
        dltConsumerFactory.addListener(new MicrometerConsumerListener<>(meterRegistry));

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(dltConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // The DLT listener gets the same observation as the archive listener.
        // The record it reads was republished by DeadLetterPublishingRecoverer
        // from inside the failed consume, so its headers still carry that
        // consume's trace context: this container joins the original trace and
        // the poison message stays correlated with the request that produced it,
        // rather than becoming an orphan log line. Boot's configurer would have
        // set this for a factory it built; this one is hand-built, so it is set
        // here (same reason as KafkaConsumerConfig).
        factory.getContainerProperties().setObservationEnabled(true);

        // Zero retries, and structurally no recoverer. A recoverer is the only
        // component that appends ".DLT" to a topic name, so passing none here is
        // what makes "ledger-events.DLT.DLT" impossible regardless of how the
        // retry policy is tuned later. A record that fails to persist is logged
        // and skipped; the original stays on ledger-events.DLT as the durable
        // source, so nothing is unrecoverable.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0L)));
        return factory;
    }
}
