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
        AccountResponse system = createAccount("system", "XOF", AccountType.SYSTEM);
        executeTransfer(system.getId().toString(), sender.getId().toString(), "500000", "XOF");

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
        AccountResponse system = createAccount("sys2", "XOF", AccountType.SYSTEM);

        executeTransfer(system.getId().toString(), sender.getId().toString(), "100000", "XOF");

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

    private void executeTransfer(String from, String to, String amount, String currency) throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                                "{\"debitAccountId\":\"%s\",\"creditAccountId\":\"%s\",\"amount\":%s,\"currency\":\"%s\"}",
                                from, to, amount, currency)))
                .andExpect(status().isCreated());
    }
}
