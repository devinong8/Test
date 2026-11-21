package com.hackathon.appointments.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Unified global exception handling: every error leaves the service as an {@link ApiError}
 * with the right HTTP status (400 / 403 / 404 / 405 / 409 / 415 / 500).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /** Business rule violations raised by the service layer. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), req, List.of());
    }

    /** @Valid request body failures -> 400 with per-field details. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiError.FieldViolation> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
                .toList();
        List<ApiError.FieldViolation> global = ex.getBindingResult().getGlobalErrors().stream()
                .map(ge -> new ApiError.FieldViolation(ge.getObjectName(), null, ge.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed", req,
                concat(fields, global));
    }

    /** @Validated path/query parameter failures -> 400. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleParamValidation(HandlerMethodValidationException ex, HttpServletRequest req) {
        List<ApiError.FieldViolation> fields = ex.getAllValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(e -> new ApiError.FieldViolation(
                        r.getMethodParameter().getParameterName(), r.getArgument(), e.getDefaultMessage())))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed", req, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        List<ApiError.FieldViolation> fields = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getInvalidValue(), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed", req, fields);
    }

    /** Wrong type in path/query, e.g. /slots/abc or date=2026-13-45 -> 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String expected = ex.getRequiredType() == null ? "valid value" : ex.getRequiredType().getSimpleName();
        String msg = "Parameter '%s' has invalid value '%s' (expected %s)".formatted(ex.getName(), ex.getValue(), expected);
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, msg, req,
                List.of(new ApiError.FieldViolation(ex.getName(), ex.getValue(), msg)));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, ex.getMessage(), req, List.of());
    }

    /** Malformed JSON, bad enum, unparseable date/time -> 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        if (ex.getCause() instanceof UnrecognizedPropertyException upe) {
            return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Unknown field '%s'".formatted(upe.getPropertyName()), req, List.of());
        }
        if (ex.getCause() instanceof MismatchedInputException mie && !mie.getPath().isEmpty()) {
            String field = mie.getPath().stream()
                    .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(java.util.stream.Collectors.joining("."));
            Object value = mie instanceof InvalidFormatException ife ? ife.getValue() : null;
            String expected = mie.getTargetType() == null ? "a valid value" : mie.getTargetType().getSimpleName();
            String msg = "Field '%s' has an invalid value (expected %s)".formatted(field, expected);
            return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, msg, req,
                    List.of(new ApiError.FieldViolation(field, value, msg)));
        }
        return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Malformed or unreadable JSON request body", req, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "No endpoint " + req.getRequestURI(), req, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.INVALID_REQUEST, ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.INVALID_REQUEST, ex.getMessage(), req, List.of());
    }

    /** Lost a race on a @Version row or a DB lock -> 409, client may retry. */
    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleConcurrency(RuntimeException ex, HttpServletRequest req) {
        log.info("Concurrent modification on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified by another request. Please retry.", req, List.of());
    }

    /** Last line of defence: DB unique index / FK violated (e.g. two active bookings for one slot) -> 409. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation on {}: {}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_RESOURCE,
                "The request conflicts with existing data", req, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred", req, List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, ErrorCode code, String message,
                                           HttpServletRequest req, List<ApiError.FieldViolation> fields) {
        ApiError body = new ApiError(OffsetDateTime.now(clock), status.value(), status.getReasonPhrase(),
                code.name(), message, req.getRequestURI(), fields);
        return ResponseEntity.status(status).body(body);
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
