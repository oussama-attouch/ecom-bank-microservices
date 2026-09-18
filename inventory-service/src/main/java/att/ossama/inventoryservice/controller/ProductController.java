/*
package att.ossama.inventoryservice.controller;

import att.ossama.inventoryservice.entities.Product;
import att.ossama.inventoryservice.repositories.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.hateoas.PagedModel;
import org.springframework.hateoas.Link;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
public class ProductController {

    private final ProductRepository productRepository;

    @Autowired
    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @GetMapping("/api/products")
    public ResponseEntity<PagedModel<Product>> getAllProducts() {
        List<Product> products = productRepository.findAll();
        long totalElements = products.size();
        PagedModel.PageMetadata pageMetadata = new PagedModel.PageMetadata(totalElements, 0, totalElements);
        PagedModel<Product> pagedModel = PagedModel.of(products, pageMetadata);
        return ResponseEntity.ok(pagedModel);
    }

    @GetMapping("/api/products/{id}")
    public ResponseEntity<Product> getProductById(@PathVariable Long id) {
        Optional<Product> product = productRepository.findById(id);
        return product.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
*/
