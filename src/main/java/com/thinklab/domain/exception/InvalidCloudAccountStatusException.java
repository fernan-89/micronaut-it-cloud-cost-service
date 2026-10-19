package com.thinklab.domain.exception;

/** Domain Exception: an illegal CloudAccount lifecycle transition, or the account changed while the write was applied. RFC 7807 mapping: HTTP 409 Conflict. */
public class InvalidCloudAccountStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-CLD-00409";

    public InvalidCloudAccountStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
