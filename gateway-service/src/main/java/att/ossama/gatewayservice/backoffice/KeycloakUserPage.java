package att.ossama.gatewayservice.backoffice;

import java.util.List;

/**
 * One page of users plus the total Keycloak reports for the query.
 *
 * <p>{@code total} comes from the {@code X-Total-Count} header, not from
 * {@code users.size()}: a page is a window, and a caller that renders "showing
 * 20 of 214" cannot recover the second number from the first.
 */
public record KeycloakUserPage(List<KeycloakUser> users, long total) {
}
