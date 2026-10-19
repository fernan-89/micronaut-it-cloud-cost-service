package com.thinklab.domain.exception;

/** Domain Exception: no cloud account with that id in the tenant (another tenant's answers the same). RFC 7807 mapping: HTTP 404 Not Found. */
public class CloudAccountNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-CLD-00404";

    public CloudAccountNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
