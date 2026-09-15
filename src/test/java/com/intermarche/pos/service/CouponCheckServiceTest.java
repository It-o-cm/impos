package com.intermarche.pos.service;

import com.intermarche.pos.domain.barcode.AlertLevel;
import com.intermarche.pos.domain.barcode.CouponControl;
import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.barcode.CouponScan;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.util.DateTimeProvider;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CouponCheckService}, targeting 100% branch coverage.
 * <p>
 * The register's day is pinned through {@link DateTimeProvider}, the store row
 * and the scan ledger are reached through Panache static finders (resolved to
 * {@link PanacheEntityBase} under plain {@code mvn test} and intercepted with
 * {@link org.mockito.Mockito#mockStatic}), and the row the ledger inserts is
 * neutralised with {@link org.mockito.Mockito#mockConstruction}. No database,
 * no Quarkus boot.
 * <p>
 * Branch enumeration (every leg exercised): {@code check} covers the null range
 * and null code legs, and for EACH control the unadministered arm, the missing
 * position arm, the unreadable value arm and both sides of its comparison at
 * the boundary; {@code addOtherStore} additionally covers the absent store row,
 * the blank store code, the zero-padded match and the non-numeric fallback both
 * ways; {@code addStoreCheck} covers the missing number, the missing key, the
 * unreadable characters on each side and the matching key; {@code record}
 * covers its three refusal legs and the sequence / whole-code identity arms;
 * {@code worst} and {@code message} cover the null list, the silent list and
 * the sternest-wins case.
 */
class CouponCheckServiceTest {

    /** The service under test. */
    private final CouponCheckService service = new CouponCheckService();

    /** The day the register is pinned to for every test. */
    private static final LocalDateTime TODAY = LocalDateTime.of(2026, 9, 14, 10, 30);

    /**
     * Pins the register's day so the date controls are deterministic.
     */
    @BeforeEach
    void pinTheDay() {
        DateTimeProvider.setFixedDateTime(TODAY);
    }

    /**
     * Releases the pinned day, which is process-wide.
     */
    @AfterEach
    void releaseTheDay() {
        DateTimeProvider.clear();
    }

    /**
     * Builds an administered range with no control at all.
     *
     * @param codeLength the total code length
     * @return the configured range
     */
    private CouponType range(int codeLength) {
        CouponType type = new CouponType();
        type.code = "RANGE";
        type.codeLength = codeLength;
        type.codeKind = CouponField.Kind.NUMERIC;
        type.amountSource = CouponType.AmountSource.MANUAL;
        type.fields = new ArrayList<>();
        type.controls = new ArrayList<>();
        return type;
    }

    /**
     * Adds an administered position to a range.
     *
     * @param type the range
     * @param role the role the field carries
     * @param offset the zero-based start position
     * @param length the number of characters
     * @return the added field, so the caller can refine it
     */
    private CouponField field(CouponType type, CouponField.Role role, int offset, int length) {
        CouponField f = new CouponField();
        f.couponType = type;
        f.role = role;
        f.offsetPosition = offset;
        f.fieldLength = length;
        f.kind = CouponField.Kind.NUMERIC;
        type.fields.add(f);
        return f;
    }

    /**
     * Adds an administered control to a range.
     *
     * @param type the range
     * @param kind the control kind
     * @param level the reaction the shop administers
     * @return the added control
     */
    private CouponControl control(CouponType type, CouponControl.Kind kind, AlertLevel level) {
        CouponControl c = new CouponControl();
        c.couponType = type;
        c.kind = kind;
        c.level = level;
        type.controls.add(c);
        return c;
    }

    /**
     * Runs the checks with no store row and no ledger, which is the context of
     * every control but the shop one and the duplicate one.
     *
     * @param type the range
     * @param code the scanned code
     * @param ticketTotal the running ticket total, or null
     * @return the findings
     */
    private List<CouponCheckService.Finding> check(CouponType type, String code,
                                                   BigDecimal ticketTotal) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubStore(panache, null);
            stubLedger(panache, false);
            return service.check(type, code, ticketTotal);
        }
    }

    /**
     * Stubs the register's own store row.
     *
     * @param panache the active static mock
     * @param code the store code to resolve, or null for a register holding no row
     */
    @SuppressWarnings("unchecked")
    private void stubStore(MockedStatic<PanacheEntityBase> panache, String code) {
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        Store store = null;
        if (code != null) {
            store = new Store();
            store.code = code;
        }
        when(query.firstResult()).thenReturn(store);
        panache.when(PanacheEntityBase::findAll).thenReturn(query);
    }

    /**
     * Stubs the scan ledger.
     *
     * @param panache the active static mock
     * @param seen whether the ledger already holds the identity
     */
    private void stubLedger(MockedStatic<PanacheEntityBase> panache, boolean seen) {
        panache.when(() -> PanacheEntityBase.count(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(seen ? 1L : 0L);
    }

    /**
     * Asserts that the findings hold exactly one, of the expected control.
     *
     * @param findings the findings
     * @param kind the control expected to have fired
     * @param message the wording expected
     */
    private void assertOnly(List<CouponCheckService.Finding> findings,
                            CouponControl.Kind kind, String message) {
        Assertions.assertEquals(1, findings.size(), findings.toString());
        Assertions.assertEquals(kind, findings.get(0).kind);
        Assertions.assertEquals(message, findings.get(0).message);
    }

    // ------------------------------------------------------------- guards

    /**
     * check finds nothing without a range (first leg of the guard).
     */
    @Test
    void checkWithoutARangeFindsNothing() {
        Assertions.assertTrue(service.check(null, "123", BigDecimal.TEN).isEmpty());
    }

    /**
     * check finds nothing without a code (second leg of the guard).
     */
    @Test
    void checkWithoutACodeFindsNothing() {
        Assertions.assertTrue(service.check(range(10), null, BigDecimal.TEN).isEmpty());
    }

    /**
     * A range carrying no control is silent, whatever it holds — which is what
     * keeps an unadministered register behaving as it did.
     */
    @Test
    void aRangeWithoutControlsIsSilent() {
        CouponType type = range(13);
        field(type, CouponField.Role.DATE_END, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        field(type, CouponField.Role.STORE_NUMBER, 9, 4);
        Assertions.assertTrue(check(type, "2980101260101", BigDecimal.ZERO).isEmpty());
    }

    // ------------------------------------------------------ validity dates

    /**
     * The expiry control fires on a date already past.
     */
    @Test
    void expiredFiresOnAPastDate() {
        CouponType type = range(9);
        field(type, CouponField.Role.DATE_END, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        control(type, CouponControl.Kind.EXPIRED, AlertLevel.BLOCK);
        assertOnly(check(type, "298130926", BigDecimal.ZERO),
                CouponControl.Kind.EXPIRED, "BON EXPIRE");
    }

    /**
     * The expiry control stays silent on the printed day itself (boundary): the
     * day named on the paper is a valid day.
     */
    @Test
    void expiredIsSilentOnTheDayItself() {
        CouponType type = range(9);
        field(type, CouponField.Role.DATE_END, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        control(type, CouponControl.Kind.EXPIRED, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "298140926", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The expiry control stays silent on a date still ahead.
     */
    @Test
    void expiredIsSilentOnAFutureDate() {
        CouponType type = range(9);
        field(type, CouponField.Role.DATE_END, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        control(type, CouponControl.Kind.EXPIRED, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "298150926", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The not-yet-valid control fires on a start date still ahead.
     */
    @Test
    void notYetValidFiresOnAFutureStart() {
        CouponType type = range(9);
        field(type, CouponField.Role.DATE_START, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        control(type, CouponControl.Kind.NOT_YET_VALID, AlertLevel.INFO);
        assertOnly(check(type, "298150926", BigDecimal.ZERO),
                CouponControl.Kind.NOT_YET_VALID, "BON PAS ENCORE VALABLE");
    }

    /**
     * The not-yet-valid control stays silent from the start day on (boundary).
     */
    @Test
    void notYetValidIsSilentOnTheStartDayItself() {
        CouponType type = range(9);
        field(type, CouponField.Role.DATE_START, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        control(type, CouponControl.Kind.NOT_YET_VALID, AlertLevel.INFO);
        Assertions.assertTrue(check(type, "298140926", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The expiry control reads the date through the layout the POSITION
     * administers: under MMDDYY the very same characters name a day still
     * ahead, so the coupon passes where DDMMYY would have refused it
     * (BO-03-06-19).
     */
    @Test
    void expiredReadsTheAdministeredLayout() {
        CouponType underMmddyy = range(9);
        field(underMmddyy, CouponField.Role.DATE_END, 3, 6).dateFormat =
                CouponField.DateFormat.MMDDYY;
        control(underMmddyy, CouponControl.Kind.EXPIRED, AlertLevel.BLOCK);
        // 09/30/26 — the end of this month, still ahead.
        Assertions.assertTrue(check(underMmddyy, "298093026", BigDecimal.ZERO).isEmpty());
        CouponType underDdmmyy = range(9);
        field(underDdmmyy, CouponField.Role.DATE_END, 3, 6).dateFormat =
                CouponField.DateFormat.DDMMYY;
        control(underDdmmyy, CouponControl.Kind.EXPIRED, AlertLevel.BLOCK);
        // The same characters read as 09/30/26 — the thirtieth month, no day at all.
        Assertions.assertTrue(check(underDdmmyy, "298093026", BigDecimal.ZERO).isEmpty());
        CouponType real = range(9);
        field(real, CouponField.Role.DATE_END, 3, 6).dateFormat = CouponField.DateFormat.MMDDYY;
        control(real, CouponControl.Kind.EXPIRED, AlertLevel.BLOCK);
        // 08/31/26 — last month, expired under the administered layout.
        assertOnly(check(real, "298083126", BigDecimal.ZERO),
                CouponControl.Kind.EXPIRED, "BON EXPIRE");
    }

    /**
     * A date control administered on a range holding no such position finds
     * nothing (missing position arm).
     */
    @Test
    void aDateControlWithoutItsPositionFindsNothing() {
        CouponType type = range(9);
        control(type, CouponControl.Kind.EXPIRED, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "298130926", BigDecimal.ZERO).isEmpty());
    }

    /**
     * A date control whose position carries no readable date finds nothing
     * (unreadable value arm).
     */
    @Test
    void aDateControlOnAnUnreadableDateFindsNothing() {
        CouponType type = range(9);
        field(type, CouponField.Role.DATE_END, 3, 6);
        control(type, CouponControl.Kind.EXPIRED, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "298130926", BigDecimal.ZERO).isEmpty());
    }

    // --------------------------------------------------------- other store

    /**
     * The shop control fires on a code naming another point of sale.
     */
    @Test
    void otherStoreFiresOnAForeignShopNumber() {
        CouponType type = range(8);
        field(type, CouponField.Role.STORE_NUMBER, 4, 4);
        control(type, CouponControl.Kind.OTHER_STORE, AlertLevel.BLOCK).message = "PAS D'ICI";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubStore(panache, "0101");
            stubLedger(panache, false);
            assertOnly(service.check(type, "12340202", BigDecimal.ZERO),
                    CouponControl.Kind.OTHER_STORE, "PAS D'ICI");
        }
    }

    /**
     * The shop control accepts a zero-padded number naming this very shop.
     */
    @Test
    void otherStoreAcceptsAZeroPaddedMatch() {
        CouponType type = range(8);
        field(type, CouponField.Role.STORE_NUMBER, 4, 4);
        control(type, CouponControl.Kind.OTHER_STORE, AlertLevel.BLOCK);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubStore(panache, "101");
            stubLedger(panache, false);
            Assertions.assertTrue(service.check(type, "12340101", BigDecimal.ZERO).isEmpty());
        }
    }

    /**
     * The shop control compares characters when neither side is a number, and
     * accepts an equal one.
     */
    @Test
    void otherStoreFallsBackOnCharactersAndAccepts() {
        CouponType type = range(8);
        field(type, CouponField.Role.STORE_NUMBER, 4, 4).kind = CouponField.Kind.ALPHANUMERIC;
        control(type, CouponControl.Kind.OTHER_STORE, AlertLevel.BLOCK);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubStore(panache, "ab01");
            stubLedger(panache, false);
            Assertions.assertTrue(service.check(type, "1234AB01", BigDecimal.ZERO).isEmpty());
        }
    }

    /**
     * The shop control compares characters when neither side is a number, and
     * refuses a different one.
     */
    @Test
    void otherStoreFallsBackOnCharactersAndRefuses() {
        CouponType type = range(8);
        field(type, CouponField.Role.STORE_NUMBER, 4, 4).kind = CouponField.Kind.ALPHANUMERIC;
        control(type, CouponControl.Kind.OTHER_STORE, AlertLevel.BLOCK);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubStore(panache, "zz99");
            stubLedger(panache, false);
            assertOnly(service.check(type, "1234AB01", BigDecimal.ZERO),
                    CouponControl.Kind.OTHER_STORE, "BON D'UN AUTRE MAGASIN");
        }
    }

    /**
     * The shop control finds nothing on a register holding no store row.
     */
    @Test
    void otherStoreFindsNothingWithoutAStoreRow() {
        CouponType type = range(8);
        field(type, CouponField.Role.STORE_NUMBER, 4, 4);
        control(type, CouponControl.Kind.OTHER_STORE, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "12340202", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The shop control finds nothing when the register's store code is blank.
     */
    @Test
    void otherStoreFindsNothingOnABlankStoreCode() {
        CouponType type = range(8);
        field(type, CouponField.Role.STORE_NUMBER, 4, 4);
        control(type, CouponControl.Kind.OTHER_STORE, AlertLevel.BLOCK);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubStore(panache, "  ");
            stubLedger(panache, false);
            Assertions.assertTrue(service.check(type, "12340202", BigDecimal.ZERO).isEmpty());
        }
    }

    /**
     * The shop control finds nothing when the range administers no shop number
     * (missing position arm).
     */
    @Test
    void otherStoreWithoutItsPositionFindsNothing() {
        CouponType type = range(8);
        control(type, CouponControl.Kind.OTHER_STORE, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "12340202", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The shop control finds nothing when the code is too short to hold the
     * shop number (unreadable value arm).
     */
    @Test
    void otherStoreOnATooShortCodeFindsNothing() {
        CouponType type = range(8);
        field(type, CouponField.Role.STORE_NUMBER, 4, 4);
        control(type, CouponControl.Kind.OTHER_STORE, AlertLevel.BLOCK);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubStore(panache, "0101");
            stubLedger(panache, false);
            Assertions.assertTrue(service.check(type, "1234", BigDecimal.ZERO).isEmpty());
        }
    }

    // ---------------------------------------------------------- store key

    /**
     * The key control accepts a key equal to the unit of the digit sum.
     */
    @Test
    void storeCheckAcceptsTheRightKey() {
        CouponType type = range(6);
        field(type, CouponField.Role.STORE_NUMBER, 0, 4);
        field(type, CouponField.Role.STORE_CHECK_DIGIT, 4, 1);
        control(type, CouponControl.Kind.STORE_CHECK, AlertLevel.BLOCK);
        // 0 + 1 + 0 + 1 = 2
        Assertions.assertTrue(check(type, "010129", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The key control fires on a key that does not check out.
     */
    @Test
    void storeCheckFiresOnAWrongKey() {
        CouponType type = range(6);
        field(type, CouponField.Role.STORE_NUMBER, 0, 4);
        field(type, CouponField.Role.STORE_CHECK_DIGIT, 4, 1);
        control(type, CouponControl.Kind.STORE_CHECK, AlertLevel.BLOCK);
        assertOnly(check(type, "010179", BigDecimal.ZERO),
                CouponControl.Kind.STORE_CHECK, "CLE MAGASIN INCORRECTE");
    }

    /**
     * The key control fires when the shop number is not made of digits.
     */
    @Test
    void storeCheckFiresOnANonNumericShopNumber() {
        CouponType type = range(6);
        field(type, CouponField.Role.STORE_NUMBER, 0, 4).kind = CouponField.Kind.ALPHANUMERIC;
        field(type, CouponField.Role.STORE_CHECK_DIGIT, 4, 1);
        control(type, CouponControl.Kind.STORE_CHECK, AlertLevel.BLOCK);
        assertOnly(check(type, "A10129", BigDecimal.ZERO),
                CouponControl.Kind.STORE_CHECK, "CLE MAGASIN INCORRECTE");
    }

    /**
     * The key control fires when the key itself is not a number.
     */
    @Test
    void storeCheckFiresOnANonNumericKey() {
        CouponType type = range(6);
        field(type, CouponField.Role.STORE_NUMBER, 0, 4);
        field(type, CouponField.Role.STORE_CHECK_DIGIT, 4, 1).kind = CouponField.Kind.ALPHANUMERIC;
        control(type, CouponControl.Kind.STORE_CHECK, AlertLevel.BLOCK);
        assertOnly(check(type, "0101A9", BigDecimal.ZERO),
                CouponControl.Kind.STORE_CHECK, "CLE MAGASIN INCORRECTE");
    }

    /**
     * The key control finds nothing when the range administers no shop number
     * (first missing-position leg).
     */
    @Test
    void storeCheckWithoutTheShopNumberFindsNothing() {
        CouponType type = range(6);
        field(type, CouponField.Role.STORE_CHECK_DIGIT, 4, 1);
        control(type, CouponControl.Kind.STORE_CHECK, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "010179", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The key control finds nothing when the range administers no key (second
     * missing-position leg).
     */
    @Test
    void storeCheckWithoutTheKeyFindsNothing() {
        CouponType type = range(6);
        field(type, CouponField.Role.STORE_NUMBER, 0, 4);
        control(type, CouponControl.Kind.STORE_CHECK, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "010179", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The key control finds nothing when the code is too short to hold the key
     * (unreadable value arm).
     */
    @Test
    void storeCheckOnATooShortCodeFindsNothing() {
        CouponType type = range(6);
        field(type, CouponField.Role.STORE_NUMBER, 0, 4);
        field(type, CouponField.Role.STORE_CHECK_DIGIT, 4, 1);
        control(type, CouponControl.Kind.STORE_CHECK, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "0101", BigDecimal.ZERO).isEmpty());
    }

    // -------------------------------------------------- minimum ticket total

    /**
     * The minimum control fires on a ticket one cent below the figure printed
     * in the code (boundary).
     */
    @Test
    void minimumFiresOneCentBelow() {
        CouponType type = range(8);
        field(type, CouponField.Role.MIN_TICKET_TOTAL, 4, 4).decimals = 2;
        control(type, CouponControl.Kind.MIN_TICKET_TOTAL, AlertLevel.BLOCK);
        assertOnly(check(type, "12342000", new BigDecimal("19.99")),
                CouponControl.Kind.MIN_TICKET_TOTAL, "TOTAL TICKET INSUFFISANT");
    }

    /**
     * The minimum control accepts a ticket exactly equal to the figure
     * (boundary): the minimum is reached, not exceeded.
     */
    @Test
    void minimumAcceptsAnExactlyEqualTicket() {
        CouponType type = range(8);
        field(type, CouponField.Role.MIN_TICKET_TOTAL, 4, 4).decimals = 2;
        control(type, CouponControl.Kind.MIN_TICKET_TOTAL, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "12342000", new BigDecimal("20.00")).isEmpty());
    }

    /**
     * The minimum control accepts a ticket above the figure.
     */
    @Test
    void minimumAcceptsATicketAbove() {
        CouponType type = range(8);
        field(type, CouponField.Role.MIN_TICKET_TOTAL, 4, 4).decimals = 2;
        control(type, CouponControl.Kind.MIN_TICKET_TOTAL, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "12342000", new BigDecimal("20.01")).isEmpty());
    }

    /**
     * The minimum control treats an absent ticket as an empty one, so a
     * voucher requiring a minimum is refused before anything is rung up.
     */
    @Test
    void minimumTreatsAnAbsentTicketAsEmpty() {
        CouponType type = range(8);
        field(type, CouponField.Role.MIN_TICKET_TOTAL, 4, 4).decimals = 2;
        control(type, CouponControl.Kind.MIN_TICKET_TOTAL, AlertLevel.BLOCK);
        assertOnly(check(type, "12342000", null),
                CouponControl.Kind.MIN_TICKET_TOTAL, "TOTAL TICKET INSUFFISANT");
    }

    /**
     * The minimum control finds nothing when the range administers no minimum
     * (missing position arm).
     */
    @Test
    void minimumWithoutItsPositionFindsNothing() {
        CouponType type = range(8);
        control(type, CouponControl.Kind.MIN_TICKET_TOTAL, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "12342000", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The minimum control finds nothing when the figure cannot be read
     * (unreadable value arm).
     */
    @Test
    void minimumOnAnUnreadableFigureFindsNothing() {
        CouponType type = range(8);
        field(type, CouponField.Role.MIN_TICKET_TOTAL, 4, 4).kind = CouponField.Kind.ALPHANUMERIC;
        control(type, CouponControl.Kind.MIN_TICKET_TOTAL, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "1234AB00", BigDecimal.ZERO).isEmpty());
    }

    // ----------------------------------------------------------- duplicate

    /**
     * The duplicate control fires on a code the ledger already holds, compared
     * on its administered sequence number.
     */
    @Test
    void duplicateFiresOnACodeAlreadySeen() {
        CouponType type = range(10);
        field(type, CouponField.Role.SEQUENCE_NUMBER, 4, 6);
        control(type, CouponControl.Kind.DUPLICATE, AlertLevel.BLOCK);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubStore(panache, null);
            stubLedger(panache, true);
            assertOnly(service.check(type, "1234567890", BigDecimal.ZERO),
                    CouponControl.Kind.DUPLICATE, "BON DEJA UTILISE");
        }
    }

    /**
     * The duplicate control stays silent on a code the ledger does not hold,
     * a range without a sequence number being compared on its whole code.
     */
    @Test
    void duplicateIsSilentOnAFirstSighting() {
        CouponType type = range(10);
        control(type, CouponControl.Kind.DUPLICATE, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "1234567890", BigDecimal.ZERO).isEmpty());
    }

    // -------------------------------------------------------------- record

    /**
     * record ignores a null range (first leg of the guard).
     */
    @Test
    void recordWithoutARangeWritesNothing() {
        try (MockedConstruction<CouponScan> scans = mockConstruction(CouponScan.class)) {
            service.record(null, "1234567890");
            Assertions.assertTrue(scans.constructed().isEmpty());
        }
    }

    /**
     * record ignores a null code (second leg of the guard).
     */
    @Test
    void recordWithoutACodeWritesNothing() {
        try (MockedConstruction<CouponScan> scans = mockConstruction(CouponScan.class)) {
            service.record(range(10), null);
            Assertions.assertTrue(scans.constructed().isEmpty());
        }
    }

    /**
     * record ignores a blank code (third leg of the guard).
     */
    @Test
    void recordOnABlankCodeWritesNothing() {
        try (MockedConstruction<CouponScan> scans = mockConstruction(CouponScan.class)) {
            service.record(range(10), "   ");
            Assertions.assertTrue(scans.constructed().isEmpty());
        }
    }

    /**
     * record writes the administered identity and the numbers the code held.
     */
    @Test
    void recordWritesTheAdministeredIdentity() {
        CouponType type = range(12);
        field(type, CouponField.Role.SEQUENCE_NUMBER, 6, 6);
        field(type, CouponField.Role.TICKET_NUMBER, 2, 4);
        field(type, CouponField.Role.TPV_NUMBER, 0, 2);
        try (MockedConstruction<CouponScan> scans = mockConstruction(CouponScan.class)) {
            service.record(type, "030042123456");
            CouponScan written = scans.constructed().get(0);
            Assertions.assertEquals("030042123456", written.code);
            Assertions.assertEquals("RANGE", written.typeCode);
            Assertions.assertEquals("123456", written.identity);
            Assertions.assertEquals("0042", written.ticketNumber);
            Assertions.assertEquals("03", written.tpvNumber);
            Assertions.assertEquals(TODAY, written.scannedAt);
            verify(written, times(1)).persist();
        }
    }

    /**
     * record falls back on the whole code when the range administers no
     * sequence number (second arm of the identity choice).
     */
    @Test
    void recordFallsBackOnTheWholeCode() {
        try (MockedConstruction<CouponScan> scans = mockConstruction(CouponScan.class)) {
            service.record(range(10), "1234567890");
            CouponScan written = scans.constructed().get(0);
            Assertions.assertEquals("1234567890", written.identity);
            Assertions.assertNull(written.ticketNumber);
            Assertions.assertNull(written.tpvNumber);
        }
    }

    // ------------------------------------------------------ worst / message

    /**
     * worst is silence on a null list.
     */
    @Test
    void worstOfNullIsSilence() {
        Assertions.assertEquals(AlertLevel.NONE, service.worst(null));
    }

    /**
     * worst is silence on an empty list.
     */
    @Test
    void worstOfNothingIsSilence() {
        Assertions.assertEquals(AlertLevel.NONE, service.worst(List.of()));
    }

    /**
     * worst keeps the sternest finding and steps over a null entry.
     */
    @Test
    void worstKeepsTheSternest() {
        List<CouponCheckService.Finding> findings = new ArrayList<>();
        findings.add(null);
        findings.add(new CouponCheckService.Finding(
                CouponControl.Kind.EXPIRED, AlertLevel.INFO, "a"));
        findings.add(new CouponCheckService.Finding(
                CouponControl.Kind.DUPLICATE, AlertLevel.BLOCK, "b"));
        Assertions.assertEquals(AlertLevel.BLOCK, service.worst(findings));
    }

    /**
     * message is absent on a null list.
     */
    @Test
    void messageOfNullIsAbsent() {
        Assertions.assertNull(service.message(null));
    }

    /**
     * message is absent when nothing speaks, a null entry included.
     */
    @Test
    void messageIsAbsentWhenNothingSpeaks() {
        List<CouponCheckService.Finding> findings = new ArrayList<>();
        findings.add(null);
        findings.add(new CouponCheckService.Finding(
                CouponControl.Kind.EXPIRED, AlertLevel.NONE, "muet"));
        Assertions.assertNull(service.message(findings));
    }

    /**
     * message hands back the wording of the sternest finding, whichever order
     * the findings came in.
     */
    @Test
    void messageHandsBackTheSternestWording() {
        List<CouponCheckService.Finding> findings = List.of(
                new CouponCheckService.Finding(CouponControl.Kind.DUPLICATE, AlertLevel.BLOCK, "b"),
                new CouponCheckService.Finding(CouponControl.Kind.EXPIRED, AlertLevel.INFO, "a"));
        Assertions.assertEquals("b", service.message(findings));
        Assertions.assertEquals("b", service.message(List.of(findings.get(1), findings.get(0))));
    }

    /**
     * A finding renders its control and its level for the log.
     */
    @Test
    void findingRendersItselfForTheLog() {
        Assertions.assertEquals("EXPIRED/BLOCK", new CouponCheckService.Finding(
                CouponControl.Kind.EXPIRED, AlertLevel.BLOCK, "x").toString());
    }

    /**
     * Several controls can fire at once, and the findings come in control order.
     */
    @Test
    void severalControlsCanFireAtOnce() {
        CouponType type = range(9);
        field(type, CouponField.Role.DATE_END, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        field(type, CouponField.Role.MIN_TICKET_TOTAL, 3, 4).decimals = 2;
        control(type, CouponControl.Kind.EXPIRED, AlertLevel.INFO);
        control(type, CouponControl.Kind.MIN_TICKET_TOTAL, AlertLevel.BLOCK);
        List<CouponCheckService.Finding> findings = check(type, "298130926", BigDecimal.ZERO);
        Assertions.assertEquals(2, findings.size());
        Assertions.assertEquals(CouponControl.Kind.EXPIRED, findings.get(0).kind);
        Assertions.assertEquals(CouponControl.Kind.MIN_TICKET_TOTAL, findings.get(1).kind);
        Assertions.assertEquals(AlertLevel.BLOCK, service.worst(findings));
        Assertions.assertEquals("TOTAL TICKET INSUFFISANT", service.message(findings));
    }

    // --- Contrôles fidélité (BO-03-06-40) ---

    /**
     * Runs the checks with a loyalty card attached to the sale.
     *
     * @param type the range
     * @param code the scanned code
     * @param card the card attached to the sale, or null when none is
     * @return the findings
     */
    private List<CouponCheckService.Finding> checkWithCard(CouponType type, String code,
                                                           String card) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubStore(panache, null);
            stubLedger(panache, false);
            return service.check(type, code, null, card);
        }
    }

    /**
     * A range demanding a card refuses a sale carrying none, at the level the
     * shop administers, with the control's own wording.
     */
    @Test
    void aRangeDemandingACardRefusesASaleWithoutOne() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_REQUIRED, AlertLevel.BLOCK);
        List<CouponCheckService.Finding> findings = checkWithCard(type, "2990000000019", null);
        Assertions.assertEquals(1, findings.size());
        Assertions.assertEquals(CouponControl.Kind.FIDELITY_REQUIRED, findings.get(0).kind);
        Assertions.assertEquals(AlertLevel.BLOCK, findings.get(0).level);
        Assertions.assertEquals("CARTE FIDELITE REQUISE", findings.get(0).message);
    }

    /**
     * A SECOND administered level lands as itself — the level is read, not
     * hard-coded — and an administered wording replaces the control's own.
     */
    @Test
    void theAdministeredLevelAndWordingAreRead() {
        CouponType type = range(13);
        CouponControl c = control(type, CouponControl.Kind.FIDELITY_REQUIRED, AlertLevel.INFO);
        c.message = "PRESENTEZ LA CARTE";
        List<CouponCheckService.Finding> findings = checkWithCard(type, "2990000000019", null);
        Assertions.assertEquals(AlertLevel.INFO, findings.get(0).level);
        Assertions.assertEquals("PRESENTEZ LA CARTE", findings.get(0).message);
    }

    /**
     * The same range says nothing when a card IS attached (the card-present arm).
     */
    @Test
    void aRangeDemandingACardSaysNothingWhenOneIsAttached() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_REQUIRED, AlertLevel.BLOCK);
        Assertions.assertTrue(checkWithCard(type, "2990000000019", "3010000000123").isEmpty());
    }

    /**
     * A BLANK card is no card — the leg a null check alone would miss.
     */
    @Test
    void aBlankCardIsNoCard() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_REQUIRED, AlertLevel.BLOCK);
        Assertions.assertEquals(1, checkWithCard(type, "2990000000019", "   ").size());
    }

    /**
     * A range administering NO loyalty control says nothing, card or no card
     * (the silent arm).
     */
    @Test
    void aRangeWithoutALoyaltyControlSaysNothing() {
        CouponType type = range(13);
        Assertions.assertTrue(checkWithCard(type, "2990000000019", null).isEmpty());
        Assertions.assertTrue(checkWithCard(type, "2990000000019", "3010000000123").isEmpty());
    }

    /**
     * A control administered at NONE is silence, card or no card.
     */
    @Test
    void aLoyaltyControlAtNoneIsSilence() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_REQUIRED, AlertLevel.NONE);
        Assertions.assertTrue(checkWithCard(type, "2990000000019", null).isEmpty());
    }

    /**
     * The mismatch control compares the part of the card the code carries with
     * the card presented: a card holding that part passes, another is refused.
     */
    @Test
    void theCodeSaysWhichCardItWasIssuedFor() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_MISMATCH, AlertLevel.BLOCK);
        field(type, CouponField.Role.CARD_MATCH, 7, 6);
        // The code carries 000123 in positions 7..12.
        Assertions.assertTrue(checkWithCard(type, "2990000000123", "3010000000123").isEmpty());
        List<CouponCheckService.Finding> findings =
                checkWithCard(type, "2990000000123", "3010000000999");
        Assertions.assertEquals(1, findings.size());
        Assertions.assertEquals(CouponControl.Kind.FIDELITY_MISMATCH, findings.get(0).kind);
        Assertions.assertEquals("CARTE FIDELITE NON CONCORDANTE", findings.get(0).message);
    }

    /**
     * A SECOND administered position compares another part of the same code,
     * which is what proves the position is read rather than hard-coded.
     */
    @Test
    void aSecondAdministeredPositionComparesAnotherPart() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_MISMATCH, AlertLevel.BLOCK);
        field(type, CouponField.Role.CARD_MATCH, 0, 3);
        // Positions 0..2 hold 299, which this card does not carry.
        Assertions.assertEquals(1, checkWithCard(type, "2990000000123", "3010000000123").size());
        Assertions.assertTrue(checkWithCard(type, "2990000000123", "3012990000123").isEmpty());
    }

    /**
     * The mismatch control stays silent when NO card is presented: what is
     * wrong then is the absence, which the other control names.
     */
    @Test
    void theMismatchControlStaysSilentWithoutACard() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_MISMATCH, AlertLevel.BLOCK);
        field(type, CouponField.Role.CARD_MATCH, 7, 6);
        Assertions.assertTrue(checkWithCard(type, "2990000000123", null).isEmpty());
    }

    /**
     * The mismatch control stays silent when the range administers no position
     * carrying a card part: a code saying nothing about its card cannot
     * contradict one (the absent-position arm).
     */
    @Test
    void theMismatchControlStaysSilentWithoutAPosition() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_MISMATCH, AlertLevel.BLOCK);
        Assertions.assertTrue(checkWithCard(type, "2990000000123", "3010000000123").isEmpty());
    }

    /**
     * A position the code is too short to carry reads nothing, and the control
     * stays silent rather than refusing every card (the unreadable arm).
     */
    @Test
    void theMismatchControlStaysSilentOnAnUnreadablePosition() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_MISMATCH, AlertLevel.BLOCK);
        field(type, CouponField.Role.CARD_MATCH, 40, 6);
        Assertions.assertTrue(checkWithCard(type, "2990000000123", "3010000000123").isEmpty());
    }

    /**
     * The two loyalty controls are independent: a range may demand a card and
     * check its identity at once, and a sale without a card then raises the
     * absence alone.
     */
    @Test
    void theTwoLoyaltyControlsAreIndependent() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_REQUIRED, AlertLevel.BLOCK);
        control(type, CouponControl.Kind.FIDELITY_MISMATCH, AlertLevel.BLOCK);
        field(type, CouponField.Role.CARD_MATCH, 7, 6);
        List<CouponCheckService.Finding> absent =
                checkWithCard(type, "2990000000123", null);
        Assertions.assertEquals(1, absent.size());
        Assertions.assertEquals(CouponControl.Kind.FIDELITY_REQUIRED, absent.get(0).kind);
        List<CouponCheckService.Finding> wrong =
                checkWithCard(type, "2990000000123", "3010000000999");
        Assertions.assertEquals(1, wrong.size());
        Assertions.assertEquals(CouponControl.Kind.FIDELITY_MISMATCH, wrong.get(0).kind);
        Assertions.assertTrue(checkWithCard(type, "2990000000123", "3010000000123").isEmpty());
    }

    /**
     * The three-argument entry point keeps its meaning: no card, so a range
     * demanding one refuses — every existing caller is unchanged.
     */
    @Test
    void theThreeArgumentEntryPointMeansNoCard() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.FIDELITY_REQUIRED, AlertLevel.BLOCK);
        Assertions.assertEquals(1, check(type, "2990000000019", null).size());
    }

    // --- Heure portée par le code (BO-03-06-30/31/32) ---

    /**
     * Builds a range opening today, with the hour at positions 9..12 under the
     * given layout. The register's clock is pinned at 10:30.
     *
     * @param layout the administered hour layout
     * @return the range
     */
    private CouponType rangeOpeningTodayAt(CouponField.DateFormat layout) {
        CouponType type = range(13);
        field(type, CouponField.Role.DATE_START, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        CouponField time = field(type, CouponField.Role.TIME, 9, layout.getWidth());
        time.dateFormat = layout;
        control(type, CouponControl.Kind.NOT_YET_VALID, AlertLevel.BLOCK);
        return type;
    }

    /**
     * On the opening day itself, a code opening at 14:00 is refused at 10:30:
     * the administered hour, not the date alone, decides.
     */
    @Test
    void theAdministeredHourPostponesTheCodeOnItsOpeningDay() {
        CouponType type = rangeOpeningTodayAt(CouponField.DateFormat.HHMM);
        assertOnly(check(type, "2981409261400", BigDecimal.ZERO),
                CouponControl.Kind.NOT_YET_VALID, "BON PAS ENCORE VALABLE");
    }

    /**
     * A SECOND administered hour on the same day lets the code through, which
     * is what proves the hour is read rather than a literal.
     */
    @Test
    void aSecondAdministeredHourLetsTheCodeThrough() {
        CouponType type = rangeOpeningTodayAt(CouponField.DateFormat.HHMM);
        Assertions.assertTrue(check(type, "2981409260900", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The hour the clock has exactly reached opens the code (the boundary):
     * 10:30 at 10:30 passes.
     */
    @Test
    void theCodeOpensAtItsAdministeredHourExactly() {
        CouponType type = rangeOpeningTodayAt(CouponField.DateFormat.HHMM);
        Assertions.assertTrue(check(type, "2981409261030", BigDecimal.ZERO).isEmpty());
        // One minute later on the clock's side is still refused.
        Assertions.assertEquals(1, check(type, "2981409261031", BigDecimal.ZERO).size());
    }

    /**
     * The HHMMSS layout reads the seconds too, and the very same six characters
     * would name another hour under HHMM — the layout the POSITION administers
     * decides (BO-03-06-32).
     */
    @Test
    void theAdministeredLayoutDecidesHowTheHourIsRead() {
        CouponType seconds = rangeOpeningTodayAt(CouponField.DateFormat.HHMMSS);
        // 10:30:31 is one second past the pinned clock: still shut.
        Assertions.assertEquals(1, check(seconds, "298140926103031", BigDecimal.ZERO).size());
        // 10:29:59 is one second before: open.
        Assertions.assertTrue(check(seconds, "298140926102959", BigDecimal.ZERO).isEmpty());
    }

    /**
     * A range administering NO hour keeps the whole opening day open, whatever
     * the clock says (the absent-position arm).
     */
    @Test
    void aRangeWithoutAnHourOpensForTheWholeDay() {
        CouponType type = range(13);
        field(type, CouponField.Role.DATE_START, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        control(type, CouponControl.Kind.NOT_YET_VALID, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "2981409261400", BigDecimal.ZERO).isEmpty());
    }

    /**
     * An hour the code cannot carry reads nothing, and the code stays open
     * rather than being refused to every customer (the unreadable arm).
     */
    @Test
    void anUnreadableHourDoesNotShutTheCode() {
        CouponType type = range(13);
        field(type, CouponField.Role.DATE_START, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        CouponField time = field(type, CouponField.Role.TIME, 40, 4);
        time.dateFormat = CouponField.DateFormat.HHMM;
        control(type, CouponControl.Kind.NOT_YET_VALID, AlertLevel.BLOCK);
        Assertions.assertTrue(check(type, "2981409261400", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The hour refines the OPENING day only: on a day already past the start,
     * an hour still ahead changes nothing — the code opened yesterday.
     */
    @Test
    void theHourOnlyGovernsTheOpeningDay() {
        CouponType type = rangeOpeningTodayAt(CouponField.DateFormat.HHMM);
        // Start date 13/09/26, yesterday: the 14:00 hour is irrelevant.
        Assertions.assertTrue(check(type, "2981309261400", BigDecimal.ZERO).isEmpty());
    }

    /**
     * The hour does NOT govern the expiry bound: a code expiring today is
     * usable all day, as every paper voucher is.
     */
    @Test
    void theHourDoesNotCloseTheExpiryDayEarly() {
        CouponType type = range(13);
        field(type, CouponField.Role.DATE_END, 3, 6).dateFormat = CouponField.DateFormat.DDMMYY;
        CouponField time = field(type, CouponField.Role.TIME, 9, 4);
        time.dateFormat = CouponField.DateFormat.HHMM;
        control(type, CouponControl.Kind.EXPIRED, AlertLevel.BLOCK);
        // The hour carried is 14:00, still ahead of the pinned 10:30 clock: if
        // the expiry bound read it, the code would be refused this morning.
        Assertions.assertTrue(check(type, "2981409261400", BigDecimal.ZERO).isEmpty());
    }

    // --- Acceptation par îlot (BO-03-06-07) ---

    /**
     * Runs the checks with the given islands administered, this register being
     * the one the service is wired to.
     *
     * @param type the range
     * @param terminal the register's identifier
     * @param islands the active islands
     * @return the findings
     */
    private List<CouponCheckService.Finding> checkOnIsland(
            CouponType type, String terminal,
            List<com.intermarche.pos.domain.store.CheckoutIsland> islands) {
        service.terminalId = terminal;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubStore(panache, null);
            stubLedger(panache, false);
            panache.when(() -> com.intermarche.pos.domain.store.CheckoutIsland
                    .list("active = true order by code")).thenReturn(islands);
            return service.check(type, "2990000000019", null, null);
        }
    }

    /**
     * Builds an island holding the given registers.
     *
     * @param code the island code
     * @param terminals the raw semicolon list
     * @return the island
     */
    private com.intermarche.pos.domain.store.CheckoutIsland island(String code, String terminals) {
        com.intermarche.pos.domain.store.CheckoutIsland island =
                new com.intermarche.pos.domain.store.CheckoutIsland();
        island.code = code;
        island.terminalIds = terminals;
        return island;
    }

    /**
     * A range accepted on the island this register stands on passes.
     */
    @Test
    void aRangeAcceptedOnThisIslandPasses() {
        CouponType type = range(13);
        type.islandCodes = "AVANT;COMPTOIR";
        control(type, CouponControl.Kind.OTHER_ISLAND, AlertLevel.BLOCK);
        Assertions.assertTrue(checkOnIsland(type, "POS01",
                List.of(island("AVANT", "POS01;POS02"))).isEmpty());
    }

    /**
     * The SAME range refuses the same code on another island, which is what
     * proves the island is read rather than assumed.
     */
    @Test
    void theSameRangeIsRefusedOnAnotherIsland() {
        CouponType type = range(13);
        type.islandCodes = "COMPTOIR";
        control(type, CouponControl.Kind.OTHER_ISLAND, AlertLevel.BLOCK);
        List<CouponCheckService.Finding> findings = checkOnIsland(type, "POS01",
                List.of(island("AVANT", "POS01;POS02")));
        Assertions.assertEquals(1, findings.size());
        Assertions.assertEquals(CouponControl.Kind.OTHER_ISLAND, findings.get(0).kind);
        Assertions.assertEquals("BON REFUSE SUR CET ILOT", findings.get(0).message);
    }

    /**
     * A range naming NO island is accepted everywhere, island or not — what a
     * store that never split its lanes wants (the empty arm).
     */
    @Test
    void aRangeNamingNoIslandIsAcceptedEverywhere() {
        CouponType type = range(13);
        control(type, CouponControl.Kind.OTHER_ISLAND, AlertLevel.BLOCK);
        Assertions.assertTrue(checkOnIsland(type, "POS01", List.of()).isEmpty());
        type.islandCodes = "   ";
        Assertions.assertTrue(checkOnIsland(type, "POS01", List.of()).isEmpty());
    }

    /**
     * A range naming islands is refused on a register belonging to none: naming
     * an island is saying "here and nowhere else" (the no-island arm).
     */
    @Test
    void aRangeNamingIslandsIsRefusedOffAnyIsland() {
        CouponType type = range(13);
        type.islandCodes = "COMPTOIR";
        control(type, CouponControl.Kind.OTHER_ISLAND, AlertLevel.BLOCK);
        Assertions.assertEquals(1, checkOnIsland(type, "POS01", List.of()).size());
    }

    /**
     * A control administered at NONE is silence, wherever the register stands.
     */
    @Test
    void anIslandControlAtNoneIsSilence() {
        CouponType type = range(13);
        type.islandCodes = "COMPTOIR";
        Assertions.assertTrue(checkOnIsland(type, "POS01", List.of()).isEmpty());
        control(type, CouponControl.Kind.OTHER_ISLAND, AlertLevel.NONE);
        Assertions.assertTrue(checkOnIsland(type, "POS01", List.of()).isEmpty());
    }

    /**
     * The administered level and wording are read: a SECOND value lands as
     * itself.
     */
    @Test
    void theIslandControlLevelAndWordingAreRead() {
        CouponType type = range(13);
        type.islandCodes = "COMPTOIR";
        CouponControl c = control(type, CouponControl.Kind.OTHER_ISLAND, AlertLevel.INFO);
        c.message = "BON DU COMPTOIR";
        List<CouponCheckService.Finding> findings = checkOnIsland(type, "POS01", List.of());
        Assertions.assertEquals(AlertLevel.INFO, findings.get(0).level);
        Assertions.assertEquals("BON DU COMPTOIR", findings.get(0).message);
    }

    /**
     * The island list tolerates the spacing a hand-typed list carries.
     */
    @Test
    void theAdministeredIslandListIsTrimmed() {
        CouponType type = range(13);
        type.islandCodes = " COMPTOIR ; AVANT ";
        control(type, CouponControl.Kind.OTHER_ISLAND, AlertLevel.BLOCK);
        Assertions.assertTrue(checkOnIsland(type, "POS01",
                List.of(island("AVANT", "POS01"))).isEmpty());
    }
}
