package att.ossama.gatewayservice.backoffice;

/**
 * A refusal this service makes on its own — a bad request body (400) or a guard
 * against an operator locking themselves, or the last manager, out (409).
 *
 * <p>Distinct from {@link KeycloakAdminException}, which reports something the
 * identity provider said; this one means the request never needed to reach it.
 */
public class BackofficeException extends RuntimeException {

    private final int status;

    public BackofficeException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP status to answer with. */
    public int status() {
        return status;
    }
}
