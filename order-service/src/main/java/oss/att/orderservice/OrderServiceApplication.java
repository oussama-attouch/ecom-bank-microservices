package oss.att.orderservice;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import oss.att.orderservice.entities.Order;
import oss.att.orderservice.entities.ProductItem;
import oss.att.orderservice.entities.enums.OrderStatus.OrderStatus;
import oss.att.orderservice.model.Customer;
import oss.att.orderservice.model.Product;
import oss.att.orderservice.repositories.OrderRepository;
import oss.att.orderservice.repositories.ProductItemRepository;
import oss.att.orderservice.service.CustomerRestClientService;
import oss.att.orderservice.service.InventoryRestClientService;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Random;

@SpringBootApplication
@EnableFeignClients
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    @Bean
    CommandLineRunner start(OrderRepository orderRepository, ProductItemRepository productItemRepository,
                            CustomerRestClientService customerRCS , InventoryRestClientService inventoryRCS){

        return args -> {

            List<Customer> customers = customerRCS.getAllCustomers().getContent().stream().toList();
            List<Product> products = inventoryRCS.getAllProducts().getContent().stream().toList();

            Long customerId=1L;
            Random random = new Random();
            Customer customer = customerRCS.customerById(customerId);

            for(int i=0;i<20;i++){

                Order order = Order.builder()
                        .customerId(customers.get(random.nextInt(customers.size())).getId())
                        .status(Math.random() > 0.5 ? OrderStatus.PENDING:OrderStatus.CREATED)
                        .createdAt(new Date())
                        .build();

                Order savedOrder  = orderRepository.save(order);

                for(int j=0;j<products.size();j++){
                    if(Math.random()>0.70){
                        ProductItem productItem = ProductItem.builder()
                                .order(savedOrder)
                                .productId(products.get(j).getId())
                                .price(products.get(j).getPrice())
                                .quantity(1+random.nextInt(10))
                                .discount(Math.random())
                                .build();
                        productItemRepository.save(productItem);
                    }
                }
            }
        };
    }
}
