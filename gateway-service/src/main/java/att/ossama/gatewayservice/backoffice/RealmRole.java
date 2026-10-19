package att.ossama.gatewayservice.backoffice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A realm role as Keycloak represents it.
 *
 * <p>Both fields are needed when assigning or revoking: the
 * {@code role-mappings/realm} endpoints accept a list of role representations
 * and Keycloak matches them by {@code id}, so sending only the name is accepted
 * by some versions and silently ignored by others.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RealmRole(String id, String name) {
}
