package att.ossama.ledgerservice.web;

import att.ossama.ledgerservice.domain.JournalEntry;
import att.ossama.ledgerservice.journal.AccountStatement;
import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.journal.TrialBalance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-side journal endpoints.
 *
 * <p>The destructive test hook that used to live here
 * ({@code POST /api/journal/test/inject-unbalanced}) has moved to
 * {@link DemoHooksController}, which is only registered when
 * {@code ledger.demo-hooks.enabled=true}.
 */
@RestController
@RequestMapping("/api/journal")
public class JournalController {

    private final JournalService journalService;

    public JournalController(JournalService journalService) {
        this.journalService = journalService;
    }

    @GetMapping("/entries")
    public List<JournalEntry> entries(
            @RequestParam(required = false) String transactionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<JournalEntry> all = transactionId != null
                ? journalService.entriesForTransaction(transactionId)
                : journalService.entries();
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    @GetMapping("/trial-balance")
    public TrialBalance trialBalance() {
        return journalService.trialBalance();
    }

    @GetMapping("/account/{accountId}/statement")
    public AccountStatement statement(@PathVariable String accountId) {
        return journalService.statement(accountId);
    }
}
