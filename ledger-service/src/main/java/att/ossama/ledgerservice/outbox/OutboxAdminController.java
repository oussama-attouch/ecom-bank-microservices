package att.ossama.ledgerservice.outbox;

import att.ossama.ledgerservice.outbox.dto.OutboxEventDto;
import att.ossama.ledgerservice.outbox.dto.OutboxPollResponse;
import att.ossama.ledgerservice.outbox.dto.OutboxStatusResponse;
import att.ossama.ledgerservice.persistence.entity.OutboxEventEntity;
import att.ossama.ledgerservice.persistence.repository.OutboxEventJpaRepository;
import att.ossama.ledgerservice.security.CallerContext;
import att.ossama.ledgerservice.security.ForbiddenException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/**
 * The outbox as an administrative surface: what the relay is holding, and a way to
 * run it on demand.
 *
 * <h2>Gate</h2>
 * Registered only when {@code ledger.outbox.enabled=true}, following
 * {@code ProjectionAdminController}: with the flag off there is no bean and no
 * handler mapping, so {@code /api/admin/outbox} is a plain <b>404</b>. The route is
 * absent rather than refused, which is the same "absent, not idle" rule every other
 * gate in this service follows.
 *
 * <h2>Authorization</h2>
 * {@code MANAGER} on both endpoints. This service has no Spring Security on
 * purpose, so there is no {@code @PreAuthorize} to attach: the check is
 * {@link CallerContext#hasRole} plus {@link ForbiddenException}, which
 * {@code ApiExceptionHandler} maps to <b>403</b>. Both layers of the deployment
 * matter — the gateway refuses the request at the edge, and this check is what
 * refuses a direct call to port 8085 that arrives with no identity headers at all.
 *
 * <h2>Status</h2>
 * Derived here, once, by the precedence rule the UI and the counters both use:
 * published, then parked, then "attempted and waiting", then "never attempted".
 * Order matters: a row that succeeded on its third attempt has both a
 * {@code publishedAt} and an {@code attemptCount} above zero and must read
 * {@code PUBLISHED}. The four counter predicates are the same rule expressed as
 * SQL, so the numbers and the rows cannot disagree about what a status means.
 */
@RestController
@RequestMapping("/api/admin/outbox")
@ConditionalOnProperty(name = "ledger.outbox.enabled", havingValue = "true")
public class OutboxAdminController {

    /** The role that may read the outbox and force a poll; the realm has no ADMIN. */
    static final String MANAGER_ROLE = "MANAGER";

    static final String STATUS_PUBLISHED = "PUBLISHED";
    static final String STATUS_PARKED = "PARKED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_PENDING = "PENDING";

    private final OutboxEventJpaRepository outboxRepository;
    private final OutboxPublisher outboxPublisher;
    private final CallerContext callerContext;
    private final Clock clock;

    public OutboxAdminController(OutboxEventJpaRepository outboxRepository,
                                 OutboxPublisher outboxPublisher,
                                 CallerContext callerContext,
                                 Clock clock) {
        this.outboxRepository = outboxRepository;
        this.outboxPublisher = outboxPublisher;
        this.callerContext = callerContext;
        this.clock = clock;
    }

    /**
     * The whole outbox as of now: counters over every row, and the newest fifty.
     *
     * <p>Deliberately not transactional. The counters and the page are separate
     * statements, so a row can move between them by a millisecond's worth of relay
     * activity; both halves are individually true, and nothing here needs a
     * consistent snapshot of a live queue. Wrapping it in a transaction would buy
     * that at the cost of a read transaction held for a dashboard refresh.
     */
    @GetMapping
    public OutboxStatusResponse status() {
        requireManager();

        long total = outboxRepository.count();
        long published = outboxRepository.countByPublishedAtIsNotNull();
        long parked = outboxRepository.countByPublishedAtIsNullAndParkedAtIsNotNull();
        long failed = outboxRepository.countByPublishedAtIsNullAndParkedAtIsNullAndAttemptCountGreaterThan(0);
        long pending = outboxRepository.countByPublishedAtIsNullAndParkedAtIsNullAndAttemptCount(0);

        List<OutboxEventDto> events = outboxRepository.findTop50ByOrderByCreatedAtDescIdDesc().stream()
                .map(OutboxAdminController::toDto)
                .toList();

        return new OutboxStatusResponse(total, pending, published, failed, parked, events);
    }

    /**
     * Runs one poll synchronously in the request thread and reports what it
     * published.
     *
     * <p>{@code POST} because it does work rather than fetch a representation —
     * the same argument the projection rebuild endpoint records. It shares the
     * relay's single-flight guard with the scheduled tick, so a poll that lands
     * while one is already running publishes nothing and answers {@code processed:
     * 0}; it does not queue a second batch.
     */
    @PostMapping("/poll")
    public OutboxPollResponse poll() {
        requireManager();
        int processed = outboxPublisher.poll();
        return new OutboxPollResponse(clock.instant(), processed);
    }

    private void requireManager() {
        if (!callerContext.hasRole(MANAGER_ROLE)) {
            throw new ForbiddenException("Outbox administration requires " + MANAGER_ROLE + " role");
        }
    }

    /**
     * One row, mapped field by field. {@code status} is the only computed value;
     * everything else is the column, renamed where the client's name is better
     * ({@code attempts} for {@code attempt_count}).
     */
    private static OutboxEventDto toDto(OutboxEventEntity entity) {
        return new OutboxEventDto(
                entity.getId(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getEventType(),
                statusOf(entity),
                entity.getAttemptCount(),
                entity.getLastError(),
                entity.getCreatedAt(),
                entity.getPublishedAt());
    }

    /**
     * The status rule, first match wins: published, parked, attempted, untouched.
     *
     * <p>Read in this order and no other. A published row that needed three
     * attempts still has {@code attemptCount = 3}, and reading the attempt count
     * first would file a success under FAILED.
     */
    static String statusOf(OutboxEventEntity entity) {
        if (entity.getPublishedAt() != null) {
            return STATUS_PUBLISHED;
        }
        if (entity.getParkedAt() != null) {
            return STATUS_PARKED;
        }
        if (entity.getAttemptCount() > 0) {
            return STATUS_FAILED;
        }
        return STATUS_PENDING;
    }
}
