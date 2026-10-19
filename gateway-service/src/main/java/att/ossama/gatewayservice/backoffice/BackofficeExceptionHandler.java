package att.ossama.gatewayservice.backoffice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.concurrent.TimeoutException;

/**
 * Maps failures of the backoffice API onto the status codes the spec promises,
 * so a caller never sees a five-hundred-and-a-Keycloak-payload.
 *
 * <p>Scoped with {@code assignableTypes}: this advice must not touch anything the
 * gateway does for other services, where an exception belongs to that route's
 * own error handling.
 *
 * <p>The upstream body is logged, never forwarded. It names realm internals and
 * occasionally a stack trace; the browser gets one sentence.
 */
@RestControllerAdvice(assignableTypes = BackofficeUserController.class)
@ConditionalOnProperty(name = "backoffice.users.enabled", havingValue = "true")
public class BackofficeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BackofficeExceptionHandler.class);

    private final String clientId;

    public BackofficeExceptionHandler(
            @Value("${backoffice.keycloak.client-id:ecom-backoffice}") String clientId) {
        this.clientId = clientId;
    }

    /** A refusal or validation failure this service decided on. */
    @ExceptionHandler(BackofficeException.class)
    public ResponseEntity<ApiError> handleBackoffice(BackofficeException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.getMessage()));
    }

    @ExceptionHandler(KeycloakAdminException.class)
    public ResponseEntity<ApiError> handleKeycloak(KeycloakAdminException e) {
        if (e.status() == 404) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("unknown user"));
        }
        if (e.status() == 401 || e.status() == 403) {
            // The overwhelmingly likely cause: the realm was imported without the
            // service account's realm-management roles, so the client
            // authenticates and is then refused. Say so, once, where an operator
            // will look.
            log.error("Backoffice admin call as client '{}' was rejected by Keycloak ({}); "
                            + "check that keycloak/realm-export.json grants view-users, query-users, "
                            + "manage-users and view-realm to the ecom-backoffice service account "
                            + "(view-realm is what roles/{{role}}/users requires)",
                    clientId, e.status());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ApiError("identity provider rejected the administrator"));
        }
        log.error("Backoffice admin call as client '{}' failed with status {}: {}",
                clientId, e.status(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("identity provider unavailable"));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ApiError> handleTimeout(TimeoutException e) {
        log.error("Backoffice admin call as client '{}' timed out", clientId);
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ApiError("identity provider unavailable"));
    }

    @ExceptionHandler(WebClientRequestException.class)
    public ResponseEntity<ApiError> handleUnreachable(WebClientRequestException e) {
        // Log the reason, not the request: the request carries the token.
        log.error("Backoffice admin call as client '{}' could not reach Keycloak: {}",
                clientId, e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("identity provider unavailable"));
    }
}
