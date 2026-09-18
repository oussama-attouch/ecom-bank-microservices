package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.SagaState;

import java.util.List;

/** Persistence seam for saga state; swap the in-memory impl for a DB later. */
public interface SagaRepository {

    SagaState find(String transactionId);

    void save(SagaState saga);

    List<SagaState> findAll();

    /** How many sagas are stored, for seed summaries and health reporting. */
    long count();
}
