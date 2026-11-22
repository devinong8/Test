package com.hackathon.inventory.config;

import java.util.Map;

/** A request that is well-formed but violates an inventory rule (capacity, budget, insufficient stock...). */
public class BusinessRuleException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;

    public BusinessRuleException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public BusinessRuleException(String code, String message) { this(code, message, Map.of()); }

    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
