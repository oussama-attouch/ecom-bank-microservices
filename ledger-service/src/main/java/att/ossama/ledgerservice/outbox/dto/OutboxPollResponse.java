package att.ossama.ledgerservice.outbox.dto;

import java.time.Instant;

/**
 * The result of one forced poll.
 *
 * <p>{@code processed} counts events <em>published</em>, not events claimed: a
 * batch that claimed fifty rows and lost the broker to a timeout processed zero.
 * Zero is also a legitimate answer when another poll already holds the relay's
 * single-flight guard — the caller asked for a poll, and none happened.
 *
 * <p>{@code triggeredAt} comes from the service's injected clock, not from the
 * wall clock, so the demo can correlate it with the poller's own log lines.
 */
public record OutboxPollResponse(
        Instant triggeredAt,
        int processed
) {
}
