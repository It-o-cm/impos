package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;

// Contexte passé le long de la chaîne
/**
 * Token flowing through the scan chain — every code, whatever its source
 * (scanner gun, simulator, manual entry, search-result tap), becomes one
 * ScanContext walked through the handlers in {@code @Priority} order.
 * <p>
 * THE CHAIN CONTRACT, in full: each handler first bails when
 * {@code handled} is already true, recognizes its own family of codes,
 * acts, and sets {@code handled} — first recognizer wins, the rest never
 * run. Current order: badge (0), then fidelity / deposit voucher / 2x
 * scale label (all at 1 — the tie is safe ONLY because their recognition
 * domains are disjoint: a regex, a 298 prefix, a 2x EAN-13; a new handler
 * at a shared priority must keep that disjointness), then payment voucher
 * and catalog EAN (2), typed PLU (3), and the unknown-code fallback (100)
 * which is the only handler allowed to answer "I don't know". Handlers
 * missing a {@code @Priority} default to 100 in the assembler.
 */
public class ScanContext {
    /** The scanned or typed code. */
    public String code;
    /** The register state the handler acts on. */
    public PosState state;
    /** True once a handler recognized and consumed the code (stops the chain). */
    public boolean handled = false; // Permet d'arrêter la chaîne

    /**
     * Wraps a code and the state for one walk through the chain.
     *
     * @param code the scanned or typed code
     * @param state the register state
     */
    public ScanContext(String code, PosState state) {
        this.code = code;
        this.state = state;
    }

    /**
     * A link of the scan chain, discovered by CDI and ordered by
     * {@code @Priority}.
     */
    public interface ScanHandler {

        /**
         * Examines the context and consumes it when the code belongs to
         * this handler's family.
         *
         * @param context the token walking the chain
         */
        void handle(ScanContext context);
    }
}