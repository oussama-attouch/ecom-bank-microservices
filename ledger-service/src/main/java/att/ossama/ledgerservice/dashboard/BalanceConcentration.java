package att.ossama.ledgerservice.dashboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The concentration rule, in one place because both {@link LedgerAggregates}
 * implementations have to answer it the same way.
 *
 * <p>The SQL implementation obtains the balances per account and the in-memory
 * one folds them out of the log, but the question — how much of the ledger the
 * largest accounts hold — is arithmetic on the resulting numbers either way, and
 * two copies of it would be two chances to drift apart.
 */
public final class BalanceConcentration {

    private BalanceConcentration() {
    }

    /**
     * The share, in percent, of an account population's positive balances that
     * the {@code topAccounts} largest of them hold.
     *
     * <p>Overdrawn accounts are left out of both the numerator and the
     * denominator: a negative balance is not concentration, and netting it off
     * would let one overdrawn account flatter the share the largest ones hold.
     * Zero for a population with nothing positive in it — no balances means no
     * concentration, and the caller withholds the percentage for a zero baseline
     * anyway.
     */
    public static double shareOfTop(Collection<Double> balances, int topAccounts) {
        List<Double> held = new ArrayList<>(balances.size());
        double total = 0;
        for (double balance : balances) {
            if (balance > 0) {
                held.add(balance);
                total += balance;
            }
        }
        if (total <= 0) {
            return 0;
        }

        held.sort(Comparator.reverseOrder());
        double top = 0;
        for (int i = 0; i < Math.min(topAccounts, held.size()); i++) {
            top += held.get(i);
        }
        return 100 * top / total;
    }
}
