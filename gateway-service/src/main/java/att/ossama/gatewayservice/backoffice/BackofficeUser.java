package att.ossama.gatewayservice.backoffice;

import java.util.List;

/**
 * A user as the backoffice sees them.
 *
 * <p>{@code roles} is filtered to the three realm roles the feature manages.
 * Keycloak also holds {@code default-roles-ecom-bank}, {@code offline_access} and
 * {@code uma_authorization} on every user; showing them would both confuse the
 * role editor and imply they are editable, which they are not.
 */
public record BackofficeUser(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        List<String> roles) {
}
