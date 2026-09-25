package att.ossama.ledgerservice.web;

import att.ossama.ledgerservice.domain.InsufficientFundsException;
import att.ossama.ledgerservice.security.ForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates domain exceptions into HTTP responses.
 *
 * <p>Without this, an uncaught {@code RuntimeException} would surface as a 500:
 * neither {@link ForbiddenException} nor {@link InsufficientFundsException} is
 * caught by the controllers, so both would fall through to the container's
 * default error handling. The body keeps the {@code {"error": "..."}} shape the
 * controllers already return, so the caller gets the reason rather than a
 * generic failure page.
 *
 * <p>{@link IllegalArgumentException} is the one generic mapping here: an
 * argument the caller got wrong is a bad request by definition, and the services
 * that validate an input token raise it with the offending value in the message.
 * The alternative — letting it reach the container — answers 500 to a client
 * whose query string was simply misspelled.
 *
 * <p>{@link HttpMessageNotReadableException} is handled for the same reason, and
 * it is the one case where falling through to the container was actively
 * misleading. A body that is empty or is not valid JSON never reaches a
 * controller, so no {@code {"error": ...}} body is produced; Spring Boot answers
 * from its own error endpoint instead, and that body carries no {@code message}
 * unless {@code server.error.include-message} is set to {@code always}. The
 * caller saw a bare {@code 400 Bad Request} with no field, no reason and nothing
 * to act on — from a request whose only fault was a shell variable that expanded
 * to nothing. Naming the fault costs one handler.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody(e, "Forbidden"));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(e, "Insufficient funds"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(e, "Bad request"));
    }

    /**
     * A request body that could not be read — absent, truncated, or not JSON.
     *
     * <p>The message is deliberately fixed rather than taken from the exception:
     * Jackson's own text names internal types and expected tokens, which is
     * noise to a caller who needs to know that the body did not arrive intact.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Request body is missing or is not valid JSON"));
    }

    private Map<String, String> errorBody(Exception e, String fallback) {
        String reason = (e.getMessage() == null) ? fallback : e.getMessage();
        return Map.of("error", reason);
    }
}
