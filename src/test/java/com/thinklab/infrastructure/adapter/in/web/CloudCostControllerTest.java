package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiateCloudAccountRequest;
import com.thinklab.application.dto.request.ReportCostRequest;
import com.thinklab.application.dto.request.UpdateCloudAccountRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.CloudAccountResponse;
import com.thinklab.application.dto.response.CostReportResponse;
import com.thinklab.application.usecase.ControlCloudAccountUseCase;
import com.thinklab.application.usecase.InitiateCloudAccountUseCase;
import com.thinklab.application.usecase.ReportCostUseCase;
import com.thinklab.application.usecase.ReportCostUseCase.Reported;
import com.thinklab.application.usecase.RetrieveCloudAccountAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveCloudAccountUseCase;
import com.thinklab.application.usecase.RetrieveCloudAccountsUseCase;
import com.thinklab.application.usecase.RetrieveCostReportUseCase;
import com.thinklab.application.usecase.RetrieveCostReportsUseCase;
import com.thinklab.application.usecase.UpdateCloudAccountUseCase;
import com.thinklab.domain.model.CloudAccount.AccountStatus;
import com.thinklab.domain.model.CloudAccount.Level;
import com.thinklab.domain.model.CloudAccount.Provider;
import com.thinklab.domain.model.Money.Currency;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** The controller only reads headers and delegates: tenant, executor and role always travel to the use case. */
@ExtendWith(MockitoExtension.class)
class CloudCostControllerTest {

    private static final String EXECUTOR = "op-1";
    private static final String ROLE = "ENGINEER";
    private final UUID tenant = UUID.randomUUID();
    private final String tenantHeader = tenant.toString();
    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-10T12:00:00Z");

    @Mock private InitiateCloudAccountUseCase initiateCloudAccount;
    @Mock private RetrieveCloudAccountUseCase retrieveCloudAccount;
    @Mock private RetrieveCloudAccountsUseCase retrieveCloudAccounts;
    @Mock private UpdateCloudAccountUseCase updateCloudAccount;
    @Mock private ControlCloudAccountUseCase controlCloudAccount;
    @Mock private RetrieveCloudAccountAuditLogUseCase accountAuditLog;
    @Mock private ReportCostUseCase reportCost;
    @Mock private RetrieveCostReportUseCase retrieveReport;
    @Mock private RetrieveCostReportsUseCase retrieveReports;

    private CloudCostController controller() {
        return new CloudCostController(initiateCloudAccount, retrieveCloudAccount, retrieveCloudAccounts, updateCloudAccount, controlCloudAccount, accountAuditLog, reportCost,
                retrieveReport, retrieveReports);
    }

    private CloudAccountResponse accountResponse() {
        return new CloudAccountResponse(id, tenant, "Production", "AWS", "123", "USD", new BigDecimal("3100.00"), 80, 48, null, "ACTIVE", "2026-10", "OK",
                new BigDecimal("500.00"), new BigDecimal("1550.00"), 16.1, false, now, now, now);
    }

    private CostReportResponse reportResponse() {
        return new CostReportResponse(id, tenant, UUID.randomUUID(), LocalDate.parse("2026-10-09"), new BigDecimal("12.34"), "billing", now);
    }

    @Test
    @DisplayName("the account routes answer 201, 200 and 204 and pass the filters and the right action")
    void accounts() {
        InitiateCloudAccountRequest initiate = new InitiateCloudAccountRequest("Production", Provider.AWS, "123", Currency.USD, new BigDecimal("3100"), 80, 48, null);
        UpdateCloudAccountRequest update = new UpdateCloudAccountRequest("Prod", new BigDecimal("5000"), 90, 24, null);
        when(initiateCloudAccount.execute(tenant, initiate, EXECUTOR, ROLE)).thenReturn(Mono.just(accountResponse()));
        when(retrieveCloudAccount.execute(id, tenant, ROLE)).thenReturn(Mono.just(accountResponse()));
        when(retrieveCloudAccounts.execute(tenant, AccountStatus.ACTIVE, Provider.AWS, Currency.USD, Level.OK, ROLE)).thenReturn(Flux.just(accountResponse()));
        when(updateCloudAccount.execute(id, tenant, update, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlCloudAccount.execute(id, tenant, ControlCloudAccountUseCase.Action.PAUSE, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlCloudAccount.execute(id, tenant, ControlCloudAccountUseCase.Action.RESUME, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(accountAuditLog.execute(id, tenant, ROLE)).thenReturn(Mono.just(List.of(new AuditEntryResponse(now, "INITIATED", EXECUTOR, null, "ACTIVE", null))));

        StepVerifier.create(controller().initiate(tenantHeader, EXECUTOR, ROLE, initiate)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieve(id, tenantHeader, ROLE)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveAll(tenantHeader, ROLE, AccountStatus.ACTIVE, Provider.AWS, Currency.USD, Level.OK)).assertNext(l -> assertEquals(1, l.size())).verifyComplete();
        StepVerifier.create(controller().update(id, tenantHeader, EXECUTOR, ROLE, update)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().pause(id, tenantHeader, EXECUTOR, ROLE)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().resume(id, tenantHeader, EXECUTOR, ROLE)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().auditLog(id, tenantHeader, ROLE)).assertNext(l -> assertEquals(1, l.size())).verifyComplete();
    }

    @Test
    @DisplayName("a new report is 201 and one already reported is 200; one report and the list pass the account and the limit")
    void reports() {
        ReportCostRequest request = new ReportCostRequest(UUID.randomUUID(), LocalDate.parse("2026-10-09"), new BigDecimal("12.34"));
        UUID accountId = UUID.randomUUID();
        when(reportCost.execute(tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.just(new Reported(reportResponse(), true))).thenReturn(Mono.just(new Reported(reportResponse(), false)));
        when(retrieveReport.execute(id, tenant, ROLE)).thenReturn(Mono.just(reportResponse()));
        when(retrieveReports.execute(tenant, accountId, 10, ROLE)).thenReturn(Flux.just(reportResponse()));

        StepVerifier.create(controller().reportCost(tenantHeader, EXECUTOR, ROLE, request)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().reportCost(tenantHeader, EXECUTOR, ROLE, request)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveReport(id, tenantHeader, ROLE)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveReports(tenantHeader, ROLE, accountId, 10)).assertNext(l -> assertEquals(1, l.size())).verifyComplete();
    }
}
