package att.ossama.gatewayservice.backoffice;

/**
 * Body of {@code PUT /backoffice/users/{id}/enabled}.
 *
 * <p>{@code Boolean} rather than {@code boolean}: an absent field must be a 400,
 * not a silent "disable this account".
 */
public record EnabledUpdateRequest(Boolean enabled) {
}
