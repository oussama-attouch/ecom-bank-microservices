package att.ossama.ledgerservice.web;

import att.ossama.ledgerservice.projection.SnapshotService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * The optional {@code at} query parameter the timeline scrubber drives.
 *
 * <p>Six endpoints take an optional instant, and the three things they must agree
 * on are easy to get subtly different in six places: what "absent" means, what
 * "blank" means, and what counts as a valid instant. This is that agreement, in
 * one method — and it is a bean rather than a static helper because the third of
 * those rules needs the application's injected {@link Clock}: "is this in the
 * future" must be answerable by a test that has pinned "now".
 *
 * <p><b>Absent and blank both mean live.</b> {@code at} is optional everywhere it
 * appears, so a client that predates the scrubber keeps the behaviour it was
 * written for — the same reasoning that makes {@code range} default to {@code 7d}
 * on the dashboard. A blank value is read as absent rather than rejected because a
 * query string that dropped an empty field is not a client error, which is the
 * distinction {@code KpiRange.parse} already draws when it treats a blank range
 * token as the default.
 *
 * <p><b>This differs from {@code /api/ledger/snapshot} on purpose.</b> That
 * endpoint's {@code at} is required — asking it for a snapshot without saying when
 * is a malformed request — so it rejects a blank value. Here the parameter is an
 * optional narrowing of an endpoint that already means something without it.
 *
 * <p><b>A present value is validated by {@link SnapshotService}</b>, not by a
 * second parser: {@link SnapshotService#parse(String)} owns the format rules and
 * {@link SnapshotService#requireNotFuture(Instant, Clock)} owns the future rule,
 * so the scrubber's endpoints reject exactly what the snapshot endpoint rejects,
 * with the same message. Two parsers would eventually disagree, and the one that
 * drifted would be the one answering a 400 to a value its sibling accepted.
 */
@Component
public class AtParam {

    private final Clock clock;

    public AtParam(Clock clock) {
        this.clock = clock;
    }

    /**
     * The instant {@code at} names, or null when the caller did not ask for one.
     *
     * @param at the raw query parameter, which may be absent or blank
     * @return the instant to read as of, or {@code null} for the live read
     * @throws IllegalArgumentException if {@code at} is present but is not an
     *         ISO-8601 instant with an offset, or names a future instant —
     *         {@code ApiExceptionHandler} turns this into a 400
     */
    public Instant parse(String at) {
        if (at == null || at.isBlank()) {
            return null;
        }
        return SnapshotService.requireNotFuture(SnapshotService.parse(at), clock);
    }
}
