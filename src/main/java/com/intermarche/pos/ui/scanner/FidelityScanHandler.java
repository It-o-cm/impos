package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.fidelity.FidelityService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Priority(1) // Juste après l'authentification
public class FidelityScanHandler implements ScanContext.ScanHandler {

    @ConfigProperty(name = "scan.pattern.fidelity")
    String fidelityPattern;

    @Inject
    FidelityService fidelityService;

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