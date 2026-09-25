package com.intermarche.pos.domain.barcode.gs1;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link Gs1Message}, driven straight through its package-private
 * constructor so every element shape — a known identifier, an identifier no version
 * knows (null), a decimal measure and a date — is placed under the register's fingers
 * with absolute control, and every branch of every accessor is walked on purpose.
 *
 * <p>The class is a pure value holder with no collaborators, so no Panache static and no
 * Mockito are needed: the constructor and the record {@link Gs1Element} suffice.
 */
class Gs1MessageTest {

    /** A known GTIN element carrying the article code, decimal-place digit excluded. */
    private static final Gs1Element GTIN =
            new Gs1Element("01", Gs1ApplicationIdentifier.of("01"), "09526000134367", 0);

    /** An element whose identifier no version knows, so {@code identifier()} is null. */
    private static final Gs1Element UNKNOWN =
            new Gs1Element("99", null, "ZZ", 0);

    /** A known net-weight element, three decimal places applied. */
    private static final Gs1Element WEIGHT =
            new Gs1Element("3103", Gs1ApplicationIdentifier.of("310"), "000195", 3);

    /** A known expiry-date element, {@code YYMMDD}. */
    private static final Gs1Element EXPIRY =
            new Gs1Element("17", Gs1ApplicationIdentifier.of("17"), "271231", 0);

    /**
     * The constructor keeps a non-null raw payload verbatim and copies the elements — the
     * non-null leg of the raw-payload ternary, observed through the copied element list.
     */
    @Test
    void aNonNullPayloadKeepsItsElements() {
        Gs1Message message = new Gs1Message("010952600013436710ABC123", List.of(GTIN));
        assertFalse(message.isEmpty());
    }

    /**
     * The constructor turns a null raw payload into an empty string rather than keeping
     * the null — the null leg of the raw-payload ternary; the elements are unaffected.
     */
    @Test
    void aNullPayloadIsAcceptedAndTheElementsKept() {
        Gs1Message message = new Gs1Message(null, List.of(GTIN));
        assertFalse(message.isEmpty());
        assertEquals("09526000134367", message.value("01"));
    }

    /**
     * The empty factory carries no element, which is how a non-GS1 payload reads.
     */
    @Test
    void theEmptyFactoryCarriesNoElement() {
        Gs1Message message = Gs1Message.empty("3560070123456");
        assertTrue(message.isEmpty());
    }

    /**
     * The empty factory tolerates a null payload, folding it to the empty string.
     */
    @Test
    void theEmptyFactoryToleratesANullPayload() {
        Gs1Message message = Gs1Message.empty(null);
        assertTrue(message.isEmpty());
    }

    /**
     * A message carrying elements is not empty — the false leg of {@code isEmpty}.
     */
    @Test
    void aMessageWithElementsIsNotEmpty() {
        Gs1Message message = new Gs1Message("x", List.of(GTIN));
        assertFalse(message.isEmpty());
    }

    /**
     * A message carrying no element is empty — the true leg of {@code isEmpty}.
     */
    @Test
    void aMessageWithoutElementsIsEmpty() {
        Gs1Message message = new Gs1Message("x", List.of());
        assertTrue(message.isEmpty());
    }

    /**
     * {@code get} skips an element whose identifier is null and returns the next one that
     * matches — the false leg of the {@code identifier() != null} guard, then its true
     * leg and the true leg of the code comparison, in one traversal.
     */
    @Test
    void getSkipsANullIdentifierAndReturnsTheMatch() {
        Gs1Message message = new Gs1Message("x", List.of(UNKNOWN, GTIN));
        assertEquals(GTIN, message.get("01"));
    }

    /**
     * {@code get} returns null when a known identifier does not match the code asked for
     * — the false leg of the code comparison and the loop's fall-through return.
     */
    @Test
    void getReturnsNullWhenNoKnownIdentifierMatches() {
        Gs1Message message = new Gs1Message("x", List.of(UNKNOWN, GTIN));
        assertNull(message.get("8005"));
    }

    /**
     * {@code get} on an empty message enters no iteration and returns null — the loop's
     * zero-trip leg.
     */
    @Test
    void getOnAnEmptyMessageReturnsNull() {
        Gs1Message message = new Gs1Message("x", List.of());
        assertNull(message.get("01"));
    }

    /**
     * {@code has} is true when an element carries the code — the non-null leg of its
     * guard.
     */
    @Test
    void hasIsTrueWhenTheCodeIsPresent() {
        Gs1Message message = new Gs1Message("x", List.of(GTIN));
        assertTrue(message.has("01"));
    }

    /**
     * {@code has} is false when no element carries the code — the null leg of its guard.
     */
    @Test
    void hasIsFalseWhenTheCodeIsAbsent() {
        Gs1Message message = new Gs1Message("x", List.of(GTIN));
        assertFalse(message.has("8005"));
    }

    /**
     * {@code value} returns the element's raw value when present — the non-null ternary
     * arm.
     */
    @Test
    void valueReturnsTheRawValueWhenPresent() {
        Gs1Message message = new Gs1Message("x", List.of(GTIN));
        assertEquals("09526000134367", message.value("01"));
    }

    /**
     * {@code value} returns null when the code is absent — the null ternary arm.
     */
    @Test
    void valueReturnsNullWhenAbsent() {
        Gs1Message message = new Gs1Message("x", List.of(GTIN));
        assertNull(message.value("8005"));
    }

    /**
     * {@code decimal} applies the identifier's decimal places when present — the non-null
     * ternary arm.
     */
    @Test
    void decimalReturnsTheShiftedNumberWhenPresent() {
        Gs1Message message = new Gs1Message("x", List.of(WEIGHT));
        assertEquals(new BigDecimal("0.195"), message.decimal("310"));
    }

    /**
     * {@code decimal} returns null when the code is absent — the null ternary arm.
     */
    @Test
    void decimalReturnsNullWhenAbsent() {
        Gs1Message message = new Gs1Message("x", List.of(WEIGHT));
        assertNull(message.decimal("390"));
    }

    /**
     * {@code date} returns the element's date when present — the non-null ternary arm.
     */
    @Test
    void dateReturnsTheDateWhenPresent() {
        Gs1Message message = new Gs1Message("x", List.of(EXPIRY));
        assertEquals(LocalDate.of(2027, 12, 31), message.date("17"));
    }

    /**
     * {@code date} returns null when the code is absent — the null ternary arm.
     */
    @Test
    void dateReturnsNullWhenAbsent() {
        Gs1Message message = new Gs1Message("x", List.of(EXPIRY));
        assertNull(message.date("15"));
    }

    /**
     * {@code getArticleCodes} is empty when the payload names no article — the null leg
     * of the {@code gtin == null} guard.
     */
    @Test
    void getArticleCodesIsEmptyWhenNoGtinIsPresent() {
        Gs1Message message = new Gs1Message("x", List.of(EXPIRY));
        assertTrue(message.getArticleCodes().isEmpty());
    }

    /**
     * {@code getArticleCodes} is empty when the GTIN is blank — the false leg of
     * {@code gtin == null} and the true leg of {@code gtin.isBlank()}.
     */
    @Test
    void getArticleCodesIsEmptyWhenTheGtinIsBlank() {
        Gs1Element blank = new Gs1Element("01", Gs1ApplicationIdentifier.of("01"), "   ", 0);
        Gs1Message message = new Gs1Message("x", List.of(blank));
        assertTrue(message.getArticleCodes().isEmpty());
    }

    /**
     * A padded EAN-13 offers its printed spelling first and keeps the padded form: this
     * walks both while-loop legs (a leading zero stripped then a non-zero stop), the
     * true leg of {@code length > shelf} (the 12- and 8-digit lengths skipped), the add
     * of each kept candidate, and the false leg of {@code !contains(gtin)} since the
     * padded fourteen-digit form already equals the raw GTIN.
     */
    @Test
    void aPaddedEan13OffersItsPrintedSpellingFirst() {
        Gs1Element padded =
                new Gs1Element("01", Gs1ApplicationIdentifier.of("01"), "03560070123456", 0);
        Gs1Message message = new Gs1Message("x", List.of(padded));
        List<String> codes = message.getArticleCodes();
        assertEquals("3560070123456", codes.get(0));
        assertTrue(codes.contains("03560070123456"));
        assertEquals(2, codes.size());
    }

    /**
     * A GTIN whose length matches no shelf length and carries no leading zero is offered
     * whole after the padded forms — the false leg of {@code length > shelf} on every
     * iteration and the true leg of {@code !contains(gtin)} that appends the raw value.
     */
    @Test
    void aGtinMatchingNoShelfLengthIsAppendedWhole() {
        Gs1Element odd = new Gs1Element("01", Gs1ApplicationIdentifier.of("01"), "12345", 0);
        Gs1Message message = new Gs1Message("x", List.of(odd));
        List<String> codes = message.getArticleCodes();
        assertEquals(List.of("00012345", "000000012345", "0000000012345", "00000000012345",
                "12345"), codes);
    }

    /**
     * An all-zeros GTIN is stripped down to a single significant digit, which stops the
     * padding loop by length rather than by a non-zero character — the false leg of the
     * {@code significant.length() > 1} guard, the arm no non-degenerate code reaches.
     */
    @Test
    void anAllZeroGtinStripsToASingleDigit() {
        Gs1Element zeros =
                new Gs1Element("01", Gs1ApplicationIdentifier.of("01"), "00000000000000", 0);
        Gs1Message message = new Gs1Message("x", List.of(zeros));
        List<String> codes = message.getArticleCodes();
        assertEquals(List.of("00000000", "000000000000", "0000000000000", "00000000000000"),
                codes);
    }

    /**
     * {@code getArticleCode} returns the likeliest spelling when an article is named —
     * the false leg of the {@code isEmpty} ternary.
     */
    @Test
    void getArticleCodeReturnsTheLikeliestSpelling() {
        Gs1Message message = new Gs1Message("x", List.of(GTIN));
        assertEquals("9526000134367", message.getArticleCode());
    }

    /**
     * {@code getArticleCode} returns null when no article is named — the true leg of the
     * {@code isEmpty} ternary.
     */
    @Test
    void getArticleCodeReturnsNullWhenNoArticleIsNamed() {
        Gs1Message message = new Gs1Message("x", List.of(EXPIRY));
        assertNull(message.getArticleCode());
    }

    /**
     * {@code describe} names every element in encoding order — the loop's body leg.
     */
    @Test
    void describeNamesEveryElement() {
        Gs1Message message = new Gs1Message("x", List.of(GTIN, UNKNOWN));
        String described = message.describe();
        assertTrue(described.contains("(01)09526000134367"));
        assertTrue(described.contains("GTIN"));
        assertTrue(described.contains("(99)ZZ"));
        assertTrue(described.contains("AI inconnu"));
    }

    /**
     * {@code describe} of an empty message is the empty string — the loop's zero-trip
     * leg and the empty join.
     */
    @Test
    void describeOfAnEmptyMessageIsEmpty() {
        Gs1Message message = new Gs1Message("x", List.of());
        assertEquals("", message.describe());
    }
}
