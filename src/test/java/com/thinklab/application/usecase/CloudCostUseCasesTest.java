package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateCloudAccountRequest;
import com.thinklab.application.dto.request.ReportCostRequest;
import com.thinklab.application.dto.request.UpdateCloudAccountRequest;
import com.thinklab.domain.exception.CloudAccountNotFoundException;
import com.thinklab.domain.exception.CostAccessDeniedException;
import com.thinklab.domain.exception.CostReportNotFoundException;
import com.thinklab.domain.exception.InvalidCloudAccountStatusException;
import com.thinklab.domain.model.CloudAccount;
import com.thinklab.domain.model.CloudAccount.AccountStatus;
import com.thinklab.domain.model.CloudAccount.DayCost;
import com.thinklab.domain.model.CloudAccount.Level;
import com.thinklab.domain.model.CloudAccount.Provider;
import com.thinklab.domain.model.CostReport;
import com.thinklab.domain.model.Money.Currency;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.CloudAccountRepository;
import com.thinklab.domain.repository.CostReportRepository;
import com.thinklab.domain.repository.CostReportRepository.Stored;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudCostUseCasesTest {

    private static final String STAFF = "ENGINEER";
    private static final String REQUESTER = "REQUESTER";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final YearMonth MONTH = YearMonth.of(2026, 10);

    private final UUID org = UUID.randomUUID();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private HashServicePort hash;
    private CloudAccountRepository accounts;
    private CostReportRepository reports;
    private CostAssessor assessor;

    @BeforeEach
    void setUp() {
        hash = mock(HashServicePort.class);
        accounts = mock(CloudAccountRepository.class);
        reports = mock(CostReportRepository.class);
        when(hash.generateSovereignId(any())).thenAnswer(call -> Mono.just(UUID.randomUUID()));
        when(reports.latestOfMonth(any(), any(), any())).thenReturn(Mono.just(List.of()));
        assessor = new CostAssessor(reports, clock);
    }

    private CloudAccount account(String name, Provider provider, Currency currency) {
        return CloudAccount.createNew(UUID.randomUUID(), org, name, provider, name.toLowerCase().replace(' ', '-'), currency, 310_000, 80, 48, null, "op");
    }

    private InitiateCloudAccountRequest initiate(String budget) {
        return new InitiateCloudAccountRequest("Production", Provider.AWS, "123456789012", Currency.USD, new BigDecimal(budget), 80, 48, null);
    }

    @Test
    @DisplayName("the access rule refuses a requester and lets staff and a missing role through; it is a utility class")
    void access() throws ReflectiveOperationException {
        CostAccess.requireStaff(STAFF, "x");
        CostAccess.requireStaff(null, "x");
        assertThrows(CostAccessDeniedException.class, () -> CostAccess.requireStaff(REQUESTER, "x"));
        Constructor<CostAccess> constructor = CostAccess.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("initiate takes the budget in minor units, spends a sovereign id and answers the account judged now; a bad budget or a requester spends nothing")
    void initiateAccount() {
        when(accounts.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        InitiateCloudAccountUseCase useCase = new InitiateCloudAccountUseCase(hash, accounts, assessor);

        StepVerifier.create(useCase.execute(org, initiate("3100.50"), "op", STAFF)).assertNext(response -> {
            assertEquals(new BigDecimal("3100.50"), response.monthlyBudget());
            assertEquals("USD", response.currency());
            assertEquals("AWS", response.provider());
            assertEquals("NO_DATA", response.level());
            assertEquals("2026-10", response.month());
            assertNull(response.spent());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(org, initiate("1.005"), "op", STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(useCase.execute(org, initiate("10"), "op", REQUESTER)).expectError(CostAccessDeniedException.class).verify();
        verify(hash).generateSovereignId("cloud-account-creation");
    }

    @Test
    @DisplayName("update, pause and resume go through the workflow with the status loaded; a requester and an unknown account are refused")
    void updateAndControl() {
        CloudAccount account = account("Production", Provider.AWS, Currency.USD);
        when(accounts.findById(account.getId(), org)).thenReturn(Mono.just(account));
        UUID elsewhere = UUID.randomUUID();
        when(accounts.findById(account.getId(), elsewhere)).thenReturn(Mono.empty());
        when(accounts.save(any(), any(), any())).thenReturn(Mono.empty());
        CloudAccountWorkflow workflow = new CloudAccountWorkflow(accounts);
        UpdateCloudAccountUseCase update = new UpdateCloudAccountUseCase(workflow);
        ControlCloudAccountUseCase control = new ControlCloudAccountUseCase(workflow);

        StepVerifier.create(update.execute(account.getId(), org, new UpdateCloudAccountRequest("Prod", new BigDecimal("5000"), 90, 24, null), "op", STAFF)).verifyComplete();
        assertEquals(500_000, account.getBudgetMinor());
        StepVerifier.create(update.execute(account.getId(), org, new UpdateCloudAccountRequest("Prod", new BigDecimal("0.001"), 90, 24, null), "op", STAFF))
                .expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(control.execute(account.getId(), org, ControlCloudAccountUseCase.Action.PAUSE, "op", null)).verifyComplete();
        assertEquals(AccountStatus.PAUSED, account.getStatus());
        StepVerifier.create(control.execute(account.getId(), org, ControlCloudAccountUseCase.Action.RESUME, "op", STAFF)).verifyComplete();
        verify(accounts).save(any(), eq(AccountStatus.PAUSED), any());
        StepVerifier.create(control.execute(account.getId(), org, ControlCloudAccountUseCase.Action.RESUME, "op", STAFF)).expectError(InvalidCloudAccountStatusException.class).verify();
        StepVerifier.create(control.execute(account.getId(), elsewhere, ControlCloudAccountUseCase.Action.PAUSE, "op", STAFF)).expectError(CloudAccountNotFoundException.class).verify();
        StepVerifier.create(update.execute(account.getId(), org, new UpdateCloudAccountRequest("Prod", BigDecimal.ONE, 90, 24, null), "op", REQUESTER))
                .expectError(CostAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("retrieve and the list judge each month from the days of the current month (UTC), filter by status, provider, currency and level")
    void retrieve() {
        CloudAccount aws = account("Production", Provider.AWS, Currency.USD);
        CloudAccount azure = account("Data", Provider.AZURE, Currency.EUR);
        CloudAccount paused = account("Old", Provider.AWS, Currency.BRL);
        paused.pause("op");
        when(reports.latestOfMonth(org, aws.getId(), MONTH)).thenReturn(Mono.just(List.of(new DayCost(LocalDate.parse("2026-10-09"), 400_000))));
        when(accounts.findById(aws.getId(), org)).thenReturn(Mono.just(aws));
        UUID elsewhere = UUID.randomUUID();
        when(accounts.findById(aws.getId(), elsewhere)).thenReturn(Mono.empty());
        when(accounts.findAll(org)).thenReturn(Flux.just(aws, azure, paused));

        RetrieveCloudAccountUseCase one = new RetrieveCloudAccountUseCase(accounts, assessor);
        StepVerifier.create(one.execute(aws.getId(), org, STAFF)).assertNext(response -> {
            assertEquals("OVER_BUDGET", response.level());
            assertEquals(new BigDecimal("4000.00"), response.spent());
        }).verifyComplete();
        StepVerifier.create(one.execute(aws.getId(), elsewhere, STAFF)).expectError(CloudAccountNotFoundException.class).verify();
        StepVerifier.create(one.execute(aws.getId(), org, REQUESTER)).expectError(CostAccessDeniedException.class).verify();

        RetrieveCloudAccountsUseCase list = new RetrieveCloudAccountsUseCase(accounts, assessor);
        StepVerifier.create(list.execute(org, null, null, null, null, STAFF).map(r -> r.name())).expectNext("Production", "Data", "Old").verifyComplete();
        StepVerifier.create(list.execute(org, AccountStatus.PAUSED, null, null, null, null).map(r -> r.name())).expectNext("Old").verifyComplete();
        StepVerifier.create(list.execute(org, null, Provider.AZURE, null, null, STAFF).map(r -> r.name())).expectNext("Data").verifyComplete();
        StepVerifier.create(list.execute(org, null, null, Currency.BRL, null, STAFF).map(r -> r.name())).expectNext("Old").verifyComplete();
        StepVerifier.create(list.execute(org, null, null, null, Level.OVER_BUDGET, STAFF).map(r -> r.name())).expectNext("Production").verifyComplete();
        StepVerifier.create(list.execute(org, null, null, null, Level.NEAR_BUDGET, STAFF)).verifyComplete();
        StepVerifier.create(list.execute(org, null, null, null, null, REQUESTER)).expectError(CostAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("the audit log is the account's trail, a 404 elsewhere, staff only")
    void auditLog() {
        CloudAccount account = account("Production", Provider.AWS, Currency.USD);
        account.pause("op");
        when(accounts.findById(account.getId(), org)).thenReturn(Mono.just(account));
        UUID elsewhere = UUID.randomUUID();
        when(accounts.findById(account.getId(), elsewhere)).thenReturn(Mono.empty());
        RetrieveCloudAccountAuditLogUseCase useCase = new RetrieveCloudAccountAuditLogUseCase(accounts);

        StepVerifier.create(useCase.execute(account.getId(), org, STAFF)).assertNext(trail -> assertEquals(List.of("INITIATED", "PAUSED"), trail.stream().map(e -> e.action()).toList())).verifyComplete();
        StepVerifier.create(useCase.execute(account.getId(), elsewhere, STAFF)).expectError(CloudAccountNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(account.getId(), org, REQUESTER)).expectError(CostAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("a new report is checked before an id is spent, stored, and moves the account's last report forward")
    void reportNew() {
        CloudAccount account = account("Production", Provider.AWS, Currency.USD);
        when(accounts.findById(account.getId(), org)).thenReturn(Mono.just(account));
        when(reports.restate(eq(org), eq(account.getId()), any(), anyLong(), any())).thenReturn(Mono.empty());
        when(reports.create(any())).thenAnswer(call -> Mono.just(new Stored(call.getArgument(0), true)));
        when(accounts.recordReport(any(), any(), any())).thenReturn(Mono.empty());
        ReportCostUseCase useCase = new ReportCostUseCase(hash, accounts, reports, clock);

        StepVerifier.create(useCase.execute(org, new ReportCostRequest(account.getId(), LocalDate.parse("2026-10-09"), new BigDecimal("123.45")), "billing", STAFF))
                .assertNext(reported -> {
                    assertTrue(reported.created());
                    assertEquals(new BigDecimal("123.45"), reported.report().amount());
                    assertEquals("billing", reported.report().reportedBy());
                }).verifyComplete();
        verify(accounts).recordReport(account.getId(), org, NOW);
        StepVerifier.create(useCase.execute(org, new ReportCostRequest(account.getId(), LocalDate.parse("2026-10-12"), BigDecimal.ONE), "billing", STAFF))
                .expectError(IllegalArgumentException.class).verify();
        verify(hash).generateSovereignId("cost-report-creation");
    }

    @Test
    @DisplayName("the same day and amount again is the stored report, restated now, and spends nothing; a bad amount, an unknown account and a requester are refused")
    void reportAgainAndRefused() {
        CloudAccount account = account("Production", Provider.AWS, Currency.USD);
        CostReport stored = CostReport.createNew(UUID.randomUUID(), org, account.getId(), LocalDate.parse("2026-10-09"), 12_345, "billing", LocalDate.parse("2026-10-10"), NOW);
        when(accounts.findById(account.getId(), org)).thenReturn(Mono.just(account));
        UUID unknown = UUID.randomUUID();
        when(accounts.findById(unknown, org)).thenReturn(Mono.empty());
        when(reports.restate(org, account.getId(), LocalDate.parse("2026-10-09"), 12_345, NOW)).thenReturn(Mono.just(stored));
        when(accounts.recordReport(any(), any(), any())).thenReturn(Mono.empty());
        ReportCostUseCase useCase = new ReportCostUseCase(hash, accounts, reports, clock);

        StepVerifier.create(useCase.execute(org, new ReportCostRequest(account.getId(), LocalDate.parse("2026-10-09"), new BigDecimal("123.45")), "billing", STAFF))
                .assertNext(reported -> {
                    assertFalse(reported.created());
                    assertEquals(stored.getId(), reported.report().id());
                }).verifyComplete();
        StepVerifier.create(useCase.execute(org, new ReportCostRequest(account.getId(), LocalDate.parse("2026-10-09"), new BigDecimal("1.234")), "billing", STAFF))
                .expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(useCase.execute(org, new ReportCostRequest(unknown, LocalDate.parse("2026-10-09"), BigDecimal.ONE), "billing", STAFF))
                .expectError(CloudAccountNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(org, new ReportCostRequest(account.getId(), LocalDate.parse("2026-10-09"), BigDecimal.ONE), "billing", REQUESTER))
                .expectError(CostAccessDeniedException.class).verify();
        verify(hash, never()).generateSovereignId(any());
        verify(reports, never()).create(any());
    }

    @Test
    @DisplayName("one report is answered for the tenant, a 404 otherwise; the list uses 50 unless asked and accepts 1 to 200")
    void retrieveReports() {
        UUID accountId = UUID.randomUUID();
        CostReport stored = CostReport.createNew(UUID.randomUUID(), org, accountId, LocalDate.parse("2026-10-09"), 500, "billing", LocalDate.parse("2026-10-10"), NOW);
        when(reports.findById(stored.getId(), org)).thenReturn(Mono.just(stored));
        UUID elsewhere = UUID.randomUUID();
        when(reports.findById(stored.getId(), elsewhere)).thenReturn(Mono.empty());
        when(reports.findAll(eq(org), eq(accountId), anyInt())).thenReturn(Flux.just(stored));

        RetrieveCostReportUseCase one = new RetrieveCostReportUseCase(reports);
        StepVerifier.create(one.execute(stored.getId(), org, STAFF)).assertNext(response -> assertEquals(new BigDecimal("5.00"), response.amount())).verifyComplete();
        StepVerifier.create(one.execute(stored.getId(), elsewhere, STAFF)).expectError(CostReportNotFoundException.class).verify();
        StepVerifier.create(one.execute(stored.getId(), org, REQUESTER)).expectError(CostAccessDeniedException.class).verify();

        RetrieveCostReportsUseCase list = new RetrieveCostReportsUseCase(reports);
        StepVerifier.create(list.execute(org, accountId, null, STAFF)).expectNextCount(1).verifyComplete();
        verify(reports).findAll(org, accountId, 50);
        StepVerifier.create(list.execute(org, accountId, 200, STAFF)).expectNextCount(1).verifyComplete();
        StepVerifier.create(list.execute(org, accountId, 0, STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(list.execute(org, accountId, 201, STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(list.execute(org, accountId, null, REQUESTER)).expectError(CostAccessDeniedException.class).verify();
    }
}
