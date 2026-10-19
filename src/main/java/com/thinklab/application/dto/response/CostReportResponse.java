package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Serdeable
public record CostReportResponse(UUID id, UUID organisationId, UUID accountId, LocalDate day, BigDecimal amount, String reportedBy, Instant reportedAt) {}
