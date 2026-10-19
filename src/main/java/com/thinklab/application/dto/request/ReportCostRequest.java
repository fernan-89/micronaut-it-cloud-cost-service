package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * DTO for {@code cost/initiate}: what one day of the account cost, in the account's currency (a decimal with at most two places). The same day and
 * amount again answers the report already stored; another amount for the same day is a correction.
 */
@Serdeable
public record ReportCostRequest(
        @NotNull(message = "The account is required") UUID accountId,
        @NotNull(message = "The day is required") LocalDate day,
        @NotNull(message = "The amount is required") @PositiveOrZero(message = "The amount cannot be negative") BigDecimal amount
) {}
