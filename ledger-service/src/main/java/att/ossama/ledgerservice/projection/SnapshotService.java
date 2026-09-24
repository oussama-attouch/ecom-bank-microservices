package att.ossama.ledgerservice.projection;

import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.domain.Event;
import att.ossama.ledgerservice.eventstore.EventStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Point-in-time read of the whole ledger: every account's state as it stood at
 * one instant, rebuilt by replaying the log's prefix up to that instant.
 *
 * <p>Why this is not a method on {@link AccountProjection}: that class is the
 * <em>live</em> read model, and its one hard-won lesson — documented on {@link
 * AccountSummaries} — is that replaying the whole log to answer a list question
 * cost eleven seconds and blocked the Command Center. A snapshot is a different
 * access pattern with a different cost profile and its own input validation, so
 * it composes the projection's folding rather than living inside it and quietly
 * reintroducing the path the projection was fixed to avoid.
 *
 * <p>What it does <em>not</em> do is re-derive anything. The fold is {@link
 * AccountProjection#rebuildAllFrom(List)}, which is the same private fold the
 * single-account endpoints use, so a balance from here and a balance from {@code
 * GET /api/accounts/{id}/balance} cannot disagree — they are the same method.
 * The only thing this class adds is the time bound and the argument checking.
 *
 * <p>Cost is honest and worth stating: a cutoff at "now" replays all 51,042
 * events of the seeded ledger, which is one indexed query and one JSON decode per
 * event. It is a deliberate point-in-time reconstruction, not a dashboard read;
 * a cutoff in the past reads only the events before it, so the cost tracks the
 * history up to the instant asked about rather than the size of the log.
 */
@Service
public class SnapshotService {

    private final EventStore eventStore;
    private final AccountProjection projection;
    private final Clock clock;

    public SnapshotService(EventStore eventStore, AccountProjection projection, Clock clock) {
        this.eventStore = eventStore;
        this.projection = projection;
        this.clock = clock;
    }

    /**
     * Every account as it existed at {@code at}, ordered by account id.
     *
     * <p>Accounts opened after {@code at} are absent rather than present with a
     * zero balance: at that instant they did not exist, and reporting them as
     * empty would invent accounts. An instant before the ledger's first event is
     * therefore an empty list, not an error — "nothing existed yet" is a true
     * answer to the question asked.
     *
     * <p>Each row carries an empty history, matching {@code GET /api/accounts}
     * exactly. The history is a per-account concern that endpoint already serves,
     * and attaching 51,000 events to a list response is what {@link
     * AccountSummaries} documents as having made the list slow to build and to
     * send; a caller wanting one account's events asks {@code
     * /api/accounts/{id}/history}.
     *
     * @param at the instant to reconstruct; must not be in the future
     * @throws IllegalArgumentException if {@code at} is unparseable or in the
     *         future — {@code ApiExceptionHandler} turns this into a 400
     */
    public List<AccountState> snapshotAt(Instant at) {
        if (at.isAfter(clock.instant())) {
            throw new IllegalArgumentException(
                    "Invalid 'at': " + at + " is in the future; the ledger has no state there yet");
        }

        List<Event> events = eventStore.allEventsBefore(at);

        return projection.rebuildAllFrom(events).stream()
                .map(state -> new AccountState(
                        state.accountId(),
                        state.customerId(),
                        state.holderName(),
                        state.balance(),
                        // No history on a snapshot row: see the method javadoc.
                        List.of()))
                .toList();
    }

    /**
     * Parses the {@code at} query parameter into an instant.
     *
     * <p>Accepts the two unambiguous ISO-8601 forms a caller can mean: a UTC
     * instant ({@code 2025-06-01T00:00:00Z}) and an offset date-time ({@code
     * 2025-06-01T00:00:00+02:00}), the second normalised to UTC so the stored
     * timestamps and the cutoff are compared on one clock.
     *
     * <p>A timestamp with no zone ({@code 2025-06-01T00:00:00}) is rejected
     * rather than assumed to be UTC. Accepting it would silently answer a question
     * up to fourteen hours off from the one asked, on an endpoint whose entire
     * purpose is the exact instant — the same call {@code KpiRange.parse} makes
     * when it rejects an unknown range token instead of falling back to the
     * default and showing a week of data under a year's heading.
     *
     * @throws IllegalArgumentException if the value is missing, blank, or not an
     *         ISO-8601 instant with an offset
     */
    public static Instant parse(String at) {
        if (at == null || at.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required parameter: at (an ISO-8601 instant, e.g. 2025-06-01T00:00:00Z)");
        }

        String value = at.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            // Offsets other than Z parse as an OffsetDateTime, not an Instant.
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException notAnOffsetDateTime) {
                throw new IllegalArgumentException(
                        "Invalid 'at': not an ISO-8601 instant with a UTC offset: " + value
                                + " (expected e.g. 2025-06-01T00:00:00Z or 2025-06-01T00:00:00+02:00)");
            }
        }
    }
}
