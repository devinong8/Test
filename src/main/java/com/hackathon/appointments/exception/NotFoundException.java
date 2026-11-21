package com.hackathon.appointments.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {
    public NotFoundException(ErrorCode code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
