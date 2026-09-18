package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.SagaState;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory saga repository (fallback).
 *
 * Only active with the "inmem" profile; otherwise {@link
 * att.ossama.ledgerservice.persistence.JpaSagaRepository} is used.
 */
@Component
@Profile("inmem")
public class InMemorySagaRepository implements SagaRepository {

    private final Map<String, SagaState> sagas = new ConcurrentHashMap<>();

    @Override
    public SagaState find(String transactionId) {
        return sagas.get(transactionId);
    }

    @Override
    public void save(SagaState saga) {
        sagas.put(saga.getTransactionId(), saga);
    }

    @Override
    public List<SagaState> findAll() {
        return List.copyOf(sagas.values());
    }

    @Override
    public long count() {
        return sagas.size();
    }
}
