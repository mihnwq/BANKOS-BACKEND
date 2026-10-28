package com.bankos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A human reviewer action. Recorded as an append-only audit event; never moves money. */
public record ReviewRequest(
        @NotBlank @Size(max = 100) String actor,
        @NotNull Decision decision,
        @Size(max = 2000) String note
) {

    public enum Decision {
        APPROVE,
        REJECT,
        ESCALATE,
        INVESTIGATING
    }
}
