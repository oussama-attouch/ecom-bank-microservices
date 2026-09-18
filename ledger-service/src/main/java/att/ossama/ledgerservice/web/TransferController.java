package att.ossama.ledgerservice.web;

import att.ossama.ledgerservice.domain.SagaState;
import att.ossama.ledgerservice.saga.SagaRepository;
import att.ossama.ledgerservice.saga.TransferSagaService;
import att.ossama.ledgerservice.web.dto.TransferRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransferController {

    private final TransferSagaService sagaService;
    private final SagaRepository sagaRepository;

    public TransferController(TransferSagaService sagaService, SagaRepository sagaRepository) {
        this.sagaService = sagaService;
        this.sagaRepository = sagaRepository;
    }

    @PostMapping("/transfers")
    public ResponseEntity<?> transfer(@RequestBody TransferRequest request,
                                      @RequestParam(name = "simulateFailure", required = false, defaultValue = "false") boolean simulateFailure) {
        if (request.transactionId() == null || request.transactionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "transactionId is required"));
        }
        try {
            SagaState saga = sagaService.execute(request, simulateFailure);
            return ResponseEntity.ok(saga);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/sagas")
    public List<SagaState> sagas() {
        return sagaRepository.findAll();
    }

    @GetMapping("/sagas/{transactionId}")
    public ResponseEntity<?> saga(@PathVariable String transactionId) {
        SagaState saga = sagaRepository.find(transactionId);
        if (saga == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Saga not found: " + transactionId));
        }
        return ResponseEntity.ok(saga);
    }
}
