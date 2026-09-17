package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Recognizes employee badges ({@code scan.pattern.badge}), first in the
 * chain: a badge must never fall through to the catalog.
 * <p>
 * Three destinations, one precedence: an ACTIVE ENDORSEMENT modal wins over
 * the lock screen (the manager badging over the cashier's shoulder is the more
 * immediate gesture), the lock screen comes second, and on a register in use
 * the operator's OWN badge asks for the close (LC-01-02-03). Any OTHER badge
 * scanned there is deliberately IGNORED: no automatic operator switch,
 * changing hands goes through a close first.
 */
@ApplicationScoped
@Priority(0) // Priorité maximale
public class AuthScanHandler implements ScanContext.ScanHandler {

    /** Recognition regex of employee badges. */
    @ConfigProperty(name = "scan.pattern.badge")
    String badgePattern;

    /** The back-office parameters (badge-scan activation — BO-10-02-29/30). */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Deposits a recognized badge into the endorsement or lock mailbox.
     *
     * @param ctx the token walking the chain
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        // BO-10-02-29/30: when badge scanning is disabled, a badge is not
        // consumed here — it walks on rather than taking the post or endorsing,
        // so the operator keys an identifier by hand instead.
        if (posSettingsService.badgeScanEnabled() && ctx.code.matches(badgePattern)) {
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
            // 3. Sinon : le badge de l'opérateur EN POSTE ferme la caisse
            // (LC-01-02-03). Le badge est la preuve d'identité, donc aucun mot
            // de passe n'est demandé ; la fermeture elle-même reste soumise à
            // ses propres contrôles (tickets en attente), que la page de
            // fermeture applique.
            else if (ctx.code.equals(state.auth.operatorBadgeId)) {
                state.auth.closeRequestedByBadge = true;
                state.touch();
                ctx.handled = true;
            }
            // 4. Sinon on ignore le badge (pas de changement d'opérateur auto)
        }
    }
}