package att.ossama.ledgerservice.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams live events to STOMP subscribers on {@value #DESTINATION}.
 *
 * <p>Registered only when {@code ledger.live-stream.enabled=true}, which is what
 * makes the flag mean "no beans, no thread" rather than "beans that do nothing".
 *
 * <h2>Batching</h2>
 * Events are queued and flushed as one message every
 * {@value #FLUSH_INTERVAL_MS}ms rather than sent one message per event. The
 * stream is driven by the ledger's own write path, and that path is bursty in a
 * way no browser wants to receive: a portfolio seed reconstructs tens of
 * thousands of appends in a few seconds, and one frame per append would spend
 * more time in the client's frame decoder than in the ledger's own work. The
 * batching is server-side on purpose — it is the only place that can coalesce
 * before the frames cross the socket, and a client that had to collapse them
 * would already have paid for receiving them.
 *
 * <p>The message body is always a JSON array, even for a single event, so a
 * client has one shape to parse rather than a shape that changes with the
 * traffic. Array order is publication order.
 *
 * <h2>Loss, and why it is acceptable here</h2>
 * This is a tail, not a log. The queue is bounded, and a full queue drops the
 * newest event and counts it; a batch produced with no subscriber connected is
 * drained and discarded rather than serialized for an empty room (which is what
 * keeps a seed with nobody watching cheap). The durable record of everything
 * broadcast here is the event store itself — this stream exists to show what is
 * happening now, not to be the thing anyone recovers from.
 */
@Component
@ConditionalOnProperty(name = "ledger.live-stream.enabled", havingValue = "true")
public class StompLiveEventBroadcaster implements LiveEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(StompLiveEventBroadcaster.class);

    /** Where a client subscribes. One topic; {@code source} distinguishes origins. */
    public static final String DESTINATION = "/topic/events";

    private static final long FLUSH_INTERVAL_MS = 100;

    /**
     * Depth of the pending queue. Sized for a burst an order of magnitude larger
     * than a 100ms window of the busiest path in this service, so it is reached
     * only when the flush thread has genuinely fallen behind and not merely when
     * a seed is running.
     */
    private static final int MAX_PENDING = 20_000;

    /** Most events in one frame. Keeps a single message bounded during a seed. */
    private static final int MAX_BATCH = 1_000;

    /**
     * Dates are written as ISO-8601 rather than epoch numbers, and the Java time
     * module is registered explicitly rather than discovered: this mapper is the
     * only thing standing between a record with an {@link java.time.Instant} in
     * it and a client, so whether it can serialize that type should not depend on
     * what a builder decided to look for on the classpath.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final SimpMessagingTemplate messaging;
    private final BlockingQueue<LiveEvent> pending = new LinkedBlockingQueue<>(MAX_PENDING);
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong dropped = new AtomicLong();

    private ScheduledExecutorService flusher;

    public StompLiveEventBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @PostConstruct
    void startFlushing() {
        flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "live-event-flush");
            // Daemon: a shutdown must never wait on an observability thread.
            thread.setDaemon(true);
            return thread;
        });
        flusher.scheduleAtFixedRate(this::flushQuietly, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopFlushing() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        log.info("Live event stream enabled: STOMP endpoint {} (no SockJS), destination {}, batched every {}ms",
                WebSocketConfig.ENDPOINT, DESTINATION, FLUSH_INTERVAL_MS);
    }

    /**
     * Non-blocking by contract. A caller on the saga path must not wait for a
     * queue, a socket or a subscriber.
     */
    @Override
    public void publish(LiveEvent event) {
        if (event == null) {
            return;
        }
        if (!pending.offer(event)) {
            long total = dropped.incrementAndGet();
            if (total == 1 || total % 1_000 == 0) {
                log.warn("Live event stream is behind: dropped {} event(s) (queue full at {})", total, MAX_PENDING);
            }
        }
    }

    /**
     * Subscriber tracking, so a batch with nobody listening is discarded instead
     * of serialized. Session ids come off the STOMP frames themselves because
     * this is the only reliable count available: the handshake carries no
     * principal yet (see the security note on {@link WebSocketConfig}), so there
     * is no user registry to ask.
     */
    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        String sessionId = sessionIdOf(event.getMessage().getHeaders());
        if (sessionId != null && sessions.add(sessionId)) {
            log.info("Live event stream client connected: {} ({} session(s) now subscribed)",
                    sessionId, sessions.size());
        }
    }

    @EventListener
    public void onSessionDisconnected(SessionDisconnectEvent event) {
        if (event.getSessionId() != null && sessions.remove(event.getSessionId())) {
            log.info("Live event stream client disconnected: {} ({} session(s) left)",
                    event.getSessionId(), sessions.size());
        }
    }

    /** Drains at most one batch and sends it, swallowing anything that goes wrong. */
    private void flushQuietly() {
        try {
            flush();
        } catch (Exception e) {
            // Must never escape: an uncaught exception in a ScheduledExecutorService
            // task cancels the schedule, which would silently end the stream.
            log.warn("Live event flush failed: {}", e.getMessage());
        }
    }

    private void flush() throws JsonProcessingException {
        if (pending.isEmpty()) {
            return;
        }
        List<LiveEvent> batch = new ArrayList<>(Math.min(MAX_BATCH, pending.size()));
        pending.drainTo(batch, MAX_BATCH);
        if (sessions.isEmpty()) {
            // Drained and dropped on purpose. Serializing a seed for nobody is
            // pure overhead, and these events are still in the event store.
            return;
        }
        messaging.convertAndSend(DESTINATION, JSON.writeValueAsString(batch));
    }

    private static String sessionIdOf(MessageHeaders headers) {
        return SimpMessageHeaderAccessor.getSessionId(headers);
    }
}
