package att.ossama.gatewayservice.backoffice;

/**
 * A non-2xx answer from the Keycloak Admin API, carrying the upstream status so
 * the controller can map it to the right client-facing status instead of
 * collapsing every upstream failure into a 500.
 *
 * <p>The message includes a truncated response body: Keycloak's error bodies are
 * short and diagnostic ({@code {"error":"HTTP 403 Forbidden"}}), and they are
 * usually the only clue that the realm import did not grant the service-account
 * roles. It never contains the client secret or an access token, because neither
 * is ever put in a response body.
 */
public class KeycloakAdminException extends RuntimeException {

    private static final int MAX_BODY_CHARS = 300;

    private final int status;

    public KeycloakAdminException(int status, String body) {
        super("Keycloak Admin API returned " + status
                + (body == null || body.isBlank() ? "" : ": " + truncate(body)));
        this.status = status;
    }

    /** The HTTP status Keycloak answered with. */
    public int status() {
        return status;
    }

    private static String truncate(String body) {
        String singleLine = body.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_BODY_CHARS
                ? singleLine
                : singleLine.substring(0, MAX_BODY_CHARS) + "…";
    }
}
