package att.ossama.gatewayservice.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns Keycloak's {@code realm_access.roles} claim into Spring Security
 * authorities, so the gateway can express authorization rules with
 * {@code hasRole(...)}.
 *
 * <p>Without this the gateway authenticates and authorizes nothing: every
 * request that carries a valid token is allowed to do everything, and the
 * {@code TELLER}/{@code MANAGER}/{@code AUDITOR} distinction exists only in
 * Keycloak and in each service's own header checks. Spring's default
 * {@code JwtGrantedAuthoritiesConverter} does not help here — it reads
 * {@code scope}/{@code scp}, and Keycloak puts these roles under
 * {@code realm_access.roles}, so a default configuration yields a token with
 * zero authorities and every {@code hasRole} check would deny.
 *
 * <p>Roles are upper-cased on the way in for the same reason
 * {@code ledger-service}'s {@code HeaderAuthFilter} does it: a role check must
 * not silently stop matching if the realm is ever reconfigured with different
 * capitalisation. The {@code ROLE_} prefix is what {@code hasRole("MANAGER")}
 * expects.
 *
 * <p>Client roles ({@code resource_access}) are deliberately not mapped. The
 * realm's only authorization model is the three realm roles, and mapping client
 * roles as well would put {@code ROLE_} authorities in play that no rule in this
 * service defines the meaning of.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** Claim Keycloak stores realm roles in. */
    static final String REALM_ACCESS_CLAIM = "realm_access";

    /** Field within that claim that holds the role names. */
    static final String ROLES_FIELD = "roles";

    /** Prefix {@code hasRole(...)} expects on the authority. */
    static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (jwt == null) {
            return List.of();
        }
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null) {
            return List.of();
        }
        Object rawRoles = realmAccess.get(ROLES_FIELD);
        if (!(rawRoles instanceof Collection<?> roles)) {
            return List.of();
        }

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (Object role : roles) {
            if (role == null) {
                continue;
            }
            String name = role.toString().trim();
            if (name.isEmpty()) {
                continue;
            }
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + name.toUpperCase(Locale.ROOT)));
        }
        // Ordered, de-duplicated, immutable: a token's authorities should not
        // depend on iteration order or be mutable by anything downstream.
        return List.copyOf(authorities);
    }
}
