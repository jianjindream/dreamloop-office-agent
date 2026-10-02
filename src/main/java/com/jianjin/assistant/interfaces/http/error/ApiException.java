package com.jianjin.assistant.interfaces.http.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Object details;

    public ApiException(String code, String message, HttpStatus status) { this(code, message, status, null); }
    public ApiException(String code, String message, HttpStatus status, Object details) {
        super(message); this.code = code; this.status = status; this.details = details;
    }
    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public Object getDetails() { return details; }
}
