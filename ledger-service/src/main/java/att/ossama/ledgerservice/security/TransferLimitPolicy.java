package att.ossama.ledgerservice.security;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Money-movement rules that depend on who is calling.
 *
 * <p>Single source of truth on purpose. The ledger has three ways to move money
 * out of an account — the saga endpoint ({@code POST /api/transfers}, via
 * {@code TransferSagaService}), and the direct posting endpoint
 * ({@code POST /api/transactions}) with either {@code type=TRANSFER} or
 * {@code type=DEBIT} — and a limit enforced on only some of them is not a limit
 * at all, because the others stay open to the same caller.
 *
 * <p>{@link CallerContext} is request-scoped and injected here as a proxy, so
 * this singleton always sees the roles of the in-flight request.
 */
@Component
public class TransferLimitPolicy {

    /** Maximum amount a TELLER may move without the MANAGER role. */
    public static final BigDecimal TELLER_TRANSFER_LIMIT = new BigDecimal("10000");

    private final CallerContext callerContext;

    public TransferLimitPolicy(CallerContext callerContext) {
        this.callerContext = callerContext;
    }

    /**
     * Rejects an outbound money movement that exceeds the teller limit.
     *
     * <p>Applies to every operation that takes money <em>out</em> of an account
     * (a transfer, or a debit). Crediting an account moves money in and is not
     * subject to the rule.
     *
     * <p>The comparison is decimal, not floating point: the request DTO carries
     * a {@code double}, so it is converted with {@link BigDecimal#valueOf}
     * (which goes through the shortest decimal representation) before being
     * compared with the limit. Exactly 10,000 is allowed; only "more than" is
     * rejected. A caller with neither role, or an unauthenticated direct-port
     * call with an empty context, is not subject to the teller rule.
     *
     * @param amount the amount leaving the account
     * @throws ForbiddenException when a TELLER without MANAGER exceeds the limit
     */
    public void assertMayMoveFunds(double amount) {
        if (callerContext.hasRole("TELLER")
                && !callerContext.hasRole("MANAGER")
                && BigDecimal.valueOf(amount).compareTo(TELLER_TRANSFER_LIMIT) > 0) {
            throw new ForbiddenException(
                    "TELLER role cannot transfer more than $10,000. Manager approval required.");
        }
    }
}
