package com.intermarche.pos.ui.hardware.terminal;

/**
 * Dictionary of the Verifone monetique tags known so far. The names come
 * from the functional requirements of the RFP questionnaire; their exact
 * semantics, formats and the complete dictionary REQUIRE THE PROTOCOL
 * SPECIFICATION — every constant below is a placeholder to be confirmed.
 */
public final class VerifoneTags {

    /** Payment mean reported by the terminal (LC-07-07-04). */
    public static final String PAYMENT_MEAN = "D16";

    /** Fallback payment mean when {D16} is unknown (LC-07-07-04). */
    public static final String PAYMENT_MEAN_FALLBACK = "D46";

    /** Terminal serial number, in the c18 response (LC-01-01-06). */
    public static final String TERMINAL_SERIAL = "D5Y";

    /** Server-forcing tag of the manual degraded mode (LC-07-08-02). */
    public static final String SERVER_FORCING = "VWAY";

    /** Non-instantiable: constants only. */
    private VerifoneTags() {
    }
}
