package com.bankos;

import com.bankos.agent.PolicyAgent;
import com.bankos.dto.Discrepancy;
import com.bankos.dto.PolicyDecision;
import com.bankos.model.CaseStatus;
import com.bankos.model.CaseType;
import com.bankos.model.PolicyVersion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyAgentTest {

    private final PolicyAgent agent = new PolicyAgent();
    private final PolicyVersion v1 = PolicyVersion.defaults();

    @Test
    void differenceWithinToleranceIsAutoMatched() {
        PolicyDecision decision = agent.evaluate(amountMismatch("-4.00", "EUR", false), v1);

        assertThat(decision.status()).isEqualTo(CaseStatus.AUTO_MATCHED);
        assertThat(decision.appliedRules()).containsExactly(PolicyAgent.RULE_AMOUNT_TOLERANCE);
        assertThat(decision.policyVersionId()).isEqualTo(1);
    }

    @Test
    void differenceOutsideToleranceRequiresReview() {
        assertThat(agent.evaluate(amountMismatch("20.00", "EUR", false), v1).status())
                .isEqualTo(CaseStatus.REVIEW_REQUIRED);
    }

    @Test
    void toleranceIsOnlyApplicableInThePolicyCurrency() {
        PolicyDecision decision = agent.evaluate(amountMismatch("1.00", "USD", false), v1);

        assertThat(decision.status()).isEqualTo(CaseStatus.REVIEW_REQUIRED);
        assertThat(decision.appliedRules()).containsExactly(PolicyAgent.RULE_TOLERANCE_CURRENCY);
    }

    @Test
    void duplicateSettlementRequiresReview() {
        assertThat(agent.evaluate(of(CaseType.DUPLICATE_SETTLEMENT, null, false), v1).status())
                .isEqualTo(CaseStatus.REVIEW_REQUIRED);
    }

    @Test
    void missingRequiredInvoiceRequiresReview() {
        assertThat(agent.evaluate(of(CaseType.MISSING_INVOICE, null, false), v1).status())
                .isEqualTo(CaseStatus.REVIEW_REQUIRED);
    }

    @Test
    void aiServiceUnavailableEscalatesAnOtherwiseAutoMatchedCase() {
        PolicyDecision decision = agent.evaluate(amountMismatch("4.00", "EUR", true), v1);

        assertThat(decision.status()).isEqualTo(CaseStatus.REVIEW_REQUIRED);
        assertThat(decision.appliedRules()).contains(PolicyAgent.RULE_AI_UNAVAILABLE);
    }

    @Test
    void ambiguousAndUnexpectedAndMissingSettlementRequireReview() {
        for (CaseType type : List.of(CaseType.AMBIGUOUS_MATCH, CaseType.UNEXPECTED_SETTLEMENT,
                CaseType.MISSING_SETTLEMENT, CaseType.CURRENCY_MISMATCH, CaseType.INVOICE_MISMATCH,
                CaseType.REFERENCE_MISMATCH, CaseType.DUPLICATE_PAYMENT)) {
            assertThat(agent.evaluate(of(type, null, false), v1).status()).as(type.name())
                    .isEqualTo(CaseStatus.REVIEW_REQUIRED);
        }
    }

    @Test
    void identicalExportDuplicateIsAutoMatchedButConflictingRowsAreNot() {
        assertThat(agent.evaluate(of(CaseType.EXPORT_DUPLICATE, null, false), v1).status())
                .isEqualTo(CaseStatus.AUTO_MATCHED);
        assertThat(agent.evaluate(of(CaseType.EXPORT_DUPLICATE, new BigDecimal("0.00"), false), v1).status())
                .isEqualTo(CaseStatus.REVIEW_REQUIRED);
    }

    @Test
    void replayWithLowerToleranceChangesOnlyTheBorderlineCase() {
        PolicyVersion v2 = new PolicyVersion(2, "EUR", new BigDecimal("5.00"), true, false,
                CaseStatus.REVIEW_REQUIRED, CaseStatus.REVIEW_REQUIRED, CaseStatus.REVIEW_REQUIRED,
                CaseStatus.REVIEW_REQUIRED, CaseStatus.AUTO_MATCHED, 1, "tolerance 10 -> 5");

        Discrepancy borderline = amountMismatch("7.50", "EUR", false);
        Discrepancy small = amountMismatch("-4.00", "EUR", false);

        assertThat(agent.evaluate(borderline, v1).status()).isEqualTo(CaseStatus.AUTO_MATCHED);
        assertThat(agent.evaluate(borderline, v2).status()).isEqualTo(CaseStatus.REVIEW_REQUIRED);
        assertThat(agent.evaluate(borderline, v2).policyVersionId()).isEqualTo(2);
        assertThat(agent.evaluate(small, v2).status()).isEqualTo(CaseStatus.AUTO_MATCHED);
    }

    private static Discrepancy amountMismatch(String difference, String currency, boolean aiFailure) {
        return new Discrepancy(CaseType.AMOUNT_MISMATCH, "PAY-1", "PAY-1", List.of("SET-1"), "RF-1", null,
                new BigDecimal("1000.00"), new BigDecimal("1000.00").add(new BigDecimal(difference)),
                new BigDecimal(difference), currency, "test", false, false, aiFailure, List.of());
    }

    private static Discrepancy of(CaseType type, BigDecimal difference, boolean aiFailure) {
        return new Discrepancy(type, "X-1", "PAY-1", List.of(), "RF-1", null, new BigDecimal("1.00"), null,
                difference, "EUR", "test", type == CaseType.MISSING_INVOICE, false, aiFailure, List.of());
    }
}
