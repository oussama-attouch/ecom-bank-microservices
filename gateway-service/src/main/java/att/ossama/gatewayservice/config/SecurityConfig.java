package att.ossama.gatewayservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Downstream identity headers; services treat these as trusted. */
    static final String USER_ID_HEADER = "X-User-Id";
    static final String USER_ROLES_HEADER = "X-User-Roles";

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/**", "/eureka/**").permitAll()
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
            .addFilterAfter(headerInjectionFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
            .build();
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
        };
        return exchange.mutate().request(decorated).build();
    }
}
