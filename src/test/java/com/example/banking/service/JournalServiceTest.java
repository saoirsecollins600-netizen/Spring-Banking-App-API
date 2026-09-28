package com.example.banking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

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
class JournalServiceTest {

    @Mock
    private JournalRepository journalRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private JournalService journalService;

    private static Journal pendingJournal(UUID id) {
        Journal journal = new Journal("desc", null);
        journal.setId(id);
        journal.setStatus(JournalStatus.PENDING);
        return journal;
    }

    private static Account activeAccount(UUID id) {
        Account account = new Account(null, "checking", "USD");
        account.setId(id);
        account.setActive(true);
        return account;
    }

    private void stubActiveJournalAndAccount(UUID journalId, UUID accountId) {
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal(journalId)));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(accountId)));
    }

    private static <T extends BankingException> T assertBankingException(
            ThrowingCallable action, Class<T> exceptionType, ErrorCode expectedCode) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).isInstanceOf(exceptionType);
        T exception = exceptionType.cast(thrown);
        assertThat(exception.getCode()).isEqualTo(expectedCode);
        return exception;
    }

    @Nested
    @DisplayName("createJournal")
    class CreateJournal {

        @Test
        @DisplayName("trims populated description and external reference")
        void trimsPopulatedFields() {
            when(journalRepository.existsByExternalRef("REF-1")).thenReturn(false);
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal result = journalService.createJournal("  Rent  ", "  REF-1  ");

            ArgumentCaptor<Journal> captor = ArgumentCaptor.forClass(Journal.class);
            verify(journalRepository).save(captor.capture());
            assertThat(captor.getValue().getDescription()).isEqualTo("Rent");
            assertThat(captor.getValue().getExternalRef()).isEqualTo("REF-1");
            assertThat(captor.getValue().getStatus()).isEqualTo(JournalStatus.PENDING);
            assertThat(result).isSameAs(captor.getValue());
            verify(journalRepository).existsByExternalRef("REF-1");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   ", "\t" })
        @DisplayName("stores blank descriptions as null")
        void blankDescriptionIsNull(String description) {
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal result = journalService.createJournal(description, null);

            assertThat(result.getDescription()).isNull();
            assertThat(result.getStatus()).isEqualTo(JournalStatus.PENDING);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   ", "\t" })
        @DisplayName("stores blank external references as null without checking duplicates")
        void blankExternalReferenceIsNull(String externalRef) {
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal result = journalService.createJournal("Rent", externalRef);

            assertThat(result.getExternalRef()).isNull();
            verify(journalRepository, never()).existsByExternalRef(any());
        }

        @Test
        @DisplayName("rejects a duplicate external reference")
        void rejectsDuplicateExternalReference() {
            when(journalRepository.existsByExternalRef("REF-1")).thenReturn(true);

            ExternalReferenceAlreadyExistsException exception = assertBankingException(
                    () -> journalService.createJournal("Rent", "  REF-1  "),
                    ExternalReferenceAlreadyExistsException.class, ErrorCode.EXTERNAL_REFERENCE_ALREADY_EXISTS);

            assertThat(exception.getMetadata()).containsEntry("externalReference", "REF-1");
            verify(journalRepository, never()).save(any(Journal.class));
        }
    }

    @Nested
    @DisplayName("addEntry")
    class AddEntry {

        @Test
        @DisplayName("creates and saves a debit entry")
        void createsDebitEntry() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            Journal journal = pendingJournal(journalId);
            Account account = activeAccount(accountId);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));
            when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of());
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry result = journalService.addEntry(journalId, accountId, "debit", "USD", 250L);

            assertThat(result.getJournal()).isSameAs(journal);
            assertThat(result.getAccount()).isSameAs(account);
            assertThat(result.getSide()).isEqualTo("debit");
            assertThat(result.getAmountCents()).isEqualTo(250L);
            assertThat(result.getCurrency()).isEqualTo("USD");
            verify(ledgerEntryRepository).save(result);
        }

        @Test
        @DisplayName("normalizes side and currency")
        void normalizesSideAndCurrency() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubActiveJournalAndAccount(journalId, accountId);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of());
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry result = journalService.addEntry(journalId, accountId, "  CREDIT ", " usd ", 250L);

            assertThat(result.getSide()).isEqualTo("credit");
            assertThat(result.getCurrency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("throws when the journal is not found before looking up the account")
        void journalNotFound() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

            JournalNotFoundException exception = assertBankingException(
                    () -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L),
                    JournalNotFoundException.class, ErrorCode.JOURNAL_NOT_FOUND);

            assertThat(exception.getMetadata()).containsEntry("journalId", journalId.toString());
            verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("rejects entries on a posted journal before looking up the account")
        void postedJournalRejected() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            Journal journal = pendingJournal(journalId);
            journal.setStatus(JournalStatus.POSTED);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));

            JournalNotPendingException exception = assertBankingException(
                    () -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L),
                    JournalNotPendingException.class, ErrorCode.JOURNAL_NOT_PENDING);

            assertThat(exception.getMetadata()).containsEntry("journalId", journalId.toString());
            verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("throws when the account is not found")
        void accountNotFound() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal(journalId)));
            when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

            AccountNotFoundException exception = assertBankingException(
                    () -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L),
                    AccountNotFoundException.class, ErrorCode.ACCOUNT_NOT_FOUND);

            assertThat(exception.getMetadata()).containsEntry("account", accountId.toString());
        }

        @Test
        @DisplayName("rejects an inactive account")
        void inactiveAccountRejected() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            Account account = activeAccount(accountId);
            account.setActive(false);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal(journalId)));
            when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

            InactiveAccountException exception = assertBankingException(
                    () -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L),
                    InactiveAccountException.class, ErrorCode.INACTIVE_ACCOUNT);

            assertThat(exception.getMetadata()).containsEntry("account", accountId.toString());
        }

        @ParameterizedTest
        @ValueSource(strings = { "deposit", "", "debits", "  " })
        @DisplayName("rejects invalid transaction sides")
        void invalidSideRejected(String side) {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubActiveJournalAndAccount(journalId, accountId);

            InvalidTransactionSideException exception = assertBankingException(
                    () -> journalService.addEntry(journalId, accountId, side, "USD", 100L),
                    InvalidTransactionSideException.class, ErrorCode.INVALID_TRANSACTION_SIDE);

            assertThat(exception.getMetadata()).containsEntry("side", side);
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        }

        @ParameterizedTest
        @ValueSource(longs = { 0L, -1L, Long.MIN_VALUE })
        @DisplayName("rejects non-positive amounts")
        void invalidAmountRejected(long amountCents) {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubActiveJournalAndAccount(journalId, accountId);

            InvalidAmountException exception = assertBankingException(
                    () -> journalService.addEntry(journalId, accountId, "debit", "USD", amountCents),
                    InvalidAmountException.class, ErrorCode.INVALID_AMOUNT);

            assertThat(exception.getMetadata()).containsEntry("amountCents", amountCents);
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        }

        @ParameterizedTest
        @ValueSource(longs = { 1L, Long.MAX_VALUE })
        @DisplayName("accepts positive boundary amounts")
        void acceptsBoundaryAmounts(long amountCents) {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubActiveJournalAndAccount(journalId, accountId);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of());
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry result = journalService.addEntry(journalId, accountId, "debit", "USD", amountCents);

            assertThat(result.getAmountCents()).isEqualTo(amountCents);
            verify(ledgerEntryRepository).save(result);
        }

        @ParameterizedTest
        @ValueSource(strings = { "US", "USDD", "QQQ", "12$", " " })
        @DisplayName("rejects invalid currency codes before checking existing currencies")
        void invalidCurrencyRejected(String currency) {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubActiveJournalAndAccount(journalId, accountId);

            InvalidCurrencyCodeException exception = assertBankingException(
                    () -> journalService.addEntry(journalId, accountId, "debit", currency, 100L),
                    InvalidCurrencyCodeException.class, ErrorCode.INVALID_CURRENCY_CODE);

            assertThat(exception.getMetadata()).containsEntry("currency", currency);
            verify(ledgerEntryRepository, never()).distinctCurrenciesForJournal(journalId);
        }

        @Test
        @DisplayName("rejects a currency that differs from the journal currency")
        void currencyMismatchRejected() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubActiveJournalAndAccount(journalId, accountId);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));

            InvalidCurrencyCodeException exception = assertBankingException(
                    () -> journalService.addEntry(journalId, accountId, "debit", "EUR", 100L),
                    InvalidCurrencyCodeException.class, ErrorCode.INVALID_CURRENCY_CODE);

            assertThat(exception.getMetadata().get("currency").toString())
                    .contains("single-currency journal mismatch", "existing=USD");
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        }

        @Test
        @DisplayName("accepts a normalized currency matching existing entries")
        void matchingCurrencyAccepted() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubActiveJournalAndAccount(journalId, accountId);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry result = journalService.addEntry(journalId, accountId, "credit", "usd", 100L);

            assertThat(result.getCurrency()).isEqualTo("USD");
            verify(ledgerEntryRepository).save(result);
        }

        @Test
        @DisplayName("validates side before amount and amount before currency")
        void validationOrder() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubActiveJournalAndAccount(journalId, accountId);

            InvalidTransactionSideException sideException = assertBankingException(
                    () -> journalService.addEntry(journalId, accountId, "unknown", "USD", 0L),
                    InvalidTransactionSideException.class, ErrorCode.INVALID_TRANSACTION_SIDE);
            assertThat(sideException.getMetadata()).containsEntry("side", "unknown");

            InvalidAmountException amountException = assertBankingException(
                    () -> journalService.addEntry(journalId, accountId, "debit", "ZZ", 0L),
                    InvalidAmountException.class, ErrorCode.INVALID_AMOUNT);
            assertThat(amountException.getMetadata()).containsEntry("amountCents", 0L);
            verify(ledgerEntryRepository, never()).distinctCurrenciesForJournal(journalId);
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        }
    }

    @Nested
    @DisplayName("isBalanced")
    class IsBalanced {

        @ParameterizedTest
        @CsvSource({ "0, true", "500, false", "-500, false" })
        @DisplayName("reports whether the net amount is zero")
        void reportsBalance(long netAmount, boolean expected) {
            UUID journalId = UUID.randomUUID();
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(netAmount);

            assertThat(journalService.isBalanced(journalId)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("postJournal")
    class PostJournal {

        @Test
        @DisplayName("posts a balanced pending journal and records its posting time")
        void postsBalancedJournal() {
            UUID journalId = UUID.randomUUID();
            Journal journal = pendingJournal(journalId);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(500L);
            when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(500L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            Instant before = Instant.now();

            Journal result = journalService.postJournal(journalId);

            Instant after = Instant.now();
            assertThat(result).isSameAs(journal);
            assertThat(result.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(result.getPostedAt()).isNotNull().isBetween(before, after);
            verify(journalRepository).save(journal);
        }

        @Test
        @DisplayName("throws when the journal is not found")
        void journalNotFound() {
            UUID journalId = UUID.randomUUID();
            when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

            JournalNotFoundException exception = assertBankingException(
                    () -> journalService.postJournal(journalId),
                    JournalNotFoundException.class, ErrorCode.JOURNAL_NOT_FOUND);

            assertThat(exception.getMetadata()).containsEntry("journalId", journalId.toString());
        }

        @Test
        @DisplayName("returns an already posted journal unchanged")
        void alreadyPostedIsNoOp() {
            UUID journalId = UUID.randomUUID();
            Instant postedAt = Instant.parse("2024-05-01T12:00:00Z");
            Journal journal = pendingJournal(journalId);
            journal.setStatus(JournalStatus.POSTED);
            journal.setPostedAt(postedAt);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));

            Journal result = journalService.postJournal(journalId);

            assertThat(result).isSameAs(journal);
            assertThat(result.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(result.getPostedAt()).isEqualTo(postedAt);
            verify(journalRepository, never()).save(any(Journal.class));
            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        @DisplayName("rejects an unbalanced journal without changing its lifecycle")
        void unbalancedJournalRejected() {
            UUID journalId = UUID.randomUUID();
            Journal journal = pendingJournal(journalId);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(100L);
            when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(600L);
            when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(500L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));

            UnbalancedJournalException exception = assertBankingException(
                    () -> journalService.postJournal(journalId),
                    UnbalancedJournalException.class, ErrorCode.UNBALANCED_JOURNAL);

            assertThat(exception.getMetadata())
                    .containsEntry("journalId", journalId.toString())
                    .containsEntry("currency", "USD")
                    .containsEntry("debitTotalCents", 600L)
                    .containsEntry("creditTotalCents", 500L)
                    .containsEntry("netCents", 100L);
            assertThat(journal.getStatus()).isEqualTo(JournalStatus.PENDING);
            assertThat(journal.getPostedAt()).isNull();
            verify(journalRepository, never()).save(any(Journal.class));
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("creates, balances, posts, and keeps the posted journal immutable")
        void createsAndPostsBalancedJournal() {
            UUID journalId = UUID.randomUUID();
            UUID debitAccountId = UUID.randomUUID();
            UUID creditAccountId = UUID.randomUUID();
            AtomicReference<Journal> journalReference = new AtomicReference<>();
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> {
                Journal journal = invocation.getArgument(0);
                if (journal.getId() == null) {
                    journal.setId(journalId);
                }
                journalReference.set(journal);
                return journal;
            });
            when(journalRepository.findById(journalId))
                    .thenAnswer(invocation -> Optional.ofNullable(journalReference.get()));
            when(accountRepository.findById(debitAccountId))
                    .thenReturn(Optional.of(activeAccount(debitAccountId)));
            when(accountRepository.findById(creditAccountId))
                    .thenReturn(Optional.of(activeAccount(creditAccountId)));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId))
                    .thenReturn(List.of(), List.of("USD"));
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(1000L);
            when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(1000L);
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal journal = journalService.createJournal("Rent", null);
            LedgerEntry debit = journalService.addEntry(journalId, debitAccountId, "debit", "USD", 1000L);
            LedgerEntry credit = journalService.addEntry(journalId, creditAccountId, "credit", "USD", 1000L);

            assertThat(journal).isSameAs(journalReference.get());
            assertThat(debit.getJournal()).isSameAs(journal);
            assertThat(credit.getJournal()).isSameAs(journal);
            assertThat(journalService.isBalanced(journalId)).isTrue();
            Journal posted = journalService.postJournal(journalId);

            assertThat(posted).isSameAs(journal);
            assertThat(posted.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertBankingException(
                    () -> journalService.addEntry(journalId, debitAccountId, "debit", "USD", 1000L),
                    JournalNotPendingException.class, ErrorCode.JOURNAL_NOT_PENDING);
            assertThat(journalService.postJournal(journalId)).isSameAs(journal);
            verify(journalRepository, times(2)).save(any(Journal.class));
            verify(ledgerEntryRepository, times(2)).save(any(LedgerEntry.class));

            InOrder order = inOrder(journalRepository, ledgerEntryRepository);
            order.verify(journalRepository).save(any(Journal.class));
            order.verify(ledgerEntryRepository).save(any(LedgerEntry.class));
            order.verify(ledgerEntryRepository).save(any(LedgerEntry.class));
            order.verify(journalRepository).save(any(Journal.class));
        }
    }
}
