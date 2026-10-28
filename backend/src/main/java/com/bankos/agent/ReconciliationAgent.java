package com.bankos.agent;

import com.bankos.dto.Discrepancy;
import com.bankos.dto.Discrepancy.SourceRef;
import com.bankos.model.CaseType;
import com.bankos.model.Invoice;
import com.bankos.model.Payment;
import com.bankos.model.Settlement;
import com.bankos.model.SettlementStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic reconciliation in plain Java. No model is involved: numeric comparisons use
 * BigDecimal, currencies are never compared as equal, and ambiguous candidates are never
 * silently resolved.
 *
 * <pre>
 * FOR EACH payment:
 *     find SETTLED settlements with matching paymentId
 *     zero  -> MISSING_SETTLEMENT
 *     many  -> DUPLICATE_SETTLEMENT
 *     one   -> compare currency, amount (BigDecimal) and normalized reference
 * FOR EACH settlement not associated with a payment:
 *     unique reference match -> link; several candidates -> AMBIGUOUS_MATCH; none -> UNEXPECTED_SETTLEMENT
 * </pre>
 */
@Service
public class ReconciliationAgent
{

    /** Uppercase and strip whitespace/punctuation so "inv-2042" and "INV 2042" compare equal. */
    public static String normalize(String reference) {
        if (reference == null) {
            return "";
        }
        return reference.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-_/.]", "");
    }

    public List<Discrepancy> reconcile(List<Payment> payments, List<Settlement> settlements) {
        List<Discrepancy> result = new ArrayList<>();

        // 1. Payment register duplicates: the same payment ID listed more than once.
        Map<String, List<Payment>> paymentsById = new LinkedHashMap<>();
        payments.forEach(p -> paymentsById.computeIfAbsent(p.getPaymentId(), k -> new ArrayList<>()).add(p));
        Map<String, Payment> paymentIndex = new LinkedHashMap<>();
        paymentsById.forEach((id, rows) -> {
            paymentIndex.put(id, rows.getFirst());
            if (rows.size() > 1) {
                result.add(duplicatePayment(rows));
            }
        });

        // 2. Repeated export rows: the same settlement ID repeated is data duplication, not a second payment.
        Map<String, List<Settlement>> settlementsById = new LinkedHashMap<>();
        settlements.forEach(s -> settlementsById.computeIfAbsent(s.getSettlementId(), k -> new ArrayList<>()).add(s));
        List<Settlement> unique = new ArrayList<>();
        settlementsById.forEach((id, rows) -> {
            unique.add(rows.getFirst());
            if (rows.size() > 1) {
                result.add(exportDuplicate(rows));
            }
        });

        // 3. Associate settlements with payments by exact payment ID first.
        Map<String, List<Settlement>> byPayment = new HashMap<>();
        List<Settlement> orphans = new ArrayList<>();
        for (Settlement s : unique) {
            if (s.getPaymentId() != null && paymentIndex.containsKey(s.getPaymentId())) {
                byPayment.computeIfAbsent(s.getPaymentId(), k -> new ArrayList<>()).add(s);
            } else {
                orphans.add(s);
            }
        }

        // 4. Orphans: link only on a unique normalized reference; never on amount alone.
        Set<String> linkedByReference = new HashSet<>();
        Set<String> coveredByAmbiguity = new HashSet<>();
        for (Settlement orphan : orphans) {
            List<Payment> open = paymentIndex.values().stream()
                    .filter(p -> settled(byPayment.get(p.getPaymentId())).isEmpty())
                    .toList();
            String ref = normalize(orphan.getReference());
            List<Payment> byReference = ref.isEmpty() ? List.of()
                    : open.stream().filter(p -> normalize(p.getReference()).equals(ref)).toList();
            if (byReference.size() == 1) {
                Payment target = byReference.getFirst();
                byPayment.computeIfAbsent(target.getPaymentId(), k -> new ArrayList<>()).add(orphan);
                linkedByReference.add(orphan.getSettlementId());
                continue;
            }
            List<Payment> candidates = !byReference.isEmpty() ? byReference
                    : open.stream()
                    .filter(p -> p.getCurrency().equals(orphan.getCurrency())
                            && p.getAmount().compareTo(orphan.getAmount()) == 0)
                    .toList();
            if (candidates.isEmpty()) {
                result.add(unexpectedSettlement(orphan));
            } else {
                result.add(ambiguousMatch(orphan, candidates, !byReference.isEmpty()));
                candidates.forEach(p -> coveredByAmbiguity.add(p.getPaymentId()));
            }
        }

        // 5. Per payment checks.
        for (Payment payment : paymentIndex.values()) {
            List<Settlement> linked = byPayment.getOrDefault(payment.getPaymentId(), List.of());
            List<Settlement> settled = settled(linked);
            if (settled.isEmpty()) {
                if (!coveredByAmbiguity.contains(payment.getPaymentId())) {
                    result.add(missingSettlement(payment, linked));
                }
            } else if (settled.size() > 1) {
                result.add(duplicateSettlement(payment, settled));
            } else {
                Settlement s = settled.getFirst();
                if (!payment.getCurrency().equals(s.getCurrency())) {
                    result.add(currencyMismatch(payment, s));
                } else if (payment.getAmount().compareTo(s.getAmount()) != 0) {
                    result.add(amountMismatch(payment, s));
                }
                String paymentRef = normalize(payment.getReference());
                String settlementRef = normalize(s.getReference());
                if (!linkedByReference.contains(s.getSettlementId())
                        && !paymentRef.isEmpty() && !settlementRef.isEmpty() && !paymentRef.equals(settlementRef)) {
                    result.add(referenceMismatch(payment, s));
                }
            }
        }
        return result;
    }

    /** Payments whose required invoice is absent, unreadable/unverified, or contradicts the payment. */
    public List<Discrepancy> reconcileInvoices(List<Payment> payments, List<Invoice> invoices) {
        Map<String, Invoice> byNumber = new LinkedHashMap<>();
        for (Invoice invoice : invoices) {
            if (invoice.getNumber() == null) {
                continue;
            }
            String key = normalize(invoice.getNumber());
            Invoice existing = byNumber.get(key);
            if (existing == null || (!existing.isUsableEvidence() && invoice.isUsableEvidence())) {
                byNumber.put(key, invoice);
            }
        }
        // Unreadable invoices have no extracted number; match them by file name (e.g. INV-2090.pdf).
        for (Invoice invoice : invoices) {
            if (invoice.getNumber() == null) {
                String stem = invoice.getSourceFile().replaceFirst("(?i)\\.pdf$", "");
                byNumber.putIfAbsent(normalize(stem), invoice);
            }
        }

        List<Discrepancy> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Payment payment : payments) {
            if (payment.getInvoiceNumber() == null || !seen.add(payment.getPaymentId())) {
                continue;
            }
            Invoice invoice = byNumber.get(normalize(payment.getInvoiceNumber()));
            if (invoice == null) {
                result.add(missingInvoice(payment, null,
                        "No supporting invoice PDF was provided for " + payment.getInvoiceNumber() + "."));
            } else if (!invoice.isUsableEvidence()) {
                result.add(missingInvoice(payment, invoice, "Invoice " + invoice.getSourceFile() + " is "
                        + invoice.getStatus() + " and cannot be used as evidence"
                        + (invoice.getVerificationNotes() == null ? "." : ": " + invoice.getVerificationNotes())));
            } else {
                Discrepancy mismatch = invoiceMismatch(payment, invoice);
                if (mismatch != null) {
                    result.add(mismatch);
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- builders

    private static List<Settlement> settled(List<Settlement> settlements) {
        if (settlements == null) {
            return List.of();
        }
        return settlements.stream().filter(s -> s.getStatus() == SettlementStatus.SETTLED).toList();
    }

    private Discrepancy duplicatePayment(List<Payment> rows) {
        Payment first = rows.getFirst();
        List<SourceRef> sources = rows.stream().map(p -> paymentRef(p, "payment", describe(p))).toList();
        return new Discrepancy(CaseType.DUPLICATE_PAYMENT, first.getPaymentId(), first.getPaymentId(), List.of(),
                first.getReference(), first.getInvoiceNumber(), first.getAmount(), null, null, first.getCurrency(),
                "Payment " + first.getPaymentId() + " appears " + rows.size() + " times in the payment register (rows "
                        + rows.stream().map(p -> String.valueOf(p.getRowNumber())).toList() + ").",
                false, false, false, sources);
    }

    /**
     * {@code difference} stays null when the repeated rows are byte-for-byte identical (pure export
     * duplication). When the repeated rows disagree, it holds the amount spread (possibly zero if only
     * status/reference differ) so the policy can escalate conflicting rows.
     */
    private Discrepancy exportDuplicate(List<Settlement> rows) {
        Settlement first = rows.getFirst();
        boolean identical = rows.stream().allMatch(s -> sameContent(first, s));
        BigDecimal spread = null;
        if (!identical) {
            BigDecimal max = rows.stream().map(Settlement::getAmount).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal min = rows.stream().map(Settlement::getAmount).min(BigDecimal::compareTo).orElseThrow();
            spread = max.subtract(min);
        }
        List<SourceRef> sources = rows.stream().map(s -> settlementRef(s, "settlement", describe(s))).toList();
        String description = "Settlement " + first.getSettlementId() + " is repeated " + rows.size()
                + " times in the export (rows " + rows.stream().map(s -> String.valueOf(s.getRowNumber())).toList() + "). "
                + (identical ? "Rows are identical: repeated export row, not a second payment."
                : "Repeated rows disagree; the export is inconsistent.");
        return new Discrepancy(CaseType.EXPORT_DUPLICATE, first.getSettlementId(), first.getPaymentId(),
                List.of(first.getSettlementId()), first.getReference(), null, first.getAmount(), null, spread,
                first.getCurrency(), description, false, false, false, sources);
    }

    private Discrepancy unexpectedSettlement(Settlement s) {
        return new Discrepancy(CaseType.UNEXPECTED_SETTLEMENT, s.getSettlementId(), null, List.of(s.getSettlementId()),
                s.getReference(), null, null, s.getAmount(), null, s.getCurrency(),
                "Settlement " + s.getSettlementId() + " (" + s.getStatus() + ", " + money(s.getAmount(), s.getCurrency())
                        + ") does not belong to any payment in the batch"
                        + (s.getPaymentId() == null ? "" : " (claims payment " + s.getPaymentId() + ")") + ".",
                false, false, false, List.of(settlementRef(s, "settlement", describe(s))));
    }

    private Discrepancy ambiguousMatch(Settlement s, List<Payment> candidates, boolean byReference) {
        List<SourceRef> sources = new ArrayList<>();
        sources.add(settlementRef(s, "settlement", describe(s)));
        candidates.forEach(p -> sources.add(paymentRef(p, "candidate", describe(p))));
        return new Discrepancy(CaseType.AMBIGUOUS_MATCH, s.getSettlementId(), null, List.of(s.getSettlementId()),
                s.getReference(), null, null, s.getAmount(), null, s.getCurrency(),
                "Settlement " + s.getSettlementId() + " has no valid payment ID. "
                        + candidates.size() + " candidate payment(s) match by "
                        + (byReference ? "reference" : "amount and currency only") + ": "
                        + candidates.stream().map(Payment::getPaymentId).toList()
                        + ". No candidate was selected automatically.",
                false, false, false, sources);
    }

    private Discrepancy missingSettlement(Payment p, List<Settlement> nonSettled) {
        List<SourceRef> sources = new ArrayList<>();
        sources.add(paymentRef(p, "payment", describe(p)));
        nonSettled.forEach(s -> sources.add(settlementRef(s, "status", s.getStatus().name())));
        String detail = nonSettled.isEmpty() ? "No settlement record references it."
                : "Only non-final settlement records exist: " + nonSettled.stream()
                .map(s -> s.getSettlementId() + "=" + s.getStatus()).toList() + ".";
        return new Discrepancy(CaseType.MISSING_SETTLEMENT, p.getPaymentId(), p.getPaymentId(),
                nonSettled.stream().map(Settlement::getSettlementId).toList(), p.getReference(), p.getInvoiceNumber(),
                p.getAmount(), null, null, p.getCurrency(),
                "Payment " + p.getPaymentId() + " (" + money(p.getAmount(), p.getCurrency())
                        + ") has no SETTLED confirmation. " + detail,
                p.getInvoiceNumber() != null, false, false, sources);
    }

    private Discrepancy duplicateSettlement(Payment p, List<Settlement> settled) {
        BigDecimal total = settled.stream().map(Settlement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<SourceRef> sources = new ArrayList<>();
        sources.add(paymentRef(p, "payment", describe(p)));
        settled.forEach(s -> sources.add(settlementRef(s, "settlement", describe(s))));
        return new Discrepancy(CaseType.DUPLICATE_SETTLEMENT, p.getPaymentId(), p.getPaymentId(),
                settled.stream().map(Settlement::getSettlementId).toList(), p.getReference(), p.getInvoiceNumber(),
                p.getAmount(), total, total.subtract(p.getAmount()), p.getCurrency(),
                settled.size() + " distinct SETTLED settlements (" + settled.stream().map(Settlement::getSettlementId).toList()
                        + ") refer to payment " + p.getPaymentId() + " of " + money(p.getAmount(), p.getCurrency())
                        + "; total settled " + money(total, p.getCurrency()) + ".",
                p.getInvoiceNumber() != null, false, false, sources);
    }

    private Discrepancy currencyMismatch(Payment p, Settlement s) {
        return new Discrepancy(CaseType.CURRENCY_MISMATCH, p.getPaymentId(), p.getPaymentId(),
                List.of(s.getSettlementId()), p.getReference(), p.getInvoiceNumber(), p.getAmount(), s.getAmount(),
                null, p.getCurrency(),
                "Payment " + p.getPaymentId() + " is " + money(p.getAmount(), p.getCurrency()) + " but settlement "
                        + s.getSettlementId() + " is " + money(s.getAmount(), s.getCurrency())
                        + ". Different currencies are never compared as equal.",
                p.getInvoiceNumber() != null, false, false,
                List.of(paymentRef(p, "currency", p.getCurrency()), settlementRef(s, "currency", s.getCurrency())));
    }

    private Discrepancy amountMismatch(Payment p, Settlement s) {
        BigDecimal difference = s.getAmount().subtract(p.getAmount());
        return new Discrepancy(CaseType.AMOUNT_MISMATCH, p.getPaymentId(), p.getPaymentId(),
                List.of(s.getSettlementId()), p.getReference(), p.getInvoiceNumber(), p.getAmount(), s.getAmount(),
                difference, p.getCurrency(),
                "Settlement " + s.getSettlementId() + " settled " + money(s.getAmount(), s.getCurrency())
                        + " for payment " + p.getPaymentId() + " of " + money(p.getAmount(), p.getCurrency())
                        + " (difference " + difference.toPlainString() + " " + p.getCurrency() + ").",
                p.getInvoiceNumber() != null, false, false,
                List.of(paymentRef(p, "amount", money(p.getAmount(), p.getCurrency())),
                        settlementRef(s, "amount", money(s.getAmount(), s.getCurrency()))));
    }

    private Discrepancy referenceMismatch(Payment p, Settlement s) {
        return new Discrepancy(CaseType.REFERENCE_MISMATCH, p.getPaymentId(), p.getPaymentId(),
                List.of(s.getSettlementId()), p.getReference(), p.getInvoiceNumber(), p.getAmount(), s.getAmount(),
                null, p.getCurrency(),
                "Payment " + p.getPaymentId() + " has reference " + p.getReference() + " but settlement "
                        + s.getSettlementId() + " carries reference " + s.getReference() + ".",
                p.getInvoiceNumber() != null, false, false,
                List.of(paymentRef(p, "reference", p.getReference()), settlementRef(s, "reference", s.getReference())));
    }

    private Discrepancy missingInvoice(Payment p, Invoice invoice, String reason) {
        List<SourceRef> sources = new ArrayList<>();
        sources.add(paymentRef(p, "invoiceNumber", p.getInvoiceNumber()));
        if (invoice != null) {
            sources.add(invoiceRef(invoice, "status", invoice.getStatus().name()));
        }
        return new Discrepancy(CaseType.MISSING_INVOICE, p.getPaymentId(), p.getPaymentId(), List.of(),
                p.getReference(), p.getInvoiceNumber(), p.getAmount(), null, null, p.getCurrency(),
                "Payment " + p.getPaymentId() + " requires invoice " + p.getInvoiceNumber() + ". " + reason,
                true, false, false, sources);
    }

    private Discrepancy invoiceMismatch(Payment p, Invoice invoice) {
        List<String> conflicts = new ArrayList<>();
        List<SourceRef> sources = new ArrayList<>();
        BigDecimal difference = null;
        if (!Objects.equals(invoice.getCurrency(), p.getCurrency())
                || invoice.getAmount() == null || invoice.getAmount().compareTo(p.getAmount()) != 0) {
            conflicts.add("amount: payment " + money(p.getAmount(), p.getCurrency()) + " vs invoice "
                    + money(invoice.getAmount(), invoice.getCurrency()));
            if (invoice.getAmount() != null && Objects.equals(invoice.getCurrency(), p.getCurrency())) {
                difference = invoice.getAmount().subtract(p.getAmount());
            }
            sources.add(paymentRef(p, "amount", money(p.getAmount(), p.getCurrency())));
            sources.add(invoiceRef(invoice, "amount", money(invoice.getAmount(), invoice.getCurrency())));
        }
        String invoiceRef = normalize(invoice.getPaymentReference());
        if (!invoiceRef.isEmpty() && !invoiceRef.equals(normalize(p.getReference()))) {
            conflicts.add("payment reference: payment " + p.getReference() + " vs invoice " + invoice.getPaymentReference());
            sources.add(paymentRef(p, "reference", p.getReference()));
            sources.add(invoiceRef(invoice, "paymentReference", invoice.getPaymentReference()));
        }
        if (conflicts.isEmpty()) {
            return null;
        }
        return new Discrepancy(CaseType.INVOICE_MISMATCH, p.getPaymentId(), p.getPaymentId(), List.of(),
                p.getReference(), p.getInvoiceNumber(), p.getAmount(), invoice.getAmount(), difference, p.getCurrency(),
                "Invoice " + invoice.getNumber() + " (" + invoice.getSourceFile() + ", page " + invoice.getSourcePage()
                        + ") contradicts payment " + p.getPaymentId() + ": " + String.join("; ", conflicts) + ".",
                true, true, false, sources);
    }

    private static boolean sameContent(Settlement a, Settlement b) {
        return Objects.equals(a.getPaymentId(), b.getPaymentId())
                && Objects.equals(a.getReference(), b.getReference())
                && a.getAmount().compareTo(b.getAmount()) == 0
                && a.getCurrency().equals(b.getCurrency())
                && a.getStatus() == b.getStatus()
                && Objects.equals(a.getSettledAt(), b.getSettledAt());
    }

    private static SourceRef paymentRef(Payment p, String field, String value) {
        return new SourceRef(p.getSourceFile(), p.getRowNumber(), null, field, p.getPaymentId(), value);
    }

    private static SourceRef settlementRef(Settlement s, String field, String value) {
        return new SourceRef(s.getSourceFile(), s.getRowNumber(), null, field, s.getSettlementId(), value);
    }

    private static SourceRef invoiceRef(Invoice i, String field, String value) {
        return new SourceRef(i.getSourceFile(), null, i.getSourcePage(), field,
                i.getNumber() != null ? i.getNumber() : i.getSourceFile(), value);
    }

    private static String describe(Payment p) {
        return p.getPaymentId() + " | ref " + p.getReference() + " | " + money(p.getAmount(), p.getCurrency());
    }

    private static String describe(Settlement s) {
        return s.getSettlementId() + " | payment " + s.getPaymentId() + " | ref " + s.getReference() + " | "
                + money(s.getAmount(), s.getCurrency()) + " | " + s.getStatus();
    }

    static String money(BigDecimal amount, String currency) {
        return amount == null ? "n/a" : amount.toPlainString() + " " + (currency == null ? "" : currency);
    }
}
