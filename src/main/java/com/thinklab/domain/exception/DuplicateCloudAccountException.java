package com.thinklab.domain.exception;

/** Domain Exception: an account name already used in the organisation, or the same provider account registered twice. RFC 7807 mapping: HTTP 409 Conflict. */
public class DuplicateCloudAccountException extends BusinessException {

    private static final String ERROR_CODE = "ERR-CLD-00409";

    public DuplicateCloudAccountException(String message) {
        super(ERROR_CODE, message);
    }
}
