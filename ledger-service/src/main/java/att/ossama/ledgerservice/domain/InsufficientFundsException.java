package att.ossama.ledgerservice.domain;

/**
 * Raised when an operation would take an account below zero.
 *
 * <p>A business-rule violation rather than an authorization decision (see
 * {@code att.ossama.ledgerservice.security.ForbiddenException}), so it lives
 * with the domain. Mapped to HTTP 400 by
 * {@code att.ossama.ledgerservice.web.ApiExceptionHandler}.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
