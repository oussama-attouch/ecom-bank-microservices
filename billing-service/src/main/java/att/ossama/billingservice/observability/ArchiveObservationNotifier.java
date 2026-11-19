package att.ossama.billingservice.observability;

import att.ossama.billingservice.kafka.TransactionEvent;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
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

    /** Resilience4j instance name; see billing-service/src/main/resources/application.properties. */
    static final String INSTANCE = "ledgerLiveStream";

    /** Counter incremented for every notification the protection layers shed. */
    static final String SUPPRESSED_METRIC = "billing.live_stream.suppressed";

    private final boolean enabled;
    private final String ingestUrl;
    private final RestTemplate restTemplate;
    private final MeterRegistry meterRegistry;

    public ArchiveObservationNotifier(
            @Value("${billing.live-stream.enabled:false}") boolean enabled,
            @Value("${billing.live-stream.ledger-url:http://localhost:8085}") String ledgerUrl,
            MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.ingestUrl = ledgerUrl + INGEST_PATH;
        this.restTemplate = restTemplate();
        this.meterRegistry = meterRegistry;
    }

    /**
     * Tells the ledger that {@code event} was archived here. Returns immediately
     * when the flag is off, and never throws.
     *
     * <p><strong>The try/catch that used to be here is gone on purpose.</strong> A
     * method that swallows its own exceptions can never trip its circuit breaker:
     * the aspect sits outside the method body, so a caught-and-logged failure is,
     * from its point of view, a success. The swallowing moved into the fallback
     * below, where it is reached only after the breaker has actually seen the
     * failure. Same contract — nothing propagates to the Kafka listener — with
     * the protection now functional instead of decorative.
     */
    @Bulkhead(name = INSTANCE, fallbackMethod = "suppressed")
    @CircuitBreaker(name = INSTANCE, fallbackMethod = "suppressed")
    public void archived(TransactionEvent event) {
        if (!enabled || event == null) {
            return;
        }
        restTemplate.postForEntity(ingestUrl, new HttpEntity<>(observation(event), jsonHeaders()), Void.class);
    }

    /**
     * The degraded outcome: drop the notification, count it, say why.
     *
     * <p>No 503 here, and that is the whole point of this instance. A status code
     * would have to propagate to the Kafka listener, which would fail the record
     * and cause a redelivery of something that was already archived — an
     * observability failure turning into a data-path failure, which is the exact
     * inversion this class was written to avoid.
     */
    void suppressed(TransactionEvent event, Throwable cause) {
        meterRegistry.counter(SUPPRESSED_METRIC, "reason", reason(cause)).increment();
        log.warn("LIVE_STREAM_SUPPRESSED: could not report the archive of {} ({}: {})",
                event == null ? "?" : event.transactionId(),
                cause.getClass().getSimpleName(),
                cause.getMessage());
    }

    /** Low-cardinality reason tag: three values, never the exception message. */
    private static String reason(Throwable cause) {
        if (cause instanceof CallNotPermittedException) {
            return "circuit_open";
        }
        if (cause instanceof BulkheadFullException) {
            return "bulkhead_full";
        }
        return "call_failed";
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
