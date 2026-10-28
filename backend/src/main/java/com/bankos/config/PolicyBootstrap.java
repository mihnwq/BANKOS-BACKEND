package com.bankos.config;

import com.bankos.model.CaseStatus;
import com.bankos.model.PolicyVersion;
import com.bankos.repository.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads policy version 1 from synthetic-data/policies.json (or built-in defaults) on first start. */
@Component
public class PolicyBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicyBootstrap.class);

    private final PolicyRepository policyRepository;
    private final BankosProperties properties;
    private final ObjectMapper objectMapper;

    public PolicyBootstrap(PolicyRepository policyRepository, BankosProperties properties, ObjectMapper objectMapper) {
        this.policyRepository = policyRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (policyRepository.count() > 0) {
            return;
        }
        PolicyVersion policy = load();
        policyRepository.save(policy);
        log.info("Loaded policy version {} (tolerance {} {})", policy.getId(), policy.getTolerance(), policy.getCurrency());
    }

    private PolicyVersion load() {
        Path file = Path.of(properties.data().policiesFile());
        if (!Files.isRegularFile(file)) {
            log.warn("Policy file {} not found; using built-in defaults", file.toAbsolutePath());
            return PolicyVersion.defaults();
        }
        try {
            return fromJson(objectMapper.readTree(file.toFile()), "Loaded from " + file.getFileName());
        } catch (RuntimeException e) {
            log.warn("Could not read policy file {}: {}; using built-in defaults", file, e.getMessage());
            return PolicyVersion.defaults();
        }
    }

    /** Parses the snake_case policies.json format. Missing keys fall back to the defaults. */
    public static PolicyVersion fromJson(JsonNode json, String description) {
        PolicyVersion d = PolicyVersion.defaults();
        return new PolicyVersion(
                json.path("version").asInt(d.getId()),
                json.path("currency").asString(d.getCurrency()),
                json.has("amount_tolerance") ? new BigDecimal(json.get("amount_tolerance").asString()) : d.getTolerance(),
                json.path("require_invoice").asBoolean(d.isRequireInvoice()),
                json.path("duplicate_payment_allowed").asBoolean(d.isDuplicateAllowed()),
                status(json, "missing_settlement", d.getMissingSettlement()),
                status(json, "ambiguous_reference", d.getAmbiguousReference()),
                status(json, "unexpected_settlement", d.getUnexpectedSettlement()),
                status(json, "reference_mismatch", d.getReferenceMismatch()),
                status(json, "export_duplicate", d.getExportDuplicate()),
                null,
                description);
    }

    private static CaseStatus status(JsonNode json, String key, CaseStatus fallback) {
        return json.has(key) ? CaseStatus.valueOf(json.get(key).asString()) : fallback;
    }
}
