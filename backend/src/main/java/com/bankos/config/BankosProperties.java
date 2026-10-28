package com.bankos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Application settings under the {@code bankos.*} prefix (see application.yml). */
@ConfigurationProperties(prefix = "bankos")
public record BankosProperties(
        Ai ai,
        Processing processing,
        Storage storage,
        Data data,
        Cors cors
) {

    public enum AiMode {
        LIVE,
        OFFLINE
    }

    /**
     * @param mode           LIVE calls the model; OFFLINE uses deterministic, labelled fallbacks
     * @param timeoutSeconds wall-clock limit for a single model call (including its tool calls)
     * @param maxToolCalls   bounded tool iterations per investigation
     */
    public record Ai(AiMode mode, int timeoutSeconds, int maxToolCalls) {}

    /**
     * @param async                     run POST /process in the background (frontend polls status)
     * @param maxInvestigationsPerBatch cases investigated automatically per batch; the rest can be
     *                                  investigated on demand via POST /api/cases/{id}/investigate
     */
    public record Processing(boolean async, int maxInvestigationsPerBatch) {}

    public record Storage(String uploadDir) {}

    public record Data(String policiesFile, String groundTruthFile) {}

    public record Cors(List<String> allowedOrigins) {}
}
