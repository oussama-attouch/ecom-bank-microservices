package att.ossama.billingservice.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
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
    public ConcurrentKafkaListenerContainerFactory<String, String> dltListenerContainerFactory() {
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
        ConsumerFactory<String, String> dltConsumerFactory = new DefaultKafkaConsumerFactory<>(props);

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(dltConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

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
