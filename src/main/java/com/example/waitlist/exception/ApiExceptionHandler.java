package com.example.waitlist.exception;

import com.example.waitlist.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Keeps framework-level rejections in the same {@code {outcome, message}} shape as service results. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OfferingNotFoundException.class)
    ResponseEntity<ApiError> offeringNotFound(OfferingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("OFFERING_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Request body is not valid JSON."));
    }

    /** The client accepts no type we can produce, so a JSON body could not be written either: status only. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> notAcceptable(HttpMediaTypeNotAcceptableException e) {
        return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders()).build();
    }

    /**
     * Spring MVC's own rejections (405, 415, unknown path, ...). Their status and headers are kept, so
     * e.g. {@code Allow} on 405 and {@code Accept} on 415 still reach the client.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e) {
        if (e instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            String detail = error.getBody().getDetail();
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .body(new ApiError("INVALID_REQUEST", detail != null ? detail : "Invalid request."));
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "Unexpected error."));
    }
}
