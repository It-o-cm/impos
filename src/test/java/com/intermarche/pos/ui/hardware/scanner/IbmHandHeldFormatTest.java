package com.intermarche.pos.ui.hardware.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link IbmHandHeldFormat}.
 * <p>
 * The two label forms read off a real till are exercised as captured — the
 * table-top's binary digits closed by {@code 16}, and the hand-held's ASCII
 * closed by {@code 00 ff 0b} — and every leg of every guard has its case:
 * the short report, the message type, the announced length below the header
 * and beyond the report, the three arms of the trailer trimming, the label
 * emptied by its own trailer, the digit and ASCII encodings, the group
 * separator of a GS1 label, and the byte that belongs to neither encoding.
 */
class IbmHandHeldFormatTest {

    /** The format under test. */
    private final IbmHandHeldFormat format = new IbmHandHeldFormat();

    /**
     * Builds a 64-byte report from its bytes, zero-padded like a real one.
     *
     * @param bytes the significant bytes, from the length byte onwards
     * @return the 64-byte report
     */
    private static byte[] report(int... bytes) {
        byte[] r = new byte[64];
        for (int i = 0; i < bytes.length; i++) {
            r[i] = (byte) bytes[i];
        }
        return r;
    }

    /**
     * Builds a table-top label report: the total length, the label message
     * type, the symbology and flags, one binary digit per byte, then the
     * {@code 16} terminator.
     *
     * @param symbology the symbology byte
     * @param digits the digit string
     * @return the 64-byte report
     */
    private static byte[] tableTop(int symbology, String digits) {
        byte[] r = new byte[64];
        r[0] = (byte) (4 + digits.length() + 1);
        r[1] = (byte) IbmHandHeldFormat.MESSAGE_TYPE_LABEL;
        r[2] = (byte) symbology;
        r[3] = 0x00;
        for (int i = 0; i < digits.length(); i++) {
            r[4 + i] = (byte) (digits.charAt(i) - '0');
        }
        r[4 + digits.length()] = (byte) IbmHandHeldFormat.TERMINATOR;
        return r;
    }

    /**
     * Builds a hand-held label report: the total length, the label message
     * type, the symbology and flags, the label in ASCII, then the
     * {@code 00 ff 0b} trailer.
     *
     * @param symbology the symbology byte
     * @param label the label text
     * @return the 64-byte report
     */
    private static byte[] handHeld(int symbology, String label) {
        byte[] r = new byte[64];
        r[0] = (byte) (4 + label.length() + 3);
        r[1] = (byte) IbmHandHeldFormat.MESSAGE_TYPE_LABEL;
        r[2] = (byte) symbology;
        r[3] = 0x00;
        for (int i = 0; i < label.length(); i++) {
            r[4 + i] = (byte) label.charAt(i);
        }
        r[4 + label.length()] = 0x00;
        r[5 + label.length()] = (byte) 0xFF;
        r[6 + label.length()] = 0x0B;
        return r;
    }

    /**
     * The table-top EAN-13 captured on the till: binary digits, {@code 16}
     * terminator, and a total length of 18 — the very value that used to be
     * mistaken for a frame identifier.
     */
    @Test
    void decodesTheCapturedTableTopEan13() {
        assertEquals("3178530403022", format.decode(tableTop(0x03, "3178530403022"), 64));
    }

    /**
     * The hand-held label captured on the same till: ASCII, {@code 00 ff 0b}
     * trailer, 40-byte report — the counter-example that proves the first
     * byte is a length and not an identifier, since it is not 0x12.
     */
    @Test
    void decodesTheCapturedHandHeldLabel() {
        assertEquals("2304796010006", format.decode(handHeld(0x03, "2304796010006"), 64));
    }

    /**
     * A label of another length decodes too — the whole point of reading the
     * first byte as a length. An EAN-8 report is 13 bytes, not 18.
     */
    @Test
    void decodesAnEan8() {
        assertEquals("30412345", format.decode(tableTop(0x04, "30412345"), 64));
    }

    /**
     * A binary-digit label containing the digit ZERO decodes it as '0': a
     * {@code 00} byte inside the label is data, which is why the trailer and
     * not a NUL identifies the hand-held ending.
     */
    @Test
    void decodesBinaryZeroAsADigit() {
        assertEquals("3000000000014", format.decode(tableTop(0x03, "3000000000014"), 64));
    }

    /**
     * A GS1 label keeps its group separator: dropping the label because of a
     * non-printable byte would lose every batch-and-date barcode.
     */
    @Test
    void keepsTheGroupSeparatorOfAGs1Label() {
        byte[] r = report(4 + 7 + 1, IbmHandHeldFormat.MESSAGE_TYPE_LABEL, 0x0F, 0x00,
                '0', '1', 0x1D, '1', '7', '2', '5', IbmHandHeldFormat.TERMINATOR);
        assertEquals("01\u001D1725", format.decode(r, 64));
    }

    /**
     * A report too short to carry a label is refused before anything is read
     * — the first leg of the opening guard.
     */
    @Test
    void aReportShorterThanTheHeaderIsRefused() {
        assertNull(format.decode(report(0x12, 0x10, 0x03, 0x00), 4));
    }

    /**
     * A message type that is not a label is refused — the second leg of the
     * same guard, the one that keeps the status reports of the device out.
     */
    @Test
    void aReportThatIsNotALabelIsRefused() {
        byte[] r = tableTop(0x03, "3178530403022");
        r[1] = 0x22;
        assertNull(format.decode(r, 64));
    }

    /**
     * An announced length below the header empties the label and the report
     * is refused rather than read backwards.
     */
    @Test
    void anAnnouncedLengthBelowTheHeaderIsRefused() {
        byte[] r = tableTop(0x03, "3178530403022");
        r[0] = 0x02;
        assertNull(format.decode(r, 64));
    }

    /**
     * An announced length beyond the report is CLAMPED to what was actually
     * read, never followed out of bounds. The clamped label then runs into
     * the padding, which is neither a digit run nor printable, so the report
     * is refused — refused, not read half-way.
     */
    @Test
    void anAnnouncedLengthBeyondTheReportIsClamped() {
        byte[] r = tableTop(0x03, "3178530403022");
        r[0] = (byte) 0x7F;
        assertNull(format.decode(r, 64));
    }

    /**
     * A label closed by the {@code ff 0b} trailer WITHOUT the usual
     * {@code 00} before it loses only the two trailer bytes — the false arm
     * of the "is the byte before the trailer a NUL" test.
     */
    @Test
    void aTrailerWithoutItsNulTrimsOnlyTwoBytes() {
        byte[] r = report(4 + 4 + 2, IbmHandHeldFormat.MESSAGE_TYPE_LABEL, 0x0F, 0x00,
                'A', 'B', 'C', 'D', 0xFF, 0x0B);
        assertEquals("ABCD", format.decode(r, 64));
    }

    /**
     * A report whose announced content is nothing but the trailer leaves no
     * label at all and is refused — the {@code trimmed > PAYLOAD_START} false
     * arm, reached when the trailer starts at the first label byte.
     */
    @Test
    void aReportCarryingOnlyItsTrailerIsRefused() {
        byte[] r = report(4 + 2, IbmHandHeldFormat.MESSAGE_TYPE_LABEL, 0x0F, 0x00, 0xFF, 0x0B);
        assertNull(format.decode(r, 64));
    }

    /**
     * A report whose announced content is nothing but the {@code 16}
     * terminator is refused the same way — the other path to an empty label.
     */
    @Test
    void aReportCarryingOnlyItsTerminatorIsRefused() {
        byte[] r = report(4 + 1, IbmHandHeldFormat.MESSAGE_TYPE_LABEL, 0x03, 0x00,
                IbmHandHeldFormat.TERMINATOR);
        assertNull(format.decode(r, 64));
    }

    /**
     * A label closed by neither ending keeps every announced byte: there is
     * nothing to trim, and the label is what the length says it is.
     */
    @Test
    void aLabelWithNoEndingKeepsEveryAnnouncedByte() {
        byte[] r = report(4 + 4, IbmHandHeldFormat.MESSAGE_TYPE_LABEL, 0x0F, 0x00,
                'W', 'X', 'Y', 'Z');
        assertEquals("WXYZ", format.decode(r, 64));
    }

    /**
     * A byte that is neither a binary digit, nor printable ASCII, nor the
     * group separator makes the whole label undecodable: nothing is returned,
     * so the register never receives a half-read code.
     */
    @Test
    void aByteInNeitherEncodingIsRefused() {
        byte[] r = report(4 + 3 + 1, IbmHandHeldFormat.MESSAGE_TYPE_LABEL, 0x0F, 0x00,
                'A', 0xAB, 'B', IbmHandHeldFormat.TERMINATOR);
        assertNull(format.decode(r, 64));
    }

    /**
     * A byte above the printable range is refused too — the upper bound of
     * the ASCII test, which the sub-space bound leaves uncovered.
     */
    @Test
    void aByteAboveThePrintableRangeIsRefused() {
        byte[] r = report(4 + 2 + 1, IbmHandHeldFormat.MESSAGE_TYPE_LABEL, 0x0F, 0x00,
                'A', 0x7F, IbmHandHeldFormat.TERMINATOR);
        assertNull(format.decode(r, 64));
    }

    /**
     * The format names itself, which is what the decoder chain reports.
     */
    @Test
    void itNamesItself() {
        assertEquals("IBM hand-held", format.name());
    }

    /**
     * A report whose second-to-last byte IS the trailer's first byte but whose
     * last byte is NOT the trailer's second byte is not a trailer at all: the
     * {@code 0xFF} stays in the label — the third-condition false arm of the
     * trailer test — and, being neither a digit nor printable ASCII, makes the
     * whole label undecodable.
     */
    @Test
    void aByteFfNotFollowedByTheTrailerSecondByteIsNotATrailer() {
        byte[] r = report(4 + 4, IbmHandHeldFormat.MESSAGE_TYPE_LABEL, 0x0F, 0x00,
                'A', 'B', 0xFF, 0x07);
        assertNull(format.decode(r, 64));
    }

    /**
     * A label whose LEADING bytes are binary digits but which then carries a
     * negative byte fails the digit test on that byte — the {@code b < 0} true
     * arm of {@code allBinaryDigits}, reached only once the earlier bytes are
     * all in 0-9 — and, being non-printable, is refused rather than half-read.
     */
    @Test
    void aBinaryDigitRunEndedByANegativeByteIsRefused() {
        byte[] r = report(4 + 2 + 1, IbmHandHeldFormat.MESSAGE_TYPE_LABEL, 0x03, 0x00,
                0x03, 0xAB, IbmHandHeldFormat.TERMINATOR);
        assertNull(format.decode(r, 64));
    }
}
