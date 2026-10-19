package att.ossama.gatewayservice.backoffice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for the backoffice API against a hand-written double of
 * {@link KeycloakAdminClient} — no Spring context, no Mockito, so the guards are
 * exercised directly and quickly.
 *
 * <p>The double is a subclass rather than a mock because the guards are the
 * subject: a mock would let a test assert that a method was called, while this
 * asserts what a manager can actually do to the realm.
 */
class BackofficeUserControllerTest {

    private static final String MANAGER_ID = "u-manager";
    private static final String TELLER_ID = "u-teller";
    private static final String AUDITOR_ID = "u-auditor";

    private final FakeKeycloak keycloak = new FakeKeycloak();
    private final BackofficeUserController controller = new BackofficeUserController(keycloak);

    @BeforeEach
    void seed() {
        // "manager" also carries a non-managed role on purpose: the controller
        // must never touch it.
        keycloak.add(MANAGER_ID, "manager", true, "TELLER", "MANAGER", "offline_access");
        keycloak.add(TELLER_ID, "teller", true, "TELLER", "default-roles-ecom-bank");
        keycloak.add(AUDITOR_ID, "auditor", true, "AUDITOR");
    }

    private static Principal caller(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", subject)
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    @Test
    void listsUsersWithOnlyTheManagedRoles() {
        BackofficeUserPage page = controller.listUsers(null, 0, 20).block();

        assertThat(page.total()).isEqualTo(3);
        BackofficeUser manager = page.users().stream()
                .filter(user -> user.username().equals("manager"))
                .findFirst()
                .orElseThrow();
        assertThat(manager.roles()).containsExactly("TELLER", "MANAGER");
        assertThat(manager.enabled()).isTrue();
    }

    @Test
    void searchIsPassedThroughToKeycloak() {
        BackofficeUserPage page = controller.listUsers("tell", 0, 20).block();

        assertThat(page.users()).extracting(BackofficeUser::username).containsExactly("teller");
    }

    // ------------------------------------------------------------------
    // Role replacement
    // ------------------------------------------------------------------

    @Test
    void rejectsARoleOutsideTheManagedSet() {
        RoleUpdateRequest request = new RoleUpdateRequest(List.of("ADMIN"));

        assertThatThrownBy(() -> controller.replaceRoles(TELLER_ID, request, caller(MANAGER_ID)))
                .isInstanceOfSatisfying(BackofficeException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void appliesTheRoleDifferenceAndLeavesOtherRealmRolesAlone() {
        BackofficeUser updated = controller
                .replaceRoles(TELLER_ID, new RoleUpdateRequest(List.of("AUDITOR")), caller(MANAGER_ID))
                .block();

        assertThat(updated.roles()).containsExactly("AUDITOR");
        assertThat(keycloak.rolesOf(TELLER_ID))
                .contains("AUDITOR", "default-roles-ecom-bank")
                .doesNotContain("TELLER");
    }

    @Test
    void refusesToRemoveTheCallersOwnManagerRole() {
        assertThatThrownBy(() -> controller
                .replaceRoles(MANAGER_ID, new RoleUpdateRequest(List.of("TELLER")), caller(MANAGER_ID))
                .block())
                .isInstanceOfSatisfying(BackofficeException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void refusesToDemoteTheLastEnabledManager() {
        assertThatThrownBy(() -> controller
                .replaceRoles(MANAGER_ID, new RoleUpdateRequest(List.of("TELLER")), caller(TELLER_ID))
                .block())
                .isInstanceOfSatisfying(BackofficeException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void allowsDemotingAManagerWhenAnotherEnabledManagerRemains() {
        keycloak.add("u-manager-2", "manager2", true, "MANAGER");

        BackofficeUser updated = controller
                .replaceRoles(MANAGER_ID, new RoleUpdateRequest(List.of("TELLER")), caller("u-manager-2"))
                .block();

        assertThat(updated.roles()).containsExactly("TELLER");
        assertThat(keycloak.rolesOf(MANAGER_ID)).doesNotContain("MANAGER");
    }

    @Test
    void reportsAnUnknownUserFromTheProvider() {
        assertThatThrownBy(() -> controller
                .replaceRoles("no-such-user", new RoleUpdateRequest(List.of("TELLER")), caller(MANAGER_ID))
                .block())
                .isInstanceOfSatisfying(KeycloakAdminException.class,
                        e -> assertThat(e.status()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // Enable / disable
    // ------------------------------------------------------------------

    @Test
    void refusesToDisableTheCallersOwnAccount() {
        assertThatThrownBy(() -> controller
                .setEnabled(MANAGER_ID, new EnabledUpdateRequest(false), caller(MANAGER_ID))
                .block())
                .isInstanceOfSatisfying(BackofficeException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void refusesToDisableTheLastEnabledManager() {
        assertThatThrownBy(() -> controller
                .setEnabled(MANAGER_ID, new EnabledUpdateRequest(false), caller(TELLER_ID))
                .block())
                .isInstanceOfSatisfying(BackofficeException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void disablesAnotherUser() {
        BackofficeUser updated = controller
                .setEnabled(TELLER_ID, new EnabledUpdateRequest(false), caller(MANAGER_ID))
                .block();

        assertThat(updated.enabled()).isFalse();
        assertThat(keycloak.enabled(TELLER_ID)).isFalse();
    }

    @Test
    void enablesADisabledUser() {
        keycloak.add(AUDITOR_ID, "auditor", false, "AUDITOR");

        BackofficeUser updated = controller
                .setEnabled(AUDITOR_ID, new EnabledUpdateRequest(true), caller(MANAGER_ID))
                .block();

        assertThat(updated.enabled()).isTrue();
    }

    @Test
    void rejectsAnEnabledRequestWithoutTheField() {
        assertThatThrownBy(() -> controller.setEnabled(TELLER_ID, new EnabledUpdateRequest(null), caller(MANAGER_ID)))
                .isInstanceOfSatisfying(BackofficeException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // Double
    // ------------------------------------------------------------------

    /** In-memory Keycloak: users, their realm roles, and their enabled flag. */
    private static final class FakeKeycloak extends KeycloakAdminClient {

        private final Map<String, KeycloakUser> users = new LinkedHashMap<>();
        private final Map<String, Set<String>> roles = new HashMap<>();

        FakeKeycloak() {
            super(WebClient.builder(), "http://localhost:1", "ecom-bank", "ecom-backoffice", "test-secret");
        }

        void add(String id, String username, boolean enabled, String... roleNames) {
            users.put(id, new KeycloakUser(id, username, username + "@ecom-bank.local",
                    username, "Test", enabled));
            roles.put(id, new LinkedHashSet<>(List.of(roleNames)));
        }

        Set<String> rolesOf(String userId) {
            return Set.copyOf(roles.getOrDefault(userId, Set.of()));
        }

        boolean enabled(String userId) {
            return Boolean.TRUE.equals(users.get(userId).enabled());
        }

        @Override
        public Mono<KeycloakUserPage> listUsers(String search, int first, int max) {
            return Mono.defer(() -> {
                List<KeycloakUser> matched = users.values().stream()
                        .filter(user -> search == null || search.isBlank() || user.username().contains(search))
                        .toList();
                return Mono.just(new KeycloakUserPage(matched, matched.size()));
            });
        }

        @Override
        public Mono<List<KeycloakUser>> usersWithRealmRole(String role) {
            return Mono.defer(() -> Mono.just(users.values().stream()
                    .filter(user -> roles.getOrDefault(user.id(), Set.of()).contains(role))
                    .toList()));
        }

        @Override
        public Mono<KeycloakUser> user(String userId) {
            return Mono.defer(() -> {
                KeycloakUser user = users.get(userId);
                return user == null
                        ? Mono.error(new KeycloakAdminException(404, "user not found"))
                        : Mono.just(user);
            });
        }

        @Override
        public Mono<RealmRole> realmRole(String name) {
            return Mono.defer(() -> Mono.just(new RealmRole("role-id-" + name, name)));
        }

        @Override
        public Mono<Set<String>> realmRolesOf(String userId) {
            return Mono.defer(() -> Mono.just(rolesOf(userId)));
        }

        @Override
        public Mono<Void> assignRealmRoles(String userId, List<RealmRole> toAssign) {
            return Mono.fromRunnable(() -> {
                Set<String> current = roles.computeIfAbsent(userId, key -> new LinkedHashSet<>());
                toAssign.forEach(role -> current.add(role.name()));
            });
        }

        @Override
        public Mono<Void> revokeRealmRoles(String userId, List<RealmRole> toRevoke) {
            return Mono.fromRunnable(() -> {
                Set<String> current = roles.get(userId);
                if (current != null) {
                    toRevoke.forEach(role -> current.remove(role.name()));
                }
            });
        }

        @Override
        public Mono<Void> setEnabled(String userId, boolean enabled) {
            return Mono.fromRunnable(() -> {
                KeycloakUser user = users.get(userId);
                if (user != null) {
                    users.put(userId, new KeycloakUser(user.id(), user.username(), user.email(),
                            user.firstName(), user.lastName(), enabled));
                }
            });
        }
    }
}
