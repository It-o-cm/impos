package com.intermarche.pos.ui.hardware.scanner;

import org.jboss.logging.Logger;

import java.util.List;
import java.util.function.Consumer;

/**
 * Turns the raw HID input reports of a scanner into barcode strings — the
 * counterpart of {@link KeyboardScancodeDecoder} for the non-keyboard channel.
 * <p>
 * ANTICIPATING THE FORMAT RATHER THAN ASSUMING ONE. The set of report formats
 * a retail scanner emits on USB is finite and documented, so this decoder does
 * not hard-code one: it tries each known {@link HidFrameFormat} in turn and
 * takes the first that recognizes the report. Supported today:
 * <ul>
 *   <li>{@link IbmHandHeldFormat} — the IBM vendor page (0xff45), what the
 *       Symbol/Zebra units of the reference tills speak;</li>
 *   <li>{@link HidPosFormat} — the USB-IF HID-POS standard page (0x8C), the
 *       most likely format of another brand on a mixed fleet.</li>
 * </ul>
 * A frame is self-contained: one report, one code, no state between reports.
 * <p>
 * NO SILENT UNKNOWN. A scanner speaking a format none of these recognize
 * cannot be translated — and must never be guessed at, since a scan the
 * register misreads is worse than one it refuses. What is guaranteed is that
 * such a report is VISIBLE: it is dumped in hex (the first
 * {@link #MAX_UNKNOWN_TRACES} per device, so an unexpected talker cannot flood
 * the log) and counted, which is exactly what is needed to write its format
 * class. Idle all-zero reports are the one thing dropped in silence — they
 * carry nothing and every unit sends them.
 */
public class HidBarcodeDecoder {

    /** The class logger. */
    private static final Logger LOG = Logger.getLogger(HidBarcodeDecoder.class);

    /** How many unrecognized reports are dumped per device before going quiet. */
    static final int MAX_UNKNOWN_TRACES = 5;

    /** The known formats, tried in order until one recognizes the report. */
    private static final List<HidFrameFormat> FORMATS =
            List.of(new IbmHandHeldFormat(), new HidPosFormat());

    /** The receiver of each completed code. */
    private final Consumer<String> onCode;

    /** Unrecognized reports seen on this device, whether traced or not. */
    private int unknownFrames;

    /**
     * Creates a decoder delivering completed codes to the given receiver.
     *
     * @param onCode the receiver of each completed, non-empty code
     */
    public HidBarcodeDecoder(Consumer<String> onCode) {
        this.onCode = onCode;
    }

    /**
     * Consumes one HID input report and emits the code it carries, when a
     * known format recognizes it. An idle report is dropped silently; an
     * unrecognized one is traced in hex and counted.
     *
     * @param report the report bytes
     * @param length the number of valid bytes in the report
     */
    public void feed(byte[] report, int length) {
        if (length <= 0 || isIdle(report, length)) {
            return;
        }
        for (HidFrameFormat format : FORMATS) {
            String code = format.decode(report, length);
            if (code != null) {
                if (!code.isEmpty()) {
                    onCode.accept(code);
                }
                return;
            }
        }
        traceUnknown(report, length);
    }

    /**
     * Whether the report is an idle one — every byte zero. Units send these
     * between scans; they carry nothing, so they are the only reports dropped
     * without a trace.
     *
     * @param report the report bytes
     * @param length the number of valid bytes
     * @return true when every byte is zero
     */
    private boolean isIdle(byte[] report, int length) {
        for (int i = 0; i < length; i++) {
            if (report[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Records a report no known format recognized, dumping the first few in
     * hex so an unknown frame format can be read and supported instead of
     * vanishing. Past {@link #MAX_UNKNOWN_TRACES} the device is assumed to be
     * a steady talker and only the running count is kept.
     *
     * @param report the report bytes
     * @param length the number of valid bytes
     */
    private void traceUnknown(byte[] report, int length) {
        unknownFrames++;
        if (unknownFrames <= MAX_UNKNOWN_TRACES) {
            LOG.warnf("Trame douchette de format inconnu (#%d): %s",
                    unknownFrames, hex(report, length));
            if (unknownFrames == MAX_UNKNOWN_TRACES) {
                LOG.warn("Trames de format inconnu: trace interrompue (seul le compteur continue).");
            }
        }
    }

    /**
     * The number of unrecognized reports seen on this device — the signal
     * that a scanner is talking a format no known class can translate.
     *
     * @return the count of unrecognized reports
     */
    int unknownFrameCount() {
        return unknownFrames;
    }

    /**
     * Renders a report in hex, for the trace of a frame no format recognized —
     * the only way to learn an unobserved format's layout.
     *
     * @param report the report bytes
     * @param length the number of valid bytes
     * @return the space-separated hex dump
     */
    private String hex(byte[] report, int length) {
        StringBuilder dump = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                dump.append(' ');
            }
            dump.append(String.format("%02X", report[i] & 0xFF));
        }
        return dump.toString();
    }
}
