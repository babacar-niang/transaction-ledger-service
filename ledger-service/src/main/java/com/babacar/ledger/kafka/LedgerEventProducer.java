package com.babacar.ledger.kafka;

import java.util.Map;

import com.babacar.ledger.domain.Transfer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LedgerEventProducer {

    public static final String TOPIC_TRANSFER_COMPLETED = "ledger-transfer-completed";
    public static final String TOPIC_TRANSFER_REVERSED  = "ledger-transfer-reversed";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishTransferCompleted(Transfer transfer) {
        publish(TOPIC_TRANSFER_COMPLETED, transfer);
    }

    public void publishTransferReversed(Transfer transfer) {
        publish(TOPIC_TRANSFER_REVERSED, transfer);
    }

    private void publish(String topic, Transfer transfer) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "transferId",      transfer.getId(),
                    "debitAccountId",  transfer.getDebitAccountId(),
                    "creditAccountId", transfer.getCreditAccountId(),
                    "amount",          transfer.getAmount(),
                    "currency",        transfer.getCurrency(),
                    "status",          transfer.getStatus()
            ));
            kafkaTemplate.send(topic, transfer.getId().toString(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) log.error("Failed to publish to {}: {}", topic, ex.getMessage());
                        else log.debug("Event published to {} for transfer {}", topic, transfer.getId());
                    });
        } catch (Exception e) {
            log.error("Failed to serialize transfer event: {}", transfer.getId(), e);
        }
    }
}
