package att.ossama.ledgerservice.saga;

import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.domain.TransactionRecord;
import att.ossama.ledgerservice.outbox.OutboxArchiveStep;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the transaction boundary itself.
 *
 * <p>BLK-1: an annotation on the saga would never run, because the controller
 * calls the two-argument overload and that overload self-invokes the four-argument
 * one, which never crosses the proxy. BLK-2: a saga-wide transaction would roll
 * back the {@code FAILED} steps, the compensations and the {@code COMPENSATING}
 * status, because the saga writes them after catching. Both are structural facts,
 * so they are asserted structurally — a later "cleanup" that folds the annotation
 * back onto the saga fails here instead of in production.
 */
class OutboxArchiveStepStructureTest {

    @Test
    void theArchiveWriterCarriesTheTransactionOnItsBoundaryMethod() throws Exception {
        Method archive = OutboxArchiveStep.class.getMethod(
                "archive", SagaState.class, TransactionRecord.class, Instant.class, boolean.class);

        assertThat(archive.isAnnotationPresent(Transactional.class))
                .as("OutboxArchiveStep.archive must open the transaction")
                .isTrue();
        assertThat(OutboxArchiveStep.class.isAnnotationPresent(Transactional.class))
                .as("the annotation belongs to the boundary method, not to the whole bean")
                .isFalse();
    }

    @Test
    void theSagaDeclaresNoTransactionOfItsOwn() {
        assertThat(TransferSagaService.class.isAnnotationPresent(Transactional.class))
                .as("a class-level annotation would cover the compensations too")
                .isFalse();

        for (Method method : TransferSagaService.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Transactional.class))
                    .as("%s must not be transactional: the saga writes after catching", method.getName())
                    .isFalse();
        }
    }

    @Test
    void theSagaCannotSelfInvokeTheBoundary() {
        assertThat(TransferSagaService.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain("archive", "saveOutboxRow", "writeArchive");
    }
}
