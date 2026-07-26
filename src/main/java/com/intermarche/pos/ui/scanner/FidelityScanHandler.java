package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.fidelity.FidelityService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Recognizes fidelity cards ({@code scan.pattern.fidelity}) and attaches
 * them to the running ticket — the primary path, the manual page being the
 * fallback. Inert while the register is locked (a card on the lock screen
 * means nothing).
 */
@ApplicationScoped
@Priority(1) // Juste après l'authentification
public class FidelityScanHandler implements ScanContext.ScanHandler {

    /** Recognition regex of fidelity cards. */
    @ConfigProperty(name = "scan.pattern.fidelity")
    String fidelityPattern;

    @Inject
    FidelityService fidelityService;

    /**
     * Attaches a recognized card to the ticket.
     *
     * @param ctx the token walking the chain
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        // On n'applique la fidélité que si la caisse est déverrouillée
        if (!ctx.state.isLocked() && ctx.code.matches(fidelityPattern)) {
            fidelityService.validateCard(ctx.state, ctx.code);
            ctx.handled = true;
        }
    }
}