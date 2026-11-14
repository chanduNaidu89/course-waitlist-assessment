package com.example.waitlist.controller;

import com.example.waitlist.dto.ApiError;
import com.example.waitlist.dto.DropResult;
import com.example.waitlist.dto.EnrollRequest;
import com.example.waitlist.dto.EnrollResult;
import com.example.waitlist.dto.OfferingSnapshot;
import com.example.waitlist.exception.OfferingNotFoundException;
import com.example.waitlist.service.EnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP adapter: maps service outcomes to status codes. All rules live in {@link EnrollmentService}. */
@RestController
@RequestMapping("/api/offerings/{offeringCode}")
@Tag(name = "Enrollment", description = "Enroll, drop and inspect the waitlist of the configured offering")
public class EnrollmentController {

    private static final String OFFERING_CODE = "Code of the configured offering";

    private final EnrollmentService service;

    public EnrollmentController(EnrollmentService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get offering state",
            description = "Capacity, free seats, prerequisites, and the enrolled and waitlisted learners in order.")
    @ApiResponse(responseCode = "200", description = "Current state",
            content = @Content(schema = @Schema(implementation = OfferingSnapshot.class)))
    @ApiResponse(responseCode = "404", description = "`OFFERING_NOT_FOUND`: unknown offering code",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public OfferingSnapshot get(
            @Parameter(description = OFFERING_CODE, example = "CS201") @PathVariable String offeringCode) {
        requireOffering(offeringCode);
        return service.snapshot();
    }

    @PostMapping("/enrollments")
    @Operation(summary = "Enroll a learner",
            description = "Takes a free seat if the learner meets every prerequisite. If the offering is full, "
                    + "an eligible learner joins the end of the waitlist. Repeating the request changes nothing.")
    @ApiResponse(responseCode = "201", description = "`ENROLLED`: a seat was free",
            content = @Content(schema = @Schema(implementation = EnrollResult.class)))
    @ApiResponse(responseCode = "202", description = "`WAITLISTED`: offering full; `waitlistPosition` is 1-based",
            content = @Content(schema = @Schema(implementation = EnrollResult.class)))
    @ApiResponse(responseCode = "200",
            description = "`ALREADY_ENROLLED` / `ALREADY_WAITLISTED`: duplicate request, no change",
            content = @Content(schema = @Schema(implementation = EnrollResult.class)))
    @ApiResponse(responseCode = "422",
            description = "`PREREQUISITE_NOT_MET`: neither enrolled nor waitlisted. A waitlisted learner who "
                    + "has become ineligible is also removed from the waitlist",
            content = @Content(schema = @Schema(implementation = EnrollResult.class)))
    @ApiResponse(responseCode = "404",
            description = "`LEARNER_NOT_FOUND` (EnrollResult) or `OFFERING_NOT_FOUND` (ApiError)",
            content = @Content(schema = @Schema(oneOf = {EnrollResult.class, ApiError.class})))
    @ApiResponse(responseCode = "400",
            description = "`INVALID_REQUEST`: blank, padded or over-long (>64 chars) learnerId, or a missing or "
                    + "malformed body",
            content = @Content(schema = @Schema(oneOf = {EnrollResult.class, ApiError.class})))
    @ApiResponse(responseCode = "415", description = "`INVALID_REQUEST`: Content-Type must be application/json",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<EnrollResult> enroll(
            @Parameter(description = OFFERING_CODE, example = "CS201") @PathVariable String offeringCode,
            @RequestBody(required = false) EnrollRequest request) {
        requireOffering(offeringCode);
        EnrollResult result = service.enroll(request == null ? null : request.learnerId());
        HttpStatus status = switch (result.outcome()) {
            case ENROLLED -> HttpStatus.CREATED;
            case WAITLISTED -> HttpStatus.ACCEPTED;
            case ALREADY_ENROLLED, ALREADY_WAITLISTED -> HttpStatus.OK;
            case PREREQUISITE_NOT_MET -> HttpStatus.UNPROCESSABLE_ENTITY;
            case LEARNER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(result);
    }

    @DeleteMapping("/enrollments/{learnerId}")
    @Operation(summary = "Drop a learner",
            description = "If the learner holds a seat, it is released and given to the earliest eligible "
                    + "waitlisted learner. Waitlisted learners found ineligible on the way are skipped and "
                    + "removed (listed in `removedIneligible`). If the learner is waitlisted, they leave the "
                    + "waitlist. Repeating the request changes nothing.")
    @ApiResponse(responseCode = "200",
            description = "`DROPPED` (see `promotedLearnerId`, `removedIneligible`) or `LEFT_WAITLIST`",
            content = @Content(schema = @Schema(implementation = DropResult.class)))
    @ApiResponse(responseCode = "404",
            description = "`NOT_ENROLLED` (DropResult): neither enrolled nor waitlisted, including repeated "
                    + "drops; or `OFFERING_NOT_FOUND` (ApiError)",
            content = @Content(schema = @Schema(oneOf = {DropResult.class, ApiError.class})))
    @ApiResponse(responseCode = "400", description = "`INVALID_REQUEST`: invalid learnerId",
            content = @Content(schema = @Schema(implementation = DropResult.class)))
    public ResponseEntity<DropResult> drop(
            @Parameter(description = OFFERING_CODE, example = "CS201") @PathVariable String offeringCode,
            @Parameter(description = "Learner to drop", example = "alice") @PathVariable String learnerId) {
        requireOffering(offeringCode);
        DropResult result = service.drop(learnerId);
        HttpStatus status = switch (result.outcome()) {
            case DROPPED, LEFT_WAITLIST -> HttpStatus.OK;
            case NOT_ENROLLED -> HttpStatus.NOT_FOUND;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(result);
    }

    private void requireOffering(String offeringCode) {
        if (!service.offering().code().equals(offeringCode)) {
            throw new OfferingNotFoundException(offeringCode);
        }
    }
}
