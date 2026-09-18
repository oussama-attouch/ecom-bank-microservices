package att.ossama.ledgerservice.saga;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The clock a transfer saga runs on.
 *
 * <p>A real saga is not instantaneous: the steps land tens to hundreds of
 * milliseconds apart, because each one appends an event, posts journal entries
 * and (on the happy path) waits for a publisher. Backfilling history through
 * {@code Instant.now()} would collapse all of that into the instant the seeder
 * ran, so every reconstructed saga would show a duration of zero and the saga
 * inspector would have nothing to show. This is therefore part of the saga's
 * contract rather than a private detail: anything reconstructing a saga uses it,
 * so reconstructed and live sagas cannot drift apart.
 *
 * <p>This hands out the instants for one saga run, measured from the moment the
 * saga started. Each step lands at random inside its band, so a seeded ledger
 * looks like a ledger rather than a metronome:
 *
 * <pre>
 *   VALIDATE             at start
 *   DEBIT_SOURCE         start +  20.. 80ms
 *   CREDIT_DESTINATION   start + 100..400ms
 *   ARCHIVE              start + 200..800ms
 *   completed            last step + 10.. 50ms
 * </pre>
 *
 * <p>Compensating runs reuse the happy-path bands and then add the two reversal
 * steps, which are measured from the failed archive rather than from the start —
 * they happen after it, so they belong after it on the timeline:
 *
 * <pre>
 *   COMPENSATE_DEBIT     failed archive    +  30..100ms
 *   COMPENSATE_CREDIT    compensate debit  +  50..200ms
 *   completed            last step         +  10.. 50ms
 * </pre>
 *
 * <p>The steps that start a saga (debit, credit, archive) are measured from
 * {@code startedAt}, which is what stops their bands compounding into
 * seconds-long sagas: the archive lands 200–800ms after the start, not
 * 200–800ms after the credit.
 *
 * <h2>Steps are ordered, not just banded</h2>
 * The bands overlap — a credit drawn late (400ms) is later than an archive drawn
 * early (200ms) — but a saga that archived a transfer before crediting the
 * destination is nonsense, and the saga inspector renders the steps in sequence.
 * So each step is forced to be at least a minimum gap after the one before it,
 * while staying inside its own band. A step can therefore land late in its band
 * but never outside it, and never out of order.
 */
public final class SagaTimeline {

    /** Smallest spacing forced between consecutive steps, so no two share an instant. */
    private static final long MIN_STEP_GAP_MS = 10;

    private final Instant startedAt;

    public SagaTimeline(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** VALIDATE is the saga's first act, so it carries no delay. */
    public Instant validate() {
        return startedAt;
    }

    public Instant debitSource() {
        return atLeast(randomBetween(20, 80), startedAt, MIN_STEP_GAP_MS);
    }

    public Instant creditDestination(Instant debitAt) {
        return atLeast(randomBetween(100, 400), debitAt, MIN_STEP_GAP_MS);
    }

    public Instant archive(Instant creditAt) {
        return atLeast(randomBetween(200, 800), creditAt, MIN_STEP_GAP_MS);
    }

    /** Runs after the archive failed: the debit that was already applied is reversed first. */
    public Instant compensateDebit(Instant archiveAttempt) {
        return archiveAttempt.plusMillis(randomBetween(30, 100));
    }

    /**
     * Then the credit, measured from the debit reversal so the two cannot land in
     * the wrong order: two independent bands after the same instant would overlap
     * and occasionally emit a saga whose steps read backwards.
     */
    public Instant compensateCredit(Instant compensateDebitAt) {
        return compensateDebitAt.plusMillis(randomBetween(50, 200));
    }

    /** Completion trails the last step of the run by a short, jittered gap. */
    public Instant completedAt(Instant lastStep) {
        return lastStep.plusMillis(randomBetween(10, 50));
    }

    /**
     * The band's draw, pushed forward only as far as the ordering requires.
     *
     * <p>If the previous step already ran past this band's offset, the step takes
     * the smallest legal gap after it instead. That trades a few milliseconds of
     * band accuracy in the rare overlapping draw for a timeline that always reads
     * in order, which is the property the saga's consumers depend on.
     */
    private Instant atLeast(long offsetMs, Instant previousStep, long minGapMs) {
        Instant drawn = startedAt.plusMillis(offsetMs);
        Instant earliest = previousStep.plusMillis(minGapMs);
        return drawn.isAfter(earliest) ? drawn : earliest;
    }

    private static long randomBetween(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxInclusive + 1L);
    }

    /** The saga's total duration so far, for logging and assertions. */
    Duration durationTo(Instant instant) {
        return Duration.between(startedAt, instant);
    }
}
