package att.ossama.ledgerservice.seed;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The distributions the portfolio seed draws from: when customers arrive, when
 * transactions happen, and how much money moves.
 *
 * <p>All of it is deterministic given a {@link Random} and is deliberately kept
 * out of the generator so the shapes can be asserted directly (see
 * {@code SeedPacingTest}) rather than inferred from a finished ledger. Every
 * weight here is a relative one: the callers normalise, so the tables can be read
 * as the brief states them ("Mon–Fri 14% each, Sat 12%, Sun 8%").
 */
final class SeedPacing {

    /** Months of history the portfolio seed reconstructs. */
    static final int PORTFOLIO_MONTHS = 24;

    /** The brief's fixed seed: same command, same ledger, every time. */
    static final long RANDOM_SEED = 42L;

    private SeedPacing() {
    }

    // -----------------------------------------------------------------------
    // Customer acquisition
    // -----------------------------------------------------------------------

    /**
     * New customers per month across the 24-month window, forming an S-curve:
     * a slow start, a ramp through the middle year, then a plateau.
     *
     * <p>The brief gives the shape as ranges (10/month, then 15→35, then 30) plus
     * a churn rate, and then asks for exactly 500 customers. Those two do not
     * reconcile on their own — the raw curve sums to roughly 590 — so the curve is
     * built from the brief's shape and then scaled to land on the target exactly.
     * Churn is applied separately, as the brief describes it: a churned customer
     * stops transacting but is not removed from the ledger, which is what an
     * append-only log of real accounts looks like.
     */
    static int[] acquisitionCurve(int months, int targetCustomers) {
        double[] weights = new double[months];
        for (int month = 0; month < months; month++) {
            weights[month] = acquisitionWeight(month);
        }
        return scaleToTotal(weights, targetCustomers);
    }

    /** The brief's month-by-month shape, as relative weights. */
    private static double acquisitionWeight(int month) {
        if (month < 6) {
            return 10;
        }
        if (month < 18) {
            // Ramp 15 -> 35 across months 7..18.
            double progress = (month - 6) / 11.0;
            return 15 + progress * 20;
        }
        return 30;
    }

    /**
     * Scales weights to sum to {@code total}, giving the remainder to the
     * earliest months so the total is exact rather than approximately right.
     */
    private static int[] scaleToTotal(double[] weights, int total) {
        double sum = 0;
        for (double weight : weights) {
            sum += weight;
        }
        int[] counts = new int[weights.length];
        int assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            counts[i] = (int) Math.floor(weights[i] / sum * total);
            assigned += counts[i];
        }
        for (int i = 0; assigned < total; i = (i + 1) % weights.length) {
            counts[i]++;
            assigned++;
        }
        return counts;
    }

    /** Accounts per customer: 10% have one, 60% have two, 30% have three. */
    static int accountCountFor(int customerIndex, Random random) {
        double roll = random.nextDouble();
        return roll < 0.10 ? 1 : roll < 0.70 ? 2 : 3;
    }

    /**
     * Days between a customer appearing and each of their accounts opening:
     * within 1–7 days, weighted toward the first three.
     */
    static int accountOpeningDelayDays(Random random) {
        double roll = random.nextDouble();
        if (roll < 0.45) {
            return 1;
        }
        if (roll < 0.70) {
            return 2;
        }
        if (roll < 0.85) {
            return 3;
        }
        if (roll < 0.92) {
            return 4;
        }
        if (roll < 0.96) {
            return 5;
        }
        return roll < 0.985 ? 6 : 7;
    }

    // -----------------------------------------------------------------------
    // Timestamp weighting
    // -----------------------------------------------------------------------

    /** Relative likelihood of a transaction on each day of the week. */
    private static double weekdayWeight(DayOfWeek day) {
        return switch (day) {
            case MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY -> 0.14;
            case SATURDAY -> 0.12;
            case SUNDAY -> 0.08;
        };
    }

    /** Relative likelihood of a transaction in each hour of the day. */
    static double hourWeight(int hour) {
        if (hour <= 5) {
            return 0.02;
        }
        if (hour <= 7) {
            return 0.06;
        }
        if (hour <= 11) {
            return 0.35;
        }
        if (hour <= 13) {
            return 0.20;
        }
        if (hour <= 17) {
            return 0.25;
        }
        if (hour <= 21) {
            return 0.10;
        }
        return 0.02;
    }

    /**
     * Seasonal multiplier, applied on top of the weekday and hour weights: a
     * quiet August, a busy December, month-end processing and quarter starts.
     */
    static double seasonalWeight(LocalDate date, List<LocalDate> businessDaysOfMonth) {
        double weight = 1.0;
        switch (date.getMonth()) {
            case AUGUST -> weight *= 0.7;
            case DECEMBER -> weight *= 1.8;
            default -> { }
        }

        int businessDaysInMonth = businessDaysOfMonth.size();
        int index = businessDaysOfMonth.indexOf(date);
        if (index >= 0) {
            // Last two business days of the month, and the first three of a quarter.
            if (index >= businessDaysInMonth - 2) {
                weight *= 2.5;
            }
            if (index < 3 && isQuarterStart(date.getMonthValue())) {
                weight *= 1.3;
            }
        }
        return weight;
    }

    private static boolean isQuarterStart(int month) {
        return month == 1 || month == 4 || month == 7 || month == 10;
    }

    /** The weekdays of a month, in order — the calendar the seasonal rule needs. */
    static List<LocalDate> businessDaysOf(LocalDate anyDayInMonth) {
        LocalDate first = anyDayInMonth.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate last = anyDayInMonth.with(TemporalAdjusters.lastDayOfMonth());
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            if (!isWeekend(day)) {
                days.add(day);
            }
        }
        return days;
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    /**
     * Picks a timestamp inside {@code [from, to)} by rejection sampling against
     * the combined weekday/hour/seasonal weight.
     *
     * <p>Rejection rather than inverse-transform: the weight is a product of
     * three independent tables plus a calendar lookup, and sampling it directly
     * would mean building and inverting a 24-month histogram. Drawing a uniform
     * instant and keeping it with probability proportional to its weight gives
     * exactly the intended distribution and costs a handful of tries.
     */
    static Instant weightedInstant(Random random, Instant from, Instant to) {
        long spanMillis = Math.max(1, to.toEpochMilli() - from.toEpochMilli());
        // The busiest hours are ~35/24 the average, so the acceptance rate stays
        // high; the cap only guards against a pathological calendar.
        for (int attempt = 0; attempt < 200; attempt++) {
            Instant candidate = from.plusMillis(random.nextLong(spanMillis));
            LocalDate date = LocalDate.ofInstant(candidate, ZoneOffset.UTC);
            double weight = weekdayWeight(date.getDayOfWeek())
                    * hourWeight(candidate.atZone(ZoneOffset.UTC).getHour())
                    * seasonalWeight(date, businessDaysOf(date));
            if (random.nextDouble() * MAX_TIMESTAMP_WEIGHT < weight) {
                return candidate;
            }
        }
        // Never reached in practice; falling back to a uniform draw keeps the
        // caller's contract (an instant inside the window) intact.
        return from.plusMillis(random.nextLong(spanMillis));
    }

    /** Upper bound of the combined weight, used as the rejection threshold. */
    private static final double MAX_TIMESTAMP_WEIGHT = 0.14 * 0.35 * 2.5 * 1.8;

    // -----------------------------------------------------------------------
    // Amounts
    // -----------------------------------------------------------------------

    /** The brief's amount law: {@code exp(normal(5.5, 1.2))}, floored and rounded. */
    static double logNormalAmount(Random random) {
        double raw = Math.exp(5.5 + 1.2 * random.nextGaussian());
        return round(Math.max(10.0, raw));
    }

    /**
     * A log-normal draw squeezed into a range by scaling its median, keeping the
     * shape while landing in the band the brief asks for.
     */
    static double logNormalAmountWithin(Random random, double min, double max) {
        double raw = Math.exp(5.5 + 1.2 * random.nextGaussian());
        // Rescale so the median sits in the middle of the band, then clamp.
        double median = Math.exp(5.5);
        double target = (min + max) / 2.0;
        double scaled = raw * (target / median);
        return round(Math.min(max, Math.max(min, scaled)));
    }

    static double uniformAmount(Random random, double min, double max) {
        return round(min + random.nextDouble() * (max - min));
    }

    /** Poisson draw by Knuth's method; the brief's per-customer monthly rate is small. */
    static int poisson(Random random, double mean) {
        double limit = Math.exp(-mean);
        double product = random.nextDouble();
        int count = 0;
        while (product > limit) {
            count++;
            product *= random.nextDouble();
        }
        return count;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    // -----------------------------------------------------------------------
    // Wealth skew
    // -----------------------------------------------------------------------

    /**
     * Opening balance for a customer by wealth rank.
     *
     * <p>The bands are the brief's, and their purpose is the concentration KPI:
     * a flat distribution would put top-10 accounts at a few percent of AUM and
     * make the concentration panel look broken, while a steep one (a Pareto tail
     * over every customer) puts them at 95%.
     */
    static double openingBalanceFor(int wealthRank, Random random) {
        if (wealthRank < 5) {
            return uniformAmount(random, 500_000, 2_000_000);
        }
        if (wealthRank < 50) {
            return uniformAmount(random, 50_000, 250_000);
        }
        return uniformAmount(random, 1_000, 20_000);
    }
}
