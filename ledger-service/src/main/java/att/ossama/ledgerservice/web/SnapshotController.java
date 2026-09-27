package att.ossama.ledgerservice.web;

import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.projection.SnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Time travel over the ledger: the whole read model as of an arbitrary instant.
 *
 * <p>Read-only by construction. It appends nothing, folds immutable events, and
 * touches no state a write path can see, so it cannot affect the money path — the
 * transfer saga, the journal and the balances derived from the live log are all
 * exactly as they were.
 *
 * <p><b>Deliberately not gated.</b> The {@code ledger.demo-hooks.enabled} pattern
 * in {@link DemoHooksController} exists because those endpoints <em>corrupt</em>
 * the ledger; this one cannot, and {@code GET /api/accounts} — the same data
 * projected at "now" instead of "then" — is not gated either. Gating a read that
 * has no invariant to break would mean the endpoint 404s for anyone who has not
 * been told about a flag, which is how a feature ends up looking broken. To gate
 * it anyway, add this to the class (class-level, not method-level — see {@code
 * DemoHooksController}):
 * <pre>{@code @ConditionalOnProperty(name = "ledger.snapshot.enabled", havingValue = "true")}</pre>
 */
@RestController
@RequestMapping("/api/ledger")
public class SnapshotController {

    private final SnapshotService snapshots;

    public SnapshotController(SnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * The entire ledger state as it existed at {@code at}.
     *
     * <p>{@code at} is required and must be an ISO-8601 instant carrying a UTC
     * offset: {@code 2025-06-01T00:00:00Z} or {@code 2025-06-01T00:00:00+02:00}.
     * A missing, unparseable, zone-less or future value is a 400 with a reason in
     * the body, via {@code ApiExceptionHandler}.
     *
     * <p>The response is the {@code GET /api/accounts} shape, ordered by account
     * id, with an empty history per row. Accounts opened after {@code at} are
     * absent rather than zeroed — at that instant they did not exist.
     */
    @GetMapping("/snapshot")
    public List<AccountState> snapshot(@RequestParam(name = "at") String at) {
        Instant cutoff = SnapshotService.parse(at);
        return snapshots.snapshotAt(cutoff);
    }
}
