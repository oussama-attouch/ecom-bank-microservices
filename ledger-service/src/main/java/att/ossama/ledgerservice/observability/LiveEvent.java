package att.ossama.ledgerservice.observability;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One instrumented event on the live stream.
 *
 * <p>Deliberately flat and transport-agnostic: this is what goes on the wire (a
 * JSON array of these makes up one STOMP message body) and it is also what every
 * call site in this service builds, so the shape the browser parses and the shape
 * the instrumentation produces cannot drift into two different things.
 *
 * <h2>{@code timestamp}</h2>
 * The event's own instant, not the moment it was observed. A saga step carries
 * the instant its {@link att.ossama.ledgerservice.saga.SagaTimeline} assigned to
 * it and an appended event carries the instant it was appended at, so a backdated
 * seed replays on its historical timeline instead of stamping two years of
 * reconstructed history "now". The two Kafka sources have no domain instant to
 * hand and use the wall clock.
 *
 * <h2>{@code metadata}</h2>
 * Source-specific detail — log offset, aggregate id, step status, partition. Free
 * form on purpose: this is an observation channel, and it has to be able to grow
 * a field without a schema negotiation at every consumer.
 *
 * <h2>Correlation</h2>
 * {@code transactionId} is what ties the sources together: a transfer saga, the
 * two events it appends, the record it publishes to Kafka and the same record
 * coming back off the topic all carry the same id, so a client can group the
 * stream by it. It is null where there is genuinely nothing to correlate with —
 * an {@code ACCOUNT_CREATED} append has no transaction.
 */
public record LiveEvent(
        Instant timestamp,
        String source,
        String type,
        String description,
        String transactionId,
        Map<String, Object> metadata
) {

    /** Where an event came from, as it appears in {@link #source()}. */
    public static final String SOURCE_SAGA = "saga";
    public static final String SOURCE_EVENT_STORE = "event-store";
    public static final String SOURCE_KAFKA_PUBLISHER = "kafka-publisher";
    public static final String SOURCE_KAFKA_CONSUMER = "kafka-consumer";

    /**
     * billing-service's own consumption of the same topic, reported over
     * {@link LiveEventIngestController}. Distinct from
     * {@link #SOURCE_KAFKA_CONSUMER} on purpose: "a consumer read this" and
     * "the archiver read this" fail for different reasons, and a single source
     * would make the stream unable to say which of them a gap belongs to.
     */
    public static final String SOURCE_BILLING_CONSUMER = "billing-consumer";

    /** What happened, as it appears in {@link #type()}. */
    public static final String TYPE_SAGA_STEP = "SAGA_STEP";
    public static final String TYPE_SAGA_FINISHED = "SAGA_FINISHED";
    public static final String TYPE_EVENT_APPENDED = "EVENT_APPENDED";
    public static final String TYPE_KAFKA_PUBLISHED = "KAFKA_PUBLISHED";
    public static final String TYPE_KAFKA_CONSUMED = "KAFKA_CONSUMED";

    /**
     * Copies the metadata into an insertion-ordered, unmodifiable map, so a
     * caller that hands over its own map and then keeps mutating it cannot
     * rewrite an event that has already been queued for broadcast.
     */
    public LiveEvent {
        metadata = (metadata == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * Builds metadata from alternating key/value pairs, so a call site can state
     * an event's detail in one expression:
     *
     * <pre>{@code
     * LiveEvent.metadataOf("offset", offset, "aggregateId", accountId)
     * }</pre>
     *
     * <p>Pairs whose value is {@code null} are dropped rather than serialized as
     * JSON null: a field that does not apply to this event should be absent from
     * the payload, not present and empty, so a consumer can tell "no log offset
     * for this step" from "the offset is unknown".
     *
     * @throws IllegalArgumentException if given an odd number of arguments or a
     *                                  non-{@code String} key.
     */
    public static Map<String, Object> metadataOf(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Map.of();
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "metadata needs alternating key/value pairs, got " + keyValues.length + " argument(s)");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (!(keyValues[i] instanceof String key)) {
                throw new IllegalArgumentException("metadata key at index " + i + " is not a String");
            }
            Object value = keyValues[i + 1];
            if (value != null) {
                metadata.put(key, value);
            }
        }
        return metadata;
    }
}
