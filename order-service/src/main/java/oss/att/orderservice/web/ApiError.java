package oss.att.orderservice.web;

import java.time.Instant;

/**
 * The body of a degraded response.
 *
 * <p>Typed rather than a stack trace, and deliberately shaped like the gateway's
 * {@code ApiError}: a caller that is already parsing one service's errors should
 * not need a second parser for this one. The contract is "the service is fine,
 * something it depends on is not, and retrying shortly is reasonable" — which is
 * exactly what HTTP 503 says and exactly what a 500 does not.
 *
 * @param code      stable machine-readable code, never localised
 * @param message   one sentence for a human; contains no internal detail
 * @param timestamp when the failure was observed, ISO-8601
 */
public record ApiError(String code, String message, Instant timestamp) {

    /** The code every degraded read from this service returns. */
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";

    public static ApiError dependencyUnavailable(String what) {
        return new ApiError(DEPENDENCY_UNAVAILABLE, what, Instant.now());
    }
}
