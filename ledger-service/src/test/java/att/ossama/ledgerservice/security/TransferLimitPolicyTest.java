package att.ossama.ledgerservice.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boundary tests for the teller transfer limit. Plain unit tests: the policy
 * depends only on the roles held in the caller context, so no Spring context is
 * needed and {@link CallerContext} can be populated directly.
 */
class TransferLimitPolicyTest {

    private static CallerContext caller(String... roles) {
        CallerContext context = new CallerContext();
        context.setRoles(new LinkedHashSet<>(List.of(roles)));
        return context;
    }

    private static TransferLimitPolicy policy(String... roles) {
        return new TransferLimitPolicy(caller(roles));
    }

    @Test
    void tellerOverTheLimitIsRejected() {
        assertThatThrownBy(() -> policy("TELLER").assertMayMoveFunds(1_000_000))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("cannot transfer more than $10,000");
    }

    @Test
    void tellerJustOverTheLimitIsRejected() {
        assertThatThrownBy(() -> policy("TELLER").assertMayMoveFunds(10_000.01))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void tellerAtTheLimitIsAllowed() {
        assertThatCode(() -> policy("TELLER").assertMayMoveFunds(10_000)).doesNotThrowAnyException();
    }

    @Test
    void tellerUnderTheLimitIsAllowed() {
        assertThatCode(() -> policy("TELLER").assertMayMoveFunds(5_000)).doesNotThrowAnyException();
    }

    /** A teller who is also a manager is not subject to the teller rule. */
    @Test
    void tellerHoldingManagerRoleIsAllowed() {
        assertThatCode(() -> policy("TELLER", "MANAGER").assertMayMoveFunds(1_000_000))
                .doesNotThrowAnyException();
    }

    @Test
    void managerOverTheLimitIsAllowed() {
        assertThatCode(() -> policy("MANAGER").assertMayMoveFunds(1_000_000)).doesNotThrowAnyException();
    }

    /** Direct-port calls carry no headers during development: no roles, no rule. */
    @Test
    void emptyContextIsAllowed() {
        assertThatCode(() -> policy().assertMayMoveFunds(1_000_000)).doesNotThrowAnyException();
    }

    @Test
    void rolesAreMatchedExactly() {
        assertThatCode(() -> policy("teller").assertMayMoveFunds(1_000_000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy("TELLER", "TELLER_READONLY").assertMayMoveFunds(20_000))
                .isInstanceOf(ForbiddenException.class);
    }
}
