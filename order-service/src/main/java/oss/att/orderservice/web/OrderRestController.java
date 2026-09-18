package oss.att.orderservice.web;

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

//Communique avec la base de donnes
@RestController
public class OrderRestController {
    private OrderRepository orderRepository;
    private ProductItemRepository productItemRepository;
    private CustomerRestClientService customerRestClientService; //communique avec customer service
    private InventoryRestClientService inventoryRestClientService; //communique avec Inventory service

    public OrderRestController(OrderRepository orderRepository, ProductItemRepository productItemRepository,
                               CustomerRestClientService customerRestClientService, InventoryRestClientService inventoryRestClientService) {
        this.orderRepository = orderRepository;
        this.productItemRepository = productItemRepository;
        this.customerRestClientService = customerRestClientService;
        this.inventoryRestClientService = inventoryRestClientService;
    }

    @GetMapping("/fullOrder/{id}")
    public Order getOrder(@PathVariable Long id){
        Order order = orderRepository.findById(id).get();
        Customer customer = customerRestClientService.customerById(order.getCustomerId());
        order.setCustomer(customer);
        order.getProductItems().forEach(productItem -> {
            Product product = inventoryRestClientService.getProductById(productItem.getProductId());
            productItem.setProduct(product);
        });
        return order;
    }
}
