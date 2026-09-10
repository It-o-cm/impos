package com.intermarche.pos.ui.hardware.scanner;

/**
 * The USB-IF HID-POS frame format (HID Point of Sale, usage page 0x8C) — the
 * OPEN STANDARD for a barcode scanner on USB, and the most likely format a
 * unit of another brand will speak on a mixed fleet. Anticipated here from
 * the specification rather than waiting to meet it on a till.
 * <p>
 * Frame layout:
 * <pre>
 *   [0]  id     report identifier (0x02 or 0x03 in the wild)
 *   [1]  len    length of the decoded data
 *   [2..4]      symbology, three AIM identifier bytes
 *   [5..]       the decoded data, in ASCII
 *   ...         padding to the report size
 * </pre>
 * Unlike the IBM format, the data is plain ASCII — no raw-value digits.
 * <p>
 * NOT VALIDATED AGAINST A DEVICE: no scanner speaking this format has been
 * seen on a till yet, so the recognition is deliberately strict — the report
 * identifier must be a known one, the announced length must fit the report
 * exactly, and EVERY data byte must be printable ASCII. Anything short of
 * that returns null, so an unrecognized report is traced in hex rather than
 * pushed as a half-read code.
 */
public class HidPosFormat implements HidFrameFormat {

    /** Report identifiers the HID-POS scanners of the field use. */
    private static final int[] REPORT_IDS = {0x02, 0x03};

    /** Bytes before the decoded data: identifier, length, three symbology bytes. */
    private static final int HEADER_SIZE = 5;

    /**
     * {@inheritDoc}
     *
     * @return the format name
     */
    @Override
    public String name() {
        return "HID-POS (0x8C)";
    }

    /**
     * Decodes a HID-POS frame, or returns null when the report is not one.
     *
     * @param report the report bytes
     * @param length the number of valid bytes
     * @return the decoded code, or null when this is not a HID-POS frame
     */
    @Override
    public String decode(byte[] report, int length) {
        if (length < HEADER_SIZE || !isKnownReportId(report[0] & 0xFF)) {
            return null;
        }
        int dataLength = report[1] & 0xFF;
        if (dataLength == 0 || HEADER_SIZE + dataLength > length) {
            return null;
        }
        StringBuilder text = new StringBuilder(dataLength);
        for (int i = HEADER_SIZE; i < HEADER_SIZE + dataLength; i++) {
            int b = report[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) {
                return null;
            }
            text.append((char) b);
        }
        return text.toString();
    }

    /**
     * Whether a report identifier is one of the HID-POS ones.
     *
     * @param id the report identifier byte
     * @return true when the identifier is known
     */
    private boolean isKnownReportId(int id) {
        for (int known : REPORT_IDS) {
            if (known == id) {
                return true;
            }
        }
        return false;
    }
}
