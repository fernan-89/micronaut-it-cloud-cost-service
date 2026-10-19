package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** DTO for {@code update}: everything but the provider, its account id and the currency, which never change (a vendor left out is cleared). */
@Serdeable
public record UpdateCloudAccountRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name must not exceed 80 characters")
        String name,
        @NotNull(message = "The monthly budget is required") @PositiveOrZero(message = "The monthly budget cannot be negative") BigDecimal monthlyBudget,
        @NotNull(message = "The warning use is required") @Min(value = 1, message = "The warning use must be at least 1 percent") @Max(value = 100, message = "The warning use must not exceed 100 percent")
        Integer warnPercent,
        @NotNull(message = "The reporting interval is required") @Min(value = 1, message = "The reporting interval must be at least 1 hour") @Max(value = 720, message = "The reporting interval must not exceed 720 hours")
        Integer reportEveryHours,
        @Nullable UUID vendorId
) {}
