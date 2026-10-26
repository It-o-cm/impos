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

    /** Card entry mode returned by the terminal — contactless vs chip-and-PIN (EXP-54). */
    public static final String ENTRY_MODE = "D17";

    /** Terminal serial number, in the c18 response (LC-01-01-06). */
    public static final String TERMINAL_SERIAL = "D5Y";

    /** Server-forcing tag of the manual degraded mode (LC-07-08-02). */
    public static final String SERVER_FORCING = "VWAY";

    /**
     * The {@value #SERVER_FORCING} value of the primary monetique server, the one
     * an ordinary transaction is accepted on (LC-07-07-03, LC-13-04-03: the
     * maintenance addresses the servers 1 and 2): what the simulated terminals
     * answer when nothing forced another server (BO-04-01-48).
     */
    public static final String PRIMARY_SERVER = "1";

    /**
     * The {@value #SERVER_FORCING} value the manual degraded mode forces, the
     * secondary monetique server (LC-07-08-02): what the simulated terminals
     * answer for a forced degraded transaction (BO-04-01-48).
     */
    public static final String DEGRADED_SERVER = "4";

    /** The island label of the centralised monetique (BO-03-09-29). */
    public static final String ISLAND_LABEL = "D7V";

    /** Non-instantiable: constants only. */
    private VerifoneTags() {
    }
}
