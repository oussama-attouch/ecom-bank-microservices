package oss.att.orderservice.service;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.hateoas.PagedModel;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import oss.att.orderservice.model.Customer;
import oss.att.orderservice.model.Product;

import java.util.List;

@FeignClient(name = "inventory-service")
public interface InventoryRestClientService {
    @GetMapping("/api/products/{id}?projection=fullProduct")
    public Product getProductById(@PathVariable Long id);

    @GetMapping("/api/products?projection=fullProduct")
    public PagedModel<Product> getAllProducts();
}
