package att.ossama.gatewayservice.backoffice;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Backoffice access-administration API: list users, replace a user's realm
 * roles, and enable or disable an account.
 *
 * <p>Manager-only, but not enforced here — {@code SecurityConfig} puts
 * {@code hasRole("MANAGER")} in front of {@code /backoffice/**}, so a request
 * that reaches this class has already been authorized. The guards that <em>are</em>
 * here are about lockout, not role: a manager may not remove their own MANAGER
 * role, disable their own account, or demote/disable the last enabled manager.
 *
 * <p>Registered only when {@code backoffice.users.enabled=true}. With the flag
 * off there is no bean and the path answers 404, which is the same "absent, not
 * refused" contract the ledger's projection rebuild route uses.
 *
 * <h2>Roles are never invented or removed wholesale</h2>
 *
 * Only {@link #MANAGED_ROLES} may be granted or revoked. Keycloak holds other
 * realm roles on every user ({@code default-roles-ecom-bank}, {@code offline_access},
 * {@code uma_authorization}); a naive "set roles to this list" that revoked
 * everything absent from it would quietly strip a composite role and change
 * what the account can do in ways this screen does not describe.
 */
@RestController
@RequestMapping("/backoffice/users")
@ConditionalOnProperty(name = "backoffice.users.enabled", havingValue = "true")
public class BackofficeUserController {

    /** The only roles this feature may grant or revoke, in display order. */
    static final List<String> MANAGED_ROLES = List.of("TELLER", "MANAGER", "AUDITOR");

    static final String MANAGER = "MANAGER";

    /** Upper bound on a page, so one request cannot ask Keycloak for everything. */
    private static final int MAX_PAGE_SIZE = 200;

    private final KeycloakAdminClient keycloak;

    public BackofficeUserController(KeycloakAdminClient keycloak) {
        this.keycloak = keycloak;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /**
     * One page of users with their managed roles.
     *
     * <p>The merge is the point: one user-page call plus three
     * {@code /roles/{role}/users} calls describe the entire page, where asking
     * Keycloak for each user's role mappings would cost one request per row.
     */
    @GetMapping
    public Mono<BackofficeUserPage> listUsers(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int first,
            @RequestParam(defaultValue = "20") int max) {

        int safeFirst = Math.max(0, first);
        int safeMax = Math.min(Math.max(1, max), MAX_PAGE_SIZE);

        return Mono.zip(
                        keycloak.listUsers(search, safeFirst, safeMax),
                        managedRolesByUser())
                .map(tuple -> {
                    KeycloakUserPage page = tuple.getT1();
                    Map<String, Set<String>> rolesByUser = tuple.getT2();
                    List<BackofficeUser> users = page.users().stream()
                            .map(user -> toBackofficeUser(user, rolesByUser.getOrDefault(user.id(), Set.of())))
                            .toList();
                    return new BackofficeUserPage(page.total(), safeFirst, safeMax, users);
                });
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @PutMapping("/{id}/roles")
    public Mono<BackofficeUser> replaceRoles(
            @PathVariable String id,
            @RequestBody(required = false) RoleUpdateRequest request,
            Principal caller) {

        List<String> desired = validatedRoles(request);
        String callerId = callerSubject(caller);

        return keycloak.user(id)
                .flatMap(user -> keycloak.realmRolesOf(id)
                        .flatMap(current -> guardManagerRemoval(id, callerId, user, current, desired)
                                .then(applyRoleChanges(id, current, desired)))
                        .then(refresh(id)));
    }

    @PutMapping("/{id}/enabled")
    public Mono<BackofficeUser> setEnabled(
            @PathVariable String id,
            @RequestBody(required = false) EnabledUpdateRequest request,
            Principal caller) {

        Boolean enabled = (request == null) ? null : request.enabled();
        if (enabled == null) {
            throw new BackofficeException(400, "'enabled' is required");
        }
        String callerId = callerSubject(caller);

        return keycloak.user(id)
                .flatMap(user -> {
                    if (enabled) {
                        // Enabling can never lock anyone out of anything.
                        return keycloak.setEnabled(id, true).then(refresh(id));
                    }
                    return guardDisable(id, callerId, user)
                            .then(keycloak.setEnabled(id, false))
                            .then(refresh(id));
                });
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    /**
     * Refuses a change that would remove the MANAGER role from the caller, or
     * from the only enabled manager left.
     */
    private Mono<Void> guardManagerRemoval(String targetId, String callerId, KeycloakUser target,
                                           Set<String> currentRoles, List<String> desiredRoles) {
        boolean losingManager = currentRoles.contains(MANAGER) && !desiredRoles.contains(MANAGER);
        if (!losingManager) {
            return Mono.empty();
        }
        if (targetId.equals(callerId)) {
            return Mono.error(new BackofficeException(409,
                    "you cannot remove your own MANAGER role"));
        }
        if (!Boolean.TRUE.equals(target.enabled())) {
            // A disabled manager cannot sign in, so demoting them cannot reduce
            // the number of managers who still can.
            return Mono.empty();
        }
        return keycloak.usersWithRealmRole(MANAGER)
                .flatMap(managers -> {
                    long enabledManagers = managers.stream()
                            .filter(user -> Boolean.TRUE.equals(user.enabled()))
                            .count();
                    return enabledManagers <= 1
                            ? Mono.<Void>error(new BackofficeException(409,
                                    "cannot remove MANAGER from the last enabled manager"))
                            : Mono.<Void>empty();
                });
    }

    /** Refuses disabling the caller, or the only enabled manager left. */
    private Mono<Void> guardDisable(String targetId, String callerId, KeycloakUser target) {
        if (targetId.equals(callerId)) {
            return Mono.error(new BackofficeException(409, "you cannot disable your own account"));
        }
        if (!Boolean.TRUE.equals(target.enabled())) {
            // Already disabled: re-disabling is a no-op, not a lockout.
            return Mono.empty();
        }
        return keycloak.usersWithRealmRole(MANAGER)
                .flatMap(managers -> {
                    boolean targetIsManager = managers.stream()
                            .anyMatch(user -> targetId.equals(user.id()));
                    long enabledManagers = managers.stream()
                            .filter(user -> Boolean.TRUE.equals(user.enabled()))
                            .count();
                    return targetIsManager && enabledManagers <= 1
                            ? Mono.<Void>error(new BackofficeException(409,
                                    "cannot disable the last enabled manager"))
                            : Mono.<Void>empty();
                });
    }

    // ------------------------------------------------------------------
    // Mechanics
    // ------------------------------------------------------------------

    /**
     * Applies the difference between the user's current managed roles and the
     * desired set, leaving every non-managed role untouched.
     */
    private Mono<Void> applyRoleChanges(String userId, Set<String> currentRoles, List<String> desiredRoles) {
        Set<String> currentManaged = currentRoles.stream()
                .filter(MANAGED_ROLES::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> desired = new LinkedHashSet<>(desiredRoles);

        List<String> toAssign = desired.stream()
                .filter(role -> !currentManaged.contains(role))
                .toList();
        List<String> toRevoke = currentManaged.stream()
                .filter(role -> !desired.contains(role))
                .toList();

        return rolesNamed(toAssign)
                .flatMap(roles -> keycloak.assignRealmRoles(userId, roles))
                .then(rolesNamed(toRevoke))
                .flatMap(roles -> keycloak.revokeRealmRoles(userId, roles));
    }

    /** Resolves role names to the representations Keycloak needs to match them by id. */
    private Mono<List<RealmRole>> rolesNamed(List<String> names) {
        return Flux.fromIterable(names)
                .flatMap(keycloak::realmRole)
                .collectList();
    }

    private Mono<BackofficeUser> refresh(String userId) {
        return Mono.zip(keycloak.user(userId), keycloak.realmRolesOf(userId))
                .map(tuple -> toBackofficeUser(tuple.getT1(), tuple.getT2()));
    }

    /**
     * The role membership of every managed role, as user id to role names.
     *
     * <p>Three calls, not one per user. The role-membership lists are fetched
     * whole while the user list is paged: at this realm's size that is three
     * cheap requests, and the alternative (a per-row role-mapping call) grows
     * with the page.
     */
    private Mono<Map<String, Set<String>>> managedRolesByUser() {
        return Flux.fromIterable(MANAGED_ROLES)
                .flatMap(role -> keycloak.usersWithRealmRole(role)
                        .map(users -> new RoleMembers(role, users)))
                .collectList()
                .map(members -> {
                    Map<String, Set<String>> rolesByUser = new HashMap<>();
                    for (RoleMembers member : members) {
                        for (KeycloakUser user : member.users()) {
                            rolesByUser.computeIfAbsent(user.id(), key -> new LinkedHashSet<>())
                                    .add(member.role());
                        }
                    }
                    return rolesByUser;
                });
    }

    private static BackofficeUser toBackofficeUser(KeycloakUser user, Set<String> roles) {
        List<String> managedRoles = roles.stream()
                .filter(MANAGED_ROLES::contains)
                .sorted(Comparator.comparingInt(MANAGED_ROLES::indexOf))
                .toList();
        return new BackofficeUser(
                user.id(),
                user.username(),
                user.email(),
                user.firstName(),
                user.lastName(),
                Boolean.TRUE.equals(user.enabled()),
                managedRoles);
    }

    private static List<String> validatedRoles(RoleUpdateRequest request) {
        if (request == null || request.roles() == null) {
            throw new BackofficeException(400, "'roles' is required");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String role : request.roles()) {
            if (role == null || !MANAGED_ROLES.contains(role)) {
                throw new BackofficeException(400,
                        "unknown role '" + role + "'; allowed roles are " + MANAGED_ROLES);
            }
            unique.add(role);
        }
        return List.copyOf(unique);
    }

    /**
     * The caller's Keycloak user id — the {@code sub} claim, which is the same
     * value used as {@code {id}} in these routes, so "is this me?" is a string
     * comparison rather than another lookup.
     */
    private static String callerSubject(Principal caller) {
        if (caller instanceof JwtAuthenticationToken token) {
            String subject = token.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        // Unreachable behind the MANAGER rule, but failing closed beats
        // dereferencing a null and turning a guard into an exception.
        throw new BackofficeException(403, "caller identity is not available");
    }

    private record RoleMembers(String role, List<KeycloakUser> users) {
    }
}
