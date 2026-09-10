package com.intermarche.pos.ui.hardware.scanner;

/**
 * One HID report format a scanner may speak. The set of formats a retail
 * scanner can emit on USB is finite and documented — keyboard emulation
 * (handled on the evdev channel, not here), the IBM hand-held vendor page,
 * the USB-IF HID-POS standard page, and vendor protocols such as Zebra's
 * SNAPI — so the reader anticipates them by trying each known format in turn
 * rather than assuming one.
 * <p>
 * A format identifies its own frames: it returns the decoded code when the
 * report is unambiguously one of its frames, and null otherwise, so the next
 * format gets its chance. Ambiguity must resolve to null — a format that
 * claims a report it does not really understand would push a wrong code, and
 * a scan the register misreads is worse than a scan it refuses.
 * <p>
 * Supporting a new scanner is therefore ONE class implementing this interface,
 * registered in {@link HidBarcodeDecoder}: nothing else in the register moves.
 */
public interface HidFrameFormat {

    /**
     * The format name, for logs and diagnosis.
     *
     * @return the human-readable format name
     */
    String name();

    /**
     * Decodes one HID input report when it is a frame of this format.
     *
     * @param report the report bytes
     * @param length the number of valid bytes in the report
     * @return the decoded code (possibly empty for a well-formed frame that
     *         carries none), or null when the report is not this format
     */
    String decode(byte[] report, int length);
}
