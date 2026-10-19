package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.CostReport;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Infrastructure-specific representation of the CostReport Aggregate for MongoDB. The day is ISO text (it sorts like the date); the amount is in minor units. */
@Introspected
public class CostReportDocument {

    @BsonId
    private UUID id;
    private UUID organisationId;
    private UUID accountId;
    private String day;
    private long amountMinor;
    private String reportedBy;
    private Instant reportedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getDay() { return day; }
    public void setDay(String day) { this.day = day; }
    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public String getReportedBy() { return reportedBy; }
    public void setReportedBy(String reportedBy) { this.reportedBy = reportedBy; }
    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }

    public static final class CostReportPersistenceMapper {

        private CostReportPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static CostReportDocument toDocument(CostReport report) {
            CostReportDocument doc = new CostReportDocument();
            doc.setId(report.getId());
            doc.setOrganisationId(report.getOrganisationId());
            doc.setAccountId(report.getAccountId());
            doc.setDay(report.getDay().toString());
            doc.setAmountMinor(report.getAmountMinor());
            doc.setReportedBy(report.getReportedBy());
            doc.setReportedAt(report.getReportedAt());
            return doc;
        }

        public static CostReport toDomain(CostReportDocument doc) {
            return CostReport.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getAccountId(), LocalDate.parse(doc.getDay()), doc.getAmountMinor(),
                    doc.getReportedBy(), doc.getReportedAt());
        }
    }
}
