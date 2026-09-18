package att.ossama.ledgerservice.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Identity of the caller for the current request.
 *
 * <p>Populated by {@link HeaderAuthFilter} from the {@code X-User-Id} and
 * {@code X-User-Roles} headers that the API gateway injects after validating
 * the JWT. The gateway strips any client-supplied values for those headers, so
 * they are trusted on this hop.
 *
 * <p>{@code @RequestScope} is proxied ({@code TARGET_CLASS}), so this bean can
 * be injected into singletons such as {@link TransferLimitPolicy} and still
 * resolve to the instance belonging to the in-flight request.
 */
@Component
@RequestScope
public class CallerContext {

    private String userId;

    /** Realm roles of the caller; never null, so callers can iterate safely. */
    private Set<String> roles = new LinkedHashSet<>();

    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }

    public boolean hasAnyRole(String... candidates) {
        if (candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (hasRole(candidate)) {
                return true;
            }
        }
        return false;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Set<String> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public void setRoles(Set<String> roles) {
        this.roles = (roles == null) ? new LinkedHashSet<>() : new LinkedHashSet<>(roles);
    }
}
