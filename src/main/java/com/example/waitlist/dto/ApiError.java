package com.example.waitlist.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Error body for rejections raised outside the service (unknown offering, bad JSON, 405, 415, 500). */
@Schema(description = "Error returned for requests rejected before reaching the enrollment rules")
public record ApiError(
        @Schema(description = "Machine-readable outcome",
                allowableValues = {"OFFERING_NOT_FOUND", "INVALID_REQUEST", "INTERNAL_ERROR"},
                example = "OFFERING_NOT_FOUND")
        String outcome,
        @Schema(description = "Human-readable explanation", example = "Unknown offering: NOPE")
        String message) {
}
