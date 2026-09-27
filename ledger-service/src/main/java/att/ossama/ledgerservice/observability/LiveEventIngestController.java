package att.ossama.ledgerservice.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Takes in observations that happen in <em>other</em> services and puts them on
 * this service's live stream.
 *
 * <p>Four of the five sources are instrumented in-process and publish straight to
 * {@link LiveEventBroadcaster}. The sixth — billing-service consuming the same
 * {@code ledger-events} records it archives — happens in a different JVM, and the
 * stream has exactly one server. Rather than give billing its own WebSocket
 * client, or route an observability message through Kafka and back, billing makes
 * one best-effort HTTP call here and this endpoint republishes it. The service
 * that owns the stream stays the only place that knows how to broadcast.
 *
 * <p>Gated with everything else behind {@code ledger.live-stream.enabled}: with
 * the flag off this bean is never created, so the path has no handler mapping and
 * answers <b>404</b> — which is also the answer a misconfigured billing-service
 * gets, and is the point. The call is a notification, not a ledger operation:
 * nothing about the ledger's state depends on it arriving.
 *
 * <p><b>Untrusted input, deliberately narrow.</b> The body can name its own
 * {@code source} and {@code type} and is published as given, so this endpoint is
 * an injection point into the stream for anyone who can reach port 8085. That is
 * acceptable only while the stream itself is unauthenticated local development
 * (see the security note on {@link WebSocketConfig}) — the endpoint exposes
 * nothing the stream does not already expose, and it cannot reach the ledger,
 * the journal or the saga. It must be authenticated, or dropped, at the same time
 * the stream is.
 */
@RestController
@RequestMapping(LiveEventIngestController.PATH)
@ConditionalOnProperty(name = "ledger.live-stream.enabled", havingValue = "true")
public class LiveEventIngestController {

    /** Internal path; not part of the public API surface of this service. */
    public static final String PATH = "/internal/live-events";

    private final LiveEventBroadcaster broadcaster;

    public LiveEventIngestController(LiveEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * Publishes one observed event and returns immediately — the caller is on its
     * own critical path (a Kafka listener, mid-consume) and must not be made to
     * wait for the stream.
     *
     * <p>202 rather than 200: the event has been accepted for broadcast, and
     * whether anyone was connected to receive it is not this call's business. A
     * caller that wanted delivery confirmation would be asking the wrong question
     * of a live tail.
     *
     * <p>A blank {@code source} or {@code type} is rejected as a 400 by
     * {@code ApiExceptionHandler}: those two fields are what every consumer
     * filters on, and an event that cannot be attributed is worse than no event.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(@RequestBody ObservedEvent observed) {
        if (observed.source() == null || observed.source().isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        if (observed.type() == null || observed.type().isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        broadcaster.publish(new LiveEvent(
                // Stamped here, not by the caller: the stream's clock is this
                // service's, so events from several reporters stay comparable.
                Instant.now(),
                observed.source(),
                observed.type(),
                observed.description(),
                observed.transactionId(),
                observed.metadata() == null ? Map.of() : observed.metadata()));
    }

    /**
     * Wire shape of the ingest body — the fields of a {@link LiveEvent} that a
     * remote observer can legitimately supply. The timestamp is absent on purpose;
     * see {@link #ingest}.
     */
    public record ObservedEvent(
            String source,
            String type,
            String description,
            String transactionId,
            Map<String, Object> metadata
    ) {
    }
}
