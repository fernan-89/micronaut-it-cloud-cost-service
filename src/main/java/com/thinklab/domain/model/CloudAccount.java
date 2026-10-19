package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidCloudAccountStatusException;
import com.thinklab.domain.model.Money.Currency;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aggregate Root (BIAN Control Record) of the IT Cloud Cost Service Domain: one account the organisation pays a cloud provider for, with its
 * monthly budget. An account names the {@link Provider}, the provider's own id for it, the {@link Currency} its bill and its budget are in
 * (never converted, ADR-030), the use of the budget at which to warn, and how often the cost tool is expected to report. The tool reports what
 * each day cost; the account is judged when read (ADR-031): what the month has cost so far, where a straight line takes it by the month's end,
 * and whether that is within the budget. It may name the vendor it is bought from (a vendor registry id, not checked).
 */
public class CloudAccount {

    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_REPORT_EVERY_HOURS = 720;
    /** The provider's id for the account: letters, digits, dots, dashes, underscores and colons (an AWS account number, an Azure subscription id...). */
    public static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final UUID id;
    private final UUID organisationId;
    private String name;
    private Provider provider;
    private String externalId;
    private Currency currency;
    private long budgetMinor;
    private int warnPercent;
    private int reportEveryHours;
    private UUID vendorId;
    private AccountStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant lastReportedAt;
    private final List<AccountAuditEntry> auditTrail;

    private CloudAccount(UUID id, UUID organisationId, String name, Provider provider, String externalId, Currency currency, long budgetMinor, int warnPercent,
                         int reportEveryHours, UUID vendorId, AccountStatus status, Instant createdAt, Instant updatedAt, Instant lastReportedAt,
                         List<AccountAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.provider = provider;
        this.externalId = externalId;
        this.currency = currency;
        this.budgetMinor = budgetMinor;
        this.warnPercent = warnPercent;
        this.reportEveryHours = reportEveryHours;
        this.vendorId = vendorId;
        this.status = status != null ? status : AccountStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.lastReportedAt = lastReportedAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    public static CloudAccount createNew(UUID id, UUID organisationId, String name, Provider provider, String externalId, Currency currency, long budgetMinor,
                                         int warnPercent, int reportEveryHours, UUID vendorId, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for CloudAccount creation.");
        }
        validate(name, provider, externalId, currency, budgetMinor, warnPercent, reportEveryHours);
        requireExecutor(executor);
        CloudAccount account = new CloudAccount(id, organisationId, name, provider, externalId, currency, budgetMinor, warnPercent, reportEveryHours, vendorId,
                AccountStatus.ACTIVE, null, null, null, null);
        account.auditTrail.add(new AccountAuditEntry(account.createdAt, "INITIATED", executor, null, AccountStatus.ACTIVE,
                provider + " account with a monthly budget of " + Money.toMajor(budgetMinor) + " " + currency + "."));
        return account;
    }

    public static CloudAccount reconstitute(UUID id, UUID organisationId, String name, Provider provider, String externalId, Currency currency, long budgetMinor,
                                            int warnPercent, int reportEveryHours, UUID vendorId, AccountStatus status, Instant createdAt, Instant updatedAt,
                                            Instant lastReportedAt, List<AccountAuditEntry> auditTrail) {
        if (id == null || organisationId == null || name == null || provider == null || externalId == null || currency == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Name, Provider, External ID and Currency are mandatory to reconstitute a CloudAccount.");
        }
        return new CloudAccount(id, organisationId, name, provider, externalId, currency, budgetMinor, warnPercent, reportEveryHours, vendorId, status, createdAt,
                updatedAt, lastReportedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /**
     * Behavior Qualifier: {@code update}. The name, budget, warning, interval and vendor, in any status. The provider, its id and the currency
     * identify what the reports are about and never change: a different account is a different account.
     */
    public AccountAuditEntry update(String newName, long newBudgetMinor, int newWarnPercent, int newReportEveryHours, UUID newVendorId, String executor) {
        validate(newName, provider, externalId, currency, newBudgetMinor, newWarnPercent, newReportEveryHours);
        requireExecutor(executor);
        this.name = newName;
        this.budgetMinor = newBudgetMinor;
        this.warnPercent = newWarnPercent;
        this.reportEveryHours = newReportEveryHours;
        this.vendorId = newVendorId;
        return record("UPDATED", executor, this.status, this.status, "Account updated: a monthly budget of " + Money.toMajor(newBudgetMinor) + " " + currency + ".");
    }

    /** Behavior Qualifier: {@code control/pause}. ACTIVE -&gt; PAUSED: the account is no longer judged. */
    public AccountAuditEntry pause(String executor) {
        requireStatus(AccountStatus.ACTIVE);
        requireExecutor(executor);
        this.status = AccountStatus.PAUSED;
        return record("PAUSED", executor, AccountStatus.ACTIVE, AccountStatus.PAUSED, "Paused: the account is no longer judged.");
    }

    /** Behavior Qualifier: {@code control/resume}. PAUSED -&gt; ACTIVE. */
    public AccountAuditEntry resume(String executor) {
        requireStatus(AccountStatus.PAUSED);
        requireExecutor(executor);
        this.status = AccountStatus.ACTIVE;
        return record("RESUMED", executor, AccountStatus.PAUSED, AccountStatus.ACTIVE, "Resumed.");
    }

    /**
     * Where the account stands in the month of {@code today} (ADR-031), given what each reported day of that month cost ({@code days}, the latest
     * report of each day). The month so far is their sum; the month's end is that sum spread over the days up to the last one reported and carried
     * to the last day of the month in a straight line. OVER_BUDGET when the month so far is above the budget, NEAR_BUDGET from the warning
     * percentage on or when the line ends above the budget, OK otherwise; NO_DATA with no day reported this month; a paused account is not judged.
     * The reports are stale when the tool has been silent for more than twice its expected interval. Nothing of it is stored.
     */
    public Assessment assess(LocalDate today, Instant now, List<DayCost> days) {
        YearMonth month = YearMonth.from(today);
        if (status == AccountStatus.PAUSED) {
            return new Assessment(month, Level.PAUSED, null, null, null, false);
        }
        boolean stale = lastReportedAt != null && now.isAfter(lastReportedAt.plus(Duration.ofHours(2L * reportEveryHours)));
        List<DayCost> ofMonth = days.stream().filter(day -> YearMonth.from(day.day()).equals(month)).toList();
        if (ofMonth.isEmpty()) {
            return new Assessment(month, Level.NO_DATA, null, null, null, stale);
        }
        long spent = ofMonth.stream().mapToLong(DayCost::amountMinor).sum();
        int lastDay = ofMonth.stream().mapToInt(day -> day.day().getDayOfMonth()).max().orElseThrow();
        long projected = Math.round((double) spent / lastDay * month.lengthOfMonth());
        double usedPercent = budgetMinor == 0 ? 0 : spent * 100.0 / budgetMinor;
        Level level = spent > budgetMinor ? Level.OVER_BUDGET : usedPercent >= warnPercent || projected > budgetMinor ? Level.NEAR_BUDGET : Level.OK;
        return new Assessment(month, level, spent, projected, usedPercent, stale);
    }

    /** A report arrived: the moment it arrived is what "stale" is measured from. Only ever moves forward. */
    public void reported(Instant at) {
        if (lastReportedAt == null || at.isAfter(lastReportedAt)) {
            this.lastReportedAt = at;
        }
    }

    // --- Internal helpers ---

    private AccountAuditEntry record(String action, String executor, AccountStatus from, AccountStatus to, String detail) {
        this.updatedAt = Instant.now();
        AccountAuditEntry entry = new AccountAuditEntry(this.updatedAt, action, executor, from, to, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(AccountStatus expected) {
        if (this.status != expected) {
            throw new InvalidCloudAccountStatusException(String.format("Illegal transition: CloudAccount is [%s], expected [%s].", this.status, expected));
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable CloudAccount mutations.");
        }
    }

    private static void validate(String name, Provider provider, String externalId, Currency currency, long budgetMinor, int warnPercent, int reportEveryHours) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Name is mandatory for a CloudAccount (up to " + MAX_NAME_LENGTH + " characters).");
        }
        if (provider == null || currency == null) {
            throw new IllegalArgumentException("The provider and the currency of the account are mandatory.");
        }
        if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
            throw new IllegalArgumentException("The provider's account id must be up to 64 letters, digits, dots, dashes, underscores or colons.");
        }
        if (budgetMinor < 0 || budgetMinor > Money.MAX_MINOR) {
            throw new IllegalArgumentException("The monthly budget must be zero or more, and not absurdly large.");
        }
        if (warnPercent < 1 || warnPercent > 100) {
            throw new IllegalArgumentException("The warning use must be between 1 and 100 percent.");
        }
        if (reportEveryHours < 1 || reportEveryHours > MAX_REPORT_EVERY_HOURS) {
            throw new IllegalArgumentException("The expected reporting interval must be between 1 and " + MAX_REPORT_EVERY_HOURS + " hours.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getName() { return name; }
    public Provider getProvider() { return provider; }
    public String getExternalId() { return externalId; }
    public Currency getCurrency() { return currency; }
    public long getBudgetMinor() { return budgetMinor; }
    public int getWarnPercent() { return warnPercent; }
    public int getReportEveryHours() { return reportEveryHours; }
    public UUID getVendorId() { return vendorId; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLastReportedAt() { return lastReportedAt; }
    public List<AccountAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum AccountStatus { ACTIVE, PAUSED }

    public enum Provider { AWS, AZURE, GCP, OCI, OTHER }

    /** PAUSED is not judged; NO_DATA has no day reported this month; the rest compare the month so far, and where it is heading, with the budget. */
    public enum Level { PAUSED, NO_DATA, OK, NEAR_BUDGET, OVER_BUDGET }

    public record AccountAuditEntry(Instant occurredAt, String action, String executor, AccountStatus fromStatus, AccountStatus toStatus, String detail) {}

    /** What one day cost, as the latest report of that day says. */
    public record DayCost(LocalDate day, long amountMinor) {}

    /** The judgment of one moment for one month: nothing of it is stored. Amounts are in minor units and null when they do not apply. */
    public record Assessment(YearMonth month, Level level, Long spentMinor, Long projectedMinor, Double usedPercent, boolean stale) {}
}
