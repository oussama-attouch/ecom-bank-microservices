package att.ossama.billingservice.repository;

import att.ossama.billingservice.entities.ProductItem;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProductItemRepository extends JpaRepository<ProductItem, Long> {
}