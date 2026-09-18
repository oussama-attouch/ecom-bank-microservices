package att.ossama.ledgerservice.web;

import att.ossama.ledgerservice.account.AccountService;
import att.ossama.ledgerservice.domain.AccountState;
import att.ossama.ledgerservice.projection.AccountProjection;
import att.ossama.ledgerservice.web.dto.CreateAccountRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AccountProjection projection;

    public AccountController(AccountService accountService, AccountProjection projection) {
        this.accountService = accountService;
        this.projection = projection;
    }

    /**
     * Creates an account holder mapped from an existing e-commerce customer.
     * Only an AccountCreatedEvent is appended — no mutable account row exists.
     */
    @PostMapping
    public ResponseEntity<AccountState> createAccount(@RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.openAccount(request.customerId()));
    }

    @GetMapping
    public List<AccountState> list() {
        return projection.allAccounts();
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
