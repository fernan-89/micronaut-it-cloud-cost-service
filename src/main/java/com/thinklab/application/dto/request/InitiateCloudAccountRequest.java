package com.thinklab.application.dto.request;

import com.thinklab.domain.model.CloudAccount.Provider;
import com.thinklab.domain.model.Money.Currency;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DTO for {@code initiate}: a cloud account, the provider's id for it, the currency its bill and its budget are in, the monthly budget (a decimal
 * with at most two places), when to warn and how often the cost tool reports. The vendor is a vendor registry id and is optional.
 */
@Serdeable
public record InitiateCloudAccountRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name must not exceed 80 characters")
        String name,
        @NotNull(message = "The provider is required") Provider provider,
        @NotBlank(message = "The provider's account id is required")
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "The provider's account id must be up to 64 letters, digits, dots, dashes, underscores or colons")
        String externalId,
        @NotNull(message = "The currency is required") Currency currency,
        @NotNull(message = "The monthly budget is required") @PositiveOrZero(message = "The monthly budget cannot be negative") BigDecimal monthlyBudget,
        @NotNull(message = "The warning use is required") @Min(value = 1, message = "The warning use must be at least 1 percent") @Max(value = 100, message = "The warning use must not exceed 100 percent")
        Integer warnPercent,
        @NotNull(message = "The reporting interval is required") @Min(value = 1, message = "The reporting interval must be at least 1 hour") @Max(value = 720, message = "The reporting interval must not exceed 720 hours")
        Integer reportEveryHours,
        @Nullable UUID vendorId
) {}
