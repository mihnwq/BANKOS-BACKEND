package com.bankos.agent;

import com.bankos.dto.Discrepancy;
import com.bankos.dto.PolicyDecision;
import com.bankos.model.CaseStatus;
import com.bankos.model.PolicyVersion;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes explicit, versioned rules in Java and produces the operational status. The LLM never
 * decides a status: investigation output only matters through the {@code aiFailure} flag, which can
 * only escalate a case, never clear it. Tolerance values are fictional hackathon rules.
 *
 * <table>
 *   <tr><th>Condition</th><th>Decision</th></tr>
 *   <tr><td>Exact payment and settlement match</td><td>AUTO_MATCHED (no case is created)</td></tr>
 *   <tr><td>EUR 4 difference; tolerance EUR 10</td><td>AUTO_MATCHED if all other checks pass</td></tr>
 *   <tr><td>EUR 20 difference; tolerance EUR 10</td><td>REVIEW_REQUIRED</td></tr>
 *   <tr><td>Duplicate settlement</td><td>REVIEW_REQUIRED</td></tr>
 *   <tr><td>Required invoice evidence absent</td><td>REVIEW_REQUIRED</td></tr>
 *   <tr><td>AI service unavailable</td><td>REVIEW_REQUIRED</td></tr>
 * </table>
 */
@Service
public class PolicyAgent {

    public static final String RULE_AMOUNT_TOLERANCE = "RULE_AMOUNT_TOLERANCE";
    public static final String RULE_TOLERANCE_CURRENCY = "RULE_TOLERANCE_CURRENCY";
    public static final String RULE_DUPLICATE = "RULE_DUPLICATE";
    public static final String RULE_MISSING_SETTLEMENT = "RULE_MISSING_SETTLEMENT";
    public static final String RULE_AMBIGUOUS_REFERENCE = "RULE_AMBIGUOUS_REFERENCE";
    public static final String RULE_UNEXPECTED_SETTLEMENT = "RULE_UNEXPECTED_SETTLEMENT";
    public static final String RULE_REFERENCE_MISMATCH = "RULE_REFERENCE_MISMATCH";
    public static final String RULE_CURRENCY_MISMATCH = "RULE_CURRENCY_MISMATCH";
    public static final String RULE_EXPORT_DUPLICATE = "RULE_EXPORT_DUPLICATE";
    public static final String RULE_REQUIRE_INVOICE = "RULE_REQUIRE_INVOICE";
    public static final String RULE_INVOICE_CONSISTENCY = "RULE_INVOICE_CONSISTENCY";
    public static final String RULE_AI_UNAVAILABLE = "RULE_AI_UNAVAILABLE";

    public PolicyDecision evaluate(Discrepancy discrepancy, PolicyVersion policy) {
        List<String> rules = new ArrayList<>();
        CaseStatus status;
        String reason;

        switch (discrepancy.type()) {
            case AMOUNT_MISMATCH -> {
                BigDecimal difference = discrepancy.difference() == null ? null : discrepancy.difference().abs();
                if (!policy.getCurrency().equals(discrepancy.currency())) {
                    rules.add(RULE_TOLERANCE_CURRENCY);
                    status = CaseStatus.REVIEW_REQUIRED;
                    reason = "Tolerance is defined in " + policy.getCurrency() + "; a " + discrepancy.currency()
                            + " difference cannot be auto-accepted.";
                } else if (difference != null && difference.compareTo(policy.getTolerance()) <= 0) {
                    rules.add(RULE_AMOUNT_TOLERANCE);
                    status = CaseStatus.AUTO_MATCHED;
                    reason = "Difference " + difference.toPlainString() + " " + policy.getCurrency()
                            + " is within tolerance " + policy.getTolerance().toPlainString() + " " + policy.getCurrency() + ".";
                } else {
                    rules.add(RULE_AMOUNT_TOLERANCE);
                    status = CaseStatus.REVIEW_REQUIRED;
                    reason = "Difference " + (difference == null ? "unknown" : difference.toPlainString()) + " "
                            + policy.getCurrency() + " exceeds tolerance " + policy.getTolerance().toPlainString()
                            + " " + policy.getCurrency() + ".";
                }
            }
            case DUPLICATE_SETTLEMENT, DUPLICATE_PAYMENT -> {
                rules.add(RULE_DUPLICATE);
                status = policy.isDuplicateAllowed() ? CaseStatus.AUTO_MATCHED : CaseStatus.REVIEW_REQUIRED;
                reason = policy.isDuplicateAllowed() ? "Duplicates are allowed by this policy version."
                        : "Duplicate payments are not allowed; mandatory review.";
            }
            case MISSING_SETTLEMENT -> {
                rules.add(RULE_MISSING_SETTLEMENT);
                status = policy.getMissingSettlement();
                reason = "No SETTLED confirmation for the payment (PENDING/REVERSED do not count).";
            }
            case AMBIGUOUS_MATCH -> {
                rules.add(RULE_AMBIGUOUS_REFERENCE);
                status = policy.getAmbiguousReference();
                reason = "Several plausible candidates; automated matching stopped without choosing one.";
            }
            case UNEXPECTED_SETTLEMENT -> {
                rules.add(RULE_UNEXPECTED_SETTLEMENT);
                status = policy.getUnexpectedSettlement();
                reason = "Settlement cannot be associated with any expected payment.";
            }
            case REFERENCE_MISMATCH -> {
                rules.add(RULE_REFERENCE_MISMATCH);
                status = policy.getReferenceMismatch();
                reason = "Settlement reference differs from the payment reference.";
            }
            case CURRENCY_MISMATCH -> {
                rules.add(RULE_CURRENCY_MISMATCH);
                status = CaseStatus.REVIEW_REQUIRED;
                reason = "Settlement currency differs from the payment currency.";
            }
            case EXPORT_DUPLICATE -> {
                rules.add(RULE_EXPORT_DUPLICATE);
                if (discrepancy.difference() == null) {
                    status = policy.getExportDuplicate();
                    reason = "Identical repeated export row: data duplication, not a second payment.";
                } else {
                    status = CaseStatus.REVIEW_REQUIRED;
                    reason = "Repeated settlement rows disagree; the export must be checked.";
                }
            }
            case MISSING_INVOICE -> {
                rules.add(RULE_REQUIRE_INVOICE);
                status = policy.isRequireInvoice() ? CaseStatus.REVIEW_REQUIRED : CaseStatus.AUTO_MATCHED;
                reason = policy.isRequireInvoice() ? "Required invoice evidence is absent or unusable."
                        : "Invoice evidence is not required by this policy version.";
            }
            case INVOICE_MISMATCH -> {
                rules.add(RULE_INVOICE_CONSISTENCY);
                status = CaseStatus.REVIEW_REQUIRED;
                reason = "Invoice facts contradict the payment; both source values are listed as evidence.";
            }
            default -> throw new IllegalStateException("Unhandled case type " + discrepancy.type());
        }

        if (discrepancy.aiFailure()) {
            rules.add(RULE_AI_UNAVAILABLE);
            if (status != CaseStatus.REVIEW_REQUIRED) {
                status = CaseStatus.REVIEW_REQUIRED;
                reason = reason + " Escalated: the AI investigation failed or its output was rejected.";
            }
        }
        return new PolicyDecision(status, List.copyOf(rules), reason, policy.getId());
    }
}
