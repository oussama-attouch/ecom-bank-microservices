package att.ossama.billingservice.model;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter @Setter
public class Product {
    private String id;
    private String name;
    private double price;
    private int quantity;
}