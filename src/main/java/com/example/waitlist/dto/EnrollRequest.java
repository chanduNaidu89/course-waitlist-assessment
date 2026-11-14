package com.example.waitlist.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body of {@code POST /api/offerings/{offeringCode}/enrollments}. */
public record EnrollRequest(
        @Schema(description = "Learner id: non-blank, no surrounding whitespace, at most 64 characters",
                example = "alice", requiredMode = Schema.RequiredMode.REQUIRED)
        String learnerId) {
}
