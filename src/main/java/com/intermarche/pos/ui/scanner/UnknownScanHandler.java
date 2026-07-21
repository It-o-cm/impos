package com.intermarche.pos.ui.scanner;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Priority;

@ApplicationScoped
@Priority(100) // Exécuté en tout dernier
public class UnknownScanHandler implements ScanContext.ScanHandler {

    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        // Si on arrive ici et que la caisse est vérouillée, on ne fait rien
        // (pas d'erreur "Code Inconnu" sur l'écran de lock)
        if (ctx.state.isLocked()) return;

        ctx.state.ticket.transientError = "CODE INCONNU: " + ctx.code;
        ctx.handled = true;
    }
}