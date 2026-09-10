package com.intermarche.pos.ui.hardware.scanner;

import java.util.function.Consumer;

/**
 * Assembles barcode strings from the key events of a scanner running in HID
 * keyboard mode: each scanned character arrives as a key press, and ENTER
 * terminates the code. Pure and stateful-per-device — one instance per
 * attached scanner, no thread of its own.
 * <p>
 * The mapping works on LINUX KEY CODES (input-event-codes.h), which are
 * layout-independent positions: digits, letters (upper-cased — EAN, PLU and
 * badge codes are caseless) and the dash, on both the main rows and the
 * keypad. Modifier keys are ignored; any other unknown key is ignored too
 * rather than corrupting the buffer, and a runaway buffer (no ENTER after
 * {@link #MAX_LENGTH} characters) resets so a chatty device cannot grow it
 * without bound.
 */
public class KeyboardScancodeDecoder {

    /** The longest code kept before the buffer is declared runaway. */
    static final int MAX_LENGTH = 64;

    /** Linux key code of ENTER. */
    private static final int KEY_ENTER = 28;

    /** Linux key code of keypad ENTER. */
    private static final int KEY_KPENTER = 96;

    /** The receiver of each completed code. */
    private final Consumer<String> onCode;

    /** The code under assembly. */
    private final StringBuilder buffer = new StringBuilder();

    /**
     * Creates a decoder delivering completed codes to the given receiver.
     *
     * @param onCode the receiver of each completed, non-empty code
     */
    public KeyboardScancodeDecoder(Consumer<String> onCode) {
        this.onCode = onCode;
    }

    /**
     * Consumes one key PRESS event. ENTER completes and emits the buffered
     * code (empty codes are dropped); a mapped character is appended; an
     * unmapped key is ignored.
     *
     * @param keyCode the Linux key code of the pressed key
     */
    public void onKeyPress(int keyCode) {
        if (keyCode == KEY_ENTER || keyCode == KEY_KPENTER) {
            String code = buffer.toString();
            buffer.setLength(0);
            if (!code.isEmpty()) {
                onCode.accept(code);
            }
            return;
        }
        char mapped = map(keyCode);
        if (mapped == 0) {
            return;
        }
        if (buffer.length() >= MAX_LENGTH) {
            buffer.setLength(0);
        }
        buffer.append(mapped);
    }

    /**
     * Maps one Linux key code to its character, or 0 when the key carries
     * none (modifiers, function keys, anything a barcode never contains).
     *
     * @param keyCode the Linux key code
     * @return the mapped character, or 0 for an ignored key
     */
    private char map(int keyCode) {
        return switch (keyCode) {
            case 2 -> '1'; case 3 -> '2'; case 4 -> '3'; case 5 -> '4';
            case 6 -> '5'; case 7 -> '6'; case 8 -> '7'; case 9 -> '8';
            case 10 -> '9'; case 11 -> '0';
            case 12 -> '-';
            case 16 -> 'Q'; case 17 -> 'W'; case 18 -> 'E'; case 19 -> 'R';
            case 20 -> 'T'; case 21 -> 'Y'; case 22 -> 'U'; case 23 -> 'I';
            case 24 -> 'O'; case 25 -> 'P';
            case 30 -> 'A'; case 31 -> 'S'; case 32 -> 'D'; case 33 -> 'F';
            case 34 -> 'G'; case 35 -> 'H'; case 36 -> 'J'; case 37 -> 'K';
            case 38 -> 'L';
            case 44 -> 'Z'; case 45 -> 'X'; case 46 -> 'C'; case 47 -> 'V';
            case 48 -> 'B'; case 49 -> 'N'; case 50 -> 'M';
            case 71 -> '7'; case 72 -> '8'; case 73 -> '9';
            case 75 -> '4'; case 76 -> '5'; case 77 -> '6';
            case 79 -> '1'; case 80 -> '2'; case 81 -> '3';
            case 82 -> '0';
            case 74 -> '-';
            default -> (char) 0;
        };
    }
}
