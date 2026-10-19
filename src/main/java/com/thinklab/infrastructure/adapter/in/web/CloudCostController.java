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
import com.thinklab.application.usecase.RetrieveCloudAccountsUseCase;
import com.thinklab.application.usecase.RetrieveCloudAccountAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveCloudAccountUseCase;
import com.thinklab.application.usecase.RetrieveCostReportUseCase;
import com.thinklab.application.usecase.RetrieveCostReportsUseCase;
import com.thinklab.application.usecase.UpdateCloudAccountUseCase;
import com.thinklab.domain.model.CloudAccount.Level;
import com.thinklab.domain.model.CloudAccount.Provider;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.model.CloudAccount.AccountStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-cloud-cost} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.CloudAccount} is the Control Record, at the root;
 * {@link com.thinklab.domain.model.CostReport} is the secondary aggregate, under {@code /usage}: counts the tool reports, never edited, never
 * deleted. There is no {@code DELETE}: a account is paused.
 *
 * <p><b>Tenant on every route, staff only (ADR-032):</b> {@code X-Tenant-Id} scopes every lookup (another tenant's account is a 404) and
 * {@code X-Role} makes every route refuse a {@code REQUESTER} with 403 {@code ERR-CLD-00403}.
 */
@Controller("/it-cloud-cost/v1")
public class CloudCostController {

    private static final Logger log = LoggerFactory.getLogger(CloudCostController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";
    static final String ROLE_HEADER = "X-Role";

    private final InitiateCloudAccountUseCase initiateCloudAccount;
    private final RetrieveCloudAccountUseCase retrieveCloudAccount;
    private final RetrieveCloudAccountsUseCase retrieveCloudAccounts;
    private final UpdateCloudAccountUseCase updateCloudAccount;
    private final ControlCloudAccountUseCase controlCloudAccount;
    private final RetrieveCloudAccountAuditLogUseCase accountAuditLog;
    private final ReportCostUseCase reportCost;
    private final RetrieveCostReportUseCase retrieveReport;
    private final RetrieveCostReportsUseCase retrieveReports;

    public CloudCostController(InitiateCloudAccountUseCase initiateCloudAccount, RetrieveCloudAccountUseCase retrieveCloudAccount, RetrieveCloudAccountsUseCase retrieveCloudAccounts,
                                    UpdateCloudAccountUseCase updateCloudAccount, ControlCloudAccountUseCase controlCloudAccount, RetrieveCloudAccountAuditLogUseCase accountAuditLog,
                                    ReportCostUseCase reportCost, RetrieveCostReportUseCase retrieveReport, RetrieveCostReportsUseCase retrieveReports) {
        this.initiateCloudAccount = initiateCloudAccount;
        this.retrieveCloudAccount = retrieveCloudAccount;
        this.retrieveCloudAccounts = retrieveCloudAccounts;
        this.updateCloudAccount = updateCloudAccount;
        this.controlCloudAccount = controlCloudAccount;
        this.accountAuditLog = accountAuditLog;
        this.reportCost = reportCost;
        this.retrieveReport = retrieveReport;
        this.retrieveReports = retrieveReports;
    }

    // ------------------------------------------------------------------ Accounts

    /** Behavior Qualifier: {@code initiate}. A cloud account and its monthly budget. */
    @Post("/initiate")
    public Mono<HttpResponse<CloudAccountResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiateCloudAccountRequest request
    ) {
        log.info("[ACTION: INITIATE_CLOUD_ACCOUNT] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return initiateCloudAccount.execute(UUID.fromString(tenantId), request, executor, role).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. One account of the tenant, judged now. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<CloudAccountResponse>> retrieve(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveCloudAccount.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection), oldest first, filterable by status, provider, currency and level. */
    @Get("/retrieve")
    public Mono<List<CloudAccountResponse>> retrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                        @QueryValue @Nullable AccountStatus status, @QueryValue @Nullable Provider provider, @QueryValue @Nullable Currency currency,
                                                        @QueryValue @Nullable Level level) {
        return Mono.defer(() -> retrieveCloudAccounts.execute(UUID.fromString(tenantId), status, provider, currency, level, role).collectList());
    }

    /** Behavior Qualifier: {@code update}. The whole definition again. */
    @Put("/{id}/update")
    public Mono<HttpResponse<Void>> update(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid UpdateCloudAccountRequest request
    ) {
        return Mono.defer(() -> updateCloudAccount.execute(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/pause}. ACTIVE -&gt; PAUSED: the account is no longer judged. */
    @Put("/{id}/control/pause")
    public Mono<HttpResponse<Void>> pause(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                          @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlCloudAccountUseCase.Action.PAUSE, executor, role);
    }

    /** Behavior Qualifier: {@code control/resume}. PAUSED -&gt; ACTIVE. */
    @Put("/{id}/control/resume")
    public Mono<HttpResponse<Void>> resume(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                           @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlCloudAccountUseCase.Action.RESUME, executor, role);
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> auditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> accountAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    // ------------------------------------------------------------------ Cost reports

    /** Behavior Qualifier: {@code cost/initiate}. What the tool reports when it measures: 201 for a new report, 200 for one already reported. */
    @Post("/cost/initiate")
    public Mono<HttpResponse<CostReportResponse>> reportCost(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid ReportCostRequest request
    ) {
        log.info("[ACTION: REPORT_COST] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return reportCost.execute(UUID.fromString(tenantId), request, executor, role)
                .map(reported -> reported.created() ? HttpResponse.created(reported.report()) : HttpResponse.ok(reported.report()));
    }

    /** Behavior Qualifier: {@code cost/retrieve}. One report of the tenant. */
    @Get("/cost/{id}/retrieve")
    public Mono<HttpResponse<CostReportResponse>> retrieveReport(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveReport.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code cost/retrieve} (collection), newest first, at most 200 (50 unless asked). */
    @Get("/cost/retrieve")
    public Mono<List<CostReportResponse>> retrieveReports(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                      @QueryValue @Nullable UUID accountId, @QueryValue @Nullable Integer limit) {
        return Mono.defer(() -> retrieveReports.execute(UUID.fromString(tenantId), accountId, limit, role).collectList());
    }

    private Mono<HttpResponse<Void>> control(UUID id, String tenantId, ControlCloudAccountUseCase.Action action, String executor, String role) {
        log.info("[ACTION: {}_CLOUD_ACCOUNT] [EXECUTOR: {}] for ID: {}", action, executor, id);

        return Mono.defer(() -> controlCloudAccount.execute(id, UUID.fromString(tenantId), action, executor, role)).thenReturn(HttpResponse.noContent());
    }
}
