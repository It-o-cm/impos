package com.intermarche.pos.imports;

import com.intermarche.pos.domain.payment.AccountCustomer;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountCustomerCsvResource}.
 * <p>
 * The importer's staged transaction machinery belongs to
 * {@link ImporterCsvResource} and is tested there; what is exercised here is
 * what this subclass adds — the full/delta decision, the blocking of the
 * accounts a full feed does not carry, and the reading of the free-field
 * reserve out of the file's header. Rows are built as plain {@link
 * ImporterCsvResource.LineData} against a hand-made header, and the one static
 * finder involved is intercepted with {@link org.mockito.Mockito#mockStatic}:
 * no Quarkus context and no database is booted.
 * <p>
 * Branch enumeration (every arm exercised): {@code isFull} — null, another
 * word, {@code full} and {@code FULL} with spaces; {@code blockAbsentAccounts}
 * — the empty-set arm and the populated arm; {@code freeFields} — a column that
 * is not a free field, one whose slot is not a number, one whose slot is out of
 * the 1..99 range, a pair whose two cells are empty, a pair carrying only a
 * label, one carrying only a value, and the ordering by slot.
 */
class AccountCustomerCsvResourceTest {

    /** The resource under test. */
    private final AccountCustomerCsvResource resource = new AccountCustomerCsvResource();

    /**
     * Builds a row from a header and its cells, in the order given.
     *
     * @param columns the header names, in column order
     * @param cells the cells of the row, in the same order
     * @return the parsed row
     */
    private ImporterCsvResource.LineData row(List<String> columns, String... cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            header.put(columns.get(index), index);
        }
        return new ImporterCsvResource.LineData(1, header, cells,
                AccountCustomerCsvResource.COL_ACCOUNT_NUMBER);
    }

    /**
     * Nothing, or any other word, is the evening's delta.
     */
    @Test
    void anythingButFullIsADelta() {
        assertFalse(resource.isFull(null));
        assertFalse(resource.isFull(""));
        assertFalse(resource.isFull("delta"));
    }

    /**
     * The word {@code full} declares the feed complete, whatever its case and
     * its surrounding spaces.
     */
    @Test
    void theWordFullDeclaresACompleteFeed() {
        assertTrue(resource.isFull("full"));
        assertTrue(resource.isFull("  FULL "));
    }

    /**
     * A full feed BLOCKS the accounts it does not carry — it does not delete
     * them, because the invoices that name them outlive the account.
     */
    @Test
    void afullFeedBlocksTheAccountsItDoesNotCarry() {
        AccountCustomer absent = mock(AccountCustomer.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.list(
                            "blocked = false and accountNumber not in ?1", Set.of("A1")))
                    .thenReturn(List.of(absent));
            assertEquals(1L, resource.blockAbsentAccounts(Set.of("A1")));
        }
        assertTrue(absent.blocked);
        verify(absent).persist();
    }

    /**
     * An EMPTY full feed blocks every account: the {@code not in ()} a bound
     * empty collection would produce is rejected at run time by the database,
     * so the query without the clause is the one that is issued.
     */
    @Test
    void anEmptyFullFeedBlocksEveryAccount() {
        AccountCustomer absent = mock(AccountCustomer.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.list("blocked = false"))
                    .thenReturn(List.of(absent));
            assertEquals(1L, resource.blockAbsentAccounts(Set.of()));
        }
        verify(absent).persist();
    }

    /**
     * A feed where nothing left the population writes nothing.
     */
    @Test
    void afullFeedCarryingEveryAccountWritesNothing() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.list(
                            "blocked = false and accountNumber not in ?1", Set.of("A1")))
                    .thenReturn(List.of());
            assertEquals(0L, resource.blockAbsentAccounts(Set.of("A1")));
        }
    }

    /**
     * The free fields are read out of the header, in slot order, whatever the
     * order the columns are declared in.
     */
    @Test
    void theFreeFieldsAreReadInSlotOrder() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                "FREE_2_LABEL", "FREE_2_VALUE", "FREE_1_LABEL", "FREE_1_VALUE");
        List<AccountCustomer.FreeField> fields =
                resource.freeFields(row(columns, "A1", "Tournée", "B", "Zone", "Nord"));
        assertEquals(2, fields.size());
        assertEquals(1, fields.get(0).slot);
        assertEquals("Zone", fields.get(0).label);
        assertEquals("Nord", fields.get(0).value);
        assertEquals(2, fields.get(1).slot);
        assertEquals("Tournée", fields.get(1).label);
        assertEquals("B", fields.get(1).value);
    }

    /**
     * A pair whose two cells are empty is NOT a field: a feed declaring the
     * ninety-nine columns for every customer would otherwise give each of them
     * ninety-nine empty rows. A pair carrying only one of the two IS one.
     */
    @Test
    void anEmptyPairIsNotAField() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                "FREE_1_LABEL", "FREE_1_VALUE", "FREE_2_LABEL", "FREE_2_VALUE",
                "FREE_3_LABEL", "FREE_3_VALUE");
        List<AccountCustomer.FreeField> fields =
                resource.freeFields(row(columns, "A1", "", "", "Zone", "", "", "Nord"));
        assertEquals(2, fields.size());
        assertEquals(2, fields.get(0).slot);
        assertEquals("Zone", fields.get(0).label);
        assertEquals(3, fields.get(1).slot);
        assertEquals("Nord", fields.get(1).value);
    }

    /**
     * A column that is not a free-field pair is left alone, and so is one whose
     * slot is not a number or falls outside the reserve of ninety-nine.
     */
    @Test
    void onlyWellFormedSlotsAreRead() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                AccountCustomerCsvResource.COL_COMPANY_NAME, "FREE_2_VALUE",
                "FREE_X_LABEL", "FREE_0_LABEL", "FREE_100_LABEL", "FREE_5_LABEL");
        List<AccountCustomer.FreeField> fields =
                resource.freeFields(row(columns, "A1", "Acme", "B", "x", "zero", "cent", "Zone"));
        assertEquals(1, fields.size());
        assertEquals(5, fields.get(0).slot);
        assertEquals("Zone", fields.get(0).label);
    }

    /**
     * A file declaring no free-field column yields none.
     */
    @Test
    void afileWithoutFreeColumnsYieldsNone() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                AccountCustomerCsvResource.COL_COMPANY_NAME);
        assertTrue(resource.freeFields(row(columns, "A1", "Acme")).isEmpty());
    }

    /**
     * The chunk hook records the account numbers it was handed, which is what
     * lets a full feed know which accounts it did NOT carry.
     */
    @Test
    void theChunkHookRecordsTheNumbersItSees() {
        AccountCustomer known = mock(AccountCustomer.class);
        known.accountNumber = "A1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.list("accountNumber IN ?1", Set.of("A1")))
                    .thenReturn(List.of(known));
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(row(columns, "A1")), Set.of("A1"), new int[]{0, 0}, new java.util.ArrayList<>());
            assertEquals(1, map.size());
            assertEquals(known, map.get("A1"));
        }
    }

    /**
     * An empty chunk touches nothing at all.
     */
    @Test
    void anEmptyChunkTouchesNothing() {
        assertTrue(resource.processChunkWithFallback(List.of(), Set.of(),
                new int[]{0, 0}, new java.util.ArrayList<>()).isEmpty());
    }

    /**
     * The one-by-one fallback looks an account up by its number.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theFallbackFindsOneAccountByItsNumber() {
        AccountCustomer known = mock(AccountCustomer.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            io.quarkus.hibernate.orm.panache.PanacheQuery<AccountCustomer> query =
                    mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            when(query.firstResult()).thenReturn(known);
            panache.when(() -> AccountCustomer.find("accountNumber", "A1")).thenReturn(query);
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER);
            assertEquals(known, resource.findEntityForLine(row(columns, "A1")));
        }
    }

    /**
     * Runs {@link AccountCustomerCsvResource#processLineLogic} on a row whose
     * key is absent from the entity map, capturing the account the create arm
     * persists through the entity manager.
     *
     * @param data the parsed row the create arm feeds and persists
     * @return the freshly created account handed to {@code persist}
     */
    private AccountCustomer createdFrom(ImporterCsvResource.LineData data) {
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager entityManager = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(entityManager);
            int[] counters = new int[]{0, 0};
            resource.processLineLogic(data, new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<AccountCustomer> captor = ArgumentCaptor.forClass(AccountCustomer.class);
            verify(entityManager).persist(captor.capture());
            return captor.getValue();
        }
    }

    /**
     * A FULL feed, once imported, blocks the accounts it did not carry: the
     * complete-population arm of {@code importCustomers} is taken and an empty
     * feed asks the database for every unblocked account.
     */
    @Test
    void afullImportBlocksTheAbsentAccounts() {
        AccountCustomer absent = mock(AccountCustomer.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.list("blocked = false")).thenReturn(List.of(absent));
            ByteArrayInputStream stream = new ByteArrayInputStream(
                    "ACCOUNT_NUMBER|COMPANY_NAME".getBytes(StandardCharsets.UTF_8));
            Response response = resource.importCustomers(stream, "full");
            assertEquals(200, response.getStatus());
            verify(absent).persist();
        }
    }

    /**
     * A DELTA feed leaves the rest of the population alone: the non-full arm of
     * {@code importCustomers} is taken and no account is ever blocked.
     */
    @Test
    void adeltaImportBlocksNothing() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ByteArrayInputStream stream = new ByteArrayInputStream(
                    "ACCOUNT_NUMBER|COMPANY_NAME".getBytes(StandardCharsets.UTF_8));
            Response response = resource.importCustomers(stream, "delta");
            assertEquals(200, response.getStatus());
            panache.verifyNoInteractions();
        }
    }

    /**
     * An account whose number is unknown to the chunk is CREATED: the row is
     * fed onto a fresh account, the created counter grows and the account is
     * persisted through the entity manager. A row carrying no balance and no
     * due date leaves the balance at zero and the date unset, and the fresh
     * account already owns its address so the address is not replaced.
     */
    @Test
    void anUnknownNumberCreatesTheAccount() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                AccountCustomerCsvResource.COL_COMPANY_NAME);
        AccountCustomer created = createdFrom(row(columns, "NEW", "Acme"));
        assertEquals("NEW", created.accountNumber);
        assertEquals("Acme", created.companyName);
        assertEquals(AccountCustomer.Origin.INTEGRATED, created.origin);
        assertEquals(BigDecimal.ZERO, created.creditBalance);
        assertNull(created.dueDate);
        assertNotNull(created.address);
    }

    /**
     * A created account carrying a balance keeps that balance rather than the
     * zero default, exercising the non-null arm of the balance ternary; an
     * EMPTY due-date cell reads as no date, taking the empty arm of the date
     * guard.
     */
    @Test
    void acreatedAccountKeepsItsBalanceAndIgnoresAnEmptyDate() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                AccountCustomerCsvResource.COL_COMPANY_NAME,
                AccountCustomerCsvResource.COL_CREDIT_BALANCE,
                AccountCustomerCsvResource.COL_DUE_DATE,
                AccountCustomerCsvResource.COL_BLOCKED);
        AccountCustomer created = createdFrom(
                row(columns, "NEW", "Acme", "100.50", "", "true"));
        assertEquals(new BigDecimal("100.50"), created.creditBalance);
        assertNull(created.dueDate);
        assertTrue(created.blocked);
    }

    /**
     * An unreadable due date counts as none: the created account carries no
     * date, exercising the catch arm of the date parser.
     */
    @Test
    void anUnreadableDueDateIsIgnored() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                AccountCustomerCsvResource.COL_COMPANY_NAME,
                AccountCustomerCsvResource.COL_DUE_DATE);
        AccountCustomer created = createdFrom(row(columns, "NEW", "Acme", "not-a-date"));
        assertNull(created.dueDate);
    }

    /**
     * A known account with NO stored checksum is rewritten: the null-checksum
     * arm of the change guard is taken, the update counter grows, a readable
     * due date is parsed and a null address is replaced with a fresh one filled
     * from the row.
     */
    @Test
    void aknownAccountWithoutChecksumIsRewritten() {
        AccountCustomer found = new AccountCustomer();
        found.id = 5L;
        found.accountNumber = "A1";
        found.address = null;
        found.checksum = null;
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("A1", found);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(5L)).thenReturn(found);
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                    AccountCustomerCsvResource.COL_COMPANY_NAME,
                    AccountCustomerCsvResource.COL_STREET_LINE1,
                    AccountCustomerCsvResource.COL_CREDIT_BALANCE,
                    AccountCustomerCsvResource.COL_DUE_DATE);
            int[] counters = new int[]{0, 0};
            resource.processLineLogic(
                    row(columns, "A1", "Acme", "1 rue", "100.50", "2025-01-01"),
                    entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Acme", found.companyName);
            assertNotNull(found.address);
            assertEquals("1 rue", found.address.streetLine1);
            assertEquals(new BigDecimal("100.50"), found.creditBalance);
            assertEquals(LocalDate.of(2025, 1, 1), found.dueDate);
        }
    }

    /**
     * A known account whose stored checksum already matches the incoming row is
     * left untouched: the equal-checksum arm is taken and the update counter
     * does not grow the second time.
     */
    @Test
    void anUnchangedKnownAccountIsNotRewritten() {
        AccountCustomer found = new AccountCustomer();
        found.id = 5L;
        found.accountNumber = "A1";
        found.checksum = null;
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("A1", found);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(5L)).thenReturn(found);
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                    AccountCustomerCsvResource.COL_COMPANY_NAME);
            ImporterCsvResource.LineData data = row(columns, "A1", "Acme");
            int[] counters = new int[]{0, 0};
            resource.processLineLogic(data, entityMap, counters);
            assertEquals(1, counters[1]);
            found.checksum = found.getChecksum();
            resource.processLineLogic(data, entityMap, counters);
            assertEquals(1, counters[1]);
        }
    }

    /**
     * A known account whose stored checksum differs from the incoming row is
     * rewritten: the not-equal arm of the change guard is taken even though the
     * stored checksum is not null.
     */
    @Test
    void aknownAccountWithADifferentChecksumIsRewritten() {
        AccountCustomer found = new AccountCustomer();
        found.id = 5L;
        found.accountNumber = "A1";
        found.checksum = null;
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("A1", found);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(5L)).thenReturn(found);
            List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                    AccountCustomerCsvResource.COL_COMPANY_NAME);
            ImporterCsvResource.LineData data = row(columns, "A1", "Acme");
            int[] counters = new int[]{0, 0};
            resource.processLineLogic(data, entityMap, counters);
            assertEquals(1, counters[1]);
            found.checksum = found.getChecksum() + 1;
            resource.processLineLogic(data, entityMap, counters);
            assertEquals(2, counters[1]);
        }
    }

    /**
     * A free-field pair whose two cells are BOTH absent from the row — the
     * header declares the columns but the line is short of them — yields no
     * field, exercising the null arms of both emptiness tests.
     */
    @Test
    void afreePairOfNullCellsIsNotAField() {
        List<String> columns = List.of(AccountCustomerCsvResource.COL_ACCOUNT_NUMBER,
                "FREE_1_LABEL", "FREE_1_VALUE");
        assertTrue(resource.freeFields(row(columns, "A1")).isEmpty());
    }
}
