package com.example.banking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
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
class JournalServiceTest {

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

    @Nested
    class CreateJournal {

        @Test
        void createJournal_trimsPopulatedDescriptionAndExternalRef() {
            when(journalRepository.existsByExternalRef("EXT-1")).thenReturn(false);
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal journal = service.createJournal("  Rent  ", "  EXT-1  ");

            assertThat(journal.getDescription()).isEqualTo("Rent");
            assertThat(journal.getExternalRef()).isEqualTo("EXT-1");
            assertThat(journal.getStatus()).isEqualTo(JournalStatus.PENDING);
            verify(journalRepository).existsByExternalRef("EXT-1");
            verify(journalRepository).save(journal);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   ", "\t\n" })
        void createJournal_normalizesBlankDescriptionToNull(String description) {
            when(journalRepository.existsByExternalRef("EXT-1")).thenReturn(false);
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal journal = service.createJournal(description, "EXT-1");

            assertThat(journal.getDescription()).isNull();
            assertThat(journal.getExternalRef()).isEqualTo("EXT-1");
            assertThat(journal.getStatus()).isEqualTo(JournalStatus.PENDING);
            verify(journalRepository).save(journal);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   ", "\t\n" })
        void createJournal_normalizesBlankExternalRefToNull(String externalRef) {
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal journal = service.createJournal("Rent", externalRef);

            assertThat(journal.getDescription()).isEqualTo("Rent");
            assertThat(journal.getExternalRef()).isNull();
            assertThat(journal.getStatus()).isEqualTo(JournalStatus.PENDING);
            verify(journalRepository, never()).existsByExternalRef(any());
            verify(journalRepository).save(journal);
        }

        @Test
        void createJournal_rejectsDuplicateExternalRef() {
            when(journalRepository.existsByExternalRef("EXT-1")).thenReturn(true);

            assertThatThrownBy(() -> service.createJournal("Rent", " EXT-1 "))
                    .satisfies(thrown -> assertBankingException(thrown,
                            ExternalReferenceAlreadyExistsException.class,
                            ErrorCode.EXTERNAL_REFERENCE_ALREADY_EXISTS,
                            Map.of("externalReference", "EXT-1")));

            verify(journalRepository).existsByExternalRef("EXT-1");
            verify(journalRepository, never()).save(any(Journal.class));
        }
    }

    @Nested
    class AddEntry {

        @Test
        void addEntry_normalizesAndSavesEntry() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            Journal journal = pendingJournal(journalId);
            Account account = activeAccount(accountId);
            stubAddEntryPrerequisites(journal, account);
            when(ledgerEntryRepository.save(any(LedgerEntry.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry returned = service.addEntry(journalId, accountId, "  DEBIT ", " usd ", 10_000L);

            ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
            verify(ledgerEntryRepository).save(captor.capture());
            LedgerEntry saved = captor.getValue();
            assertThat(returned).isSameAs(saved);
            assertThat(saved.getSide()).isEqualTo("debit");
            assertThat(saved.getCurrency()).isEqualTo("USD");
            assertThat(saved.getAmountCents()).isEqualTo(10_000L);
            assertThat(saved.getJournal()).isSameAs(journal);
            assertThat(saved.getAccount()).isSameAs(account);
            assertThat(account.isActive()).isTrue();
        }

        @Test
        void addEntry_acceptsCreditSide() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubAddEntryPrerequisites(pendingJournal(journalId), activeAccount(accountId));
            when(ledgerEntryRepository.save(any(LedgerEntry.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry entry = service.addEntry(journalId, accountId, "credit", "USD", 1L);

            assertThat(entry.getSide()).isEqualTo("credit");
        }

        @ParameterizedTest
        @ValueSource(longs = { 1L, Long.MAX_VALUE })
        void addEntry_acceptsBoundaryAmounts(long amountCents) {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubAddEntryPrerequisites(pendingJournal(journalId), activeAccount(accountId));
            when(ledgerEntryRepository.save(any(LedgerEntry.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry entry = service.addEntry(journalId, accountId, "debit", "USD", amountCents);

            assertThat(entry.getAmountCents()).isEqualTo(amountCents);
        }

        @Test
        void addEntry_acceptsCurrencyMatchingExistingEntries() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubAddEntryPrerequisites(pendingJournal(journalId), activeAccount(accountId));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));
            when(ledgerEntryRepository.save(any(LedgerEntry.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            LedgerEntry entry = service.addEntry(journalId, accountId, "debit", "usd", 10L);

            assertThat(entry.getCurrency()).isEqualTo("USD");
        }

        @Test
        void addEntry_rejectsMissingJournalBeforeLookingUpAccountOrLedger() {
            UUID journalId = UUID.randomUUID();
            when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addEntry(journalId, UUID.randomUUID(), "debit", "USD", 1L))
                    .satisfies(thrown -> assertBankingException(thrown, JournalNotFoundException.class,
                            ErrorCode.JOURNAL_NOT_FOUND, Map.of("journalId", journalId.toString())));

            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        void addEntry_rejectsNonPendingJournalBeforeLookingUpAccountOrLedger() {
            UUID journalId = UUID.randomUUID();
            Journal journal = pendingJournal(journalId);
            journal.setStatus(JournalStatus.POSTED);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));

            assertThatThrownBy(() -> service.addEntry(journalId, UUID.randomUUID(), "debit", "USD", 1L))
                    .satisfies(thrown -> assertBankingException(thrown, JournalNotPendingException.class,
                            ErrorCode.JOURNAL_NOT_PENDING, Map.of("journalId", journalId.toString())));

            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        void addEntry_rejectsMissingAccountBeforeUsingLedger() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(pendingJournal(journalId)));
            when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addEntry(journalId, accountId, "debit", "USD", 1L))
                    .satisfies(thrown -> assertBankingException(thrown, AccountNotFoundException.class,
                            ErrorCode.ACCOUNT_NOT_FOUND, Map.of("account", accountId.toString())));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        void addEntry_rejectsInactiveAccountBeforeUsingLedger() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            Account account = activeAccount(accountId);
            account.setActive(false);
            stubAddEntryPrerequisites(pendingJournal(journalId), account);

            assertThatThrownBy(() -> service.addEntry(journalId, accountId, "debit", "USD", 1L))
                    .satisfies(thrown -> assertBankingException(thrown, InactiveAccountException.class,
                            ErrorCode.INACTIVE_ACCOUNT, Map.of("account", accountId.toString())));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = { "deposit", "dr", "debits", "", "   " })
        void addEntry_rejectsInvalidSideBeforeUsingLedger(String side) {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubAddEntryPrerequisites(pendingJournal(journalId), activeAccount(accountId));

            assertThatThrownBy(() -> service.addEntry(journalId, accountId, side, "USD", 1L))
                    .satisfies(thrown -> assertBankingException(thrown, InvalidTransactionSideException.class,
                            ErrorCode.INVALID_TRANSACTION_SIDE, Map.of("side", side)));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @ParameterizedTest
        @ValueSource(longs = { 0L, -1L, Long.MIN_VALUE })
        void addEntry_rejectsNonPositiveAmountBeforeUsingLedger(long amountCents) {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubAddEntryPrerequisites(pendingJournal(journalId), activeAccount(accountId));

            assertThatThrownBy(() -> service.addEntry(journalId, accountId, "debit", "USD", amountCents))
                    .satisfies(thrown -> assertBankingException(thrown, InvalidAmountException.class,
                            ErrorCode.INVALID_AMOUNT, Map.of("amountCents", amountCents)));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = { "US", "USDD", "QQQ", "", "   ", "$$$" })
        void addEntry_rejectsInvalidCurrencyBeforeCheckingExistingEntries(String currency) {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubAddEntryPrerequisites(pendingJournal(journalId), activeAccount(accountId));

            assertThatThrownBy(() -> service.addEntry(journalId, accountId, "debit", currency, 1L))
                    .satisfies(thrown -> assertBankingException(thrown, InvalidCurrencyCodeException.class,
                            ErrorCode.INVALID_CURRENCY_CODE, Map.of("currency", currency)));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        void addEntry_rejectsCurrencyThatDiffersFromExistingEntries() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubAddEntryPrerequisites(pendingJournal(journalId), activeAccount(accountId));
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));

            assertThatThrownBy(() -> service.addEntry(journalId, accountId, "debit", "EUR", 1L))
                    .satisfies(thrown -> assertBankingException(thrown, InvalidCurrencyCodeException.class,
                            ErrorCode.INVALID_CURRENCY_CODE,
                            Map.of("currency", "EUR (single-currency journal mismatch, existing=USD)")));

            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
        }

        @Test
        void addEntry_validatesSideBeforeAmountAndCurrency() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubAddEntryPrerequisites(pendingJournal(journalId), activeAccount(accountId));

            assertThatThrownBy(() -> service.addEntry(journalId, accountId, "deposit", "$$$", 0L))
                    .satisfies(thrown -> assertBankingException(thrown, InvalidTransactionSideException.class,
                            ErrorCode.INVALID_TRANSACTION_SIDE, Map.of("side", "deposit")));

            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        void addEntry_validatesAmountBeforeCurrency() {
            UUID journalId = UUID.randomUUID();
            UUID accountId = UUID.randomUUID();
            stubAddEntryPrerequisites(pendingJournal(journalId), activeAccount(accountId));

            assertThatThrownBy(() -> service.addEntry(journalId, accountId, "debit", "$$$", 0L))
                    .satisfies(thrown -> assertBankingException(thrown, InvalidAmountException.class,
                            ErrorCode.INVALID_AMOUNT, Map.of("amountCents", 0L)));

            verifyNoInteractions(ledgerEntryRepository);
        }
    }

    @Nested
    class IsBalanced {

        @Test
        void isBalanced_returnsTrueForZeroNet() {
            UUID journalId = UUID.randomUUID();
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);

            assertThat(service.isBalanced(journalId)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(longs = { 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE })
        void isBalanced_returnsFalseForNonzeroNet(long net) {
            UUID journalId = UUID.randomUUID();
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(net);

            assertThat(service.isBalanced(journalId)).isFalse();
        }
    }

    @Nested
    class PostJournal {

        @Test
        void postJournal_postsBalancedPendingJournal() {
            UUID journalId = UUID.randomUUID();
            Journal journal = pendingJournal(journalId);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(10_000L);
            when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(10_000L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));
            Instant before = Instant.now();

            Journal returned = service.postJournal(journalId);
            Instant after = Instant.now();

            assertThat(returned).isSameAs(journal);
            assertThat(journal.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(journal.getPostedAt()).isNotNull().isBetween(before, after);
            verify(journalRepository).save(same(journal));
        }

        @Test
        void postJournal_rejectsMissingJournalBeforeUsingLedger() {
            UUID journalId = UUID.randomUUID();
            when(journalRepository.findById(journalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.postJournal(journalId))
                    .satisfies(thrown -> assertBankingException(thrown, JournalNotFoundException.class,
                            ErrorCode.JOURNAL_NOT_FOUND, Map.of("journalId", journalId.toString())));

            verifyNoInteractions(ledgerEntryRepository);
            verify(journalRepository, never()).save(any(Journal.class));
        }

        @Test
        void postJournal_rejectsUnbalancedJournalWithoutSavingOrChangingIt() {
            UUID journalId = UUID.randomUUID();
            Journal journal = pendingJournal(journalId);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(1L);
            when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(10_000L);
            when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(9_999L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of("USD"));

            assertThatThrownBy(() -> service.postJournal(journalId))
                    .satisfies(thrown -> {
                        assertBankingException(thrown, UnbalancedJournalException.class,
                                ErrorCode.UNBALANCED_JOURNAL,
                                Map.of("journalId", journalId.toString(), "currency", "USD",
                                        "debitTotalCents", 10_000L, "creditTotalCents", 9_999L,
                                        "netCents", 1L));
                        assertThat(thrown).hasMessage("Cannot post an unbalanced journal");
                    });

            assertThat(journal.getStatus()).isEqualTo(JournalStatus.PENDING);
            assertThat(journal.getPostedAt()).isNull();
            verify(journalRepository, never()).save(any(Journal.class));
        }

        @Test
        void postJournal_returnsAlreadyPostedJournalWithoutLedgerAccessOrSaving() {
            UUID journalId = UUID.randomUUID();
            Journal journal = pendingJournal(journalId);
            Instant postedAt = Instant.parse("2024-01-01T00:00:00Z");
            journal.setStatus(JournalStatus.POSTED);
            journal.setPostedAt(postedAt);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));

            Journal returned = service.postJournal(journalId);

            assertThat(returned).isSameAs(journal);
            assertThat(returned.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(returned.getPostedAt()).isEqualTo(postedAt);
            verify(journalRepository, never()).save(any(Journal.class));
            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        void postJournal_postsEmptyJournal() {
            UUID journalId = UUID.randomUUID();
            Journal journal = pendingJournal(journalId);
            when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));
            when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(0L);
            when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId)).thenReturn(List.of());
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal returned = service.postJournal(journalId);

            assertThat(returned).isSameAs(journal);
            assertThat(returned.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(returned.getPostedAt()).isNotNull();
            verify(journalRepository).save(same(journal));
        }
    }

    @Test
    void lifecycle_createsAddsBalancesPostsAndRejectsLateEntry() {
        UUID journalId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Account account = activeAccount(accountId);
        when(journalRepository.existsByExternalRef("EXT-1")).thenReturn(false);
        when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> {
            Journal saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(journalId);
            }
            return saved;
        });

        Journal journal = service.createJournal("Transfer", "EXT-1");
        when(journalRepository.findById(journalId)).thenReturn(Optional.of(journal));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(ledgerEntryRepository.distinctCurrenciesForJournal(journalId))
                .thenReturn(List.of(), List.of("USD"));
        when(ledgerEntryRepository.save(any(LedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerEntryRepository.netAmountForJournal(journalId)).thenReturn(0L, 0L);
        when(ledgerEntryRepository.totalDebits(journalId)).thenReturn(10_000L);
        when(ledgerEntryRepository.totalCredits(journalId)).thenReturn(10_000L);

        LedgerEntry debit = service.addEntry(journalId, accountId, "debit", "USD", 10_000L);
        LedgerEntry credit = service.addEntry(journalId, accountId, "credit", "usd", 10_000L);

        assertThat(service.isBalanced(journalId)).isTrue();
        Journal posted = service.postJournal(journalId);

        assertThat(posted).isSameAs(journal);
        assertThat(posted.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(posted.getPostedAt()).isNotNull();
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(debit, credit);
        assertThat(captor.getAllValues()).extracting(LedgerEntry::getSide).containsExactly("debit", "credit");
        assertThat(captor.getAllValues()).extracting(LedgerEntry::getAmountCents)
                .containsExactly(10_000L, 10_000L);
        assertThat(captor.getAllValues()).extracting(LedgerEntry::getCurrency).containsExactly("USD", "USD");
        assertThat(captor.getAllValues()).allSatisfy(entry -> {
            assertThat(entry.getJournal()).isSameAs(journal);
            assertThat(entry.getAccount()).isSameAs(account);
        });

        assertThatThrownBy(() -> service.addEntry(journalId, accountId, "debit", "USD", 1L))
                .satisfies(thrown -> assertBankingException(thrown, JournalNotPendingException.class,
                        ErrorCode.JOURNAL_NOT_PENDING, Map.of("journalId", journalId.toString())));
        verify(ledgerEntryRepository, times(2)).save(any(LedgerEntry.class));
        verify(accountRepository, times(2)).findById(accountId);
    }

    private void stubAddEntryPrerequisites(Journal journal, Account account) {
        when(journalRepository.findById(journal.getId())).thenReturn(Optional.of(journal));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
    }

    private static Journal pendingJournal(UUID id) {
        Journal journal = new Journal("desc", "ref");
        journal.setId(id);
        journal.setStatus(JournalStatus.PENDING);
        return journal;
    }

    private static Account activeAccount(UUID id) {
        Account account = new Account(null, "checking", "USD");
        account.setId(id);
        return account;
    }

    private static void assertBankingException(Throwable thrown,
            Class<? extends BankingException> exceptionType, ErrorCode errorCode, Map<String, ?> metadata) {
        assertThat(thrown).isInstanceOf(exceptionType);
        BankingException exception = (BankingException) thrown;
        assertThat(exception.getCode()).isEqualTo(errorCode);
        assertThat(exception.getMetadata()).containsExactlyInAnyOrderEntriesOf(metadata);
    }
}
