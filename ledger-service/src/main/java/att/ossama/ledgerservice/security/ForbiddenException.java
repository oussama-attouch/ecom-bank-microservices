package att.ossama.ledgerservice.security;

/**
 * Raised when the caller is authenticated but not permitted to perform the
 * requested operation. Mapped to HTTP 403 by
 * {@code att.ossama.ledgerservice.web.ApiExceptionHandler}.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
