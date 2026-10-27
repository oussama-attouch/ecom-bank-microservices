package att.ossama.gatewayservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Downstream identity headers; services treat these as trusted. */
    static final String USER_ID_HEADER = "X-User-Id";
    static final String USER_ROLES_HEADER = "X-User-Roles";

    /**
     * Path of ledger-service's live event stream, as routed here. Must match
     * {@code WebSocketConfig.ENDPOINT} in that service, since it is also what
     * decides where a query-string token is accepted (see
     * {@link #bearerTokenConverter()}).
     */
    static final String STREAM_PATH_SUFFIX = "/ws/events";

    /** Query parameter a WebSocket client has to use in place of a header. */
    static final String ACCESS_TOKEN_PARAM = "access_token";

    /** Role a backoffice caller must hold, and a writer of e-commerce data. */
    static final String MANAGER_ROLE = "MANAGER";

    /**
     * Paths whose <em>writes</em> are manager-only. Reads are unaffected, so
     * every signed-in operator still sees the customer, product and order
     * lists; only create/update/delete are restricted.
     */
    static final String[] ECOMMERCE_WRITE_PATHS = {"/customer-service/**", "/inventory-service/**"};

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/**", "/eureka/**").permitAll()
                // Backoffice: MANAGER only. The endpoints themselves are gated by
                // backoffice.users.enabled, so with that flag off a manager gets a
                // 404 (no handler) rather than a 403 — absent, not refused, the
                // same contract as ledger-service's projection rebuild route.
                .pathMatchers("/backoffice/**").hasRole(MANAGER_ROLE)
                // Ledger admin API: MANAGER only. The service refuses a non-manager
                // itself — CallerContext plus ForbiddenException — but that check
                // only runs after the request has been routed. This is the layer
                // that keeps a teller's request off a downstream service it should
                // never reach, and the service's own check is the layer that
                // survives a direct call to its port. Neither replaces the other.
                .pathMatchers("/ledger-service/api/admin/**").hasRole(MANAGER_ROLE)
                // E-commerce writes are a manager's job, not a teller's and not
                // an auditor's. Before this rule the gateway only asked whether
                // the caller was authenticated, so any signed-in user could
                // create or delete customers and products. PATCH is included
                // because Spring Data REST also exposes partial updates.
                .pathMatchers(HttpMethod.POST, ECOMMERCE_WRITE_PATHS).hasRole(MANAGER_ROLE)
                .pathMatchers(HttpMethod.PUT, ECOMMERCE_WRITE_PATHS).hasRole(MANAGER_ROLE)
                .pathMatchers(HttpMethod.PATCH, ECOMMERCE_WRITE_PATHS).hasRole(MANAGER_ROLE)
                .pathMatchers(HttpMethod.DELETE, ECOMMERCE_WRITE_PATHS).hasRole(MANAGER_ROLE)
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .bearerTokenConverter(bearerTokenConverter())
                .jwt(jwt -> jwt.jwtAuthenticationConverter(reactiveJwtAuthenticationConverter())))
            .addFilterAfter(headerInjectionFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
            .build();
    }

    /**
     * Supplies the authorities the rules above match on.
     *
     * <p>Spring Security's defaults give a Keycloak token no authorities — the
     * built-in converter reads {@code scope}, and these roles live under
     * {@code realm_access.roles} — so without this the chain authenticates
     * successfully and then denies every {@code hasRole} rule. The
     * {@link KeycloakRealmRoleConverter} does the extracting.
     *
     * <p>{@link JwtAuthenticationConverter} is a blocking
     * {@code Converter<Jwt, AbstractAuthenticationToken>}, which the reactive
     * chain cannot take directly;
     * {@link ReactiveJwtAuthenticationConverterAdapter} is the supported
     * bridge and does no I/O.
     */
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> reactiveJwtAuthenticationConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }

    /**
     * Reads the bearer token from the query string, but only on a WebSocket
     * handshake for the live event stream.
     *
     * <p>The browser WebSocket API cannot set request headers — {@code new
     * WebSocket(url, protocols)} takes a URL and a subprotocol list and nothing
     * else — so the stream cannot be authenticated the way every other call is,
     * with {@code Authorization: Bearer …}. The gateway has to accept the
     * alternative, because {@code .anyExchange().authenticated()} would otherwise
     * answer 401 to the handshake and leave the stream unreachable through the
     * gateway: working on port 8085 directly and broken at the edge.
     *
     * <p>Accepting the query parameter everywhere would be a needless widening —
     * a token in a URL ends up in access logs, browser history and referrers, and
     * every non-browser caller can already send the header. So it is honoured
     * only when the request is an {@code Upgrade: websocket} handshake for
     * {@link #STREAM_PATH_SUFFIX}; every other exchange goes through the standard
     * header-only converter, unchanged.
     */
    private ServerAuthenticationConverter bearerTokenConverter() {
        ServerBearerTokenAuthenticationConverter headerOnly = new ServerBearerTokenAuthenticationConverter();
        ServerBearerTokenAuthenticationConverter allowQueryParameter = new ServerBearerTokenAuthenticationConverter();
        allowQueryParameter.setAllowUriQueryParameter(true);
        return exchange -> isStreamHandshake(exchange)
                ? allowQueryParameter.convert(exchange)
                : headerOnly.convert(exchange);
    }

    private static boolean isStreamHandshake(ServerWebExchange exchange) {
        String upgrade = exchange.getRequest().getHeaders().getUpgrade();
        return "websocket".equalsIgnoreCase(upgrade)
                && exchange.getRequest().getPath().value().endsWith(STREAM_PATH_SUFFIX);
    }

    /**
     * Replaces the caller-supplied identity headers with values derived from the
     * validated JWT.
     *
     * <p>Order of operations matters and is the whole point of this filter:
     * <ol>
     *   <li>the two headers are always dropped from the request that is handed
     *       downstream, so a forged value can never reach a service — including
     *       on the fallback path below;</li>
     *   <li>they are then added back from the authenticated token.</li>
     * </ol>
     * Downstream services make authorization decisions from these headers, so
     * this must fail closed: forwarding the exchange untouched when the
     * principal cannot be resolved hands the caller control of its own roles.
     *
     * <p>A third rewrite happens here for the same reason — controlling what
     * reaches a downstream service — but about the credential rather than the
     * identity: the query-string access token that a WebSocket handshake is
     * allowed to use (see {@link #bearerTokenConverter()}) is removed from the
     * forwarded URL. By this point it has been validated, no downstream service
     * reads it, and a JWT left in a URL is a JWT in the next service's access
     * log.
     *
     * <p>Two things about the previous implementation are worth recording,
     * because both were silent:
     * <ul>
     *   <li>it resolved the principal with {@code exchange.getPrincipal()},
     *       which is empty at this point in the WebFlux Security chain. The
     *       resulting {@code defaultIfEmpty(exchange)} meant the filter never
     *       rewrote anything and never failed — forged headers simply passed
     *       through. The authentication is now read from the reactive
     *       {@link SecurityContext}, which is populated by the
     *       {@code ReactorContextWebFilter}/{@code AuthenticationWebFilter}
     *       upstream of this filter.</li>
     *   <li>it mutated headers through
     *       {@code mutate().request(b -> b.headers(h -> h.remove(...)))}. That
     *       consumer receives a read-only {@code HttpHeaders} and throws
     *       {@code UnsupportedOperationException}, so the branch could never
     *       have worked. A {@link ServerHttpRequestDecorator} replaces the
     *       header map instead of mutating it in place.</li>
     * </ul>
     */
    private WebFilter headerInjectionFilter() {
        return (exchange, chain) -> ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(JwtAuthenticationToken.class::isInstance)
            .cast(JwtAuthenticationToken.class)
            .map(auth -> withIdentityHeaders(exchange, auth))
            .defaultIfEmpty(stripIdentityHeaders(exchange))
            .flatMap(chain::filter);
    }

    /** Drops both identity headers, keeping every other header as sent. */
    private ServerWebExchange stripIdentityHeaders(ServerWebExchange exchange) {
        return replaceIdentityHeaders(exchange, null, null);
    }

    /** Drops both identity headers and re-adds them from the token. */
    private ServerWebExchange withIdentityHeaders(ServerWebExchange exchange, JwtAuthenticationToken auth) {
        Jwt jwt = auth.getToken();

        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        @SuppressWarnings("unchecked")
        List<String> roles = (realmAccess == null) ? null : (List<String>) realmAccess.get("roles");

        return replaceIdentityHeaders(exchange, jwt.getSubject(), String.join(",", roles == null ? List.of() : roles));
    }

    private ServerWebExchange replaceIdentityHeaders(ServerWebExchange exchange,
                                                     String userId,
                                                     String roles) {
        ServerHttpRequest decorated = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.remove(USER_ID_HEADER);
                headers.remove(USER_ROLES_HEADER);
                if (userId != null) {
                    headers.add(USER_ID_HEADER, userId);
                }
                if (roles != null) {
                    headers.add(USER_ROLES_HEADER, roles);
                }
                return headers;
            }

            /** The credential has done its job by the time anything is forwarded. */
            @Override
            public URI getURI() {
                return withoutAccessToken(super.getURI());
            }
        };
        return exchange.mutate().request(decorated).build();
    }

    /**
     * Returns {@code uri} with {@link #ACCESS_TOKEN_PARAM} removed, or the same
     * URI when it never had one — which is every request except a stream
     * handshake, so the common path allocates nothing.
     */
    private static URI withoutAccessToken(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || !query.contains(ACCESS_TOKEN_PARAM)) {
            return uri;
        }
        return UriComponentsBuilder.fromUri(uri)
                .replaceQueryParam(ACCESS_TOKEN_PARAM)
                .build(true)
                .toUri();
    }
}
