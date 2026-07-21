package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FidelityService {
    public void validateCard(PosState state, String card) {
        state.fidelity.assignCard(card);
        state.touch(); // Indispensable pour le polling
    }
}