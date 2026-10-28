package com.bankos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** A pointer from a case to an exact source fact: a CSV row or a PDF page. */
@Entity
@Table(name = "evidence", indexes = @Index(columnList = "caseId"))
public class Evidence {

    /** Stable ID, e.g. EV-B001-PAY-1042-DUP-1. Agents must cite these. */
    @Id
    private String id;

    @Column(nullable = false)
    private String caseId;

    /** Source file name, e.g. settlements.csv or INV-2042.pdf. */
    @Column(nullable = false)
    private String source;

    private Integer rowNumber;
    private Integer pageNumber;

    /** Which record/field this evidence supports, e.g. "settlement", "amount", "paymentReference". */
    private String field;

    /** Identifier of the referenced record (payment, settlement or invoice number). */
    private String recordId;

    /** Snapshot of the value at detection time. */
    @Column(name = "snapshot_value", length = 1000)
    private String value;

    protected Evidence() {
    }

    public Evidence(String id, String caseId, String source, Integer rowNumber, Integer pageNumber,
                    String field, String recordId, String value) {
        this.id = id;
        this.caseId = caseId;
        this.source = source;
        this.rowNumber = rowNumber;
        this.pageNumber = pageNumber;
        this.field = field;
        this.recordId = recordId;
        this.value = value;
    }

    public String getId() {
        return id;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getSource() {
        return source;
    }

    public Integer getRowNumber() {
        return rowNumber;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public String getField() {
        return field;
    }

    public String getRecordId() {
        return recordId;
    }

    public String getValue() {
        return value;
    }
}
