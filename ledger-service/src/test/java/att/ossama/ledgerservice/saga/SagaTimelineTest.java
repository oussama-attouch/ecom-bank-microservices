package att.ossama.ledgerservice.saga;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The saga timeline is what makes a backfilled saga look like something that
 * happened rather than something written in one go. Its bands come from the
 * brief, so they are asserted exactly: a step that drifts outside its window
 * would quietly produce sagas that took seconds, or none at all.
 *
 * <p>These tests run repeatedly on purpose. Every instant is a random draw, so a
 * single pass proves very little — the failures worth catching are the ones that
 * only appear in some draws.
 */
class SagaTimelineTest {

    private static final Instant START = Instant.parse("2026-03-04T09:15:00Z");

    private static long deltaMs(Instant from, Instant to) {
        return Duration.between(from, to).toMillis();
    }

    @RepeatedTest(25)
    void happyPathStepsLandInsideTheirBandsAndInOrder() {
        SagaTimeline timeline = new SagaTimeline(START);

        Instant validate = timeline.validate();
        Instant debit = timeline.debitSource();
        Instant credit = timeline.creditDestination(debit);
        Instant archive = timeline.archive(credit);
        Instant completed = timeline.completedAt(archive);

        assertThat(validate).isEqualTo(START);
        assertThat(deltaMs(START, debit)).isBetween(20L, 80L);
        assertThat(deltaMs(START, credit)).isBetween(100L, 400L);
        assertThat(deltaMs(START, archive)).isBetween(200L, 800L);
        assertThat(deltaMs(archive, completed)).isBetween(10L, 50L);

        // Strictly increasing. The bands overlap (a late credit at 400ms versus an
        // early archive at 200ms), so this is only guaranteed by the ordering
        // clamp — without it, roughly a quarter of runs would archive before
        // crediting the destination.
        assertThat(validate).isBefore(debit);
        assertThat(debit).isBefore(credit);
        assertThat(credit).isBefore(archive);
        assertThat(archive).isBefore(completed);
    }

    @RepeatedTest(25)
    void aSagaLastsLessThanASecond() {
        SagaTimeline timeline = new SagaTimeline(START);
        Instant debit = timeline.debitSource();
        Instant credit = timeline.creditDestination(debit);

        // The bands are relative to the start, not to each other: if they
        // compounded, the worst case would be 800 + 400 + 80ms of drift.
        Instant completed = timeline.completedAt(timeline.archive(credit));

        assertThat(deltaMs(START, completed)).isBetween(210L, 860L);
    }

    @RepeatedTest(25)
    void compensationStepsFollowTheFailedArchiveInTheirOwnBands() {
        SagaTimeline timeline = new SagaTimeline(START);
        Instant archive = timeline.archive(timeline.creditDestination(timeline.debitSource()));

        Instant reverseCredit = timeline.compensateDebit(archive);
        Instant reverseDebit = timeline.compensateCredit(reverseCredit);
        Instant completed = timeline.completedAt(reverseDebit);

        assertThat(deltaMs(archive, reverseCredit)).isBetween(30L, 100L);
        assertThat(deltaMs(reverseCredit, reverseDebit)).isBetween(50L, 200L);
        assertThat(deltaMs(reverseDebit, completed)).isBetween(10L, 50L);

        assertThat(reverseCredit).isAfter(archive);
        assertThat(reverseDebit).isAfter(reverseCredit);
        assertThat(completed).isAfter(reverseDebit);
    }

    @RepeatedTest(25)
    void theWholeCompensatingRunFitsWellInsideTwoSeconds() {
        SagaTimeline timeline = new SagaTimeline(START);
        Instant archive = timeline.archive(timeline.creditDestination(timeline.debitSource()));
        Instant lastStep = timeline.compensateCredit(timeline.compensateDebit(archive));

        // The reversals are measured from the failed archive, so the compensating
        // run is the archive's band plus theirs rather than a single band.
        assertThat(deltaMs(START, timeline.completedAt(lastStep))).isBetween(290L, 1_160L);
    }

    @RepeatedTest(25)
    void stepsNeverShareAnInstant() {
        SagaTimeline timeline = new SagaTimeline(START);
        Instant debit = timeline.debitSource();
        Instant credit = timeline.creditDestination(debit);
        Instant archive = timeline.archive(credit);

        // Two steps at the same timestamp would render as one event in the saga
        // inspector, which is exactly the artefact the timeline exists to avoid.
        assertThat(debit).isNotEqualTo(credit);
        assertThat(credit).isNotEqualTo(archive);
    }

    @Test
    void delaysAreActuallyRandomRatherThanFixed() {
        // A degenerate implementation that always returned the band minimum would
        // pass every bounds assertion above and still produce a metronome.
        boolean sawVariation = false;
        long previous = deltaMs(START, new SagaTimeline(START).debitSource());

        for (int i = 0; i < 50 && !sawVariation; i++) {
            long current = deltaMs(START, new SagaTimeline(START).debitSource());
            sawVariation = current != previous;
        }

        assertThat(sawVariation).as("step delays should vary between runs").isTrue();
    }

    @Test
    void startedAtIsHandedBackUnchanged() {
        assertThat(new SagaTimeline(START).startedAt()).isEqualTo(START);
    }
}
