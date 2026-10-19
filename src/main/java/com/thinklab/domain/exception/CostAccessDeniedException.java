package com.thinklab.domain.exception;

/** Domain Exception: a REQUESTER tried to use cloud cost (staff only, ADR-032). RFC 7807 mapping: HTTP 403 Forbidden. */
public class CostAccessDeniedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-CLD-00403";

    public CostAccessDeniedException(String message) {
        super(ERROR_CODE, message);
    }
}
