package oss.att.orderservice.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import oss.att.orderservice.entities.enums.OrderStatus.OrderStatus;
import oss.att.orderservice.model.Customer;

import java.util.Date;
import java.util.List;

@Entity
@Table(name = "orders")
@Data @NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {
    public static Order Builder;
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Date createdAt;
    private OrderStatus status;
    private Long customerId;
    @Transient
    private Customer customer;
    @OneToMany(mappedBy = "order")
    private List<ProductItem> productItems;


}
