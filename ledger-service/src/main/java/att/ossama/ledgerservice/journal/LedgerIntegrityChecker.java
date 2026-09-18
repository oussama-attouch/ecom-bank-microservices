package att.ossama.ledgerservice.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On startup, verifies the entire journal balances (total debits == total
 * credits). If not, logs CRITICAL and refuses to start — the way real banks
 * protect ledger integrity.
 */
@Component
public class LedgerIntegrityChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LedgerIntegrityChecker.class);

    private final JournalService journalService;

    public LedgerIntegrityChecker(JournalService journalService) {
        this.journalService = journalService;
    }

    @Override
    public void run(ApplicationArguments args) {
        TrialBalance tb = journalService.trialBalance();
        if (!tb.balanced()) {
            log.error("CRITICAL: ledger journal is unbalanced! totalDebits={} totalCredits={}",
                    tb.totalDebits(), tb.totalCredits());
            throw new IllegalStateException("Ledger journal is unbalanced — refusing to start.");
        }
        log.info("Ledger integrity check passed: totalDebits={} totalCredits={}",
                tb.totalDebits(), tb.totalCredits());
    }
}
