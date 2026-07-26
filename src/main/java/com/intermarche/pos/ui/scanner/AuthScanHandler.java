package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Recognizes employee badges ({@code scan.pattern.badge}), first in the
 * chain: a badge must never fall through to the catalog.
 * <p>
 * Two mailboxes, one precedence: an ACTIVE ENDORSEMENT modal wins over the
 * lock screen (the manager badging over the cashier's shoulder is the more
 * immediate gesture), the lock screen comes second — and a badge scanned
 * while an operator is logged in and no modal is open is deliberately
 * IGNORED: no automatic operator switch, changing hands goes through an
 * explicit lock first.
 */
@ApplicationScoped
@Priority(0) // Priorité maximale
public class AuthScanHandler implements ScanContext.ScanHandler {

    /** Recognition regex of employee badges. */
    @ConfigProperty(name = "scan.pattern.badge")
    String badgePattern;

    /**
     * Deposits a recognized badge into the endorsement or lock mailbox.
     *
     * @param ctx the token walking the chain
     */
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