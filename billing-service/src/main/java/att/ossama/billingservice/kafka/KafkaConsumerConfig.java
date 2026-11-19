package att.ossama.billingservice.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:billing-service}")
    private String groupId;

    /**
     * Boot's {@code KafkaMetricsAutoConfiguration} contributes
     * {@code DefaultKafkaConsumerFactoryCustomizer} beans, and
     * {@code KafkaAutoConfiguration} applies them to the factory <em>it</em>
     * creates. This factory is hand-built, so that auto-configuration backs off
     * (it is {@code @ConditionalOnMissingBean}) and the customizer never runs:
     * without the listener below, {@code kafka.consumer.*} meters would simply
     * not exist, with nothing to notice. The same applies to the producer factory
     * and to {@link DltKafkaConsumerConfig}.
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory(MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Read only committed (transactional) records.
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // Manual ack after successful archive.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // Same hole as the factories, one layer up: Boot's
        // ConcurrentKafkaListenerContainerFactoryConfigurer applies
        // spring.kafka.listener.observation-enabled to the factory IT builds,
        // and this one is hand-built, so that property is inert here. Enabling
        // observation on the ContainerProperties is what makes the container
        // open a CONSUMER span, extract the W3C trace context from the record
        // headers, and put its traceId in the MDC for the whole listener call.
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory(MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        // See consumerFactory above: hand-built factories get no metrics unless
        // the listener is attached explicitly.
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        // Observation is what writes the W3C `traceparent` header onto a record.
        // Without it a consumer has no parent to attach to and starts a brand-new
        // trace, which is exactly the "async flow is untraceable" problem this
        // milestone closes. Boot would set this from
        // spring.kafka.template.observation-enabled only for the template its
        // KafkaAutoConfiguration builds; this one is hand-built, so the switch is
        // here. The template resolves the ObservationRegistry itself from the
        // application context once all singletons are instantiated.
        //
        // This template is also the DeadLetterPublishingRecoverer's producer, so
        // a poison message republished to ledger-events.DLT inherits the trace of
        // the consume that rejected it — the DLT row and its cause share one id.
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
