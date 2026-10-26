package com.intermarche.pos.ui.endorsement;

import java.util.Locale;

/**
 * What the supervisor-authorisation panel asks of the supervisor
 * (BO-10-02-31), as the shop administered it ({@code supervisor.validation-mode}).
 *
 * <p>THE BARCODE IS A PHYSICAL PROOF, the code and the password are typed ones.
 * A mode naming the barcode accepts only a supervisor barcode the register's
 * scanner actually READ during the request: a badge number typed on the keyboard
 * is printed on the badge, and accepting it would turn the barcode into a second
 * password anybody can read. The same holds for the Dallas key, which only the
 * reader delivers.
 */
public enum SupervisorValidationMode {

    /** Supervisor code — typed, or read from the barcode — then password: the historical panel. */
    CODE_PASSWORD(false, true, true),

    /** The supervisor barcode, read, and nothing else. */
    BARCODE(true, false, false),

    /** The supervisor barcode read, then the supervisor code typed, then the password. */
    BARCODE_CODE_PASSWORD(true, true, true),

    /** The supervisor barcode read, then the password. */
    BARCODE_PASSWORD(true, false, true),

    /** The supervisor's Dallas key, touched to the reader, and nothing else. */
    DALLAS(false, false, false);

    /** Whether a supervisor barcode must have been read. */
    private final boolean barcode;

    /** Whether the supervisor code must be typed. */
    private final boolean code;

    /** Whether the password must be typed. */
    private final boolean password;

    /**
     * Declares what a mode asks.
     *
     * @param barcode whether a supervisor barcode must have been read
     * @param code whether the supervisor code must be typed
     * @param password whether the password must be typed
     */
    SupervisorValidationMode(boolean barcode, boolean code, boolean password) {
        this.barcode = barcode;
        this.code = code;
        this.password = password;
    }

    /**
     * Reads an administered mode, tolerating case and padding.
     *
     * @param administered the mode as the back office holds it, possibly null
     * @return the mode, {@link #CODE_PASSWORD} when nothing readable was
     *         administered — the panel every shop had before the parameter
     */
    public static SupervisorValidationMode of(String administered) {
        if (administered == null || administered.isBlank()) {
            return CODE_PASSWORD;
        }
        try {
            return valueOf(administered.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CODE_PASSWORD;
        }
    }

    /**
     * Whether a supervisor barcode must have been read.
     *
     * @return true for the three barcode modes
     */
    public boolean needsBarcode() {
        return barcode;
    }

    /**
     * Whether the supervisor code must be typed.
     *
     * @return true for the two code modes
     */
    public boolean needsCode() {
        return code;
    }

    /**
     * Whether the password must be typed.
     *
     * @return true for the three password modes
     */
    public boolean needsPassword() {
        return password;
    }
}
