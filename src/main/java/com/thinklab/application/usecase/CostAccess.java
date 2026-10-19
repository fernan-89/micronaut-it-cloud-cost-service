package com.thinklab.application.usecase;

import com.thinklab.domain.exception.CostAccessDeniedException;

/** Cloud cost is for IT staff and for the tools they run (ADR-032): a REQUESTER is refused everywhere. */
final class CostAccess {

    static final String REQUESTER_ROLE = "REQUESTER";

    private CostAccess() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static void requireStaff(String role, String operation) {
        if (REQUESTER_ROLE.equals(role)) {
            throw new CostAccessDeniedException("A requester cannot " + operation + ": cloud accounts and their costs are handled by IT staff.");
        }
    }
}
