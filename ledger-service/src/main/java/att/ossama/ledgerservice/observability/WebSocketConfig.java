package att.ossama.ledgerservice.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Publishes the live event stream at {@code ws://<host>:8085/ws/events} as a
 * STOMP endpoint.
 *
 * <p>Registered only when {@code ledger.live-stream.enabled=true}. The condition
 * is on the configuration class rather than on a method because
 * {@code @EnableWebSocketMessageBroker} is an {@code @Import}: when the condition
 * fails the class is skipped during configuration parsing and its import is never
 * processed, so no {@code WebSocketHandlerMapping} is registered at all and the
 * path falls through to the normal handler mappings. A request to
 * {@code /ws/events} then gets a plain <b>404</b> — the endpoint is absent rather
 * than refused, which is the same shape {@code ProjectionAdminController} uses
 * for its gate.
 *
 * <h2>Why STOMP on a raw WebSocket, and not raw frames</h2>
 * A raw WebSocket would mean inventing a message envelope, a subscription
 * protocol and a reconnect story by hand, and the browser side would be a
 * {@code JSON.parse} inside a hand-rolled client. STOMP is a few lines on both
 * sides, gives the client a destination to subscribe to, and lets this service
 * publish into the broker without knowing how many clients are attached or what
 * they are interested in. SockJS is deliberately <em>not</em> enabled: it exists
 * to emulate WebSockets on transports that no longer matter here, and it would
 * put a second HTTP path in front of the same endpoint.
 *
 * <h2>One topic, not one per source</h2>
 * Everything goes to {@value StompLiveEventBroadcaster#DESTINATION} and the
 * payload's {@code source} field says where it came from. Per-source topics would
 * push the fan-out decision onto the client — which would then have to subscribe
 * to four destinations to reconstruct the order of one transfer — and would make
 * adding a fifth source a frontend change.
 *
 * <h2>Security</h2>
 * There is no authentication on this path. The existing {@code HeaderAuthFilter}
 * does not get in the way (see below), but it also does not help: nothing here
 * validates a JWT or a role, so any client that can reach port 8085 can read the
 * ledger's live activity. That is acceptable only because the endpoint is
 * off by default and this is a local development stack; it is not acceptable past
 * this session.
 *
 * <p>On the filter chain specifically: the WebSocket <em>handshake</em> is an
 * ordinary HTTP {@code GET} with an {@code Upgrade} header, so it does pass
 * through the servlet filters — {@code HeaderAuthFilter} runs, copies
 * {@code X-User-Id}/{@code X-User-Roles} into the request-scoped
 * {@code CallerContext}, and always continues the chain. It never rejects, so it
 * cannot break the upgrade. What it cannot do is authenticate the session that
 * follows: once the connection is upgraded, STOMP {@code SUBSCRIBE} and
 * {@code SEND} frames travel over the WebSocket and do not re-enter the servlet
 * chain, so the handshake's request-scoped {@code CallerContext} is gone by then.
 * Anything that needs the caller's identity on this endpoint has to read it from
 * the handshake itself and carry it on the session.
 */
@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(name = "ledger.live-stream.enabled", havingValue = "true")
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Handshake URL, as it appears after {@code ws://host:8085}. */
    public static final String ENDPOINT = "/ws/events";

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // No .withSockJS(): every client for this endpoint speaks STOMP over a
        // real WebSocket.
        //
        // TODO(security): the handshake is unauthenticated by design in this
        //  session — no JWT is validated and no principal is attached to the
        //  session. Authentication belongs on the next hop, where the gateway
        //  routes /ws/events and can validate the token before the upgrade (and
        //  where the browser can be told to carry it), rather than being bolted
        //  onto this service now and reworked then.
        //
        // setAllowedOriginPatterns("*") is a development allowance: it lets a
        // page served from any origin open a socket to this port. It is pointless
        // to restrict here while the endpoint is unauthenticated and directly
        // reachable, and it would have to be replaced by the real origin list at
        // the same time as the token check above.
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // The in-memory simple broker: this service both produces and brokers the
        // stream, and there is no external relay to configure. Prefix is the
        // conventional "/topic" for one-to-many destinations; there is no
        // application destination prefix because nothing here consumes client
        // messages, and leaving one configured would advertise a path that has no
        // handler.
        registry.enableSimpleBroker("/topic");
    }
}
