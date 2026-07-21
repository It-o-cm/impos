package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Priority(0) // Priorité maximale
public class AuthScanHandler implements ScanContext.ScanHandler {

    @ConfigProperty(name = "scan.pattern.badge")
    String badgePattern;

    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        if (ctx.code.matches(badgePattern)) {
            PosState state = ctx.state;

            // 1. Priorité : Endossement (Manager)
            if (state.endorsement.active) {
                state.endorsement.setScannedBadge(ctx.code);
                state.touch();
                ctx.handled = true;
            }
            // 2. Sinon : Écran de verrouillage (Login Caissier)
            else if (state.isLocked()) {
                state.auth.setScannedBadge(ctx.code);
                state.touch();
                ctx.handled = true;
            }
            // 3. Sinon on ignore le badge (pas de changement d'opérateur auto)
        }
    }
}