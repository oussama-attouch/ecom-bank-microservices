package att.ossama.gatewayservice.backoffice;

/**
 * The only error shape this feature returns: one human-readable message.
 *
 * <p>Deliberately not Keycloak's error body. That body is logged, not forwarded:
 * it names internals (realm, client, sometimes a stack trace) and the operator
 * reading the UI needs "the identity provider rejected the administrator", not a
 * verbatim upstream payload.
 */
public record ApiError(String error) {
}
