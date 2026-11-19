package att.ossama.billingservice;

import att.ossama.billingservice.feign.CustomerRestClient;
import att.ossama.billingservice.feign.ProductRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.hateoas.PagedModel;

import static org.mockito.Mockito.when;

@SpringBootTest
@Import(BillingServiceApplicationTests.StubRemoteCatalogConfiguration.class)
class BillingServiceApplicationTests {

    /**
     * The application's {@code CommandLineRunner} seeds bills from
     * customer-service and inventory-service on startup. In a full reactor run
     * that call races the Eureka client's first registry fetch and fails with
     * {@code UnknownHostException: inventory-service} — a network dependency in
     * a test whose subject is "does the context start".
     */
    @MockBean
    private CustomerRestClient customerRestClient;

    @MockBean
    private ProductRestClient productRestClient;

    /**
     * Stubs the two clients before the application's runner reads them. A
     * {@code CommandLineRunner} is the only hook that is guaranteed to execute
     * before another runner, so ordering decides it: this one is
     * {@link Ordered#HIGHEST_PRECEDENCE}, the seeding runner is unordered.
     */
    @TestConfiguration
    static class StubRemoteCatalogConfiguration {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        CommandLineRunner stubRemoteCatalog(CustomerRestClient customers, ProductRestClient products) {
            return args -> {
                when(customers.getAllCustomers()).thenReturn(PagedModel.empty());
                when(products.getAllProducts()).thenReturn(PagedModel.empty());
            };
        }
    }

    @Test
    void contextLoads() {
    }
}
