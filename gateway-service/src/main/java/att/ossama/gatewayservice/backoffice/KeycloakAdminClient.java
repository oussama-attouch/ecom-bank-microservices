package att.ossama.gatewayservice.backoffice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Minimal client for the Keycloak Admin API, authenticated as the
 * {@code ecom-backoffice} service account.
 *
 * <p>Exists so the gateway — already the only service that trusts Keycloak —
 * is also the only holder of the admin credential. The browser never talks to
 * Keycloak's admin endpoints, and the client secret never leaves this process.
 *
 * <p>Registered only when {@code backoffice.users.enabled=true}, matching the
 * controller and the rest of the feature ("absent, not idle"): with the flag off
 * there is no bean, no token fetch, and no credential read at all.
 *
 * <h2>Token handling</h2>
 *
 * The service-account token comes from the {@code client_credentials} grant and
 * is cached in memory until shortly before {@code expires_in}. Refreshes are
 * <em>single-flight</em>: concurrent callers that arrive with an expired cache
 * share one token request rather than each firing their own. Without that, a
 * page load that issues several admin calls at once would stampede the token
 * endpoint exactly when the cached token expires — the moment it is least
 * welcome.
 */
@Component
@ConditionalOnProperty(name = "backoffice.users.enabled", havingValue = "true")
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    /** Stop using a token this long before Keycloak expires it. */
    private static final Duration EARLY_REFRESH = Duration.ofSeconds(30);

    /** Upper bound on any single call, so a wedged Keycloak cannot hang a request. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    /** Last token obtained, absent until the first call. */
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    /** The refresh currently in progress, if any — this is what makes it single-flight. */
    private final AtomicReference<Mono<CachedToken>> refreshInFlight = new AtomicReference<>();

    public KeycloakAdminClient(
            WebClient.Builder webClientBuilder,
            @Value("${backoffice.keycloak.base-url:http://localhost:8180}") String baseUrl,
            @Value("${backoffice.keycloak.realm:ecom-bank}") String realm,
            @Value("${backoffice.keycloak.client-id:ecom-backoffice}") String clientId,
            @Value("${backoffice.keycloak.client-secret:}") String clientSecret) {

        // Fail closed. An admin surface that starts without its credential answers
        // 500 on every call, which reads as a bug; refusing to start reads as the
        // misconfiguration it is.
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "backoffice.users.enabled=true requires backoffice.keycloak.client-secret "
                            + "(or BACKOFFICE_KEYCLOAK_CLIENT_SECRET); refusing to start without it");
        }

        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * One page of users; {@code search} is Keycloak's substring search.
     *
     * <p>Carries {@code X-Total-Count} back to the caller: the endpoint is paged,
     * so the total number of matches is not derivable from the rows returned.
     */
    public Mono<KeycloakUserPage> listUsers(String search, int first, int max) {
        Optional<String> term = Optional.ofNullable(search)
                .map(String::trim)
                .filter(value -> !value.isEmpty());

        return accessToken().flatMap(token -> http.get()
                .uri(builder -> builder.path("/admin/realms/{realm}/users")
                        .queryParamIfPresent("search", term)
                        .queryParam("first", first)
                        .queryParam("max", max)
                        .build(realm))
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakAdminClient::asAdminException)
                .toEntity(KeycloakUser[].class)
                .map(entity -> new KeycloakUserPage(asList(entity.getBody()), totalOf(entity)))
                .timeout(REQUEST_TIMEOUT));
    }

    private static List<KeycloakUser> asList(KeycloakUser[] body) {
        return body == null ? List.of() : List.of(body);
    }

    private static long totalOf(ResponseEntity<KeycloakUser[]> entity) {
        String header = entity.getHeaders().getFirst("X-Total-Count");
        if (header != null) {
            try {
                return Long.parseLong(header.trim());
            } catch (NumberFormatException ignored) {
                // Fall through: a page size is a worse total than the header, but
                // better than failing the call over a header.
            }
        }
        KeycloakUser[] body = entity.getBody();
        return body == null ? 0 : body.length;
    }

    /**
     * Every user holding a realm role.
     *
     * <p>This is the endpoint that keeps the user list from being an N+1: three
     * of these calls (one per role) describe the whole page, where asking each
     * user for their role mappings would cost one call per row.
     */
    public Mono<List<KeycloakUser>> usersWithRealmRole(String role) {
        return accessToken().flatMap(token -> http.get()
                .uri("/admin/realms/{realm}/roles/{role}/users", realm, role)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakAdminClient::asAdminException)
                .bodyToFlux(KeycloakUser.class)
                .collectList()
                .timeout(REQUEST_TIMEOUT));
    }

    /** One user, or {@link KeycloakAdminException} with status 404. */
    public Mono<KeycloakUser> user(String userId) {
        return accessToken().flatMap(token -> http.get()
                .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakAdminClient::asAdminException)
                .bodyToMono(KeycloakUser.class)
                .timeout(REQUEST_TIMEOUT));
    }

    /** The realm role representation, needed to assign or revoke it. */
    public Mono<RealmRole> realmRole(String name) {
        return accessToken().flatMap(token -> http.get()
                .uri("/admin/realms/{realm}/roles/{name}", realm, name)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakAdminClient::asAdminException)
                .bodyToMono(RealmRole.class)
                .timeout(REQUEST_TIMEOUT));
    }

    /** Realm roles currently assigned to a user, by name. */
    public Mono<Set<String>> realmRolesOf(String userId) {
        return accessToken().flatMap(token -> http.get()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakAdminClient::asAdminException)
                .bodyToFlux(RealmRole.class)
                .collectList()
                .map(roles -> roles.stream()
                        .map(RealmRole::name)
                        .collect(Collectors.toUnmodifiableSet()))
                .timeout(REQUEST_TIMEOUT));
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** Assigns roles the user does not already hold. No-op for an empty list. */
    public Mono<Void> assignRealmRoles(String userId, List<RealmRole> roles) {
        if (roles.isEmpty()) {
            return Mono.empty();
        }
        return accessToken().flatMap(token -> http.post()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(roles)
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakAdminClient::asAdminException)
                .bodyToMono(Void.class)
                .timeout(REQUEST_TIMEOUT));
    }

    /** Revokes roles the user holds. No-op for an empty list. */
    public Mono<Void> revokeRealmRoles(String userId, List<RealmRole> roles) {
        if (roles.isEmpty()) {
            return Mono.empty();
        }
        // DELETE with a body: the role-mappings endpoint requires the roles to
        // remove, and WebClient has to be driven through method(DELETE) for that
        // because delete() has no body variant on the request spec.
        return accessToken().flatMap(token -> http.method(HttpMethod.DELETE)
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(roles)
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakAdminClient::asAdminException)
                .bodyToMono(Void.class)
                .timeout(REQUEST_TIMEOUT));
    }

    /**
     * Enables or disables a user.
     *
     * <p>Sends a partial representation — only {@code enabled} — because Keycloak
     * treats a {@code PUT /users/{id}} as a replace. Rebuilding a full user object
     * from the list response and sending it back would overwrite fields this
     * client never modelled.
     */
    public Mono<Void> setEnabled(String userId, boolean enabled) {
        return accessToken().flatMap(token -> http.put()
                .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", enabled))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakAdminClient::asAdminException)
                .bodyToMono(Void.class)
                .timeout(REQUEST_TIMEOUT));
    }

    // ------------------------------------------------------------------
    // Token
    // ------------------------------------------------------------------

    private Mono<String> accessToken() {
        CachedToken current = cachedToken.get();
        if (current != null && current.valid()) {
            return Mono.just(current.token());
        }
        return Mono.defer(() -> {
            while (true) {
                Mono<CachedToken> inFlight = refreshInFlight.get();
                if (inFlight != null) {
                    return inFlight.map(CachedToken::token);
                }
                // Cold until subscribed, so a CAS that loses costs nothing: this
                // Mono is simply dropped and the winner's is shared instead.
                Mono<CachedToken> fetch = fetchToken()
                        .doOnNext(this::cache)
                        .doFinally(signal -> refreshInFlight.set(null))
                        .cache();
                if (refreshInFlight.compareAndSet(null, fetch)) {
                    return fetch.map(CachedToken::token);
                }
                // Lost the race: loop, read the winner, share it.
            }
        });
    }

    private Mono<CachedToken> fetchToken() {
        return http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakAdminClient::asAdminException)
                .bodyToMono(TokenResponse.class)
                .timeout(REQUEST_TIMEOUT)
                .map(response -> new CachedToken(
                        response.accessToken(),
                        Instant.now().plusSeconds(response.expiresInSeconds()).minus(EARLY_REFRESH)));
    }

    private void cache(CachedToken token) {
        cachedToken.set(token);
        log.info("Keycloak service-account token acquired for client {} in realm {} (valid for {}s)",
                clientId, realm, Math.max(0, Duration.between(Instant.now(), token.expiresAt()).toSeconds()));
    }

    private static Mono<KeycloakAdminException> asAdminException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new KeycloakAdminException(response.statusCode().value(), body));
    }

    /** A token and the instant it stops being usable. */
    private record CachedToken(String token, Instant expiresAt) {

        boolean valid() {
            return Instant.now().isBefore(expiresAt);
        }

        /**
         * Never print the token. A record's generated {@code toString} would
         * include it, and a token in a log line is a token in the log aggregator.
         */
        @Override
        public String toString() {
            return "CachedToken[expiresAt=" + expiresAt + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresInSeconds) {
    }
}
