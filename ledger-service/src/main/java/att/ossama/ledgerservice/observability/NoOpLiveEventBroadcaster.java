package att.ossama.ledgerservice.observability;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The broadcaster that is present when the live stream is off: it accepts every
 * event and does nothing with it.
 *
 * <p>Exists so that {@link LiveEventBroadcaster} is never an optional dependency.
 * The alternative — injecting with {@code @Autowired(required = false)} and
 * null-checking at each of the eight instrumented call sites — puts the same
 * conditional in eight places and makes the disabled path something each caller
 * has to remember.
 *
 * <h2>Why the condition is stated rather than "missing bean"</h2>
 * {@code @ConditionalOnMissingBean} is only evaluated against bean definitions
 * registered before the one being considered, which makes it reliable in an
 * auto-configuration and merely conventional in an application's own component
 * scan — nothing a reader of this class can see decides whether
 * {@link StompLiveEventBroadcaster} was registered first. The two conditions are
 * exact complements on the same key instead, so exactly one broadcaster exists
 * whichever way the flag is set, and the ordering question does not arise.
 *
 * <p>Which value this bean matches matters. It is registered when the key is
 * absent or exactly {@code "false"}; {@code "true"} hands over to the STOMP
 * implementation; any <em>other</em> value matches neither, so the context fails
 * at startup on an unsatisfied {@link LiveEventBroadcaster} dependency. That is
 * the intended failure: a typo in the flag is loud, not a service that comes up
 * healthy with the stream silently missing.
 */
@Component
@ConditionalOnProperty(name = "ledger.live-stream.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpLiveEventBroadcaster implements LiveEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(NoOpLiveEventBroadcaster.class);

    /**
     * States the gate positively in the startup log. Silence would be ambiguous —
     * it is also what a misconfigured endpoint or a failed scan looks like — and
     * the whole feature is meant to be verifiable from the log of a running
     * service.
     */
    @PostConstruct
    void announceDisabled() {
        log.info("Live event stream disabled: ledger.live-stream.enabled is not true, "
                + "so /ws/events and the ledger-events observation consumer are absent");
    }

    @Override
    public void publish(LiveEvent event) {
        // Deliberately empty. With the gate off the stream costs one no-op call
        // and no allocation beyond the LiveEvent the caller already built.
    }
}
