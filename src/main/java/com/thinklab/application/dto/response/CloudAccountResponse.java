package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An account and where its month stands right now: {@code month}, {@code level}, {@code spent}, {@code projected}, {@code usedPercent} and
 * {@code stale} are judged when read, never stored. Amounts are in the account's currency.
 */
@Serdeable
public record CloudAccountResponse(UUID id, UUID organisationId, String name, String provider, String externalId, String currency, BigDecimal monthlyBudget,
                                   int warnPercent, int reportEveryHours, @Nullable UUID vendorId, String status, String month, String level, @Nullable BigDecimal spent,
                                   @Nullable BigDecimal projected, @Nullable Double usedPercent, boolean stale, @Nullable Instant lastReportedAt, Instant createdAt,
                                   Instant updatedAt) {}
