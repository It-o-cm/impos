package com.intermarche.pos.ui.scanner;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Priority;

@ApplicationScoped
@Priority(100) // Exécuté en tout dernier
public class UnknownScanHandler implements ScanContext.ScanHandler {

    /**
     * Stamps the "unknown code" error onto the ticket when no earlier link of
     * the chain recognized the scanned code.
     *
     * @param ctx the scan context carrying the code and the register state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        // Si on arrive ici et que la caisse est vérouillée, on ne fait rien
        // (pas d'erreur "Code Inconnu" sur l'écran de lock)
        if (ctx.state.isLocked()) return;

        // Through setError, never by assigning transientError: only setError
        // calls onChange(), which bumps the polling version. Assigning the
        // field left the version untouched, /ticket-fragment answered
        // "changed: false" and the message never reached the screen.
        ctx.state.ticket.setError("CODE INCONNU: " + ctx.code);
        ctx.handled = true;
    }
}