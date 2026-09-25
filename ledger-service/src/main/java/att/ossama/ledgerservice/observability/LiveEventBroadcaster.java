package att.ossama.ledgerservice.observability;

/**
 * Sink for the live event stream.
 *
 * <p>Instrumented call sites depend on this interface and nothing else, and one
 * implementation of it is <em>always</em> in the context:
 * {@link StompLiveEventBroadcaster} when {@code ledger.live-stream.enabled=true},
 * {@link NoOpLiveEventBroadcaster} otherwise. That is the whole point of the
 * interface — a caller publishes unconditionally, with no
 * {@code @Autowired(required = false)} and no null check, and "the feature is
 * off" is expressed once, by which bean exists, instead of at every call site.
 *
 * <p>Implementations must not throw and must not block. Every call arrives from a
 * money path — the saga, the event store, the publisher — and an observability
 * feature that can fail a transfer, or add a network round trip to one, is worse
 * than no observability at all. The contract is therefore "hand the event over
 * and return"; delivery is best-effort by design.
 */
public interface LiveEventBroadcaster {

    /**
     * Hands one event to the stream. Returns immediately; may drop the event if
     * the stream is behind, and does nothing at all when the feature is off.
     *
     * @param event the event to publish; {@code null} is ignored rather than
     *              rejected, so instrumentation can never be the thing that
     *              breaks a caller.
     */
    void publish(LiveEvent event);
}
