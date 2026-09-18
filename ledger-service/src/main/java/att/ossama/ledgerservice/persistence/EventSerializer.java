package att.ossama.ledgerservice.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * JSON <-> object conversion for event payloads.
 *
 * Uses the Spring-managed {@link ObjectMapper} so the JavaTime module (Instant)
 * and the shared Jackson configuration are applied consistently.
 */
@Component
public class EventSerializer {

    private final ObjectMapper objectMapper;

    public EventSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Serialise a value to a JSON string; wraps any failure in a RuntimeException. */
    public String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }

    /** Deserialise a JSON string to the requested type; wraps any failure in a RuntimeException. */
    public <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize event payload", e);
        }
    }
}
