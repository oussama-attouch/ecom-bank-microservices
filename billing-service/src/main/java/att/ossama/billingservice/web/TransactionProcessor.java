package att.ossama.billingservice.web;

import att.ossama.billingservice.entities.ArchivedTransaction;
import att.ossama.billingservice.repository.ArchivedTransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * TransactionProcessor: listens for committed ledger transactions (pushed by
 * ledger-service's event publisher) and archives them.
 *
 * When Kafka is introduced this becomes a @KafkaListener consumer of the
 * "transactions" topic; the ledger's publisher is the producer side.
 */
@RestController
@RequestMapping("/api/archived-transactions")
public class TransactionProcessor {

    private final ArchivedTransactionRepository repository;

    public TransactionProcessor(ArchivedTransactionRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<ArchivedTransaction> archive(@RequestBody ArchivedTransaction transaction) {
        ArchivedTransaction saved = repository.save(transaction);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping
    public List<ArchivedTransaction> archived() {
        return repository.findAll();
    }
}
