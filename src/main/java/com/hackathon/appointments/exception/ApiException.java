package com.hackathon.appointments.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for all business errors. Each carries an HTTP status and a stable machine-readable
 * {@code code} that clients can switch on (messages are for humans and may change).
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    protected ApiException(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public ErrorCode getCode() { return code; }
}
