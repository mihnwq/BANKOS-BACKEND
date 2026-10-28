package com.bankos.service;

import com.bankos.agent.InvestigationTools;
import com.bankos.config.BankosProperties;
import com.bankos.dto.MetricsResponse;
import com.bankos.model.BankingCase;
import com.bankos.model.Batch;
import com.bankos.model.CaseStatus;
import com.bankos.model.ExecutionState;
import com.bankos.repository.AgentExecutionRepository;
import com.bankos.repository.AuditEventRepository;
import com.bankos.repository.CaseRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Batch metrics: processing time, anomaly counts, review load, tool usage and, for the generated
 * synthetic dataset, precision/recall against ground_truth.json. Measured values only; no projections.
 */
@Service
public class MetricsService {

    private final BatchService batchService;
    private final CaseRepository caseRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final AuditEventRepository auditEventRepository;
    private final BankosProperties properties;
    private final ObjectMapper objectMapper;

    public MetricsService(BatchService batchService, CaseRepository caseRepository,
                          AgentExecutionRepository agentExecutionRepository, AuditEventRepository auditEventRepository,
                          BankosProperties properties, ObjectMapper objectMapper) {
        this.batchService = batchService;
        this.caseRepository = caseRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.auditEventRepository = auditEventRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public MetricsResponse metrics(String batchId) {
        Batch batch = batchService.getBatch(batchId);
        List<BankingCase> cases = caseRepository.findByBatchIdOrderByIdAsc(batchId);
        Map<String, Long> byType = cases.stream()
                .collect(Collectors.groupingBy(c -> c.getType().name(), TreeMap::new, Collectors.counting()));
        Map<String, Long> byStatus = cases.stream()
                .collect(Collectors.groupingBy(c -> c.getStatus().name(), TreeMap::new, Collectors.counting()));
        Map<String, Long> byInvestigation = cases.stream()
                .collect(Collectors.groupingBy(c -> c.getInvestigationStatus().name(), TreeMap::new, Collectors.counting()));
        long reviewActions = auditEventRepository.findByBatchIdOrderByIdAsc(batchId).stream()
                .filter(e -> e.getAction().startsWith("REVIEW_")).count();

        return new MetricsResponse(batchId, batch.getStatus().name(), batch.getDurationMs(),
                batch.getPaymentCount(), batch.getSettlementCount(), batch.getInvoiceCount(),
                batch.getMatchedPaymentCount(), cases.size(), byType, byStatus,
                byStatus.getOrDefault(CaseStatus.AUTO_MATCHED.name(), 0L),
                byStatus.getOrDefault(CaseStatus.REVIEW_REQUIRED.name(), 0L),
                byInvestigation,
                agentExecutionRepository.countByBatchIdAndAgentAndToolNotNull(batchId, InvestigationTools.AGENT),
                agentExecutionRepository.countByBatchIdAndState(batchId, ExecutionState.FAILED),
                reviewActions,
                evaluate(batch, cases));
    }

    private MetricsResponse.Evaluation evaluate(Batch batch, List<BankingCase> cases) {
        Path file = Path.of(properties.data().groundTruthFile());
        if (!Files.isRegularFile(file)) {
            return MetricsResponse.Evaluation.unavailable("ground_truth.json not found at " + file);
        }
        JsonNode truth;
        try {
            truth = objectMapper.readTree(file.toFile());
        } catch (RuntimeException e) {
            return MetricsResponse.Evaluation.unavailable("ground_truth.json unreadable: " + e.getMessage());
        }
        String expectedSha = truth.path("payments_sha256").asString("");
        if (!expectedSha.equalsIgnoreCase(batch.getPaymentsSha256())) {
            return MetricsResponse.Evaluation.unavailable(
                    "Uploaded payments file is not the generated synthetic dataset; no ground truth for this batch.");
        }

        Map<String, String> expected = new TreeMap<>();
        for (JsonNode anomaly : truth.path("anomalies")) {
            expected.put(anomaly.path("type").asString() + "|" + anomaly.path("subject_id").asString(),
                    anomaly.path("expected_status").asString(""));
        }
        Map<String, BankingCase> detected = new HashMap<>();
        cases.forEach(c -> detected.put(c.getType().name() + "|" + c.getSubjectId(), c));

        List<String> missed = expected.keySet().stream().filter(k -> !detected.containsKey(k)).sorted().toList();
        List<String> unexpected = detected.keySet().stream().filter(k -> !expected.containsKey(k)).sorted().toList();
        int truePositives = expected.size() - missed.size();
        long statusCorrect = expected.entrySet().stream()
                .filter(e -> detected.containsKey(e.getKey()))
                .filter(e -> detected.get(e.getKey()).getStatus().name().equals(e.getValue()))
                .count();
        boolean sameVersion = truth.path("policy_version").asInt(1) == (batch.getPolicyVersionId() == null ? -1 : batch.getPolicyVersionId());

        return new MetricsResponse.Evaluation(true,
                "Synthetic dataset seed " + truth.path("seed").asInt() + (sameVersion ? ""
                        : "; status accuracy assumes policy v" + truth.path("policy_version").asInt(1)),
                expected.size(), detected.size(), truePositives,
                ratio(truePositives, detected.size()), ratio(truePositives, expected.size()),
                ratio((int) statusCorrect, truePositives), missed, unexpected);
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : Math.round(10000.0 * numerator / denominator) / 10000.0;
    }
}
