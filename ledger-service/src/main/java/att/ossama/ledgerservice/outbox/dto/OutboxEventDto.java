package att.ossama.ledgerservice.outbox.dto;

import java.time.Instant;

/**
 * One outbox row as the admin list renders it.
 *
 * <p>{@code payload} is deliberately absent. It is the full event body — the same
 * bytes that go on the topic — so returning it would put every published
 * transfer's details in a dashboard response that shows none of them, and grow the
 * response with data the page never draws. An operator who needs the bytes reads
 * them in the database, where reading them is a deliberate act.
 *
 * <p>{@code status} is derived, not stored: see the precedence rule in
 * {@code OutboxAdminController}. The client renders the word, never infers it from
 * the timestamps — otherwise a row can read PUBLISHED in one place and FAILED in
 * another as soon as the rule changes.
 */
public record OutboxEventDto(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String status,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant publishedAt
) {
}
