package att.ossama.gatewayservice.backoffice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The subset of a Keycloak user the backoffice cares about.
 *
 * <p>{@code ignoreUnknown} is deliberate rather than defensive habit: Keycloak
 * returns roughly forty fields per user, and this record is a projection of
 * them, not a model of them.
 *
 * <p>{@code enabled} is boxed so "the field was absent" is distinguishable from
 * {@code false} — Keycloak always sends it, but a primitive would quietly turn a
 * malformed response into "this account is disabled".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakUser(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        Boolean enabled) {
}
