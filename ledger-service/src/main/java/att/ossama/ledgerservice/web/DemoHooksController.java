package att.ossama.ledgerservice.web;

import att.ossama.ledgerservice.journal.JournalService;
import att.ossama.ledgerservice.journal.TrialBalance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoints that deliberately break the ledger's invariants, so the integrity
 * checker and the dashboard can be exercised on demand.
 *
 * <p>Registered only when {@code ledger.demo-hooks.enabled=true} (default
 * {@code false} in application.properties). When the flag is off the bean is
 * never created, so no handler mapping exists and a caller gets a plain
 * <b>404</b> — the route is absent rather than merely refused.
 *
 * <p>The condition sits on the class on purpose. Spring evaluates
 * {@code @Conditional*} annotations while registering bean definitions, so a
 * condition placed on a single {@code @RequestMapping} method of an ordinary
 * {@code @RestController} is never consulted by
 * {@code RequestMappingHandlerMapping} and would silently do nothing.
 */
@RestController
@RequestMapping("/api/journal/test")
@ConditionalOnProperty(name = "ledger.demo-hooks.enabled", havingValue = "true")
public class DemoHooksController {

    private final JournalService journalService;

    public DemoHooksController(JournalService journalService) {
        this.journalService = journalService;
    }

    /** Injects a deliberately unbalanced entry to exercise the invariant check. */
    @PostMapping("/inject-unbalanced")
    public ResponseEntity<?> injectUnbalanced() {
        journalService.injectUnbalanced();
        TrialBalance tb = journalService.trialBalance();
        return ResponseEntity.ok(Map.of("injected", true, "trialBalance", tb));
    }
}
