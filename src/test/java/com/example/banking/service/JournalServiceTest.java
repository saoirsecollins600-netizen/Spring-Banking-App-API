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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

    private JournalService journalService;

    private UUID journalId;
    private UUID accountId;
    private Journal pendingJournal;
    private Account activeAccount;

    @BeforeEach
    void setUp() {
        journalService = new JournalService(journalRepository, ledgerEntryRepository, accountRepository);

        journalId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        pendingJournal = new Journal("Test journal", "EXT-1");
        pendingJournal.setId(journalId);
        pendingJournal.setStatus(JournalStatus.PENDING);

        activeAccount = new Account(null, "checking", "USD");
        activeAccount.setId(accountId);
    }

    private void stubPendingJournalAndActiveAccount() {
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));
    }

    private void stubEntrySaveReturnsArgument() {
        when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ====================== createJournal ======================

    @Nested
    @DisplayName("createJournal")
    class CreateJournal {

        @Test
        void savesPendingJournalWithTrimmedDescriptionAndExternalRef() {
            when(journalRepository.existsByExternalRef("EXT-42")).thenReturn(false);
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Journal result = journalService.createJournal("  Payroll run  ", "  EXT-42  ");

            assertThat(result.getStatus()).isEqualTo(JournalStatus.PENDING);
            assertThat(result.getDescription()).isEqualTo("Payroll run");
            assertThat(result.getExternalRef()).isEqualTo("EXT-42");
            assertThat(result.getPostedAt()).isNull();
            verify(journalRepository).save(result);
        }

        @Test
        void nullDescriptionAndExternalRefAreStoredAsNullAndSkipDuplicateCheck() {
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Journal result = journalService.createJournal(null, null);

            assertThat(result.getDescription()).isNull();
            assertThat(result.getExternalRef()).isNull();
            assertThat(result.getStatus()).isEqualTo(JournalStatus.PENDING);
            verify(journalRepository, never()).existsByExternalRef(anyString());
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   ", "\t\n" })
        void blankDescriptionAndExternalRefAreNormalisedToNull(String blank) {
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Journal result = journalService.createJournal(blank, blank);

            assertThat(result.getDescription()).isNull();
            assertThat(result.getExternalRef()).isNull();
            verify(journalRepository, never()).existsByExternalRef(anyString());
        }

        @Test
        void populatedDescriptionWithBlankExternalRefKeepsDescriptionOnly() {
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Journal result = journalService.createJournal("Opening balance", "  ");

            assertThat(result.getDescription()).isEqualTo("Opening balance");
            assertThat(result.getExternalRef()).isNull();
            verify(journalRepository, never()).existsByExternalRef(anyString());
        }

        @Test
        void throwsWhenExternalRefAlreadyExists() {
            when(journalRepository.existsByExternalRef("DUP-1")).thenReturn(true);

            assertThatThrownBy(() -> journalService.createJournal("dup", " DUP-1 "))
                .isInstanceOf(ExternalReferenceAlreadyExistsException.class)
                .satisfies(ex -> {
                    ExternalReferenceAlreadyExistsException e = (ExternalReferenceAlreadyExistsException) ex;
                    assertThat(e.getCode()).isEqualTo(ErrorCode.EXTERNAL_REFERENCE_ALREADY_EXISTS);
                    assertThat(e.getMetadata()).containsEntry("externalReference", "DUP-1");
                });

            verify(journalRepository, never()).save(any(Journal.class));
        }
    }

    // ====================== addEntry ======================

    @Nested
    @DisplayName("addEntry")
    class AddEntry {

        @Test
        void savesNormalisedDebitEntry() {
            stubPendingJournalAndActiveAccount();
            stubEntrySaveReturnsArgument();
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of());

            LedgerEntry entry = journalService.addEntry(journalId, accountId, "  DEBIT ", " usd ", 1_000L);

            assertThat(entry.getJournal()).isSameAs(pendingJournal);
            assertThat(entry.getAccount()).isSameAs(activeAccount);
            assertThat(entry.getSide()).isEqualTo("debit");
            assertThat(entry.getCurrency()).isEqualTo("USD");
            assertThat(entry.getAmountCents()).isEqualTo(1_000L);
        }

        @Test
        void savesCreditEntryWhenCurrencyMatchesExistingEntries() {
            stubPendingJournalAndActiveAccount();
            stubEntrySaveReturnsArgument();
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("EUR"));

            LedgerEntry entry = journalService.addEntry(journalId, accountId, "credit", "eur", 250L);

            assertThat(entry.getSide()).isEqualTo("credit");
            assertThat(entry.getCurrency()).isEqualTo("EUR");
        }

        @Test
        void acceptsMinimumPositiveAmountOfOneCent() {
            stubPendingJournalAndActiveAccount();
            stubEntrySaveReturnsArgument();
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of());

            LedgerEntry entry = journalService.addEntry(journalId, accountId, "debit", "USD", 1L);

            assertThat(entry.getAmountCents()).isEqualTo(1L);
        }

        @Test
        void acceptsMaximumLongAmount() {
            stubPendingJournalAndActiveAccount();
            stubEntrySaveReturnsArgument();
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of());

            LedgerEntry entry = journalService.addEntry(journalId, accountId, "credit", "USD", Long.MAX_VALUE);

            assertThat(entry.getAmountCents()).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        void throwsWhenJournalNotFound() {
            when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L))
                .isInstanceOf(JournalNotFoundException.class)
                .satisfies(ex -> assertThat(((JournalNotFoundException) ex).getMetadata())
                    .containsEntry("journalId", journalId.toString()));

            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        void throwsWhenJournalIsPosted() {
            pendingJournal.setStatus(JournalStatus.POSTED);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));

            assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L))
                .isInstanceOf(JournalNotPendingException.class)
                .satisfies(ex -> assertThat(((JournalNotPendingException) ex).getCode())
                    .isEqualTo(ErrorCode.JOURNAL_NOT_PENDING));

            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        void throwsWhenAccountNotFound() {
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
            when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L))
                .isInstanceOf(AccountNotFoundException.class)
                .satisfies(ex -> assertThat(((AccountNotFoundException) ex).getMetadata())
                    .containsEntry("account", accountId.toString()));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        void throwsWhenAccountIsInactive() {
            activeAccount.setActive(false);
            stubPendingJournalAndActiveAccount();

            assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", 100L))
                .isInstanceOf(InactiveAccountException.class)
                .satisfies(ex -> assertThat(((InactiveAccountException) ex).getCode())
                    .isEqualTo(ErrorCode.INACTIVE_ACCOUNT));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = { "deposit", "dr", "", "   ", "debits" })
        void throwsWhenSideIsInvalid(String side) {
            stubPendingJournalAndActiveAccount();

            assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, side, "USD", 100L))
                .isInstanceOf(InvalidTransactionSideException.class)
                .satisfies(ex -> assertThat(((InvalidTransactionSideException) ex).getMetadata())
                    .containsEntry("side", side));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @ParameterizedTest
        @ValueSource(longs = { 0L, -1L, Long.MIN_VALUE })
        void throwsWhenAmountIsNotPositive(long amount) {
            stubPendingJournalAndActiveAccount();

            assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", amount))
                .isInstanceOf(InvalidAmountException.class)
                .satisfies(ex -> assertThat(((InvalidAmountException) ex).getMetadata())
                    .containsEntry("amountCents", amount));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = { "US", "USDD", "XXX1", "ABC", "", "  " })
        void throwsWhenCurrencyCodeIsInvalid(String currency) {
            stubPendingJournalAndActiveAccount();

            assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", currency, 100L))
                .isInstanceOf(InvalidCurrencyCodeException.class)
                .satisfies(ex -> assertThat(((InvalidCurrencyCodeException) ex).getMetadata())
                    .containsEntry("currency", currency));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        void throwsWhenCurrencyDoesNotMatchExistingJournalEntries() {
            stubPendingJournalAndActiveAccount();
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));

            assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "credit", "eur", 100L))
                .isInstanceOf(InvalidCurrencyCodeException.class)
                .satisfies(ex -> assertThat(((InvalidCurrencyCodeException) ex).getMetadata().get("currency"))
                    .asString()
                    .startsWith("EUR")
                    .contains("existing=USD"));

            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        }
    }

    // ====================== isBalanced ======================

    @Nested
    @DisplayName("isBalanced")
    class IsBalanced {

        @Test
        void returnsTrueWhenNetIsZero() {
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);

            assertThat(journalService.isBalanced(journalId)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(longs = { 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE })
        void returnsFalseWhenNetIsNonZero(long net) {
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(net);

            assertThat(journalService.isBalanced(journalId)).isFalse();
        }
    }

    // ====================== postJournal ======================

    @Nested
    @DisplayName("postJournal")
    class PostJournal {

        @Test
        void postsBalancedJournalAndStampsPostedAt() {
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(500L);
            when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(500L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Instant before = Instant.now();
            Journal result = journalService.postJournal(journalId);

            assertThat(result.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(result.getPostedAt()).isNotNull().isAfterOrEqualTo(before);
            verify(journalRepository).save(pendingJournal);
        }

        @Test
        void rePostingAlreadyPostedJournalIsNoOp() {
            Instant originalPostedAt = Instant.parse("2024-01-01T00:00:00Z");
            pendingJournal.setStatus(JournalStatus.POSTED);
            pendingJournal.setPostedAt(originalPostedAt);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));

            Journal result = journalService.postJournal(journalId);

            assertThat(result).isSameAs(pendingJournal);
            assertThat(result.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(result.getPostedAt()).isEqualTo(originalPostedAt);
            verify(journalRepository, never()).save(any(Journal.class));
            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        void throwsWhenJournalNotFound() {
            when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> journalService.postJournal(journalId))
                .isInstanceOf(JournalNotFoundException.class);

            verifyNoInteractions(ledgerEntryRepository);
            verify(journalRepository, never()).save(any(Journal.class));
        }

        @Test
        void throwsUnbalancedWithDiagnosticsMetadata() {
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(300L);
            when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(800L);
            when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(500L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));

            assertThatThrownBy(() -> journalService.postJournal(journalId))
                .isInstanceOf(UnbalancedJournalException.class)
                .satisfies(ex -> {
                    UnbalancedJournalException e = (UnbalancedJournalException) ex;
                    assertThat(e.getCode()).isEqualTo(ErrorCode.UNBALANCED_JOURNAL);
                    assertThat(e.getMetadata())
                        .containsEntry("journalId", journalId.toString())
                        .containsEntry("currency", "USD")
                        .containsEntry("debitTotalCents", 800L)
                        .containsEntry("creditTotalCents", 500L)
                        .containsEntry("netCents", 300L);
                });

            assertThat(pendingJournal.getStatus()).isEqualTo(JournalStatus.PENDING);
            assertThat(pendingJournal.getPostedAt()).isNull();
            verify(journalRepository, never()).save(any(Journal.class));
        }

        @Test
        void postsEmptyJournalWithNoEntries() {
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal));
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of());
            when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

            Journal result = journalService.postJournal(journalId);

            assertThat(result.getStatus()).isEqualTo(JournalStatus.POSTED);
        }
    }

    // ====================== full lifecycle ======================

    @Test
    @DisplayName("double-entry lifecycle: createJournal -> addEntry x2 -> isBalanced -> postJournal")
    void doubleEntryLifecycle() {
        UUID secondAccountId = UUID.randomUUID();
        Account secondAccount = new Account(null, "savings", "USD");
        secondAccount.setId(secondAccountId);

        when(journalRepository.existsByExternalRef("TXN-1")).thenReturn(false);
        when(journalRepository.save(any(Journal.class))).thenAnswer(inv -> {
            Journal j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId(journalId);
            }
            return j;
        });

        Journal created = journalService.createJournal("Transfer", "TXN-1");
        assertThat(created.getId()).isEqualTo(journalId);
        assertThat(created.getStatus()).isEqualTo(JournalStatus.PENDING);

        when(journalRepository.findById(journalId)).thenReturn(Optional.of(created));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));
        when(accountRepository.findById(secondAccountId)).thenReturn(Optional.of(secondAccount));
        stubEntrySaveReturnsArgument();
        when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId))
            .thenReturn(List.of())
            .thenReturn(List.of("USD"));

        LedgerEntry debit = journalService.addEntry(journalId, accountId, "debit", "USD", 5_000L);
        LedgerEntry credit = journalService.addEntry(journalId, secondAccountId, "credit", "USD", 5_000L);

        assertThat(debit.getSide()).isEqualTo("debit");
        assertThat(credit.getSide()).isEqualTo("credit");

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        long net = captor.getAllValues().stream()
            .mapToLong(e -> "debit".equals(e.getSide()) ? e.getAmountCents() : -e.getAmountCents())
            .sum();
        assertThat(net).isZero();

        when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(net);
        assertThat(journalService.isBalanced(journalId)).isTrue();

        when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(5_000L);
        when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(5_000L);
        when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));

        Journal posted = journalService.postJournal(journalId);

        assertThat(posted.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(posted.getPostedAt()).isNotNull();

        assertThatThrownBy(() -> journalService.addEntry(journalId, accountId, "debit", "USD", 1L))
            .isInstanceOf(JournalNotPendingException.class);
    }
}
