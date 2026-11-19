package oss.att.orderservice.web;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import oss.att.orderservice.entities.Order;
import oss.att.orderservice.model.Customer;
import oss.att.orderservice.model.Product;
import oss.att.orderservice.repositories.OrderRepository;
import oss.att.orderservice.repositories.ProductItemRepository;
import oss.att.orderservice.service.CustomerRestClientService;
import oss.att.orderservice.service.InventoryRestClientService;

import java.util.NoSuchElementException;

/**
 * Reads an order and enriches it from two other services.
 *
 * <p>This is the only real fan-out in the repository — one call to
 * {@code customer-service} and one per line item to {@code inventory-service} —
 * which makes it the place where a slow downstream turns into a slow
 * {@code order-service}, and then into a slow gateway. It is a <em>read</em>, so
 * a degraded answer is legitimate: 503 with a typed body is a better outcome than
 * a worker parked on a socket, and far better than a 500 stack trace.
 *
 * <h2>Why the three annotations are on the handler</h2>
 * They are on the method that calls out, because that method is the unit the
 * protection describes: the fan-out is the thing that can saturate. In a larger
 * service the same three would sit on the client bean and the controller would
 * stay unaware, but there is no such bean here and inventing one would be a
 * business-logic change.
 *
 * <h2>The failure this does not change</h2>
 * A missing order raises {@link NoSuchElementException} from
 * {@code OrderRepository#findById}, exactly as before. It is in the circuit
 * breaker's ignore list and is re-thrown from the fallback, because "no such
 * order" is a fact about the request, not a health signal about a dependency.
 */
@RestController
public class OrderRestController {

    private static final Logger log = LoggerFactory.getLogger(OrderRestController.class);

    /** Instance name shared by all three patterns; see application.properties. */
    private static final String INSTANCE = "orderEnrichment";

    private final OrderRepository orderRepository;
    private final ProductItemRepository productItemRepository;
    private final CustomerRestClientService customerRestClientService;
    private final InventoryRestClientService inventoryRestClientService;

    public OrderRestController(OrderRepository orderRepository, ProductItemRepository productItemRepository,
                               CustomerRestClientService customerRestClientService, InventoryRestClientService inventoryRestClientService) {
        this.orderRepository = orderRepository;
        this.productItemRepository = productItemRepository;
        this.customerRestClientService = customerRestClientService;
        this.inventoryRestClientService = inventoryRestClientService;
    }

    /**
     * Annotation order on the source line is <strong>not</strong> the execution
     * order. Each pattern is a separate AOP aspect with its own {@code @Order},
     * so the effective nesting is decided by Resilience4j, not by how these lines
     * are written. The required nesting here is
     * Bulkhead (outer) → CircuitBreaker → Retry (inner), for a reason that is not
     * stylistic:
     *
     * <ul>
     *   <li><b>Retry innermost</b> — it must be inside the circuit breaker. If the
     *       breaker were inside, its fallback would turn a failed attempt into a
     *       successful return, the retry would see success, and no retry would
     *       ever happen. A circuit-breaker fallback above a retry is the only
     *       arrangement in which both are real.</li>
     *   <li><b>Circuit breaker outside retry</b> — it sees one outcome per
     *       logical request (a retry-exhausted failure counts once), so the 50%
     *       failure rate means "half of the requests are failing", not "half of
     *       the attempts".</li>
     *   <li><b>Bulkhead outermost</b> — the 20 permits must bound requests, not
     *       attempts. Inside the retry, one request would hold a permit across
     *       three attempts and two backoff sleeps.</li>
     * </ul>
     */
    @GetMapping("/fullOrder/{id}")
    @Bulkhead(name = INSTANCE, fallbackMethod = "orderUnavailable")
    @CircuitBreaker(name = INSTANCE, fallbackMethod = "orderUnavailable")
    @Retry(name = INSTANCE)
    public ResponseEntity<Object> getOrder(@PathVariable Long id) {
        Order order = orderRepository.findById(id).get();
        Customer customer = customerRestClientService.customerById(order.getCustomerId());
        order.setCustomer(customer);
        order.getProductItems().forEach(productItem -> {
            Product product = inventoryRestClientService.getProductById(productItem.getProductId());
            productItem.setProduct(product);
        });
        return ResponseEntity.ok().body(order);
    }

    /**
     * Reached on an open circuit, a full bulkhead, or a retry-exhausted
     * connectivity failure. Answers what is actually true: this service is up, a
     * dependency is not, and the request can be retried shortly.
     *
     * <p>The signature is fixed by Resilience4j — same parameters as the guarded
     * method plus the {@link Throwable} — and {@code Throwable} is deliberately
     * the widest type so one method covers every rejection class the three
     * patterns can raise.
     */
    ResponseEntity<Object> orderUnavailable(Long id, Throwable cause) {
        // A missing order is not a dependency failure. Let it keep its original
        // path to the container rather than relabelling it as 503.
        if (cause instanceof NoSuchElementException missing) {
            throw missing;
        }
        log.warn("ORDER_ENRICHMENT_DEGRADED: order {} answered 503 because {}: {}",
                id, cause.getClass().getSimpleName(), cause.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.dependencyUnavailable(
                        "Order enrichment is unavailable: customer-service or inventory-service "
                                + "did not answer. The order itself was read; retry shortly."));
    }
}
