package att.ossama.ledgerservice.outbox.dto;

import java.util.List;

/**
 * The outbox as a whole: four counters plus the newest rows.
 *
 * <p>The counters are the truth about the table and {@code events} is a sample of
 * it, so {@code pending + published + failed + parked == total} always holds — the
 * four predicates partition the rows exactly as the status rule does. A client that
 * counted statuses in {@code events} instead would be reading the newest fifty and
 * calling it the outbox.
 */
public record OutboxStatusResponse(
        long total,
        long pending,
        long published,
        long failed,
        long parked,
        List<OutboxEventDto> events
) {
}
