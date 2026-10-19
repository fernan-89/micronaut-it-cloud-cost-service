package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.AccountAuditEntry;
import com.thinklab.domain.model.CloudAccount.AccountStatus;
import com.thinklab.domain.model.CloudAccount.Provider;
import com.thinklab.domain.model.Money.Currency;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the CloudAccount Aggregate for MongoDB. The budget is kept in minor units, a whole number. */
@Introspected
public class CloudAccountDocument {

    @BsonId
    private UUID id;
    private UUID organisationId;
    private String name;
    private String provider;
    private String externalId;
    private String currency;
    private long budgetMinor;
    private int warnPercent;
    private int reportEveryHours;
    private UUID vendorId;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastReportedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getBudgetMinor() { return budgetMinor; }
    public void setBudgetMinor(long budgetMinor) { this.budgetMinor = budgetMinor; }
    public int getWarnPercent() { return warnPercent; }
    public void setWarnPercent(int warnPercent) { this.warnPercent = warnPercent; }
    public int getReportEveryHours() { return reportEveryHours; }
    public void setReportEveryHours(int reportEveryHours) { this.reportEveryHours = reportEveryHours; }
    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getLastReportedAt() { return lastReportedAt; }
    public void setLastReportedAt(Instant lastReportedAt) { this.lastReportedAt = lastReportedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(AccountAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        AccountAuditEntry toDomain() {
            return new AccountAuditEntry(occurredAt, action, executor, fromStatus != null ? AccountStatus.valueOf(fromStatus) : null, AccountStatus.valueOf(toStatus), detail);
        }
    }

    public static final class CloudAccountPersistenceMapper {

        private CloudAccountPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static CloudAccountDocument toDocument(CloudAccount account) {
            CloudAccountDocument doc = new CloudAccountDocument();
            doc.setId(account.getId());
            doc.setOrganisationId(account.getOrganisationId());
            doc.setName(account.getName());
            doc.setProvider(account.getProvider().name());
            doc.setExternalId(account.getExternalId());
            doc.setCurrency(account.getCurrency().name());
            doc.setBudgetMinor(account.getBudgetMinor());
            doc.setWarnPercent(account.getWarnPercent());
            doc.setReportEveryHours(account.getReportEveryHours());
            doc.setVendorId(account.getVendorId());
            doc.setStatus(account.getStatus().name());
            doc.setCreatedAt(account.getCreatedAt());
            doc.setUpdatedAt(account.getUpdatedAt());
            doc.setLastReportedAt(account.getLastReportedAt());
            doc.setAuditTrail(account.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static CloudAccount toDomain(CloudAccountDocument doc) {
            return CloudAccount.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getName(), Provider.valueOf(doc.getProvider()), doc.getExternalId(),
                    Currency.valueOf(doc.getCurrency()), doc.getBudgetMinor(), doc.getWarnPercent(), doc.getReportEveryHours(), doc.getVendorId(),
                    AccountStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(), doc.getLastReportedAt(),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
