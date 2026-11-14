package com.example.waitlist.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.waitlist.dto.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    @Test
    void unexpectedExceptionBecomes500InStandardShape() {
        ResponseEntity<ApiError> response = new ApiExceptionHandler().other(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().outcome()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("boom"); // no internals leaked
    }
}
