package com.example.banking.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.banking.entity.Account;
import com.example.banking.entity.Journal;
import com.example.banking.entity.LedgerEntry;
import com.example.banking.exceptions.BankingExceptions.AccountNotFoundException;
import com.example.banking.exceptions.BankingExceptions.BankingException;
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
@DisplayName("JournalService")
class JournalServiceTest {

    private static final UUID JOURNAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private JournalRepository journalRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private AccountRepository accountRepository;

    private JournalService service;

    @BeforeEach
    void setUp() {
        service = new JournalService(journalRepository, ledgerEntryRepository, accountRepository);
    }

    private Journal journal(JournalStatus status) {
        Journal journal = new Journal("Description", null);
        journal.setId(JOURNAL_ID);
        journal.setStatus(status);
        return journal;
    }

    private Account account(UUID id) {
        Account account = new Account(null, "checking", "USD");
        account.setId(id);
        return account;
    }

    private void stubPendingJournalAndAccount(Journal journal, Account account) {
        when(journalRepository.findById(journal.getId())).thenReturn(Optional.of(journal));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
    }

    private void assertCode(BankingException exception, ErrorCode code) {
        assertEquals(code, exception.getCode());
    }

    @Nested
    @DisplayName("createJournal")
    class CreateJournal {

        @Test
        @DisplayName("trims populated fields, checks the normalized external reference, and saves")
        void trimsFieldsAndSavesPendingJournal() {
            when(journalRepository.existsByExternalRef("REF-1")).thenReturn(false);
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            service.createJournal("  Rent  ", "  REF-1  ");

            ArgumentCaptor<Journal> captor = ArgumentCaptor.forClass(Journal.class);
            verify(journalRepository).save(captor.capture());
            Journal saved = captor.getValue();
            assertEquals("Rent", saved.getDescription());
            assertEquals("REF-1", saved.getExternalRef());
            assertEquals(JournalStatus.PENDING, saved.getStatus());
            verify(journalRepository).existsByExternalRef("REF-1");
        }

        @ParameterizedTest
        @CsvSource(value = {"null", "''", "'   '"}, nullValues = "null")
        @DisplayName("stores a null description when it is null or blank")
        void blankDescriptionIsStoredAsNull(String description) {
            when(journalRepository.existsByExternalRef("REF-2")).thenReturn(false);
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal created = service.createJournal(description, "REF-2");

            assertNull(created.getDescription());
            verify(journalRepository).existsByExternalRef("REF-2");
        }

        @ParameterizedTest
        @CsvSource(value = {"null", "''", "'   '"}, nullValues = "null")
        @DisplayName("stores a null external reference and skips the duplicate check")
        void blankExternalReferenceIsStoredAsNull(String externalRef) {
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal created = service.createJournal("Description", externalRef);

            assertNull(created.getExternalRef());
            verify(journalRepository, never()).existsByExternalRef(any());
        }

        @Test
        @DisplayName("rejects a duplicate trimmed external reference")
        void rejectsDuplicateExternalReference() {
            when(journalRepository.existsByExternalRef("REF-1")).thenReturn(true);

            ExternalReferenceAlreadyExistsException exception = assertThrows(
                    ExternalReferenceAlreadyExistsException.class,
                    () -> service.createJournal("Rent", "  REF-1  "));

            assertCode(exception, ErrorCode.EXTERNAL_REFERENCE_ALREADY_EXISTS);
            assertEquals("REF-1", exception.getMetadata().get("externalReference"));
            verify(journalRepository, never()).save(any(Journal.class));
        }
    }

    @Nested
    @DisplayName("addEntry")
    class AddEntry {

        @Test
        @DisplayName("normalizes a debit entry and associates its journal and account")
        void addsNormalizedDebitEntry() {
            Journal journal = journal(JournalStatus.PENDING);
            Account account = account(ACCOUNT_ID);
            stubPendingJournalAndAccount(journal, account);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of());
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry result = service.addEntry(JOURNAL_ID, ACCOUNT_ID, " Debit ", " usd ", 1250L);

            assertEquals("debit", result.getSide());
            assertEquals("USD", result.getCurrency());
            assertEquals(1250L, result.getAmountCents());
            assertSame(journal, result.getJournal());
            assertSame(account, result.getAccount());
            verify(ledgerEntryRepository).save(result);
        }

        @ParameterizedTest
        @ValueSource(strings = {"credit", "DEBIT", "Credit"})
        @DisplayName("accepts and normalizes the supported side spellings")
        void acceptsSupportedSides(String side) {
            Journal journal = journal(JournalStatus.PENDING);
            Account account = account(ACCOUNT_ID);
            stubPendingJournalAndAccount(journal, account);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of());
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry result = service.addEntry(JOURNAL_ID, ACCOUNT_ID, side, "USD", 10L);

            assertEquals(side.equalsIgnoreCase("credit") ? "credit" : "debit", result.getSide());
            verify(ledgerEntryRepository).save(result);
        }

        @Test
        @DisplayName("accepts a currency matching existing journal entries")
        void acceptsMatchingExistingCurrency() {
            Journal journal = journal(JournalStatus.PENDING);
            Account account = account(ACCOUNT_ID);
            stubPendingJournalAndAccount(journal, account);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of("USD"));
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry result = service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debit", "usd", 10L);

            assertEquals("USD", result.getCurrency());
            verify(ledgerEntryRepository).save(result);
        }

        @Test
        @DisplayName("reports a missing journal before touching the account or ledger repositories")
        void rejectsMissingJournal() {
            when(journalRepository.findById(JOURNAL_ID)).thenReturn(Optional.empty());

            JournalNotFoundException exception = assertThrows(
                    JournalNotFoundException.class,
                    () -> service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debit", "USD", 10L));

            assertCode(exception, ErrorCode.JOURNAL_NOT_FOUND);
            assertEquals(JOURNAL_ID.toString(), exception.getMetadata().get("journalId"));
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("rejects a posted journal before looking up its account")
        void rejectsNonPendingJournal() {
            when(journalRepository.findById(JOURNAL_ID)).thenReturn(Optional.of(journal(JournalStatus.POSTED)));

            JournalNotPendingException exception = assertThrows(
                    JournalNotPendingException.class,
                    () -> service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debit", "USD", 10L));

            assertCode(exception, ErrorCode.JOURNAL_NOT_PENDING);
            verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("reports a missing account")
        void rejectsMissingAccount() {
            Journal journal = journal(JournalStatus.PENDING);
            when(journalRepository.findById(JOURNAL_ID)).thenReturn(Optional.of(journal));
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            AccountNotFoundException exception = assertThrows(
                    AccountNotFoundException.class,
                    () -> service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debit", "USD", 10L));

            assertCode(exception, ErrorCode.ACCOUNT_NOT_FOUND);
            assertEquals(ACCOUNT_ID.toString(), exception.getMetadata().get("account"));
        }

        @Test
        @DisplayName("rejects an inactive account")
        void rejectsInactiveAccount() {
            Journal journal = journal(JournalStatus.PENDING);
            Account account = account(ACCOUNT_ID);
            account.setActive(false);
            stubPendingJournalAndAccount(journal, account);

            InactiveAccountException exception = assertThrows(
                    InactiveAccountException.class,
                    () -> service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debit", "USD", 10L));

            assertCode(exception, ErrorCode.INACTIVE_ACCOUNT);
        }

        @ParameterizedTest
        @ValueSource(strings = {"debits", "cr", "", "   ", "left"})
        @DisplayName("rejects unsupported transaction sides")
        void rejectsInvalidSide(String side) {
            stubPendingJournalAndAccount(journal(JournalStatus.PENDING), account(ACCOUNT_ID));

            InvalidTransactionSideException exception = assertThrows(
                    InvalidTransactionSideException.class,
                    () -> service.addEntry(JOURNAL_ID, ACCOUNT_ID, side, "USD", 10L));

            assertCode(exception, ErrorCode.INVALID_TRANSACTION_SIDE);
            assertEquals(side, exception.getMetadata().get("side"));
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
        @DisplayName("rejects zero and negative amounts")
        void rejectsInvalidAmount(long amount) {
            stubPendingJournalAndAccount(journal(JournalStatus.PENDING), account(ACCOUNT_ID));

            InvalidAmountException exception = assertThrows(
                    InvalidAmountException.class,
                    () -> service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debit", "USD", amount));

            assertCode(exception, ErrorCode.INVALID_AMOUNT);
            assertEquals(amount, exception.getMetadata().get("amountCents"));
        }

        @ParameterizedTest
        @ValueSource(longs = {1L, Long.MAX_VALUE})
        @DisplayName("accepts positive amount boundaries")
        void acceptsValidAmountBoundaries(long amount) {
            stubPendingJournalAndAccount(journal(JournalStatus.PENDING), account(ACCOUNT_ID));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of());
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry result = service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debit", "USD", amount);

            assertEquals(amount, result.getAmountCents());
        }

        @ParameterizedTest
        @ValueSource(strings = {"US", "USDX", "", "ZZZ", "12$"})
        @DisplayName("rejects malformed or unassigned currency codes")
        void rejectsInvalidCurrency(String currency) {
            stubPendingJournalAndAccount(journal(JournalStatus.PENDING), account(ACCOUNT_ID));

            InvalidCurrencyCodeException exception = assertThrows(
                    InvalidCurrencyCodeException.class,
                    () -> service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debit", currency, 10L));

            assertCode(exception, ErrorCode.INVALID_CURRENCY_CODE);
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("rejects a currency that differs from existing journal entries")
        void rejectsMismatchedCurrency() {
            stubPendingJournalAndAccount(journal(JournalStatus.PENDING), account(ACCOUNT_ID));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of("EUR"));

            InvalidCurrencyCodeException exception = assertThrows(
                    InvalidCurrencyCodeException.class,
                    () -> service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debit", "USD", 10L));

            assertCode(exception, ErrorCode.INVALID_CURRENCY_CODE);
            String currency = (String) exception.getMetadata().get("currency");
            assertTrue(currency.contains("mismatch"));
            assertTrue(currency.contains("EUR"));
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("validates side before amount")
        void validatesSideBeforeAmount() {
            stubPendingJournalAndAccount(journal(JournalStatus.PENDING), account(ACCOUNT_ID));

            InvalidTransactionSideException exception = assertThrows(
                    InvalidTransactionSideException.class,
                    () -> service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debits", "USD", 0L));

            assertCode(exception, ErrorCode.INVALID_TRANSACTION_SIDE);
        }

        @Test
        @DisplayName("validates amount before currency")
        void validatesAmountBeforeCurrency() {
            stubPendingJournalAndAccount(journal(JournalStatus.PENDING), account(ACCOUNT_ID));

            InvalidAmountException exception = assertThrows(
                    InvalidAmountException.class,
                    () -> service.addEntry(JOURNAL_ID, ACCOUNT_ID, "debit", "US", 0L));

            assertCode(exception, ErrorCode.INVALID_AMOUNT);
        }
    }

    @Nested
    @DisplayName("isBalanced")
    class IsBalanced {

        @ParameterizedTest
        @CsvSource({"0, true", "100, false", "-100, false"})
        @DisplayName("compares the journal net amount with zero")
        void comparesNetWithZero(long net, boolean expected) {
            when(ledgerEntryRepository.netAmountForJournal(JOURNAL_ID)).thenReturn(net);

            assertEquals(expected, service.isBalanced(JOURNAL_ID));
        }
    }

    @Nested
    @DisplayName("postJournal")
    class PostJournal {

        @Test
        @DisplayName("posts a balanced pending journal and records its posting time")
        void postsBalancedJournal() {
            Journal journal = journal(JournalStatus.PENDING);
            when(journalRepository.findById(JOURNAL_ID)).thenReturn(Optional.of(journal));
            when(ledgerEntryRepository.netAmountForJournal(JOURNAL_ID)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(JOURNAL_ID)).thenReturn(500L);
            when(ledgerEntryRepository.totalCredits(JOURNAL_ID)).thenReturn(500L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of("USD"));
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            Instant beforeCall = Instant.now();

            Journal result = service.postJournal(JOURNAL_ID);

            Instant afterCall = Instant.now();
            assertSame(journal, result);
            assertEquals(JournalStatus.POSTED, result.getStatus());
            assertNotNull(result.getPostedAt());
            assertFalse(result.getPostedAt().isBefore(beforeCall));
            assertFalse(result.getPostedAt().isAfter(afterCall));
            verify(journalRepository).save(journal);
        }

        @Test
        @DisplayName("returns an already posted journal unchanged")
        void alreadyPostedJournalIsNoOp() {
            Journal journal = journal(JournalStatus.POSTED);
            Instant postedAt = Instant.parse("2024-03-15T10:15:30Z");
            journal.setPostedAt(postedAt);
            when(journalRepository.findById(JOURNAL_ID)).thenReturn(Optional.of(journal));

            Journal result = service.postJournal(JOURNAL_ID);

            assertSame(journal, result);
            assertEquals(postedAt, result.getPostedAt());
            verifyNoInteractions(ledgerEntryRepository);
            verify(journalRepository, never()).save(any(Journal.class));
        }

        @Test
        @DisplayName("reports a missing journal")
        void rejectsMissingJournal() {
            when(journalRepository.findById(JOURNAL_ID)).thenReturn(Optional.empty());

            JournalNotFoundException exception = assertThrows(
                    JournalNotFoundException.class,
                    () -> service.postJournal(JOURNAL_ID));

            assertCode(exception, ErrorCode.JOURNAL_NOT_FOUND);
            assertEquals(JOURNAL_ID.toString(), exception.getMetadata().get("journalId"));
        }

        @Test
        @DisplayName("rejects an unbalanced journal with totals and net in metadata")
        void rejectsUnbalancedJournal() {
            Journal journal = journal(JournalStatus.PENDING);
            when(journalRepository.findById(JOURNAL_ID)).thenReturn(Optional.of(journal));
            when(ledgerEntryRepository.netAmountForJournal(JOURNAL_ID)).thenReturn(200L);
            when(ledgerEntryRepository.totalDebits(JOURNAL_ID)).thenReturn(700L);
            when(ledgerEntryRepository.totalCredits(JOURNAL_ID)).thenReturn(500L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of("USD"));

            UnbalancedJournalException exception = assertThrows(
                    UnbalancedJournalException.class,
                    () -> service.postJournal(JOURNAL_ID));

            assertCode(exception, ErrorCode.UNBALANCED_JOURNAL);
            assertEquals(JOURNAL_ID.toString(), exception.getMetadata().get("journalId"));
            assertEquals("USD", exception.getMetadata().get("currency"));
            assertEquals(700L, exception.getMetadata().get("debitTotalCents"));
            assertEquals(500L, exception.getMetadata().get("creditTotalCents"));
            assertEquals(200L, exception.getMetadata().get("netCents"));
            assertEquals(JournalStatus.PENDING, journal.getStatus());
            verify(journalRepository, never()).save(any(Journal.class));
        }

        @Test
        @DisplayName("posts a journal with no entries (net zero)")
        void postsEmptyPendingJournal() {
            Journal journal = journal(JournalStatus.PENDING);
            when(journalRepository.findById(JOURNAL_ID)).thenReturn(Optional.of(journal));
            when(ledgerEntryRepository.netAmountForJournal(JOURNAL_ID)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(JOURNAL_ID)).thenReturn(0L);
            when(ledgerEntryRepository.totalCredits(JOURNAL_ID)).thenReturn(0L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of());
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal result = service.postJournal(JOURNAL_ID);

            assertSame(journal, result);
            assertEquals(JournalStatus.POSTED, result.getStatus());
            assertNotNull(result.getPostedAt());
            verify(journalRepository).save(journal);
        }
    }

    @Nested
    @DisplayName("getJournal")
    class GetJournal {

        @Test
        @DisplayName("returns a found journal")
        void returnsFoundJournal() {
            Journal journal = journal(JournalStatus.PENDING);
            when(journalRepository.findById(JOURNAL_ID)).thenReturn(Optional.of(journal));

            assertSame(journal, service.getJournal(JOURNAL_ID));
        }

        @Test
        @DisplayName("reports a missing journal")
        void rejectsMissingJournal() {
            when(journalRepository.findById(JOURNAL_ID)).thenReturn(Optional.empty());

            JournalNotFoundException exception = assertThrows(
                    JournalNotFoundException.class,
                    () -> service.getJournal(JOURNAL_ID));

            assertCode(exception, ErrorCode.JOURNAL_NOT_FOUND);
            assertEquals(JOURNAL_ID.toString(), exception.getMetadata().get("journalId"));
        }
    }

    @Nested
    @DisplayName("listEntries")
    class ListEntries {

        @Test
        @DisplayName("delegates to the journal entry query")
        void delegatesToRepository() {
            List<LedgerEntry> entries = List.of(new LedgerEntry(
                    journal(JournalStatus.PENDING), account(ACCOUNT_ID), "debit", 25L, "USD"));
            when(ledgerEntryRepository.findByJournal_Id(JOURNAL_ID)).thenReturn(entries);

            assertSame(entries, service.listEntries(JOURNAL_ID));
            verify(ledgerEntryRepository).findByJournal_Id(JOURNAL_ID);
        }
    }

    @Nested
    @DisplayName("diagnostics")
    class Diagnostics {

        @Test
        @DisplayName("returns currency totals, net, and balanced status")
        void returnsBalancedDiagnostics() {
            when(ledgerEntryRepository.netAmountForJournal(JOURNAL_ID)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(JOURNAL_ID)).thenReturn(500L);
            when(ledgerEntryRepository.totalCredits(JOURNAL_ID)).thenReturn(500L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of("USD"));

            JournalService.JournalDiagnostics diagnostics = service.diagnostics(JOURNAL_ID);

            assertEquals("USD", diagnostics.currency());
            assertEquals(500L, diagnostics.debitTotalCents());
            assertEquals(500L, diagnostics.creditTotalCents());
            assertEquals(0L, diagnostics.netCents());
            assertTrue(diagnostics.balanced());
        }

        @Test
        @DisplayName("reports no currency and balanced status for empty net-zero diagnostics")
        void returnsEmptyCurrencyDiagnostics() {
            when(ledgerEntryRepository.netAmountForJournal(JOURNAL_ID)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(JOURNAL_ID)).thenReturn(0L);
            when(ledgerEntryRepository.totalCredits(JOURNAL_ID)).thenReturn(0L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of());

            JournalService.JournalDiagnostics diagnostics = service.diagnostics(JOURNAL_ID);

            assertNull(diagnostics.currency());
            assertEquals(0L, diagnostics.debitTotalCents());
            assertEquals(0L, diagnostics.creditTotalCents());
            assertEquals(0L, diagnostics.netCents());
            assertTrue(diagnostics.balanced());
        }

        @Test
        @DisplayName("reports unbalanced diagnostics")
        void returnsUnbalancedDiagnostics() {
            when(ledgerEntryRepository.netAmountForJournal(JOURNAL_ID)).thenReturn(-100L);
            when(ledgerEntryRepository.totalDebits(JOURNAL_ID)).thenReturn(100L);
            when(ledgerEntryRepository.totalCredits(JOURNAL_ID)).thenReturn(200L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(JOURNAL_ID)).thenReturn(List.of("EUR"));

            JournalService.JournalDiagnostics diagnostics = service.diagnostics(JOURNAL_ID);

            assertEquals("EUR", diagnostics.currency());
            assertEquals(100L, diagnostics.debitTotalCents());
            assertEquals(200L, diagnostics.creditTotalCents());
            assertEquals(-100L, diagnostics.netCents());
            assertFalse(diagnostics.balanced());
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("creates, balances, posts, and prevents further entries")
        void createsBalancesAndPostsJournal() {
            UUID journalId = JOURNAL_ID;
            UUID debitAccountId = ACCOUNT_ID;
            UUID creditAccountId = UUID.fromString("00000000-0000-0000-0000-000000000003");
            Account debitAccount = account(debitAccountId);
            Account creditAccount = account(creditAccountId);

            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> {
                Journal saved = invocation.getArgument(0);
                if (saved.getId() == null) {
                    saved.setId(journalId);
                }
                return saved;
            });
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal created = service.createJournal("Lifecycle", null);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(created));
            when(accountRepository.findById(debitAccountId)).thenReturn(Optional.of(debitAccount));
            when(accountRepository.findById(creditAccountId)).thenReturn(Optional.of(creditAccount));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId))
                    .thenReturn(List.of(), List.of("USD"), List.of("USD"));
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(1000L);
            when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(1000L);

            LedgerEntry debit = service.addEntry(journalId, debitAccountId, "debit", "USD", 1000L);
            LedgerEntry credit = service.addEntry(journalId, creditAccountId, "credit", "USD", 1000L);

            assertSame(created, debit.getJournal());
            assertSame(debitAccount, debit.getAccount());
            assertSame(creditAccount, credit.getAccount());
            assertTrue(service.isBalanced(journalId));
            assertEquals(JournalStatus.POSTED, service.postJournal(journalId).getStatus());

            clearInvocations(accountRepository);
            JournalNotPendingException exception = assertThrows(
                    JournalNotPendingException.class,
                    () -> service.addEntry(journalId, debitAccountId, "debit", "USD", 1L));
            assertCode(exception, ErrorCode.JOURNAL_NOT_PENDING);
            verifyNoInteractions(accountRepository);

            clearInvocations(journalRepository, ledgerEntryRepository);
            assertSame(created, service.postJournal(journalId));
            verifyNoInteractions(ledgerEntryRepository);
            verify(journalRepository, never()).save(any(Journal.class));
        }
    }
}
