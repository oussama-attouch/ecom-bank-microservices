package att.ossama.ledgerservice.feign;

import att.ossama.ledgerservice.model.Customer;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Reads account-holder identity from the existing customer-service so that
 * ledger accounts are mapped from the e-commerce customers.
 */
@FeignClient(name = "customer-service")
public interface CustomerRestClient {

    @GetMapping("/api/customers/{id}")
    Customer getCustomer(@PathVariable("id") Long id);
}
