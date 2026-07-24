// AuthState.java
package com.intermarche.pos.ui.auth;

import java.io.Serializable;

/**
 * In-memory authentication state of the register, carried by
 * {@code PosState}.
 * <p>
 * Semantic contract:
 * <ul>
 *   <li>{@link #isLocked} is THE gate every screen resource checks
 *       ({@code state.isLocked()} delegates here): true means the lock page
 *       owns the register.</li>
 *   <li>{@link #operatorId} / {@link #operatorName} identify the logged-in
 *       operator; the id is what the endorsement flow and the PIN change
 *       resolve the employee from.</li>
 *   <li>{@link #scannedBadgeId} is a one-shot mailbox between the badge
 *       reader (or the simulator) and the lock page: the scan handler
 *       deposits the badge while the register is locked, the lock page's
 *       polling picks it up exactly once ({@code /lock-data} clears it on
 *       read) and prefills the login field. It never survives a login or a
 *       logout.</li>
 * </ul>
 * The register boots locked by design: after a restart the recovered cart
 * waits behind the lock screen for the cashier to log back in.
 */
public class AuthState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** True while the lock page owns the register (no operator logged in). */
    public boolean isLocked = true;

    /** The display name of the logged-in operator, or an empty string. */
    public String operatorName = "";

    /** The database id of the logged-in operator, or null when locked. */
    public Long operatorId = null;

    /** The last badge scanned while locked, or null (one-shot mailbox). */
    public String scannedBadgeId = null;

    /**
     * Creates the state; the register starts locked.
     */
    public AuthState() {
        System.out.println("AuthState()");
    }

    /**
     * Logs an operator in and clears the badge mailbox.
     *
     * @param id the database id of the operator
     * @param name the display name of the operator
     */
    public void login(Long id, String name) {
        this.operatorId = id;
        this.operatorName = name;
        this.isLocked = false;
        this.scannedBadgeId = null;
    }

    /**
     * Logs the operator out and returns the register to the lock page.
     */
    public void logout() {
        this.isLocked = true;
        this.operatorName = "";
        this.operatorId = null; // On nettoie l'ID
        this.scannedBadgeId = null;
    }

    /**
     * Returns the database id of the logged-in operator.
     *
     * @return the operator id, or null when locked
     */
    public Long getOperatorId() {
        return operatorId;
    }

    /**
     * Deposits a badge scanned while the register is locked.
     *
     * @param badge the scanned badge identifier
     */
    public void setScannedBadge(String badge) { this.scannedBadgeId = badge; }

    /**
     * Clears the badge mailbox (called once the lock page consumed it).
     */
    public void clearScannedBadge() { this.scannedBadgeId = null; }
}
