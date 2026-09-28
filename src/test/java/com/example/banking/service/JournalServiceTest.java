package com.example.banking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.banking.entity.Account;
import com.example.banking.entity.Journal;
import com.example.banking.entity.LedgerEntry;
import com.example.banking.exceptions.BankingExceptions.BankingException;
import com.example.banking.exceptions.BankingExceptions.ErrorCode;
import com.example.banking.exceptions.BankingExceptions.AccountNotFoundException;
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

    private JournalService journalService;

    @BeforeEach
    void setUp() {
        journalService = new JournalService(journalRepository, ledgerEntryRepository, accountRepository);
    }

    private Journal pendingJournal() {
        Journal j = new Journal("desc", null);
        j.setId(UUID.randomUUID());
        j.setStatus(JournalStatus.PENDING);
        return j;
    }

    private Account activeAccount() {
        Account a = new Account(null, "checking", "USD");
        a.setId(UUID.randomUUID());
        return a;
    }

    @Test
    @DisplayName("journal lifecycle: create, add balanced entries, post, then reject further entries")
    void journalLifecycle() {
        when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        Journal created = journalService.createJournal("Transfer", "EXT-1");
        assertThat(created.getStatus()).isEqualTo(JournalStatus.PENDING);
        assertThat(created.getDescription()).isEqualTo("Transfer");
        assertThat(created.getExternalRef()).isEqualTo("EXT-1");

        created.setId(UUID.randomUUID());
        Account account = activeAccount();
        when(journalRepository.findById(created.getId())).thenReturn(Optional.of(created));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(ledgerEntryRepository.distinctCurrenciesForJournal(created.getId()))
                .thenReturn(List.of())
                .thenReturn(List.of("USD"));

        journalService.addEntry(created.getId(), account.getId(), "debit", "USD", 10_000);
        journalService.addEntry(created.getId(), account.getId(), "credit", "USD", 10_000);

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        InOrder order = inOrder(ledgerEntryRepository);
        order.verify(ledgerEntryRepository).distinctCurrenciesForJournal(created.getId());
        order.verify(ledgerEntryRepository).save(captor.capture());
        order.verify(ledgerEntryRepository).distinctCurrenciesForJournal(created.getId());
        order.verify(ledgerEntryRepository).save(captor.capture());

        List<LedgerEntry> saved = captor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getSide()).isEqualTo("debit");
        assertThat(saved.get(0).getAmountCents()).isEqualTo(10_000L);
        assertThat(saved.get(0).getCurrency()).isEqualTo("USD");
        assertThat(saved.get(0).getJournal()).isSameAs(created);
        assertThat(saved.get(0).getAccount()).isSameAs(account);
        assertThat(saved.get(1).getSide()).isEqualTo("credit");
        assertThat(saved.get(1).getAmountCents()).isEqualTo(10_000L);
        assertThat(saved.get(1).getCurrency()).isEqualTo("USD");
        assertThat(saved.get(1).getJournal()).isSameAs(created);
        assertThat(saved.get(1).getAccount()).isSameAs(account);

        when(ledgerEntryRepository.netAmountForJournal(created.getId())).thenReturn(0L);
        assertThat(journalService.isBalanced(created.getId())).isTrue();

        when(ledgerEntryRepository.totalDebits(created.getId())).thenReturn(10_000L);
        when(ledgerEntryRepository.totalCredits(created.getId())).thenReturn(10_000L);
        when(ledgerEntryRepository.distinctCurrenciesForJournal(created.getId()))
                .thenReturn(List.of("USD"));

        Journal posted = journalService.postJournal(created.getId());
        assertThat(posted.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(posted.getPostedAt()).isNotNull();
        verify(journalRepository, times(2)).save(created);

        assertThatThrownBy(() -> journalService.addEntry(created.getId(), account.getId(), "debit", "USD", 100))
                .isInstanceOf(JournalNotPendingException.class)
                .extracting(e -> ((BankingException) e).getCode())
                .isEqualTo(ErrorCode.JOURNAL_NOT_PENDING);
    }

    @Nested
    @DisplayName("createJournal")
    class CreateJournal {

        @Test
        @DisplayName("trims populated description and externalRef")
        void trimsPopulatedFields() {
            when(journalRepository.existsByExternalRef("EXT-1")).thenReturn(false);
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Journal j = journalService.createJournal("  Transfer  ", "  EXT-1  ");

            assertThat(j.getDescription()).isEqualTo("Transfer");
            assertThat(j.getExternalRef()).isEqualTo("EXT-1");
            assertThat(j.getStatus()).isEqualTo(JournalStatus.PENDING);
            verify(journalRepository).save(j);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        @DisplayName("blank description is normalized to null")
        void blankDescriptionBecomesNull(String description) {
            when(journalRepository.existsByExternalRef("EXT-1")).thenReturn(false);
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Journal j = journalService.createJournal(description, "EXT-1");

            assertThat(j.getDescription()).isNull();
            assertThat(j.getExternalRef()).isEqualTo("EXT-1");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        @DisplayName("blank externalRef is normalized to null and uniqueness check is skipped")
        void blankExternalRefBecomesNull(String externalRef) {
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Journal j = journalService.createJournal("Transfer", externalRef);

            assertThat(j.getExternalRef()).isNull();
            verify(journalRepository, never()).existsByExternalRef(any());
            verify(journalRepository).save(j);
        }

        @Test
        @DisplayName("populated description with blank externalRef is kept independently")
        void populatedDescriptionBlankRef() {
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Journal j = journalService.createJournal("  Transfer  ", "   ");

            assertThat(j.getDescription()).isEqualTo("Transfer");
            assertThat(j.getExternalRef()).isNull();
            verify(journalRepository, never()).existsByExternalRef(any());
        }

        @Test
        @DisplayName("duplicate externalRef throws ExternalReferenceAlreadyExistsException")
        void duplicateExternalRef() {
            when(journalRepository.existsByExternalRef("EXT-1")).thenReturn(true);

            assertThatThrownBy(() -> journalService.createJournal("Transfer", "  EXT-1  "))
                    .isInstanceOf(ExternalReferenceAlreadyExistsException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.EXTERNAL_REFERENCE_ALREADY_EXISTS);

            assertThatThrownBy(() -> journalService.createJournal("Transfer", "  EXT-1  "))
                    .isInstanceOf(BankingException.class)
                    .extracting(e -> ((BankingException) e).getMetadata())
                    .isEqualTo(Map.of("externalReference", "EXT-1"));

            verify(journalRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("addEntry")
    class AddEntry {

        @Test
        @DisplayName("throws JournalNotFoundException when journal does not exist")
        void journalNotFound() {
            UUID journalId = UUID.randomUUID();
            when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> journalService.addEntry(journalId, UUID.randomUUID(), "debit", "USD", 100))
                    .isInstanceOf(JournalNotFoundException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.JOURNAL_NOT_FOUND);

            assertThatThrownBy(() -> journalService.addEntry(journalId, UUID.randomUUID(), "debit", "USD", 100))
                    .isInstanceOf(BankingException.class)
                    .extracting(e -> ((BankingException) e).getMetadata())
                    .isEqualTo(Map.of("journalId", journalId.toString()));

            verifyNoInteractions(accountRepository);
            verify(ledgerEntryRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws JournalNotPendingException when journal is POSTED")
        void journalNotPending() {
            Journal j = pendingJournal();
            j.setStatus(JournalStatus.POSTED);
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), UUID.randomUUID(), "debit", "USD", 100))
                    .isInstanceOf(JournalNotPendingException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.JOURNAL_NOT_PENDING);

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), UUID.randomUUID(), "debit", "USD", 100))
                    .isInstanceOf(BankingException.class)
                    .extracting(e -> ((BankingException) e).getMetadata())
                    .isEqualTo(Map.of("journalId", j.getId().toString()));

            verifyNoInteractions(accountRepository);
            verify(ledgerEntryRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws AccountNotFoundException when account does not exist")
        void accountNotFound() {
            Journal j = pendingJournal();
            UUID accountId = UUID.randomUUID();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), accountId, "debit", "USD", 100))
                    .isInstanceOf(AccountNotFoundException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), accountId, "debit", "USD", 100))
                    .isInstanceOf(BankingException.class)
                    .extracting(e -> ((BankingException) e).getMetadata())
                    .isEqualTo(Map.of("account", accountId.toString()));

            verify(ledgerEntryRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws InactiveAccountException when account is inactive")
        void inactiveAccount() {
            Journal j = pendingJournal();
            Account a = activeAccount();
            a.setActive(false);
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), "debit", "USD", 100))
                    .isInstanceOf(InactiveAccountException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.INACTIVE_ACCOUNT);

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), "debit", "USD", 100))
                    .isInstanceOf(BankingException.class)
                    .extracting(e -> ((BankingException) e).getMetadata())
                    .isEqualTo(Map.of("account", a.getId().toString()));

            verify(ledgerEntryRepository, never()).save(any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"deposit", "dr", "debits", "", "   "})
        @DisplayName("throws InvalidTransactionSideException for invalid side")
        void invalidSide(String side) {
            Journal j = pendingJournal();
            Account a = activeAccount();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), side, "USD", 100))
                    .isInstanceOf(InvalidTransactionSideException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.INVALID_TRANSACTION_SIDE);

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), side, "USD", 100))
                    .isInstanceOf(BankingException.class)
                    .extracting(e -> ((BankingException) e).getMetadata())
                    .isEqualTo(Map.of("side", side));

            verify(ledgerEntryRepository, never()).save(any());
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
        @DisplayName("throws InvalidAmountException for non-positive amounts")
        void invalidAmount(long amountCents) {
            Journal j = pendingJournal();
            Account a = activeAccount();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), "debit", "USD", amountCents))
                    .isInstanceOf(InvalidAmountException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.INVALID_AMOUNT);

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), "debit", "USD", amountCents))
                    .isInstanceOf(BankingException.class)
                    .extracting(e -> ((BankingException) e).getMetadata())
                    .isEqualTo(Map.of("amountCents", amountCents));

            verify(ledgerEntryRepository, never()).save(any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"US", "USDD", "QQQ", "", "   ", "$$$"})
        @DisplayName("throws InvalidCurrencyCodeException for invalid currency")
        void invalidCurrency(String currency) {
            Journal j = pendingJournal();
            Account a = activeAccount();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), "debit", currency, 100))
                    .isInstanceOf(InvalidCurrencyCodeException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.INVALID_CURRENCY_CODE);

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), "debit", currency, 100))
                    .isInstanceOf(BankingException.class)
                    .extracting(e -> ((BankingException) e).getMetadata())
                    .isEqualTo(Map.of("currency", currency));

            verify(ledgerEntryRepository, never()).distinctCurrenciesForJournal(any());
            verify(ledgerEntryRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws InvalidCurrencyCodeException when currency mismatches existing journal currency")
        void currencyMismatch() {
            Journal j = pendingJournal();
            Account a = activeAccount();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(j.getId()))
                    .thenReturn(List.of("USD"));

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), "debit", "EUR", 100))
                    .isInstanceOf(InvalidCurrencyCodeException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.INVALID_CURRENCY_CODE);

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), "debit", "EUR", 100))
                    .isInstanceOf(BankingException.class)
                    .extracting(e -> ((BankingException) e).getMetadata())
                    .isEqualTo(Map.of("currency", "EUR (single-currency journal mismatch, existing=USD)"));

            verify(ledgerEntryRepository, never()).save(any());
        }

        @Test
        @DisplayName("validation order: invalid side wins over invalid amount")
        void sideCheckedBeforeAmount() {
            Journal j = pendingJournal();
            Account a = activeAccount();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), "bogus", "USD", -5))
                    .isInstanceOf(InvalidTransactionSideException.class);
        }

        @Test
        @DisplayName("validation order: invalid amount wins over invalid currency")
        void amountCheckedBeforeCurrency() {
            Journal j = pendingJournal();
            Account a = activeAccount();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));

            assertThatThrownBy(() -> journalService.addEntry(j.getId(), a.getId(), "debit", "QQQ", -5))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @ParameterizedTest
        @ValueSource(longs = {1L, Long.MAX_VALUE})
        @DisplayName("accepts boundary positive amounts")
        void boundaryAmounts(long amountCents) {
            Journal j = pendingJournal();
            Account a = activeAccount();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(j.getId())).thenReturn(List.of());
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            LedgerEntry entry = journalService.addEntry(j.getId(), a.getId(), "debit", "USD", amountCents);

            assertThat(entry.getAmountCents()).isEqualTo(amountCents);
        }

        @Test
        @DisplayName("normalizes side and currency whitespace/case")
        void normalizesSideAndCurrency() {
            Journal j = pendingJournal();
            Account a = activeAccount();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(j.getId())).thenReturn(List.of());
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            LedgerEntry entry = journalService.addEntry(j.getId(), a.getId(), "  DEBIT ", " usd ", 500);

            assertThat(entry.getSide()).isEqualTo("debit");
            assertThat(entry.getCurrency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("accepts currency matching existing journal currency")
        void matchingCurrencyAccepted() {
            Journal j = pendingJournal();
            Account a = activeAccount();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(j.getId()))
                    .thenReturn(List.of("USD"));
            when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            LedgerEntry entry = journalService.addEntry(j.getId(), a.getId(), "credit", "USD", 500);

            assertThat(entry.getCurrency()).isEqualTo("USD");
            verify(ledgerEntryRepository).save(entry);
        }
    }

    @Nested
    @DisplayName("isBalanced")
    class IsBalanced {

        @Test
        @DisplayName("returns true when net amount is zero")
        void balanced() {
            UUID journalId = UUID.randomUUID();
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);

            assertThat(journalService.isBalanced(journalId)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(longs = {1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE})
        @DisplayName("returns false when net amount is non-zero")
        void unbalanced(long net) {
            UUID journalId = UUID.randomUUID();
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(net);

            assertThat(journalService.isBalanced(journalId)).isFalse();
        }
    }

    @Nested
    @DisplayName("postJournal")
    class PostJournal {

        @Test
        @DisplayName("throws JournalNotFoundException when journal does not exist")
        void journalNotFound() {
            UUID journalId = UUID.randomUUID();
            when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> journalService.postJournal(journalId))
                    .isInstanceOf(JournalNotFoundException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.JOURNAL_NOT_FOUND);

            verify(journalRepository, never()).save(any());
        }

        @Test
        @DisplayName("posts a balanced journal, setting status and postedAt")
        void postsBalancedJournal() {
            Journal j = pendingJournal();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(ledgerEntryRepository.netAmountForJournal(j.getId())).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(j.getId())).thenReturn(1_000L);
            when(ledgerEntryRepository.totalCredits(j.getId())).thenReturn(1_000L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(j.getId()))
                    .thenReturn(List.of("USD"));
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Instant before = Instant.now();
            Journal posted = journalService.postJournal(j.getId());
            Instant after = Instant.now();

            assertThat(posted.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(posted.getPostedAt()).isBetween(before, after);
            verify(journalRepository).save(j);
        }

        @Test
        @DisplayName("throws UnbalancedJournalException with full metadata for unbalanced journal")
        void unbalancedJournal() {
            Journal j = pendingJournal();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(ledgerEntryRepository.netAmountForJournal(j.getId())).thenReturn(500L);
            when(ledgerEntryRepository.totalDebits(j.getId())).thenReturn(1_500L);
            when(ledgerEntryRepository.totalCredits(j.getId())).thenReturn(1_000L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(j.getId()))
                    .thenReturn(List.of("USD"));

            assertThatThrownBy(() -> journalService.postJournal(j.getId()))
                    .isInstanceOf(UnbalancedJournalException.class)
                    .extracting(e -> ((BankingException) e).getCode())
                    .isEqualTo(ErrorCode.UNBALANCED_JOURNAL);

            assertThatThrownBy(() -> journalService.postJournal(j.getId()))
                    .isInstanceOf(BankingException.class)
                    .extracting(e -> ((BankingException) e).getMetadata())
                    .isEqualTo(Map.of(
                            "journalId", j.getId().toString(),
                            "currency", "USD",
                            "debitTotalCents", 1_500L,
                            "creditTotalCents", 1_000L,
                            "netCents", 500L));

            assertThat(j.getStatus()).isEqualTo(JournalStatus.PENDING);
            assertThat(j.getPostedAt()).isNull();
            verify(journalRepository, never()).save(any());
        }

        @Test
        @DisplayName("already POSTED journal is a no-op returning the same instance")
        void alreadyPosted() {
            Journal j = pendingJournal();
            j.setStatus(JournalStatus.POSTED);
            Instant postedAt = Instant.now().minusSeconds(60);
            j.setPostedAt(postedAt);
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));

            Journal result = journalService.postJournal(j.getId());

            assertThat(result).isSameAs(j);
            assertThat(result.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(result.getPostedAt()).isEqualTo(postedAt);
            verify(journalRepository, never()).save(any());
            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        @DisplayName("empty journal with net zero posts fine")
        void emptyJournal() {
            Journal j = pendingJournal();
            when(journalRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(ledgerEntryRepository.netAmountForJournal(j.getId())).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(j.getId())).thenReturn(0L);
            when(ledgerEntryRepository.totalCredits(j.getId())).thenReturn(0L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(j.getId())).thenReturn(List.of());
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Journal posted = journalService.postJournal(j.getId());

            assertThat(posted.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(posted.getPostedAt()).isNotNull();
            verify(journalRepository).save(j);
        }
    }
}
