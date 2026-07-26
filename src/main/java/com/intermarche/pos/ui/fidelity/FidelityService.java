package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Attaches a fidelity card to the current ticket.
 * <p>
 * Two callers share this single entry point: the fidelity SCAN handler
 * (primary path — card recognized by the {@code scan.pattern.fidelity}
 * regex anywhere during the sale) and the manual-entry page (fallback for
 * an unreadable card). The next draft sync persists the card.
 */
@ApplicationScoped
public class FidelityService {

    /**
     * Attaches the card to the in-memory state and wakes the polling.
     *
     * @param state the current POS state
     * @param card the card number, scanned or typed
     */
    public void validateCard(PosState state, String card) {
        state.fidelity.assignCard(card);
        state.touch(); // Indispensable pour le polling
    }
}