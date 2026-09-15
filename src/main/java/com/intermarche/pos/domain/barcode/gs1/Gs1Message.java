package com.intermarche.pos.domain.barcode.gs1;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A decoded GS1 payload: its elements in the order they were encoded, and the readings
 * the register acts on.
 *
 * <p>ONE MESSAGE, WHATEVER THE CARRIER. A GS1 Databar, a GS1 DataMatrix and a GS1 QR
 * code differ in how they are printed and in how the reader photographs them, not in
 * what they say — by the time the payload reaches this register through the scan bus it
 * is a string, and the same string means the same thing whichever symbol carried it.
 * That is why {@code LC-11-01-11}, {@code LC-11-02-01}, {@code LC-11-02-03} and
 * {@code LC-11-02-06} are one piece of code and not four.
 *
 * <p>An empty message is what a payload that is NOT GS1 decodes to, and it is how the
 * scan chain tells the two apart: a plain EAN-13 is not a GS1 payload and must go on
 * being handled as a plain EAN-13.
 */
public final class Gs1Message {

    /** The elements, in the order the payload encoded them. */
    private final List<Gs1Element> elements;

    /** The payload as it arrived, kept verbatim for the technical journal. */
    private final String rawPayload;

    /**
     * Builds a decoded message.
     *
     * @param rawPayload the payload as it arrived
     * @param elements   the decoded elements, in encoding order
     */
    Gs1Message(String rawPayload, List<Gs1Element> elements) {
        this.rawPayload = rawPayload == null ? "" : rawPayload;
        this.elements = List.copyOf(elements);
    }

    /**
     * Returns the empty message, which is what a payload that is not GS1 decodes to.
     *
     * @param rawPayload the payload as it arrived
     * @return a message carrying no element
     */
    static Gs1Message empty(String rawPayload) {
        return new Gs1Message(rawPayload, Collections.emptyList());
    }

    /**
     * Returns the elements, in the order the payload encoded them.
     *
     * @return the elements, never null
     */
    public List<Gs1Element> getElements() {
        return elements;
    }

    /**
     * Returns the payload as it arrived.
     *
     * @return the raw payload
     */
    public String getRawPayload() {
        return rawPayload;
    }

    /**
     * Tells whether the payload decoded to nothing, which is how a non-GS1 code reads.
     *
     * @return true when no element was decoded
     */
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /**
     * Returns the elements this version has no meaning for ({@code LC-11-03-03}).
     *
     * <p>They are separated rather than dropped because they are journalled: an
     * identifier the register ignored is the first thing anyone looks for when a
     * supplier's code behaves oddly.
     *
     * @return the unknown elements, in encoding order
     */
    public List<Gs1Element> getUnknownElements() {
        List<Gs1Element> unknown = new ArrayList<>();
        for (Gs1Element element : elements) {
            if (!element.isKnown()) {
                unknown.add(element);
            }
        }
        return unknown;
    }

    /**
     * Returns the first element carrying that identifier.
     *
     * @param code the identifier's digits, the decimal-place digit excluded
     * @return the element, or null when the payload carries none
     */
    public Gs1Element get(String code) {
        for (Gs1Element element : elements) {
            if (element.identifier() != null && element.identifier().code().equals(code)) {
                return element;
            }
        }
        return null;
    }

    /**
     * Tells whether the payload carries that identifier.
     *
     * @param code the identifier's digits, the decimal-place digit excluded
     * @return true when an element carries it
     */
    public boolean has(String code) {
        return get(code) != null;
    }

    /**
     * Returns the raw value of an identifier.
     *
     * @param code the identifier's digits
     * @return the value, or null when the payload carries no such element
     */
    public String value(String code) {
        Gs1Element element = get(code);
        return element == null ? null : element.value();
    }

    /**
     * Returns the number an identifier carries, its decimal places applied.
     *
     * @param code the identifier's digits
     * @return the value, or null when the payload carries no such readable element
     */
    public BigDecimal decimal(String code) {
        Gs1Element element = get(code);
        return element == null ? null : element.asDecimal();
    }

    /**
     * Returns the date an identifier carries.
     *
     * @param code the identifier's digits
     * @return the date, or null when the payload carries no such readable element
     */
    public LocalDate date(String code) {
        Gs1Element element = get(code);
        return element == null ? null : element.asDate();
    }

    /** The lengths a code printed on a shelf article really has, longest first. */
    private static final int[] SHELF_LENGTHS = {14, 13, 12, 8};

    /**
     * Returns the codes the article identifier may be spelled as in this register's
     * catalog ({@code LC-11-03-04}), the likeliest first.
     *
     * <p>A GTIN IS ALWAYS FOURTEEN DIGITS AND A SHELF CODE NEVER IS. The standard pads
     * shorter codes on the left with zeros: the EAN-13 {@code 3560070123456} is encoded
     * {@code 03560070123456}, a UPC-A carries two zeros and an EAN-8 six. Handing the
     * padded form to a catalog that stores the printed one finds nothing.
     *
     * <p>Removing the padding is NOT a computation with one answer, which is why this
     * returns several. {@code 00003560070129} has ten significant digits, and ten is not
     * a length any article carries — it is an EAN-13 with three of its own leading zeros
     * kept, or a UPC-A with one. Guessing would pick the wrong article silently; the
     * candidates are therefore offered in order and the caller stops at the first its
     * catalog knows.
     *
     * <p>The fourteen-digit form is kept in the list, last: the leading digit of a true
     * GTIN-14 is a packaging INDICATOR — a case, a pallet — and a store that sells such
     * a unit at the till has it in its catalog under exactly those fourteen digits.
     *
     * @return the codes to try, likeliest first, empty when the payload names no article
     */
    public List<String> getArticleCodes() {
        String gtin = value(Gs1ApplicationIdentifier.GTIN);
        if (gtin == null || gtin.isBlank()) {
            return List.of();
        }
        String significant = gtin;
        while (significant.length() > 1 && significant.charAt(0) == '0') {
            significant = significant.substring(1);
        }
        List<String> candidates = new ArrayList<>();
        for (int length : SHELF_LENGTHS) {
            if (significant.length() > length) {
                continue;
            }
            String padded = "0".repeat(length - significant.length()) + significant;
            if (!candidates.contains(padded)) {
                candidates.add(padded);
            }
        }
        // Shortest first: an EAN-8 the standard padded is an EAN-8, and offering the
        // thirteen-digit form of it before would only find an article that does not
        // exist. The fourteen-digit form, being the widest, ends up last.
        Collections.reverse(candidates);
        if (!candidates.contains(gtin)) {
            candidates.add(gtin);
        }
        return candidates;
    }

    /**
     * Returns the likeliest spelling of the article code the payload names.
     *
     * @return the article code, or null when the payload names no article
     */
    public String getArticleCode() {
        List<String> candidates = getArticleCodes();
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Returns the message as the technical journal writes it ({@code LC-11-03-02}).
     *
     * @return every decoded element, in encoding order
     */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (Gs1Element element : elements) {
            parts.add(element.toString());
        }
        return String.join(" ", parts);
    }
}
