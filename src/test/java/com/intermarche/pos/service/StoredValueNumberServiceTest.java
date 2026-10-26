package com.intermarche.pos.service;

import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.payment.StoredValue;
import com.intermarche.pos.domain.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StoredValueNumberService} (BO-03-06-30 to -38),
 * targeting 100% branch coverage.
 *
 * <p>The issuing range, the registry and the store row are read through mocked
 * static finders. Branch enumeration: no range, a range composing a free
 * number, a composed number already taken, both kinds; a range without length,
 * a prefix longer than the code, a field inside the prefix, a field beyond the
 * code; every role, with and without what it prints; the helpers on null,
 * empty, dashed and undashed input.
 */
class StoredValueNumberServiceTest {

    /**
     * A service on register POS01.
     *
     * @return the service
     */
    private StoredValueNumberService service() {
        StoredValueNumberService service = new StoredValueNumberService();
        service.terminalId = "POS01";
        return service;
    }

    /**
     * An issued credit note.
     *
     * @return the instrument
     */
    private StoredValue note() {
        StoredValue note = new StoredValue();
        note.id = 7L;
        note.kind = StoredValue.Kind.CREDIT_NOTE;
        note.initialAmount = new BigDecimal("12.40");
        note.issuedAt = LocalDateTime.of(2026, 10, 24, 14, 5, 9);
        note.validUntil = LocalDateTime.of(2027, 10, 24, 14, 5, 9);
        return note;
    }

    /**
     * A field of a range.
     *
     * @param role the role
     * @param offset the offset
     * @param length the length
     * @param format the date layout, or null
     * @return the field
     */
    private CouponField field(CouponField.Role role, int offset, int length, CouponField.DateFormat format) {
        CouponField field = new CouponField();
        field.role = role;
        field.offsetPosition = offset;
        field.fieldLength = length;
        field.dateFormat = format;
        return field;
    }

    /**
     * A range of the given length and prefix.
     *
     * @param length the code length
     * @param prefix the prefix, or null
     * @param fields the fields
     * @return the range
     */
    private CouponType range(Integer length, String prefix, CouponField... fields) {
        CouponType range = new CouponType();
        range.codeLength = length;
        range.prefix = prefix;
        range.fields = new java.util.ArrayList<>(java.util.List.of(fields));
        return range;
    }

    /**
     * Mocks the store row read by the store fields.
     *
     * @param panache the static mock
     * @param code the store code, or null for no row
     */
    @SuppressWarnings("unchecked")
    private void store(MockedStatic<PanacheEntityBase> panache, String code) {
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        Store store = code == null ? null : new Store();
        if (store != null) {
            store.code = code;
        }
        when(query.firstResult()).thenReturn(store);
        panache.when(PanacheEntityBase::findAll).thenReturn(query);
    }

    /**
     * Without an issuing range the historical numbers are kept, for both kinds.
     */
    @Test
    void withoutARangeTheHistoricalNumberIsKept() {
        try (MockedStatic<CouponType> types = mockStatic(CouponType.class)) {
            types.when(() -> CouponType.findIssuingRange(any())).thenReturn(null);
            assertEquals("297000000000007", service().numberOf(note(), "POS01-00000003"));
            StoredValue card = note();
            card.kind = StoredValue.Kind.GIFT_CARD;
            assertEquals("296000000000007", service().numberOf(card, "POS01-00000003"));
        }
    }

    /**
     * An issuing range composes the number from its fields: register, ticket
     * number, time, dates, amount, sequence, store and its check; a number the
     * registry already holds falls back to the historical one.
     */
    @Test
    void aRangeComposesTheNumber() {
        CouponType range = range(40, "98",
                field(CouponField.Role.TPV_NUMBER, 2, 3, null),
                field(CouponField.Role.TICKET_NUMBER, 5, 6, null),
                field(CouponField.Role.TIME, 11, 6, CouponField.DateFormat.HHMMSS),
                field(CouponField.Role.DATE_START, 17, 6, CouponField.DateFormat.DDMMYY),
                field(CouponField.Role.DATE_END, 23, 4, CouponField.DateFormat.MMDD),
                field(CouponField.Role.PRICE, 27, 5, null),
                field(CouponField.Role.SEQUENCE_NUMBER, 32, 4, null),
                field(CouponField.Role.STORE_NUMBER, 36, 3, null),
                field(CouponField.Role.STORE_CHECK_DIGIT, 39, 1, null),
                field(CouponField.Role.QUANTITY, 0, 0, null));
        try (MockedStatic<CouponType> types = mockStatic(CouponType.class);
             MockedStatic<StoredValue> registry = mockStatic(StoredValue.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            types.when(() -> CouponType.findIssuingRange("CREDIT_NOTE")).thenReturn(range);
            store(panache, "1234");
            registry.when(() -> StoredValue.findByNumber(any())).thenReturn(null);
            String composed = service().numberOf(note(), "POS01-00000003");
            assertEquals("9800100000314050924102610240124000072340", composed);
            registry.when(() -> StoredValue.findByNumber(composed)).thenReturn(new StoredValue());
            assertEquals("297000000000007", service().numberOf(note(), "POS01-00000003"));
        }
    }

    /**
     * A range that cannot hold the instrument composes nothing: no length, a
     * prefix longer than the code, a field inside the prefix, a field beyond it.
     */
    @Test
    void aRangeThatCannotHoldTheInstrumentComposesNothing() {
        StoredValueNumberService service = service();
        assertNull(service.compose(range(null, "9"), note(), "1"));
        assertNull(service.compose(range(0, "9"), note(), "1"));
        assertNull(service.compose(range(2, "123"), note(), "1"));
        assertNull(service.compose(range(10, "98", field(CouponField.Role.SEQUENCE_NUMBER, 1, 3, null)), note(), "1"));
        assertNull(service.compose(range(10, "98", field(CouponField.Role.SEQUENCE_NUMBER, 8, 3, null)), note(), "1"));
        assertEquals("0000000007", service.compose(range(10, null, field(CouponField.Role.SEQUENCE_NUMBER, 6, 4, null)),
                note(), "1"));
    }

    /**
     * A field with nothing to print leaves its zeros: no amount, no layout, no
     * date, no id, no store, no digits in the register or the document.
     */
    @Test
    void aFieldWithNothingToPrintLeavesItsZeros() {
        StoredValueNumberService service = new StoredValueNumberService();
        service.terminalId = "CAISSE";
        StoredValue bare = new StoredValue();
        assertNull(service.valueOf(field(CouponField.Role.PRICE, 0, 2, null), bare, null));
        assertNull(service.valueOf(field(CouponField.Role.TIME, 0, 4, null), note(), null));
        assertNull(service.valueOf(field(CouponField.Role.TIME, 0, 4, CouponField.DateFormat.HHMM), bare, null));
        assertNull(service.valueOf(field(CouponField.Role.DATE_START, 0, 6, null), note(), null));
        assertNull(service.valueOf(field(CouponField.Role.DATE_START, 0, 6, CouponField.DateFormat.DDMMYY), bare, null));
        assertNull(service.valueOf(field(CouponField.Role.DATE_END, 0, 6, null), note(), null));
        assertNull(service.valueOf(field(CouponField.Role.DATE_END, 0, 6, CouponField.DateFormat.DDMMYY), bare, null));
        assertNull(service.valueOf(field(CouponField.Role.SEQUENCE_NUMBER, 0, 3, null), bare, null));
        assertNull(service.valueOf(field(CouponField.Role.TPV_NUMBER, 0, 3, null), bare, null));
        assertNull(service.valueOf(field(CouponField.Role.TICKET_NUMBER, 0, 3, null), bare, null));
        assertEquals("1440", service.valueOf(field(CouponField.Role.TIME, 0, 4, CouponField.DateFormat.HHMM),
                timed(14, 40), null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            store(panache, null);
            assertNull(service.valueOf(field(CouponField.Role.STORE_NUMBER, 0, 3, null), bare, null));
            assertNull(service.valueOf(field(CouponField.Role.STORE_CHECK_DIGIT, 0, 1, null), bare, null));
        }
    }

    /**
     * A note issued at a given time.
     *
     * @param hour the hour
     * @param minute the minute
     * @return the note
     */
    private StoredValue timed(int hour, int minute) {
        StoredValue note = note();
        note.issuedAt = LocalDateTime.of(2026, 10, 24, hour, minute);
        return note;
    }

    /**
     * The helpers read digits, sequences, fit fields and compute the store check.
     */
    @Test
    void theHelpers() {
        assertNull(StoredValueNumberService.digits(null));
        assertNull(StoredValueNumberService.digits("ABC"));
        assertEquals("01", StoredValueNumberService.digits("POS01"));
        assertNull(StoredValueNumberService.sequenceOf(null));
        assertEquals("00000003", StoredValueNumberService.sequenceOf("POS01-00000003"));
        assertEquals("12", StoredValueNumberService.sequenceOf("R12"));
        assertEquals("0012", StoredValueNumberService.fit("12", 4));
        assertEquals("345", StoredValueNumberService.fit("12345", 3));
        assertNull(StoredValueNumberService.checkOf(null));
        assertEquals("0", StoredValueNumberService.checkOf("1234"));
        assertEquals("7", StoredValueNumberService.checkOf("16"));
    }
}
