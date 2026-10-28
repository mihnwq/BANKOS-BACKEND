package com.bankos;

import com.bankos.agent.InvestigationAgent;
import com.bankos.agent.InvestigationAgent.InvestigationOutcome;
import com.bankos.agent.InvestigationTools;
import com.bankos.config.AiRuntime;
import com.bankos.config.BankosProperties;
import com.bankos.dto.InvestigationResult;
import com.bankos.model.BankingCase;
import com.bankos.model.CaseType;
import com.bankos.model.InvestigationStatus;
import com.bankos.service.AuditService;
import com.bankos.service.EvidenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Guard rails around the live model: verified evidence IDs, required tool use, timeout, outage. */
class InvestigationAgentTest {

    private static final String CASE_ID = "CASE-B001-PAY-1042-DUP";

    private ExecutorService executor;
    private ChatClient chatClient;
    private EvidenceService evidenceService;
    private InvestigationTools tools;
    private InvestigationAgent agent;
    private final BankingCase bankingCase = new BankingCase(CASE_ID, "B001", CaseType.DUPLICATE_SETTLEMENT);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executor = Executors.newCachedThreadPool();
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(builder);

        evidenceService = mock(EvidenceService.class);
        when(evidenceService.evidenceIds(CASE_ID)).thenReturn(List.of("EV-1", "EV-2", "EV-3"));
        when(evidenceService.getEvidenceJson(CASE_ID)).thenReturn("{\"caseId\":\"" + CASE_ID + "\"}");
        when(evidenceService.getPolicyJson(CASE_ID)).thenReturn("{\"policyVersion\":1}");
        AuditService auditService = mock(AuditService.class);

        tools = new InvestigationTools(evidenceService, auditService);
        AiRuntime live = new AiRuntime(BankosProperties.AiMode.LIVE, Duration.ofMillis(800), 4, executor);
        agent = new InvestigationAgent(provider, tools, live, evidenceService, auditService);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private void modelAnswers(Answer<InvestigationResult> answer) {
        when(chatClient.prompt().system(anyString()).user(anyString()).tools(any()).call()
                .entity(InvestigationResult.class)).thenAnswer(answer);
    }

    @Test
    void verifiedToolDrivenInvestigationIsAccepted() {
        modelAnswers(inv -> {
            tools.getCaseEvidence(CASE_ID);
            tools.getCasePolicy(CASE_ID);
            return new InvestigationResult("Two settlements refer to one payment.", "Duplicate processing",
                    List.of("EV-2", "EV-3"), true);
        });

        InvestigationOutcome outcome = agent.investigateSafely(bankingCase);

        assertThat(outcome.status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(outcome.result().evidenceIds()).containsExactly("EV-2", "EV-3");
    }

    @Test
    void inventedEvidenceIdsAreRejected() {
        modelAnswers(inv -> {
            tools.getCaseEvidence(CASE_ID);
            return new InvestigationResult("summary", "cause", List.of("EV-2", "EV-999"), false);
        });

        InvestigationOutcome outcome = agent.investigateSafely(bankingCase);

        assertThat(outcome.status()).isEqualTo(InvestigationStatus.REJECTED);
        assertThat(outcome.error()).contains("EV-999");
        assertThat(outcome.result().evidenceIds()).isEmpty();
        assertThat(outcome.result().requiresHumanReview()).isTrue();
    }

    @Test
    void answerWithoutRequiredToolCallIsRejected() {
        modelAnswers(inv -> new InvestigationResult("summary", "cause", List.of("EV-1"), false));

        InvestigationOutcome outcome = agent.investigateSafely(bankingCase);

        assertThat(outcome.status()).isEqualTo(InvestigationStatus.REJECTED);
        assertThat(outcome.error()).contains("getCaseEvidence");
    }

    @Test
    void serviceOutageFailsClosed() {
        modelAnswers(inv -> {
            throw new IllegalStateException("503 Service Unavailable");
        });

        InvestigationOutcome outcome = agent.investigateSafely(bankingCase);

        assertThat(outcome.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(outcome.error()).contains("503");
    }

    @Test
    void slowModelIsCutOffByTimeout() {
        modelAnswers(inv -> {
            Thread.sleep(5_000);
            return new InvestigationResult("late", "late", List.of("EV-1"), false);
        });

        InvestigationOutcome outcome = agent.investigateSafely(bankingCase);

        assertThat(outcome.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(outcome.error()).contains("timed out");
    }

    @Test
    void toolsAreScopedToTheCaseAndBudgeted() {
        List<String> responses = new ArrayList<>();
        modelAnswers(inv -> {
            responses.add(tools.getCaseEvidence("CASE-B001-PAY-9999-MISS"));
            for (int i = 0; i < 4; i++) {
                responses.add(tools.getCasePolicy(CASE_ID));
            }
            responses.add(tools.getCaseEvidence(CASE_ID));
            return new InvestigationResult("summary", "cause", List.of("EV-1"), true);
        });

        agent.investigateSafely(bankingCase);

        assertThat(responses.getFirst()).contains("OUT_OF_SCOPE");
        assertThat(responses.getLast()).contains("TOOL_BUDGET_EXCEEDED");
        verify(evidenceService, never()).getEvidenceJson("CASE-B001-PAY-9999-MISS");
    }
}
