package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.CloudAccountResponse;
import com.thinklab.application.dto.response.CostReportResponse;
import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.AccountAuditEntry;
import com.thinklab.domain.model.CloudAccount.Assessment;
import com.thinklab.domain.model.CostReport;
import com.thinklab.domain.model.Money;

public final class CostMapper {

    private CostMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** The account as it stands, with how its month was judged. */
    public static CloudAccountResponse toResponse(CloudAccount account, Assessment judged) {
        return new CloudAccountResponse(account.getId(), account.getOrganisationId(), account.getName(), account.getProvider().name(), account.getExternalId(),
                account.getCurrency().name(), Money.toMajor(account.getBudgetMinor()), account.getWarnPercent(), account.getReportEveryHours(), account.getVendorId(),
                account.getStatus().name(), judged.month().toString(), judged.level().name(), judged.spentMinor() != null ? Money.toMajor(judged.spentMinor()) : null,
                judged.projectedMinor() != null ? Money.toMajor(judged.projectedMinor()) : null, judged.usedPercent(), judged.stale(), account.getLastReportedAt(),
                account.getCreatedAt(), account.getUpdatedAt());
    }

    public static CostReportResponse toResponse(CostReport report) {
        return new CostReportResponse(report.getId(), report.getOrganisationId(), report.getAccountId(), report.getDay(), Money.toMajor(report.getAmountMinor()),
                report.getReportedBy(), report.getReportedAt());
    }

    public static AuditEntryResponse toResponse(AccountAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus().name(), entry.detail());
    }
}
