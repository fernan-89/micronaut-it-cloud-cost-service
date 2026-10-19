package com.thinklab.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CostReportTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-10");
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final UUID org = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();

    private static void rejects(Executable action) {
        assertThrows(IllegalArgumentException.class, action);
    }

    @Test
    @DisplayName("a report is a fact: the day, the amount in minor units, who reported it and when")
    void createNew() {
        UUID id = UUID.randomUUID();
        CostReport report = CostReport.createNew(id, org, account, TODAY.minusDays(1), 12_345, "billing-export", TODAY, NOW);

        assertEquals(id, report.getId());
        assertEquals(org, report.getOrganisationId());
        assertEquals(account, report.getAccountId());
        assertEquals(TODAY.minusDays(1), report.getDay());
        assertEquals(12_345, report.getAmountMinor());
        assertEquals("billing-export", report.getReportedBy());
        assertEquals(NOW, report.getReportedAt());
        assertEquals(TODAY.plusDays(1), CostReport.createNew(id, org, account, TODAY.plusDays(1), 0, "t", TODAY, NOW).getDay());
    }

    @Test
    @DisplayName("identity, a day not past tomorrow, a sane amount and the reporter are checked")
    void validation() {
        UUID id = UUID.randomUUID();
        rejects(() -> CostReport.createNew(null, org, account, TODAY, 1, "t", TODAY, NOW));
        rejects(() -> CostReport.createNew(id, null, account, TODAY, 1, "t", TODAY, NOW));
        rejects(() -> CostReport.createNew(id, org, null, TODAY, 1, "t", TODAY, NOW));
        rejects(() -> CostReport.createNew(id, org, account, null, 1, "t", TODAY, NOW));
        rejects(() -> CostReport.createNew(id, org, account, TODAY.plusDays(2), 1, "t", TODAY, NOW));
        rejects(() -> CostReport.createNew(id, org, account, TODAY, -1, "t", TODAY, NOW));
        rejects(() -> CostReport.createNew(id, org, account, TODAY, Money.MAX_MINOR + 1, "t", TODAY, NOW));
        rejects(() -> CostReport.createNew(id, org, account, TODAY, 1, null, TODAY, NOW));
        rejects(() -> CostReport.createNew(id, org, account, TODAY, 1, " ", TODAY, NOW));
    }

    @Test
    @DisplayName("reconstitute keeps what was stored and refuses a missing identity or day")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        assertEquals(7, CostReport.reconstitute(id, org, account, TODAY, 7, "t", NOW).getAmountMinor());
        rejects(() -> CostReport.reconstitute(null, org, account, TODAY, 7, "t", NOW));
        rejects(() -> CostReport.reconstitute(id, null, account, TODAY, 7, "t", NOW));
        rejects(() -> CostReport.reconstitute(id, org, null, TODAY, 7, "t", NOW));
        rejects(() -> CostReport.reconstitute(id, org, account, null, 7, "t", NOW));
    }
}
