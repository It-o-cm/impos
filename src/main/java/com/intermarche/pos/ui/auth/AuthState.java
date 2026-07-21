// AuthState.java
package com.intermarche.pos.ui.auth;

import java.io.Serializable;

public class AuthState implements Serializable {
    private static final long serialVersionUID = 1L;

    public boolean isLocked = true;
    public String operatorName = "";

    public Long operatorId = null;

    public String scannedBadgeId = null;

    public AuthState() {
        System.out.println("AuthState()");
    }

    public void login(Long id, String name) {
        this.operatorId = id;
        this.operatorName = name;
        this.isLocked = false;
        this.scannedBadgeId = null;
    }

    public void logout() {
        this.isLocked = true;
        this.operatorName = "";
        this.operatorId = null; // On nettoie l'ID
        this.scannedBadgeId = null;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    // ... reste des méthodes (setScannedBadge, clearScannedBadge)
    public void setScannedBadge(String badge) { this.scannedBadgeId = badge; }
    public void clearScannedBadge() { this.scannedBadgeId = null; }
}