package att.ossama.billingservice.observability;

import att.ossama.billingservice.kafka.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports each archived transaction to ledger-service's live event stream, so the
 * Command Center can show that the <em>archiver</em> consumed a record and not
 * only that some consumer did.
 *
 * <p>This is the sixth event source of the live stream feature and the only one
 * that lives outside ledger-service. It is a plain outbound notification rather
 * than a Kafka round trip: the ledger already owns the only WebSocket server, so
 * the cheapest correct thing is one HTTP call to its ingest endpoint.
 *
 * <h2>It must never matter</h2>
 * Everything here is best-effort. The call is made <em>after</em> the archive has
 * been written and before the offset is acknowledged, so a failure here must not
 * turn into a redelivery of a record that was in fact archived. Every exception
 * is swallowed and logged; a slow or stopped ledger costs one bounded timeout and
 * nothing else. Observability that can break the thing it observes is worse than
 * none.
 *
 * <h2>Off by default, and inert when off</h2>
 * {@code billing.live-stream.enabled=false} unless set, so this service's
 * behaviour is unchanged for anyone not running the stream. Unlike the ledger's
 * gate, the flag here is a runtime check rather than a bean condition: the only
 * thing it guards is an outbound call, so a boolean is the whole cost, and one
 * call site does not justify the two-implementation arrangement the ledger uses
 * for its eight.
 */
@Component
public class ArchiveObservationNotifier {

    private static final Logger log = LoggerFactory.getLogger(ArchiveObservationNotifier.class);

    /** Path of ledger-service's ingest endpoint; see its LiveEventIngestController. */
    static final String INGEST_PATH = "/internal/live-events";

    /** Source and type as they appear on the ledger's stream. */
    static final String SOURCE = "billing-consumer";
    static final String TYPE = "KAFKA_CONSUMED";

    private final boolean enabled;
    private final String ingestUrl;
    private final RestTemplate restTemplate;

    public ArchiveObservationNotifier(
            @Value("${billing.live-stream.enabled:false}") boolean enabled,
            @Value("${billing.live-stream.ledger-url:http://localhost:8085}") String ledgerUrl) {
        this.enabled = enabled;
        this.ingestUrl = ledgerUrl + INGEST_PATH;
        this.restTemplate = restTemplate();
    }

    /**
     * Tells the ledger that {@code event} was archived here. Returns immediately
     * when the flag is off, and never throws.
     */
    public void archived(TransactionEvent event) {
        if (!enabled || event == null) {
            return;
        }
        try {
            restTemplate.postForEntity(ingestUrl, new HttpEntity<>(observation(event), jsonHeaders()), Void.class);
        } catch (Exception e) {
            log.warn("Could not report the archive of {} to the ledger live stream: {}",
                    event.transactionId(), e.getMessage());
        }
    }

    private static Observation observation(TransactionEvent event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("topic", "ledger-events");
        metadata.put("type", event.type());
        metadata.put("fromAccountId", event.fromAccountId());
        metadata.put("toAccountId", event.toAccountId());
        metadata.put("amount", event.amount());
        metadata.put("archivedBy", "billing-service");
        return new Observation(
                SOURCE,
                TYPE,
                "billing-service archived transaction " + event.transactionId(),
                event.transactionId(),
                metadata);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Timeouts are short on purpose. The default {@code RestTemplate} waits
     * indefinitely, which on this path would mean a Kafka listener blocked on a
     * stopped ledger until the container gave up on it.
     */
    private static RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(1_000);
        return new RestTemplate(factory);
    }

    /** Body shape of ledger-service's ingest endpoint. */
    record Observation(
            String source,
            String type,
            String description,
            String transactionId,
            Map<String, Object> metadata
    ) {
    }
}
