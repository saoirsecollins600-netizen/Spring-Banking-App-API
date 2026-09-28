package com.example.banking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.banking.entity.Account;
import com.example.banking.entity.Journal;
import com.example.banking.entity.LedgerEntry;
import com.example.banking.exceptions.BankingExceptions.AccountNotFoundException;
import com.example.banking.exceptions.BankingExceptions.ErrorCode;
import com.example.banking.exceptions.BankingExceptions.ExternalReferenceAlreadyExistsException;
import com.example.banking.exceptions.BankingExceptions.InactiveAccountException;
import com.example.banking.exceptions.BankingExceptions.InvalidAmountException;
import com.example.banking.exceptions.BankingExceptions.InvalidCurrencyCodeException;
import com.example.banking.exceptions.BankingExceptions.InvalidTransactionSideException;
import com.example.banking.exceptions.BankingExceptions.JournalNotFoundException;
import com.example.banking.exceptions.BankingExceptions.JournalNotPendingException;
import com.example.banking.exceptions.BankingExceptions.UnbalancedJournalException;
import com.example.banking.model.JournalStatus;
import com.example.banking.repository.AccountRepository;
import com.example.banking.repository.JournalRepository;
import com.example.banking.repository.LedgerEntryRepository;

@ExtendWith(MockitoExtension.class)
class JournalServiceTest {

    @Mock
    private JournalRepository journalRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private JournalService journalService;

    private UUID journalId;
    private UUID accountId;
    private Journal pendingJournal;
    private Account activeAccount;

    @BeforeEach
    void setUp() {
        journalId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        pendingJournal = new Journal("desc", "ref-1");
        pendingJournal.setId(journalId);
        pendingJournal.setStatus(JournalStatus.PENDING);

        activeAccount = new Account(null, "checking", "USD");
        activeAccount.setId(accountId);
    }

    private void stubPendingJournalAndActiveAccount() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));
    }

    private void stubValidEntryPersistence() {
        when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of());
        when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubTotals(long net, long debits, long credits, List<String> currencies) {
        when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(net);
        when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(debits);
        when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(credits);
        when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(currencies);
    }

    @Test
    void lifecycle_createAddBalanceAndPost_happyPath() {
        UUID creditAccountId = UUID.randomUUID();
        Account creditAccount = new Account(null, "savings", "USD");
        creditAccount.setId(creditAccountId);

        when(journalRepository.existsByExternalRef("txn-100")).thenReturn(false);
        when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> {
            Journal j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId(journalId);
            }
            return j;
        });

        Journal created = journalService.createJournal("Transfer", "txn-100");
        assertThat(created.getId()).isEqualTo(journalId);
        assertThat(created.getStatus()).isEqualTo(JournalStatus.PENDING);
        assertThat(created.getPostedAt()).isNull();

        when(journalRepository.findById(journalId)).thenReturn(Optional.of(created));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));
        when(accountRepository.findById(creditAccountId)).thenReturn(Optional.of(creditAccount));
        when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId))
                .thenReturn(List.of(), List.of("USD"), List.of("USD"));
        when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        LedgerEntry debit = journalService.addEntry(journalId, accountId, "debit", "USD", 5_000L);
        LedgerEntry credit = journalService.addEntry(journalId, creditAccountId, "credit", "USD", 5_000L);

        assertThat(debit.getJournal()).isSameAs(created);
        assertThat(debit.getAccount()).isSameAs(activeAccount);
        assertThat(debit.getSide()).isEqualTo("debit");
        assertThat(debit.getAmountCents()).isEqualTo(5_000L);
        assertThat(debit.getCurrency()).isEqualTo("USD");
        assertThat(credit.getAccount()).isSameAs(creditAccount);
        assertThat(credit.getSide()).isEqualTo("credit");

        when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);
        when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(5_000L);
        when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(5_000L);

        assertThat(journalService.isBalanced(journalId)).isTrue();

        Instant before = Instant.now();
        Journal posted = journalService.postJournal(journalId);

        assertThat(posted.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(posted.getPostedAt()).isNotNull().isAfterOrEqualTo(before);
        verify(ledgerEntryRepository, times(2)).save(any(LedgerEntry.class));
    }

    @Test
    void lifecycle_isBalanced_trueWhenNetIsZero() {
        when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);
        assertThat(journalService.isBalanced(journalId)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = { 1L, -1L, 10_000L, -10_000L })
    void lifecycle_isBalanced_falseWhenNetIsNonZero(long net) {
        when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(net);
        assertThat(journalService.isBalanced(journalId)).isFalse();
    }

    @Test
    void createJournal_populatedDescriptionAndExternalRef_areTrimmedAndSaved() {
        when(journalRepository.existsByExternalRef("ext-42")).thenReturn(false);
        when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

        Journal result = journalService.createJournal("  Payroll run  ", "  ext-42  ");

        assertThat(result.getDescription()).isEqualTo("Payroll run");
        assertThat(result.getExternalRef()).isEqualTo("ext-42");
        assertThat(result.getStatus()).isEqualTo(JournalStatus.PENDING);
        verify(journalRepository).existsByExternalRef("ext-42");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "   ", "\t", "\n" })
    void createJournal_blankDescriptionAndExternalRef_normalizedToNull_andSkipDuplicateCheck(String blank) {
        when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

        Journal result = journalService.createJournal(blank, blank);

        assertThat(result.getDescription()).isNull();
        assertThat(result.getExternalRef()).isNull();
        assertThat(result.getStatus()).isEqualTo(JournalStatus.PENDING);
        verify(journalRepository, never()).existsByExternalRef(anyString());
    }

    @Test
    void createJournal_blankDescriptionWithPopulatedExternalRef() {
        when(journalRepository.existsByExternalRef("ref")).thenReturn(false);
        when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

        Journal result = journalService.createJournal("  ", "ref");

        assertThat(result.getDescription()).isNull();
        assertThat(result.getExternalRef()).isEqualTo("ref");
    }

    @Test
    void createJournal_populatedDescriptionWithBlankExternalRef() {
        when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

        Journal result = journalService.createJournal("Memo", null);

        assertThat(result.getDescription()).isEqualTo("Memo");
        assertThat(result.getExternalRef()).isNull();
        verify(journalRepository, never()).existsByExternalRef(anyString());
    }

    @Test
    void createJournal_savesJournalWithPendingStatus() {
        when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

        journalService.createJournal("d", null);

        ArgumentCaptor<Journal> captor = ArgumentCaptor.forClass(Journal.class);
        verify(journalRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(JournalStatus.PENDING);
    }

    @Test
    void createJournal_duplicateExternalRef_throwsAndDoesNotSave() {
        when(journalRepository.existsByExternalRef("dup")).thenReturn(true);

        assertThatThrownBy(() -> journalService.createJournal("desc", "  dup "))
                .isInstanceOf(ExternalReferenceAlreadyExistsException.class)
                .satisfies(ex -> {
                    ExternalReferenceAlreadyExistsException e = (ExternalReferenceAlreadyExistsException) ex;
                    assertThat(e.getCode()).isEqualTo(ErrorCode.EXTERNAL_REFERENCE_ALREADY_EXISTS);
                    assertThat(e.getMetadata()).containsEntry("externalReference", "dup");
                });

        verify(journalRepository, never()).save(any());
    }

    @Test
    void addEntry_journalNotFound_throws() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L))
                .isInstanceOf(JournalNotFoundException.class)
                .satisfies(ex -> assertThat(((JournalNotFoundException) ex).getMetadata())
                        .containsEntry("journalId", journalId.toString()));

        verifyNoInteractions(accountRepository, ledgerEntryRepository);
    }

    @Test
    void addEntry_journalNotPending_throws() {
        pendingJournal.setStatus(JournalStatus.POSTED);
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L))
                .isInstanceOf(JournalNotPendingException.class)
                .satisfies(ex -> assertThat(((JournalNotPendingException) ex).getCode())
                        .isEqualTo(ErrorCode.JOURNAL_NOT_PENDING));

        verifyNoInteractions(accountRepository, ledgerEntryRepository);
    }

    @Test
    void addEntry_accountNotFound_throws() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L))
                .isInstanceOf(AccountNotFoundException.class)
                .satisfies(ex -> assertThat(((AccountNotFoundException) ex).getMetadata())
                        .containsEntry("account", accountId.toString()));

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void addEntry_inactiveAccount_throws() {
        activeAccount.setActive(false);
        stubPendingJournalAndActiveAccount();

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L))
                .isInstanceOf(InactiveAccountException.class)
                .satisfies(ex -> assertThat(((InactiveAccountException) ex).getCode())
                        .isEqualTo(ErrorCode.INACTIVE_ACCOUNT));

        verifyNoInteractions(ledgerEntryRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "deb", "debits", "DR", "CR", "transfer", "credit debit" })
    void addEntry_invalidSide_throws(String side) {
        stubPendingJournalAndActiveAccount();

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, side, "USD", 100L))
                .isInstanceOf(InvalidTransactionSideException.class)
                .satisfies(ex -> assertThat(((InvalidTransactionSideException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_TRANSACTION_SIDE));

        verifyNoInteractions(ledgerEntryRepository);
    }

    @ParameterizedTest
    @CsvSource({ "DEBIT,debit", "' Debit ',debit", "credit,credit", "'  CREDIT',credit" })
    void addEntry_side_isTrimmedAndLowercased(String input, String expected) {
        stubPendingJournalAndActiveAccount();
        stubValidEntryPersistence();

        LedgerEntry entry = journalService.addEntry(journalId, accountId, input, "USD", 100L);

        assertThat(entry.getSide()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(longs = { 0L, -1L, -100L, Long.MIN_VALUE })
    void addEntry_nonPositiveAmount_throws(long amount) {
        stubPendingJournalAndActiveAccount();

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", amount))
                .isInstanceOf(InvalidAmountException.class)
                .satisfies(ex -> {
                    InvalidAmountException e = (InvalidAmountException) ex;
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_AMOUNT);
                    assertThat(e.getMetadata()).containsEntry("amountCents", amount);
                });

        verifyNoInteractions(ledgerEntryRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = { 1L, Long.MAX_VALUE })
    void addEntry_boundaryPositiveAmounts_areAccepted(long amount) {
        stubPendingJournalAndActiveAccount();
        stubValidEntryPersistence();

        LedgerEntry entry = journalService.addEntry(journalId, accountId, "credit", "USD", amount);

        assertThat(entry.getAmountCents()).isEqualTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "US", "USDD", "XYZ", "ABC", "12$", "U S" })
    void addEntry_invalidCurrency_throws(String currency) {
        stubPendingJournalAndActiveAccount();

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", currency, 100L))
                .isInstanceOf(InvalidCurrencyCodeException.class)
                .satisfies(ex -> assertThat(((InvalidCurrencyCodeException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_CURRENCY_CODE));

        verifyNoInteractions(ledgerEntryRepository);
    }

    @ParameterizedTest
    @CsvSource({ "usd,USD", "' eur ',EUR", "Gbp,GBP", "JPY,JPY" })
    void addEntry_currency_isTrimmedAndUppercased(String input, String expected) {
        stubPendingJournalAndActiveAccount();
        stubValidEntryPersistence();

        LedgerEntry entry = journalService.addEntry(journalId, accountId, "debit", input, 100L);

        assertThat(entry.getCurrency()).isEqualTo(expected);
    }

    @Test
    void addEntry_currencyMismatchWithExistingEntries_throws() {
        stubPendingJournalAndActiveAccount();
        when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "credit", "EUR", 100L))
                .isInstanceOf(InvalidCurrencyCodeException.class)
                .satisfies(ex -> assertThat(((InvalidCurrencyCodeException) ex).getMetadata())
                        .containsEntry("currency", "EUR (single-currency journal mismatch, existing=USD)"));

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void addEntry_currencyMatchingExistingEntries_isAccepted() {
        stubPendingJournalAndActiveAccount();
        when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));
        when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        LedgerEntry entry = journalService.addEntry(journalId, accountId, "credit", "usd", 100L);

        assertThat(entry.getCurrency()).isEqualTo("USD");
    }

    @Test
    void addEntry_validationOrder_sideCheckedBeforeAmountAndCurrency() {
        stubPendingJournalAndActiveAccount();

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "bogus", "XXX1", 0L))
                .isInstanceOf(InvalidTransactionSideException.class);
    }

    @Test
    void addEntry_validationOrder_amountCheckedBeforeCurrency() {
        stubPendingJournalAndActiveAccount();

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "XXX1", 0L))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void postJournal_journalNotFound_throws() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> journalService.postJournal(journalId))
                .isInstanceOf(JournalNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
        verify(journalRepository, never()).save(any());
    }

    @Test
    void postJournal_balancedJournal_isPosted() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
        when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));
        stubTotals(0L, 2_500L, 2_500L, List.of("USD"));

        Journal result = journalService.postJournal(journalId);

        assertThat(result.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(result.getPostedAt()).isNotNull();
        verify(journalRepository).save(pendingJournal);
    }

    @Test
    void postJournal_unbalancedJournal_throwsWithDiagnosticMetadata_andStaysPending() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
        stubTotals(500L, 1_500L, 1_000L, List.of("USD"));

        assertThatThrownBy(() -> journalService.postJournal(journalId))
                .isInstanceOf(UnbalancedJournalException.class)
                .hasMessage("Cannot post an unbalanced journal")
                .satisfies(ex -> {
                    UnbalancedJournalException uje = (UnbalancedJournalException) ex;
                    assertThat(uje.getCode()).isEqualTo(ErrorCode.UNBALANCED_JOURNAL);
                    assertThat(uje.getMetadata())
                            .containsEntry("journalId", journalId.toString())
                            .containsEntry("currency", "USD")
                            .containsEntry("debitTotalCents", 1_500L)
                            .containsEntry("creditTotalCents", 1_000L)
                            .containsEntry("netCents", 500L);
                });

        assertThat(pendingJournal.getStatus()).isEqualTo(JournalStatus.PENDING);
        assertThat(pendingJournal.getPostedAt()).isNull();
        verify(journalRepository, never()).save(any());
    }

    @Test
    void postJournal_unbalancedByOneCent_throws() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
        stubTotals(-1L, 999L, 1_000L, List.of("EUR"));

        assertThatThrownBy(() -> journalService.postJournal(journalId))
                .isInstanceOf(UnbalancedJournalException.class);

        verify(journalRepository, never()).save(any());
    }

    @Test
    void postJournal_alreadyPostedJournal_isNoOp() {
        Instant originalPostedAt = Instant.parse("2024-01-01T00:00:00Z");
        pendingJournal.setStatus(JournalStatus.POSTED);
        pendingJournal.setPostedAt(originalPostedAt);
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));

        Journal result = journalService.postJournal(journalId);

        assertThat(result).isSameAs(pendingJournal);
        assertThat(result.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(result.getPostedAt()).isEqualTo(originalPostedAt);
        verify(journalRepository, never()).save(any());
        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void postJournal_postingTwice_secondCallIsNoOp() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
        when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));
        stubTotals(0L, 100L, 100L, List.of("USD"));

        Journal first = journalService.postJournal(journalId);
        Instant firstPostedAt = first.getPostedAt();
        Journal second = journalService.postJournal(journalId);

        assertThat(second).isSameAs(first);
        assertThat(second.getPostedAt()).isEqualTo(firstPostedAt);
        verify(journalRepository, times(1)).save(any(Journal.class));
    }

    @Test
    void getJournal_returnsJournal() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));

        assertThat(journalService.getJournal(journalId)).isSameAs(pendingJournal);
    }

    @Test
    void getJournal_notFound_throws() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> journalService.getJournal(journalId))
                .isInstanceOf(JournalNotFoundException.class);
    }

    @Test
    void listEntries_delegatesToRepository() {
        LedgerEntry entry = new LedgerEntry(pendingJournal, activeAccount, "debit", 100L, "USD");
        when(ledgerEntryRepository.findByJournal_Id(journalId)).thenReturn(List.of(entry));

        assertThat(journalService.listEntries(journalId)).containsExactly(entry);
    }

    @Test
    void diagnostics_balanced() {
        stubTotals(0L, 700L, 700L, List.of("USD"));

        JournalService.JournalDiagnostics d = journalService.diagnostics(journalId);

        assertThat(d).isEqualTo(new JournalService.JournalDiagnostics("USD", 700L, 700L, 0L, true));
    }

    @Test
    void diagnostics_unbalanced() {
        stubTotals(300L, 1_000L, 700L, List.of("GBP"));

        JournalService.JournalDiagnostics d = journalService.diagnostics(journalId);

        assertThat(d).isEqualTo(new JournalService.JournalDiagnostics("GBP", 1_000L, 700L, 300L, false));
    }

    @Test
    void diagnostics_emptyJournal_hasNullCurrency() {
        stubTotals(0L, 0L, 0L, List.of());

        JournalService.JournalDiagnostics d = journalService.diagnostics(journalId);

        assertThat(d.currency()).isNull();
        assertThat(d.balanced()).isTrue();
    }
}
