package att.ossama.gatewayservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit tests: no Spring context, no Mockito. The converter is the only
 * thing standing between "authenticated" and "authorized" in this service, so
 * it is worth pinning down without the overhead of a web test.
 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private static Jwt jwtWithRealmRoles(List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user-1")
                .claim("realm_access", Map.of("roles", roles))
                .build();
    }

    private List<String> authoritiesOf(Jwt jwt) {
        return converter.convert(jwt).stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    @Test
    void mapsRealmRolesToRolePrefixedAuthorities() {
        assertThat(authoritiesOf(jwtWithRealmRoles(List.of("TELLER", "MANAGER"))))
                .containsExactly("ROLE_TELLER", "ROLE_MANAGER");
    }

    @Test
    void upperCasesRoleNamesSoChecksCannotSilentlyStopMatching() {
        assertThat(authoritiesOf(jwtWithRealmRoles(List.of("manager", "Auditor"))))
                .containsExactly("ROLE_MANAGER", "ROLE_AUDITOR");
    }

    @Test
    void deDuplicatesAndDropsBlanks() {
        assertThat(authoritiesOf(jwtWithRealmRoles(List.of("MANAGER", "MANAGER", "  ", ""))))
                .containsExactly("ROLE_MANAGER");
    }

    @Test
    void returnsNoAuthoritiesWhenRealmAccessIsAbsent() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user-1")
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void returnsNoAuthoritiesWhenRolesFieldIsNotACollection() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", "MANAGER"))
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void doesNotMapScopesToRoles() {
        // Spring's default converter maps `scope`; this one must not, or a
        // Keycloak client scope called "MANAGER" would be mistaken for the role.
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("scope", "MANAGER AUDITOR")
                .claim("realm_access", Map.of("roles", List.of("TELLER")))
                .build();

        assertThat(authoritiesOf(jwt)).containsExactly("ROLE_TELLER");
    }
}
