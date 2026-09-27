package att.ossama.ledgerservice.web;

import att.ossama.ledgerservice.account.AccountService;
import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.projection.AccountProjection;
import att.ossama.ledgerservice.projection.SnapshotService;
import att.ossama.ledgerservice.web.dto.CreateAccountRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AccountProjection projection;
    private final SnapshotService snapshots;
    private final AtParam atParam;

    public AccountController(AccountService accountService, AccountProjection projection,
                             SnapshotService snapshots, AtParam atParam) {
        this.accountService = accountService;
        this.projection = projection;
        this.snapshots = snapshots;
        this.atParam = atParam;
    }

    /**
     * Creates an account holder mapped from an existing e-commerce customer.
     * Only an AccountCreatedEvent is appended — no mutable account row exists.
     */
    @PostMapping
    public ResponseEntity<AccountState> createAccount(@RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.openAccount(request.customerId()));
    }

    /**
     * Every account, as of {@code at} when it is given.
     *
     * <p>With no {@code at} this is the live list: one SQL aggregate, no time
     * predicate, no event history on the rows. With an {@code at} it is the same
     * JSON shape answered by replaying the log up to that instant, which is
     * {@link SnapshotService}'s existing job — the scrubber does not need a second
     * implementation of "the accounts as they stood then", only a second way to
     * reach it.
     *
     * <p>{@code at} is parsed by the same rules the snapshot endpoint applies —
     * absent and blank both mean live — so an unparseable or future value is the
     * same 400 with the same message on both.
     */
    @GetMapping
    public List<AccountState> list(@RequestParam(name = "at", required = false) String at) {
        Instant asOf = atParam.parse(at);
        return asOf == null ? projection.allAccounts() : snapshots.snapshotAt(asOf);
    }

    /** Read-side balance projection, computed by replaying the event stream. */
    @GetMapping("/{id}/balance")
    public AccountState balance(@PathVariable String id) {
        return projection.rebuild(requireAccount(id));
    }

    /** Full event history of an account, rebuilt by replay. */
    @GetMapping("/{id}/history")
    public AccountState history(@PathVariable String id) {
        return projection.rebuild(requireAccount(id));
    }

    private String requireAccount(String id) {
        if (!projection.exists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found: " + id);
        }
        return id;
    }
}
