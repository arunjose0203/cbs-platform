package com.cbs.tenancy;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiErrors {
    @ExceptionHandler(TenantException.class)
    ResponseEntity<ProblemDetail> business(TenantException exception) {
        return problem(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalid(Exception exception) {
        // Do not echo rejected payloads, identifiers or untrusted input in error details.
        return problem(400, "INVALID_REQUEST", "Request fields or required headers are invalid");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> database(DataAccessException exception) {
        return problem(503, "CONTROL_PLANE_UNAVAILABLE", "The administration store is unavailable");
    }

    private static ResponseEntity<ProblemDetail> problem(int status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setTitle(code);
        body.setDetail(detail);
        body.setProperty("code", code);
        body.setProperty("correlationId", MDC.get("correlationId"));
        return ResponseEntity.status(status).body(body);
    }
}
