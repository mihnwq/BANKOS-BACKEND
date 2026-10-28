package com.bankos.dto;

import java.util.List;
import java.util.Map;

/** Processing times, anomaly counts and quality metrics for a batch. */
public record MetricsResponse(
        String batchId,
        String batchStatus,
        Long processingTimeMs,
        int payments,
        int settlements,
        int invoices,
        int matchedPayments,
        long totalCases,
        Map<String, Long> casesByType,
        Map<String, Long> casesByStatus,
        long autoMatchedCases,
        long reviewRequiredCases,
        Map<String, Long> investigationsByStatus,
        long toolInvocations,
        long failedExecutions,
        long reviewActions,
        Evaluation evaluation
) {

    /**
     * Detection quality against ground_truth.json. Only computed when the uploaded payments file is
     * byte-identical to the generated synthetic dataset; otherwise {@code available} is false.
     */
    public record Evaluation(
            boolean available,
            String note,
            int expectedAnomalies,
            int detectedAnomalies,
            int truePositives,
            double precision,
            double recall,
            double statusAccuracy,
            List<String> missed,
            List<String> unexpected
    ) {

        public static Evaluation unavailable(String note) {
            return new Evaluation(false, note, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        }
    }
}
