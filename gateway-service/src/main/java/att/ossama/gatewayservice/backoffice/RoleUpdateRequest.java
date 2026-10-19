package att.ossama.gatewayservice.backoffice;

import java.util.List;

/**
 * Body of {@code PUT /backoffice/users/{id}/roles}: the <em>complete</em> set of
 * roles the user should end up with.
 *
 * <p>A full set rather than an assign/revoke delta, so the call is idempotent
 * under retry and the contract is "this user ends in exactly this state".
 */
public record RoleUpdateRequest(List<String> roles) {
}
