package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;

// Contexte passé le long de la chaîne
public class ScanContext {
    public String code;
    public PosState state;
    public boolean handled = false; // Permet d'arrêter la chaîne

    public ScanContext(String code, PosState state) {
        this.code = code;
        this.state = state;
    }

    public interface ScanHandler {
        void handle(ScanContext context);
    }
}