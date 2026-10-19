package att.ossama.gatewayservice.backoffice;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error contract of the backoffice API, asserted directly on the advice —
 * no web context, because the mapping is the whole subject.
 */
class BackofficeExceptionHandlerTest {

    private final BackofficeExceptionHandler handler = new BackofficeExceptionHandler("ecom-backoffice");

    @Test
    void validationRefusalsKeepTheirStatus() {
        ResponseEntity<ApiError> response = handler.handleBackoffice(new BackofficeException(409, "nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("nope");
    }

    @Test
    void anUnknownUserIs404() {
        ResponseEntity<ApiError> response = handler.handleKeycloak(new KeycloakAdminException(404, "{}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("unknown user");
    }

    @Test
    void aRejectedAdministratorIs502WithItsOwnMessage() {
        ResponseEntity<ApiError> response = handler.handleKeycloak(new KeycloakAdminException(403, "{\"error\":\"HTTP 403\"}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().error()).isEqualTo("identity provider rejected the administrator");
    }

    @Test
    void anyOtherUpstreamFailureIs502Unavailable() {
        ResponseEntity<ApiError> response = handler.handleKeycloak(new KeycloakAdminException(500, "boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().error()).isEqualTo("identity provider unavailable");
    }

    @Test
    void aTimeoutIs504() {
        ResponseEntity<ApiError> response = handler.handleTimeout(new TimeoutException("slow"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody().error()).isEqualTo("identity provider unavailable");
    }
}
