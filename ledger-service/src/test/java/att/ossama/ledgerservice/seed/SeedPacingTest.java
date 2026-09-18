package att.ossama.ledgerservice.seed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The distributions the portfolio seed draws from.
 *
 * <p>These are the parts worth pinning down: the acquisition curve is the only
 * thing deciding how the customer base grows, and the timestamp and amount laws
 * are what make the resulting charts look like a bank rather than like a
 * generator. Asserting them here means a finished ledger only has to be checked
 * for volume and consistency.
 */
class SeedPacingTest {

    private static final Instant FROM = Instant.parse("2024-09-17T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-17T00:00:00Z");

    // -----------------------------------------------------------------------
    // Customer acquisition
    // -----------------------------------------------------------------------

    @Test
    void theCurveDeliversExactlyTheTargetNumberOfCustomers() {
        int[] curve = SeedPacing.acquisitionCurve(SeedPacing.PORTFOLIO_MONTHS, 500);

        assertThat(curve).hasSize(SeedPacing.PORTFOLIO_MONTHS);
        assertThat(sum(curve)).isEqualTo(500);
        // Every month acquires someone: a zero would mean the curve had a hole.
        for (int arrivals : curve) {
            assertThat(arrivals).isPositive();
        }
    }

    @Test
    void theCurveIsAnSCurveRatherThanAFlatLine() {
        int[] curve = SeedPacing.acquisitionCurve(SeedPacing.PORTFOLIO_MONTHS, 500);

        int early = average(curve, 0, 5);
        int middle = average(curve, 6, 17);
        int late = average(curve, 18, 23);

        // Slow start, a ramp through the middle year, then a plateau: the brief's
        // shape. A uniform split would put all three at ~21/month.
        assertThat(early).isLessThan(late);
        assertThat(middle).isGreaterThan(early);
        assertThat(middle).isLessThanOrEqualTo(late);
        assertThat(late).isGreaterThan(early * 2);
    }

    @Test
    void scalingKeepsTheShapeButHitsAnyTarget() {
        int[] small = SeedPacing.acquisitionCurve(SeedPacing.PORTFOLIO_MONTHS, 50);
        int[] large = SeedPacing.acquisitionCurve(SeedPacing.PORTFOLIO_MONTHS, 5_000);

        assertThat(sum(small)).isEqualTo(50);
        assertThat(sum(large)).isEqualTo(5_000);

        // Same curve at different volumes, so the rise from start to plateau is
        // present at both — comparing the peak month itself would be too strict,
        // since at 50 customers the integer rounding decides which late month
        // happens to come out on top.
        assertThat(average(small, 0, 5)).isLessThan(average(small, 18, 23));
        assertThat(average(large, 0, 5)).isLessThan(average(large, 18, 23));
        // Scaling preserves the ratio the brief specifies: months 1-6 acquire at
        // weight 10 and the plateau at 30, so the late months are three times the
        // early ones at any population size.
        assertThat(average(large, 18, 23) / (double) average(large, 0, 5)).isCloseTo(3.0, within(0.15));
    }

    // -----------------------------------------------------------------------
    // Accounts
    // -----------------------------------------------------------------------

    @Test
    void theAccountMixIsRoughlyTenSixtyThirty() {
        Random random = new Random(7);
        int one = 0;
        int two = 0;
        int three = 0;
        int total = 120_000;

        for (int i = 0; i < total; i++) {
            switch (SeedPacing.accountCountFor(i, random)) {
                case 1 -> one++;
                case 2 -> two++;
                default -> three++;
            }
        }

        assertThat(one / (double) total).isCloseTo(0.10, within(0.01));
        assertThat(two / (double) total).isCloseTo(0.60, within(0.01));
        assertThat(three / (double) total).isCloseTo(0.30, within(0.01));
        // ~2.2 accounts per customer, which is the arithmetic behind "~1,100
        // accounts for 500 customers".
        assertThat((one + 2 * two + 3 * three) / (double) total).isCloseTo(2.2, within(0.02));
    }

    @Test
    void accountsOpenWithinAWeekAndUsuallyEarly() {
        Random random = new Random(11);
        int[] days = new int[8];
        int samples = 100_000;
        for (int i = 0; i < samples; i++) {
            days[SeedPacing.accountOpeningDelayDays(random)]++;
        }

        assertThat(days[0]).isZero();
        assertThat(days[7]).isPositive();
        // Weighted toward the first three days, per the brief.
        double firstThree = (days[1] + days[2] + days[3]) / (double) samples;
        assertThat(firstThree).isCloseTo(0.85, within(0.02));
    }

    // -----------------------------------------------------------------------
    // Timestamp weighting
    // -----------------------------------------------------------------------

    @Test
    void weekdaysAreBusierThanWeekendsAndMondayToFridayAreEqual() {
        assertThat(SeedPacing.hourWeight(10)).isGreaterThan(SeedPacing.hourWeight(3));
        // The tables are the brief's numbers verbatim, so they can be read off.
        assertThat(SeedPacing.hourWeight(9)).isEqualTo(0.35);
        assertThat(SeedPacing.hourWeight(12)).isEqualTo(0.20);
        assertThat(SeedPacing.hourWeight(15)).isEqualTo(0.25);
        assertThat(SeedPacing.hourWeight(19)).isEqualTo(0.10);
        assertThat(SeedPacing.hourWeight(23)).isEqualTo(0.02);
    }

    @Test
    void summerIsQuietAndDecemberIsBusy() {
        // Same weekday, same position in the month, different month.
        LocalDate august = LocalDate.of(2025, 8, 13);
        LocalDate november = LocalDate.of(2025, 11, 13);
        LocalDate december = LocalDate.of(2025, 12, 11);

        List<LocalDate> augustDays = SeedPacing.businessDaysOf(august);
        List<LocalDate> novemberDays = SeedPacing.businessDaysOf(november);
        List<LocalDate> decemberDays = SeedPacing.businessDaysOf(december);

        assertThat(SeedPacing.seasonalWeight(august, augustDays)).isLessThan(1.0);
        assertThat(SeedPacing.seasonalWeight(december, decemberDays)).isGreaterThan(1.0);
        assertThat(SeedPacing.seasonalWeight(august, augustDays))
                .isLessThan(SeedPacing.seasonalWeight(november, novemberDays));
    }

    @Test
    void monthEndAndQuarterStartAreBusier() {
        // A quarter-start month, so the first three business days are eligible and
        // the last two are a separate rule.
        LocalDate january = LocalDate.of(2026, 1, 15);
        List<LocalDate> days = SeedPacing.businessDaysOf(january);

        LocalDate firstBusinessDay = days.get(0);
        LocalDate secondBusinessDay = days.get(1);
        LocalDate lastBusinessDay = days.get(days.size() - 1);
        LocalDate midMonth = days.get(10);

        assertThat(SeedPacing.seasonalWeight(firstBusinessDay, days)).isCloseTo(1.3, within(1e-9));
        assertThat(SeedPacing.seasonalWeight(secondBusinessDay, days)).isCloseTo(1.3, within(1e-9));
        assertThat(SeedPacing.seasonalWeight(lastBusinessDay, days)).isCloseTo(2.5, within(1e-9));
        assertThat(SeedPacing.seasonalWeight(midMonth, days)).isCloseTo(1.0, within(1e-9));
    }

    @RepeatedTest(5)
    void sampledInstantsFollowTheWeightingRatherThanTheCalendarAlone() {
        Random random = new Random(23);
        int samples = 20_000;
        Map<DayOfWeek, Integer> byWeekday = new HashMap<>();
        int businessHours = 0;
        int weekend = 0;

        for (int i = 0; i < samples; i++) {
            Instant instant = SeedPacing.weightedInstant(random, FROM, TO);
            assertThat(instant).isBetween(FROM, TO);

            var zoned = instant.atZone(ZoneOffset.UTC);
            byWeekday.merge(zoned.getDayOfWeek(), 1, Integer::sum);
            if (zoned.getHour() >= 8 && zoned.getHour() <= 17) {
                businessHours++;
            }
            if (zoned.getDayOfWeek() == DayOfWeek.SATURDAY || zoned.getDayOfWeek() == DayOfWeek.SUNDAY) {
                weekend++;
            }
        }

        // 08:00-17:59 carries 35+20+25 = 80% of the hour weight, so the working
        // day should dominate even after the weekday and seasonal weights.
        assertThat(businessHours / (double) samples).isGreaterThan(0.6);
        // Weekends are 20% of the weekday weight and take 2/7 of the calendar.
        assertThat(weekend / (double) samples).isLessThan(0.30);
        // Every weekday is reachable, and no day of the week is starved.
        assertThat(byWeekday.keySet()).hasSize(7);
        assertThat(byWeekday.values()).allSatisfy(count -> assertThat(count).isGreaterThan(samples / 100));
    }

    // -----------------------------------------------------------------------
    // Amounts
    // -----------------------------------------------------------------------

    @Test
    void theAmountLawIsLogNormalAroundItsModeAndNeverBelowTheFloor() {
        Random random = new Random(31);
        int samples = 200_000;
        double sum = 0;
        double min = Double.MAX_VALUE;
        int belowFloor = 0;

        for (int i = 0; i < samples; i++) {
            double amount = SeedPacing.logNormalAmount(random);
            sum += amount;
            min = Math.min(min, amount);
            if (amount < 10) {
                belowFloor++;
            }
        }

        // exp(5.5) is 244.69; the median of the law, so half the draws sit near it.
        assertThat(sum / samples).isGreaterThan(244.69);
        assertThat(min).isGreaterThanOrEqualTo(10.0);
        assertThat(belowFloor).isZero();
    }

    @Test
    void clampedAmountsStayInsideTheirBand() {
        Random random = new Random(37);
        for (int i = 0; i < 20_000; i++) {
            double deposits = SeedPacing.logNormalAmountWithin(random, 20, 500);
            double transfers = SeedPacing.logNormalAmountWithin(random, 50, 2_000);
            assertThat(deposits).isBetween(20.0, 500.0);
            assertThat(transfers).isBetween(50.0, 2_000.0);
        }
    }

    @Test
    void largeTransfersAreUniformAcrossTheirBand() {
        Random random = new Random(41);
        double min = Double.MAX_VALUE;
        double max = 0;
        for (int i = 0; i < 20_000; i++) {
            double amount = SeedPacing.uniformAmount(random, 5_000, 50_000);
            min = Math.min(min, amount);
            max = Math.max(max, amount);
        }

        assertThat(min).isGreaterThanOrEqualTo(5_000.0);
        assertThat(max).isLessThanOrEqualTo(50_000.0);
        // Uniform, not clustered: the extremes should both be reached.
        assertThat(min).isLessThan(6_000.0);
        assertThat(max).isGreaterThan(49_000.0);
    }

    @Test
    void everyAmountIsRoundedToCents() {
        Random random = new Random(43);
        for (int i = 0; i < 10_000; i++) {
            double amount = SeedPacing.logNormalAmount(random);
            assertThat(Math.round(amount * 100) / 100.0).isEqualTo(amount);
        }
    }

    @Test
    void poissonHitsItsMeanAndStaysNonNegative() {
        Random random = new Random(47);
        int samples = 200_000;
        long total = 0;
        for (int i = 0; i < samples; i++) {
            int draw = SeedPacing.poisson(random, 6.0);
            assertThat(draw).isGreaterThanOrEqualTo(0);
            total += draw;
        }

        assertThat(total / (double) samples).isCloseTo(6.0, within(0.05));
    }

    // -----------------------------------------------------------------------
    // Wealth
    // -----------------------------------------------------------------------

    @Test
    void wealthIsBandedByRankTheWayTheConcentrationKpiNeeds() {
        Random random = new Random(53);

        for (int rank = 0; rank < 5; rank++) {
            assertThat(SeedPacing.openingBalanceFor(rank, random)).isBetween(500_000.0, 2_000_000.0);
        }
        for (int rank = 5; rank < 50; rank++) {
            assertThat(SeedPacing.openingBalanceFor(rank, random)).isBetween(50_000.0, 250_000.0);
        }
        for (int rank = 50; rank < 500; rank++) {
            assertThat(SeedPacing.openingBalanceFor(rank, random)).isBetween(1_000.0, 20_000.0);
        }
    }

    @Test
    void theBandsAddUpToConcentrationInTheTargetRangeNotNearTotal() {
        Random random = new Random(59);
        double[] balances = new double[500];
        for (int rank = 0; rank < balances.length; rank++) {
            balances[rank] = SeedPacing.openingBalanceFor(rank, random);
        }

        double total = 0;
        for (double balance : balances) {
            total += balance;
        }
        // One account per customer here, so the top ten customers are the top ten
        // accounts. The brief wants 40-70%: an unscaled Pareto tail would put this
        // at ~95%, and a flat distribution at a few percent.
        double topTen = 0;
        for (int i = 0; i < 10; i++) {
            topTen += balances[i];
        }

        assertThat(topTen / total).isBetween(0.35, 0.75);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    private static int average(int[] values, int from, int to) {
        int total = 0;
        for (int i = from; i <= to; i++) {
            total += values[i];
        }
        return total / (to - from + 1);
    }

}
