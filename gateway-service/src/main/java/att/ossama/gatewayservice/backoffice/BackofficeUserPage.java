package att.ossama.gatewayservice.backoffice;

import java.util.List;

/**
 * One page of backoffice users, echoing the window that produced it.
 *
 * <p>{@code total} is the number of matches in Keycloak, not {@code users.size()}
 * — an operator paging through a search needs to know how much is behind the
 * page they are looking at.
 */
public record BackofficeUserPage(long total, int first, int max, List<BackofficeUser> users) {
}
