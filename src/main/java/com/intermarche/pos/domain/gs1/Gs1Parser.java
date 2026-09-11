package com.intermarche.pos.domain.gs1;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a GS1 payload, in either of the two syntaxes the standard defines.
 *
 * <p>THE ELEMENT STRING is the historical one: identifiers and values run together with
 * no punctuation, {@code 010952600013436710ABC123}. It arrives in three shapes and all
 * three are read here — the human-readable form a specification writes,
 * {@code (01)09526000134367(10)ABC123}; the transmitted form, where a reader separates
 * variable-length elements with FNC1 (the {@code GS} character, 0x1D) and may prefix the
 * whole with a symbology identifier such as {@code ]C1} or {@code ]d2}; and the bare
 * concatenation, which is unambiguous only while every element but the last has a length
 * fixed by the standard.
 *
 * <p>THE DIGITAL LINK URI is the modern one, and it is a web address:
 * {@code https://example.com/01/09526000134367/10/ABC123?3103=000195}. The path carries
 * identifier and value in pairs after the domain, the query string carries the rest.
 * A register reads it for what it says and never fetches it — the address exists so a
 * shopper's telephone can open a page, and a till that followed it would be waiting on
 * the internet with a customer in front of it.
 *
 * <p>IT NEVER THROWS AND NEVER REFUSES. A payload it cannot make sense of decodes to an
 * empty message, which is how the scan chain learns that the code is not GS1 and must go
 * on to the next handler; a payload it can only half read yields the half it read, which
 * is what {@code LC-11-03-03} demands — an unknown or damaged identifier may not stop
 * the article being registered.
 */
public final class Gs1Parser {

    /** FNC1 as a reader transmits it: the ASCII group separator. */
    public static final char SEPARATOR = '\u001D';

    /** The prefix a reader puts before a payload to name the symbology it read. */
    private static final char SYMBOLOGY_PREFIX = ']';

    /** How long a symbology identifier is, prefix included ({@code ]C1}, {@code ]d2}). */
    private static final int SYMBOLOGY_LENGTH = 3;

    /** The scheme a Digital Link URI carries. */
    private static final String HTTP = "http";

    /**
     * Not instantiable: decoding is a function, not an object.
     */
    private Gs1Parser() {
    }

    /**
     * Decodes a payload.
     *
     * @param payload the code as the reader or the operator gave it, possibly null
     * @return the decoded message, empty when the payload is not GS1
     */
    public static Gs1Message parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return Gs1Message.empty(payload);
        }
        String trimmed = payload.trim();
        if (isDigitalLink(trimmed)) {
            return parseDigitalLink(trimmed);
        }
        if (!looksLikeElementString(trimmed)) {
            return Gs1Message.empty(payload);
        }
        return parseElementString(trimmed);
    }

    /**
     * Tells whether a payload announces itself as a GS1 Element String.
     *
     * <p>THIS GUARD IS THE WHOLE SAFETY OF THE DECODER, and it exists because an
     * Element String has no syntax of its own: it is a run of characters, and so is an
     * EAN-13. Read greedily, {@code 3560070123456} — an ordinary shelf code — decodes as
     * a net-weight identifier followed by an unknown one, and the till registers
     * something nobody scanned. The decoder therefore claims a payload only when the
     * payload SAYS it is GS1:
     *
     * <ul>
     *   <li>the human-readable parentheses a specification writes;</li>
     *   <li>the symbology identifier a reader prefixes ({@code ]C1}, {@code ]d2},
     *       {@code ]Q3}, {@code ]e0}), which is precisely how the standard has a reader
     *       announce it;</li>
     *   <li>an FNC1 separator, which nothing but a GS1 payload carries.</li>
     * </ul>
     *
     * <p>ONE EXCEPTION, for the bare concatenation a simulator or a hand-typed test
     * produces: a payload beginning with the article identifier at its predefined length
     * — {@code 01} and fourteen digits — and carrying something after it. That is
     * sixteen characters at least, where an EAN-8 has eight, a UPC-A twelve and an
     * EAN-13 thirteen, so no shelf code can be mistaken for it.
     *
     * @param payload the trimmed payload
     * @return true when it may be read as an Element String
     */
    public static boolean looksLikeElementString(String payload) {
        if (hasExplicitMarker(payload)) {
            return true;
        }
        return payload.length() > 16
                && payload.startsWith(Gs1ApplicationIdentifier.GTIN)
                && payload.substring(2, 16).chars().allMatch(Character::isDigit);
    }

    /**
     * Tells whether a payload says in so many words that it is GS1.
     *
     * <p>THE BARE CONCATENATION SAYS NOTHING, and that is the whole point of telling the
     * two apart. A marked payload can be claimed outright and its failures reported as
     * failures; an unmarked one is a plausible reading of a code that may belong to
     * another family entirely — a long numeric loyalty card, a store badge — so a
     * handler that acts on it must be able to change its mind and let the code go on
     * down the chain.
     *
     * @param payload the trimmed payload
     * @return true when it carries parentheses, a symbology identifier, a separator or
     *         a Digital Link address
     */
    public static boolean hasExplicitMarker(String payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        String trimmed = payload.trim();
        return !trimmed.isEmpty()
                && (trimmed.indexOf('(') >= 0
                    || trimmed.indexOf(SEPARATOR) >= 0
                    || trimmed.charAt(0) == SYMBOLOGY_PREFIX
                    || isDigitalLink(trimmed));
    }

    /**
     * Tells whether a payload is a GS1 Digital Link URI ({@code LC-11-02-03/06}).
     *
     * @param payload the trimmed payload
     * @return true when it is a web address
     */
    public static boolean isDigitalLink(String payload) {
        String lower = payload.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith(HTTP + "://") || lower.startsWith(HTTP + "s://");
    }

    /**
     * Decodes a Digital Link URI.
     *
     * <p>The path is read in PAIRS from the left, and a segment that is not an
     * identifier this version knows simply ends the pairing: a Digital Link may carry
     * any number of ordinary path segments before its identifiers, and refusing the
     * whole address because of one of them would lose an article the code names
     * perfectly well.
     *
     * @param payload the trimmed payload
     * @return the decoded message
     */
    private static Gs1Message parseDigitalLink(String payload) {
        String withoutScheme = payload.substring(payload.indexOf("://") + 3);
        int query = withoutScheme.indexOf('?');
        String path = query < 0 ? withoutScheme : withoutScheme.substring(0, query);
        List<Gs1Element> elements = new ArrayList<>();
        String[] segments = path.split("/");
        // Index 0 is the domain. What follows is read in pairs, but the pairs do not
        // necessarily start there: a Digital Link is an ordinary web address and may
        // carry any number of its own segments before its identifiers. So a segment
        // that is not digits costs ONE step and a pair costs two — pairing blindly from
        // the domain would lose every address whose owner put a word in front.
        int index = 1;
        while (index < segments.length) {
            if (index + 1 < segments.length && isDigits(segments[index])) {
                addPair(elements, segments[index], decode(segments[index + 1]));
                index += 2;
                continue;
            }
            index++;
        }
        if (query >= 0) {
            for (String parameter : withoutScheme.substring(query + 1).split("&")) {
                int equals = parameter.indexOf('=');
                if (equals > 0) {
                    addPair(elements, parameter.substring(0, equals),
                            decode(parameter.substring(equals + 1)));
                }
            }
        }
        return elements.isEmpty() ? Gs1Message.empty(payload) : new Gs1Message(payload, elements);
    }

    /**
     * Adds one identifier-and-value pair read from a Digital Link URI.
     *
     * <p>The identifier is a whole segment here, so it is looked up on its own rather
     * than read out of a run of digits. An unknown one is KEPT — {@code LC-11-03-02}
     * asks for every decoded identifier to be recorded, and a Digital Link delimits
     * them for us, so there is nothing preventing us from keeping it.
     *
     * @param elements the elements being collected
     * @param code     the path segment or parameter name naming an identifier
     * @param value    the value it carries
     */
    private static void addPair(List<Gs1Element> elements, String code, String value) {
        String digits = code == null ? "" : code.trim();
        if (!isDigits(digits)) {
            return;
        }
        Gs1ApplicationIdentifier known = Gs1ApplicationIdentifier.of(digits);
        if (known == null && digits.length() > 2) {
            // A decimal identifier: the stem is known and the last digit is the number
            // of decimal places.
            Gs1ApplicationIdentifier stem =
                    Gs1ApplicationIdentifier.of(digits.substring(0, digits.length() - 1));
            if (stem != null && stem.decimal()) {
                elements.add(new Gs1Element(digits, stem, value,
                        digits.charAt(digits.length() - 1) - '0'));
                return;
            }
        }
        elements.add(new Gs1Element(digits, known, value, 0));
    }

    /**
     * Tells whether a text is a non-empty run of digits, which is what an identifier
     * looks like in a Digital Link address.
     *
     * @param text the path segment or parameter name, possibly null
     * @return true when it is digits and nothing else
     */
    private static boolean isDigits(String text) {
        return text != null && !text.isEmpty() && text.chars().allMatch(Character::isDigit);
    }

    /**
     * Percent-decodes a Digital Link segment.
     *
     * @param raw the segment as the address carried it
     * @return the decoded value, the raw one when it is not decodable
     */
    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A stray percent sign: the segment is worth more as it stands than lost.
            return raw;
        }
    }

    /**
     * Decodes an Element String, in any of its three shapes.
     *
     * @param payload the trimmed payload
     * @return the decoded message
     */
    private static Gs1Message parseElementString(String payload) {
        String body = payload;
        if (body.length() > SYMBOLOGY_LENGTH && body.charAt(0) == SYMBOLOGY_PREFIX) {
            body = body.substring(SYMBOLOGY_LENGTH);
        }
        if (body.indexOf('(') >= 0) {
            body = fromHumanReadable(body);
        }
        if (body.isEmpty()) {
            return Gs1Message.empty(payload);
        }
        List<Gs1Element> elements = new ArrayList<>();
        int at = 0;
        while (at < body.length()) {
            // A separator between two elements, or a run of them: nothing to read here.
            if (body.charAt(at) == SEPARATOR) {
                at++;
                continue;
            }
            Gs1ApplicationIdentifier identifier = Gs1ApplicationIdentifier.at(body, at);
            if (identifier == null) {
                // An identifier no version of this table knows. It can still be read
                // when the payload separates it, which is what the standard requires of
                // a variable-length element: take two digits as its code and everything
                // up to the separator as its value (LC-11-03-03). Without a separator
                // there is nothing to go on, and reading further would invent elements.
                at = readUnknown(body, at, elements);
                if (at < 0) {
                    break;
                }
                continue;
            }
            at = readKnown(body, at, identifier, elements);
        }
        return elements.isEmpty() ? Gs1Message.empty(payload) : new Gs1Message(payload, elements);
    }

    /**
     * Reads one element whose identifier this version knows.
     *
     * @param body       the payload being read
     * @param at         where the identifier starts
     * @param identifier the identifier found there
     * @param elements   the elements being collected
     * @return where the next element starts
     */
    private static int readKnown(String body, int at, Gs1ApplicationIdentifier identifier,
            List<Gs1Element> elements) {
        int codeLength = identifier.code().length() + (identifier.decimal() ? 1 : 0);
        int decimals = 0;
        if (identifier.decimal()) {
            char last = body.charAt(at + identifier.code().length());
            decimals = Character.isDigit(last) ? last - '0' : 0;
        }
        int from = at + codeLength;
        int to;
        if (identifier.isFixedLength()) {
            // A fixed-length element carries no separator after it, and a payload cut
            // short carries fewer characters than the standard promises: take what is
            // there rather than reading past the end.
            to = Math.min(from + identifier.dataLength(), body.length());
        } else {
            to = body.indexOf(SEPARATOR, from);
            if (to < 0) {
                to = body.length();
            }
            // A value longer than the standard allows is a payload that lost its
            // separator. Cutting it at the maximum would invent an element out of what
            // follows, so it is kept whole and the caller sees what was really encoded.
        }
        elements.add(new Gs1Element(body.substring(at, from), identifier,
                body.substring(from, to), decimals));
        return to;
    }

    /**
     * Reads one element whose identifier this version does not know
     * ({@code LC-11-03-03}).
     *
     * @param body     the payload being read
     * @param at       where the identifier starts
     * @param elements the elements being collected
     * @return where the next element starts, or -1 when the payload cannot be read
     *         further
     */
    private static int readUnknown(String body, int at, List<Gs1Element> elements) {
        if (at + 2 > body.length()) {
            return -1;
        }
        int separator = body.indexOf(SEPARATOR, at);
        if (separator < 0) {
            // Nothing closes it. Everything left belongs to this element, or to several
            // the payload gave us no way of telling apart — either way, reading on would
            // be guessing.
            elements.add(new Gs1Element(body.substring(at, at + 2), null,
                    body.substring(at + 2), 0));
            return -1;
        }
        elements.add(new Gs1Element(body.substring(at, at + 2), null,
                body.substring(at + 2, separator), 0));
        return separator;
    }

    /**
     * Turns the human-readable form into the transmitted one.
     *
     * <p>{@code (01)09526000134367(10)ABC123} becomes the same digits with a separator
     * before every identifier but the first: the parentheses are exactly the delimiting
     * a reader transmits as FNC1, so restoring them as separators means one decoder and
     * not two.
     *
     * @param body the payload in its human-readable form
     * @return the payload in its transmitted form
     */
    private static String fromHumanReadable(String body) {
        StringBuilder transmitted = new StringBuilder();
        int at = 0;
        while (at < body.length()) {
            char current = body.charAt(at);
            if (current == '(') {
                int close = body.indexOf(')', at);
                if (close < 0) {
                    // An unclosed parenthesis: what follows cannot be an identifier, so
                    // the rest of the payload is copied as it stands.
                    transmitted.append(body, at + 1, body.length());
                    break;
                }
                if (transmitted.length() > 0) {
                    transmitted.append(SEPARATOR);
                }
                transmitted.append(body, at + 1, close);
                at = close + 1;
                continue;
            }
            transmitted.append(current);
            at++;
        }
        return transmitted.toString();
    }
}
