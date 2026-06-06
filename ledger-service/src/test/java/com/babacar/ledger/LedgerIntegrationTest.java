package com.babacar.ledger;

import com.babacar.ledger.service.dto.*;
import com.babacar.ledger.domain.AccountType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LedgerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void transfer_doubleEntry_balancesCorrect() throws Exception {
        // Create sender account
        AccountResponse sender = createAccount("owner_A", "XOF", AccountType.CUSTOMER);
        AccountResponse receiver = createAccount("owner_B", "XOF", AccountType.CUSTOMER);

        // Fund sender via system transfer
        fundAccount(sender.getId().toString(), "500000");

        // Transfer 50000 XOF from sender to receiver
        MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest() {{
                            setDebitAccountId(sender.getId());
                            setCreditAccountId(receiver.getId());
                            setAmount(new BigDecimal("50000"));
                            setCurrency("XOF");
                            setReference("PAY-001");
                        }})))
                .andExpect(status().isCreated())
                .andReturn();

        TransferResponse transfer = objectMapper.readValue(
                result.getResponse().getContentAsString(), TransferResponse.class);

        // Verify balances
        String senderBalance = mockMvc.perform(get("/api/v1/accounts/" + sender.getId() + "/balance"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String receiverBalance = mockMvc.perform(get("/api/v1/accounts/" + receiver.getId() + "/balance"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(senderBalance).contains("450000");
        assertThat(receiverBalance).contains("50000");

        // Verify audit trail — sender has 1 debit entry
        mockMvc.perform(get("/api/v1/accounts/" + sender.getId() + "/entries?type=DEBIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void transfer_insufficientBalance_returns422() throws Exception {
        AccountResponse a = createAccount("owner_C", "XOF", AccountType.CUSTOMER);
        AccountResponse b = createAccount("owner_D", "XOF", AccountType.CUSTOMER);

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest() {{
                            setDebitAccountId(a.getId());
                            setCreditAccountId(b.getId());
                            setAmount(new BigDecimal("999999"));
                            setCurrency("XOF");
                        }})))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void transfer_reversal_restoresBalances() throws Exception {
        AccountResponse sender = createAccount("owner_E", "XOF", AccountType.CUSTOMER);
        AccountResponse receiver = createAccount("owner_F", "XOF", AccountType.CUSTOMER);
        fundAccount(sender.getId().toString(), "100000");

        MvcResult transferResult = mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest() {{
                            setDebitAccountId(sender.getId());
                            setCreditAccountId(receiver.getId());
                            setAmount(new BigDecimal("30000"));
                            setCurrency("XOF");
                        }})))
                .andExpect(status().isCreated())
                .andReturn();

        TransferResponse transfer = objectMapper.readValue(
                transferResult.getResponse().getContentAsString(), TransferResponse.class);

        // Reverse it
        mockMvc.perform(post("/api/v1/transfers/" + transfer.getId() + "/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Customer dispute\"}"))
                .andExpect(status().isOk());

        // Sender balance should be restored to 100000
        mockMvc.perform(get("/api/v1/accounts/" + sender.getId() + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100000));
    }
    @Test
void concurrentTransfers_optimisticLocking_balanceConsistent() throws Exception {
    // Setup
    AccountResponse sender   = createAccount("concurrent_sender", "XOF", AccountType.CUSTOMER);
    AccountResponse receiver = createAccount("concurrent_receiver", "XOF", AccountType.CUSTOMER);
    fundAccount(sender.getId().toString(), "500000");

    int threads       = 10;
    int amountEach    = 10_000;
    int expectedFinal = 500_000 - (threads * amountEach); // 400,000

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch   ready   = new CountDownLatch(threads);
    CountDownLatch   start   = new CountDownLatch(1);
    CountDownLatch   done    = new CountDownLatch(threads);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount    = new AtomicInteger(0);

    for (int i = 0; i < threads; i++) {
        final int idx = i;
        executor.submit(() -> {
            try {
                ready.countDown();
                start.await();

                String body = String.format(
                    "{\"debitAccountId\":\"%s\",\"creditAccountId\":\"%s\"," +
                    "\"amount\":%d,\"currency\":\"XOF\",\"reference\":\"CONCURRENT-%d\"}",
                    sender.getId(), receiver.getId(), amountEach, idx
                );

                mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                       .andDo(result -> {
                           if (result.getResponse().getStatus() == 201)
                               successCount.incrementAndGet();
                           else
                               failCount.incrementAndGet();
                       });

            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
    }

    ready.await();
    start.countDown();
    done.await();
    executor.shutdown();

    // Tous les transferts doivent réussir
    assertThat(successCount.get())
        .as("All concurrent transfers should succeed")
        .isEqualTo(threads);

    assertThat(failCount.get())
        .as("No transfer should fail")
        .isEqualTo(0);

    // Balance finale exacte — pas de double-spend
    String balanceJson = mockMvc.perform(
            get("/api/v1/accounts/" + sender.getId() + "/balance"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

    assertThat(balanceJson)
        .as("Balance must be exactly " + expectedFinal + " XOF")
        .contains(String.valueOf(expectedFinal));

    // Audit trail — exactement 10 entrées DEBIT, pas de doublons
    String entries = mockMvc.perform(
            get("/api/v1/accounts/" + sender.getId() + "/entries?type=DEBIT"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

    long debitCount = entries.chars().filter(c -> c == '{').count();
    assertThat(debitCount)
        .as("Audit trail must contain exactly 10 debit entries")
        .isEqualTo(threads);
}

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AccountResponse createAccount(String ownerId, String currency, AccountType type) throws Exception {
        CreateAccountRequest req = new CreateAccountRequest();
        req.setOwnerId(ownerId);
        req.setCurrency(currency);
        req.setType(type);

        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), AccountResponse.class);
    }

    private void fundAccount(String accountId, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/fund")
            .contentType(MediaType.APPLICATION_JSON)
            .content(String.format(
                    "{\"amount\":%s,\"reference\":\"TEST-FUNDING\"}",
                    amount)))
            .andExpect(status().isCreated());
        }


}
