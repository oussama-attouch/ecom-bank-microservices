package att.ossama.inventoryservice.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.*;

import java.util.UUID;


@Entity
@Data @AllArgsConstructor @NoArgsConstructor
@Builder
@Getter @Setter
public class Product {
    @Id  @GeneratedValue(strategy = GenerationType.IDENTITY) // or another strategy suitable for your DB

    private Long id;
    private String name;
    private double price;
    private int quantity;
}
