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

import java.time.Instant;
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
    private final AtParam atParam;

    public JournalController(JournalService journalService, AtParam atParam) {
        this.journalService = journalService;
        this.atParam = atParam;
    }

    /**
     * A page of the journal, newest first.
     *
     * <p>{@code at} reads the journal as it stood at that instant instead of now:
     * the same list, filtered to the postings stamped at or before it, paged the
     * same way. The page is taken after the filter, so page 0 of a snapshot is the
     * 20 most recent postings <em>as of the snapshot</em> rather than the first 20
     * rows of a page that the filter would then have emptied.
     */
    @GetMapping("/entries")
    public List<JournalEntry> entries(
            @RequestParam(required = false) String transactionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "at", required = false) String at) {
        Instant asOf = atParam.parse(at);
        List<JournalEntry> all = transactionId != null
                ? journalService.entriesForTransaction(transactionId)
                : (asOf != null ? journalService.entriesBefore(asOf) : journalService.entries());
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    /**
     * The trial balance, as of {@code at} when it is given.
     *
     * <p>A trial balance is a pair of running totals, so the historical form is
     * the same sum over fewer rows — and it should still read BALANCED at every
     * instant, which is what makes it worth watching under the scrubber.
     */
    @GetMapping("/trial-balance")
    public TrialBalance trialBalance(@RequestParam(name = "at", required = false) String at) {
        Instant asOf = atParam.parse(at);
        return asOf != null ? journalService.trialBalanceAt(asOf) : journalService.trialBalance();
    }

    @GetMapping("/account/{accountId}/statement")
    public AccountStatement statement(@PathVariable String accountId) {
        return journalService.statement(accountId);
    }
}
