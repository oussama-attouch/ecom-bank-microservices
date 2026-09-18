package oss.att.orderservice.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import oss.att.orderservice.entities.ProductItem;

@RepositoryRestResource
public interface ProductItemRepository extends JpaRepository<ProductItem, Long> {
}
