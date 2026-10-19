package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.CloudAccountResponse;
import com.thinklab.application.dto.response.CostReportResponse;
import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.DayCost;
import com.thinklab.domain.model.CloudAccount.Provider;
import com.thinklab.domain.model.CostReport;
import com.thinklab.domain.model.Money.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CostMapperTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-10");
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private final UUID org = UUID.randomUUID();

    @Test
    @DisplayName("an account answers its budget and its month in the major unit of its currency")
    void account() {
        UUID vendor = UUID.randomUUID();
        CloudAccount account = CloudAccount.createNew(UUID.randomUUID(), org, "Production", Provider.AWS, "123", Currency.USD, 310_000, 80, 48, vendor, "op");

        CloudAccountResponse response = CostMapper.toResponse(account, account.assess(TODAY, NOW, List.of(new DayCost(TODAY.withDayOfMonth(4), 50_000))));

        assertEquals("Production", response.name());
        assertEquals("AWS", response.provider());
        assertEquals("123", response.externalId());
        assertEquals("USD", response.currency());
        assertEquals(new BigDecimal("3100.00"), response.monthlyBudget());
        assertEquals(80, response.warnPercent());
        assertEquals(48, response.reportEveryHours());
        assertEquals(vendor, response.vendorId());
        assertEquals("ACTIVE", response.status());
        assertEquals("2026-10", response.month());
        assertEquals("NEAR_BUDGET", response.level());
        assertEquals(new BigDecimal("500.00"), response.spent());
        assertEquals(new BigDecimal("3875.00"), response.projected());
        assertFalse(response.stale());
        assertNull(response.lastReportedAt());
    }

    @Test
    @DisplayName("an account with no day this month answers no amounts; a report answers its amount; an audit entry keeps the earlier status")
    void emptyReportAndAudit() {
        CloudAccount account = CloudAccount.createNew(UUID.randomUUID(), org, "Data", Provider.GCP, "p", Currency.BRL, 0, 80, 48, null, "op");
        account.pause("op-2");
        CloudAccountResponse response = CostMapper.toResponse(account, account.assess(TODAY, NOW, List.of()));
        CostReport report = CostReport.createNew(UUID.randomUUID(), org, account.getId(), TODAY, 7, "billing", TODAY, NOW);
        CostReportResponse reportResponse = CostMapper.toResponse(report);
        AuditEntryResponse first = CostMapper.toResponse(account.getAuditTrail().get(0));
        AuditEntryResponse second = CostMapper.toResponse(account.getAuditTrail().get(1));

        assertEquals("PAUSED", response.level());
        assertNull(response.spent());
        assertNull(response.projected());
        assertEquals(new BigDecimal("0.07"), reportResponse.amount());
        assertEquals(TODAY, reportResponse.day());
        assertEquals("billing", reportResponse.reportedBy());
        assertNull(first.fromStatus());
        assertEquals("ACTIVE", second.fromStatus());
        assertEquals("op-2", second.executor());
    }

    @Test
    @DisplayName("the mapper is a utility class")
    void utility() throws ReflectiveOperationException {
        Constructor<CostMapper> constructor = CostMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }
}
