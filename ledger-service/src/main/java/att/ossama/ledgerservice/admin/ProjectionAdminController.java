package att.ossama.ledgerservice.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative edge over the read side: replay the log and check the read
 * models against it.
 *
 * <p>Registered only when {@code ledger.projection-rebuild.enabled=true} (default
 * {@code false} in application.properties), following
 * {@code DemoHooksController}. When the flag is off the bean is never created, so
 * no handler mapping exists and a caller gets a plain <b>404</b> — the route is
 * absent rather than merely refused. The condition sits on the class on purpose:
 * Spring evaluates {@code @Conditional*} annotations while registering bean
 * definitions, so a condition on a single {@code @RequestMapping} method of an
 * ordinary {@code @RestController} is never consulted by
 * {@code RequestMappingHandlerMapping} and would silently do nothing.
 *
 * <p>Gated despite being read-only, which is the opposite of the call
 * {@code SnapshotController} makes for its own endpoint and worth stating
 * plainly. That one is a read of ledger data that anyone may perform; this is an
 * administrative operation that folds the entire log into memory, and on a real
 * deployment it is not something an arbitrary caller should be able to trigger
 * on demand. The flag is about who may spend the service's resources, not about
 * an invariant it could break — it breaks none.
 *
 * <p>{@code @Profile("!inmem")} for the same reason
 * {@code JpaAccountSummaries} carries it: the verification half compares against
 * the SQL aggregate, which does not exist without a database.
 */
@RestController
@RequestMapping("/api/admin/projections")
@Profile("!inmem")
@ConditionalOnProperty(name = "ledger.projection-rebuild.enabled", havingValue = "true")
public class ProjectionAdminController {

    private final ProjectionRebuilder rebuilder;

    public ProjectionAdminController(ProjectionRebuilder rebuilder) {
        this.rebuilder = rebuilder;
    }

    /**
     * Replay every event and report how the result compares with the read models.
     *
     * <p>Blocks until the replay and the comparison are done, and returns one
     * body. There is deliberately no streaming progress: the work is a single
     * ordered read of the log and an in-memory fold, which on the portfolio
     * ledger is a couple of seconds — not the eleven-second N+1 this service used
     * to serve — and a progress protocol would cost more complexity than it
     * informs. The caller shows an indeterminate indicator for the duration.
     *
     * <p>{@code POST} rather than {@code GET} because the operation is not
     * cacheable and not free: it is an instruction to do work, not a
     * representation to fetch.
     */
    @PostMapping("/rebuild")
    public ProjectionRebuildReport rebuild() {
        return rebuilder.rebuild();
    }
}
