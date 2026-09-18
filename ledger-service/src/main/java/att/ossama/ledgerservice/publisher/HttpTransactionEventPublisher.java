package att.ossama.ledgerservice.publisher;

import att.ossama.ledgerservice.domain.TransactionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP publisher (fallback profile). Pushes each committed transaction to the
 * billing-service TransactionProcessor for archival.
 */
@Component
@ConditionalOnProperty(name = "ledger.publisher", havingValue = "http", matchIfMissing = true)
public class HttpTransactionEventPublisher implements TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(HttpTransactionEventPublisher.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${ledger.billing-service-url:http://localhost:8083}")
    private String billingServiceUrl;

    @Override
    public void publish(TransactionRecord record) {
        try {
            doArchive(record);
            log.info("Archived transaction {} at billing-service", record.transactionId());
        } catch (Exception e) {
            log.warn("Could not archive transaction {} at billing-service: {}",
                    record.transactionId(), e.getMessage());
        }
    }

    @Override
    public void publishStrict(TransactionRecord record) {
        doArchive(record);
    }

    private void doArchive(TransactionRecord record) {
        restTemplate.postForEntity(
                billingServiceUrl + "/api/archived-transactions",
                record,
                Void.class);
    }
}
