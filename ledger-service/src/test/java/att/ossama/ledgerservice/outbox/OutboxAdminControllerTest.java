package att.ossama.ledgerservice.outbox;

import att.ossama.ledgerservice.persistence.entity.OutboxEventEntity;
import att.ossama.ledgerservice.persistence.repository.OutboxEventJpaRepository;
import att.ossama.ledgerservice.security.CallerContext;
import att.ossama.ledgerservice.web.ApiExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the admin API contract the Outbox page parses, and the authorization on
 * both endpoints.
 *
 * <p>Standalone MockMvc rather than {@code @WebMvcTest}, matching
 * {@code DashboardControllerTest}: the controller takes an ordinary
 * request-scoped {@link CallerContext} collaborator, so a plain instance with roles
 * set exercises the real check instead of a mocked one, and the advice is wired
 * explicitly so a {@code ForbiddenException} is shown to reach 403 through the same
 * handler production uses.
 *
 * <p>The status precedence is asserted through the wire, not by calling the helper:
 * a published row is given {@code attemptCount = 3} so the "PUBLISHED wins over
 * FAILED" case is the one the test would catch if the order were ever reversed.
 */
@ExtendWith(MockitoExtension.class)
class OutboxAdminControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-08T09:10:11Z");

    /**
     * The mapper the real service serves with: dates as ISO-8601 strings, not as
     * decimal seconds. Standalone MockMvc's default converter leaves
     * {@code WRITE_DATES_AS_TIMESTAMPS} on, which would let this test pass while
     * the browser received numbers the page cannot format — so the converter is
     * supplied explicitly and the wire format is asserted, not assumed.
     */
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Mock private OutboxEventJpaRepository outboxRepository;
    @Mock private OutboxPublisher outboxPublisher;

    private CallerContext caller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        caller = new CallerContext();
        caller.setRoles(Set.of("MANAGER"));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new OutboxAdminController(outboxRepository, outboxPublisher, caller,
                        Clock.fixed(NOW, ZoneOffset.UTC)))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                .build();
    }

    @Test
    void servesTheCountersAndTheNewestRowsWithDerivedStatuses() throws Exception {
        OutboxEventEntity published = row("tx-published", 3);
        published.setPublishedAt(NOW.minusSeconds(1));

        OutboxEventEntity parked = row("tx-parked", 10);
        parked.setParkedAt(NOW.minusSeconds(2));

        OutboxEventEntity failed = row("tx-failed", 2);
        failed.setNextAttemptAt(NOW.plusSeconds(4));
        failed.setLastError("TimeoutException: broker down");

        OutboxEventEntity pending = row("tx-pending", 0);

        when(outboxRepository.count()).thenReturn(4L);
        when(outboxRepository.countByPublishedAtIsNotNull()).thenReturn(1L);
        when(outboxRepository.countByPublishedAtIsNullAndParkedAtIsNotNull()).thenReturn(1L);
        when(outboxRepository.countByPublishedAtIsNullAndParkedAtIsNullAndAttemptCountGreaterThan(0)).thenReturn(1L);
        when(outboxRepository.countByPublishedAtIsNullAndParkedAtIsNullAndAttemptCount(0)).thenReturn(1L);
        when(outboxRepository.findTop50ByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(published, parked, failed, pending));

        MvcResult result = mockMvc.perform(get("/api/admin/outbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.published").value(1))
                .andExpect(jsonPath("$.parked").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.events", hasSize(4)))
                .andExpect(jsonPath("$.events[0].aggregateId").value("tx-published"))
                .andExpect(jsonPath("$.events[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.events[0].attempts").value(3))
                .andExpect(jsonPath("$.events[0].publishedAt").value("2026-07-08T09:10:10Z"))
                .andExpect(jsonPath("$.events[1].status").value("PARKED"))
                .andExpect(jsonPath("$.events[2].status").value("FAILED"))
                .andExpect(jsonPath("$.events[2].lastError").value("TimeoutException: broker down"))
                .andExpect(jsonPath("$.events[3].status").value("PENDING"))
                .andExpect(jsonPath("$.events[3].publishedAt").doesNotExist())
                .andExpect(jsonPath("$.events[0].payload").doesNotExist())
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());

        assertThat(fieldNames(body))
                .as("the six fields the page reads, in declaration order")
                .containsExactly("total", "pending", "published", "failed", "parked", "events");
        assertThat(fieldNames(body.get("events").get(0)))
                .as("the DTO contract, and the deliberate absence of payload")
                .containsExactly("id", "aggregateType", "aggregateId", "eventType",
                        "status", "attempts", "lastError", "createdAt", "publishedAt");
        assertThat(body.get("pending").asLong() + body.get("published").asLong()
                + body.get("failed").asLong() + body.get("parked").asLong())
                .as("the four counters partition the table")
                .isEqualTo(body.get("total").asLong());
    }

    @Test
    void refusesAReaderWithoutTheManagerRole() throws Exception {
        caller.setRoles(Set.of("AUDITOR"));

        mockMvc.perform(get("/api/admin/outbox"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Outbox administration requires MANAGER role"));

        verifyNoInteractions(outboxRepository, outboxPublisher);
    }

    @Test
    void refusesACallerWithNoIdentityAtAll() throws Exception {
        caller.setRoles(Set.of());

        mockMvc.perform(get("/api/admin/outbox"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxRepository);
    }

    @Test
    void forcesAPollAndReportsWhatItPublished() throws Exception {
        when(outboxPublisher.poll()).thenReturn(2);

        mockMvc.perform(post("/api/admin/outbox/poll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(2))
                .andExpect(jsonPath("$.triggeredAt").value(NOW.toString()));

        verify(outboxPublisher).poll();
    }

    @Test
    void refusesToPollWithoutTheManagerRole() throws Exception {
        caller.setRoles(Set.of("TELLER"));

        mockMvc.perform(post("/api/admin/outbox/poll"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(outboxPublisher);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * A row with no publish bookkeeping; the caller stamps exactly the field its
     * scenario needs, so each test states its own status inputs.
     */
    private static OutboxEventEntity row(String aggregateId, int attempts) {
        OutboxEventEntity row = new OutboxEventEntity();
        row.setId(UUID.randomUUID().toString());
        row.setAggregateType("TRANSACTION");
        row.setAggregateId(aggregateId);
        row.setEventType("TRANSFER");
        row.setPayload("{\"transactionId\":\"" + aggregateId + "\"}");
        row.setCreatedAt(NOW.minusSeconds(60));
        row.setAttemptCount(attempts);
        return row;
    }
}
