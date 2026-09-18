package att.ossama.ledgerservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Copies the gateway-injected identity headers into the request-scoped
 * {@link CallerContext}.
 *
 * <p>This is a plain servlet filter, not a Spring Security filter chain:
 * ledger-service intentionally does not depend on spring-boot-starter-security.
 * Authentication happens at the gateway; this service only reads the result.
 *
 * <p>If both headers are absent the context is left empty rather than the
 * request being rejected, which keeps direct-port development and the existing
 * curl-based checks working. An empty context has no roles, so role-gated rules
 * simply do not apply.
 *
 * <p>Registered through component scanning (Spring Boot auto-registers any
 * {@code Filter} bean). The explicit {@code @Order} is deliberate: it keeps
 * this filter behind Spring Boot's {@code OrderedRequestContextFilter}
 * ({@code LOWEST_PRECEDENCE - 105}), which is what binds the thread-local
 * request attributes that the request-scoped {@link CallerContext} needs.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class HeaderAuthFilter extends OncePerRequestFilter {

    /** Authenticated subject (Keycloak {@code sub}), injected by the gateway. */
    public static final String USER_ID_HEADER = "X-User-Id";

    /** Realm roles, comma separated, injected by the gateway. */
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    private final CallerContext callerContext;

    public HeaderAuthFilter(CallerContext callerContext) {
        this.callerContext = callerContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        callerContext.setUserId(trimToNull(request.getHeader(USER_ID_HEADER)));
        callerContext.setRoles(parseRoles(request.getHeader(USER_ROLES_HEADER)));
        filterChain.doFilter(request, response);
    }

    /**
     * Roles are upper-cased on the way in so role checks cannot silently stop
     * matching if the realm is ever reconfigured with different capitalisation.
     */
    private Set<String> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .forEach(parsed::add);
        return parsed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
