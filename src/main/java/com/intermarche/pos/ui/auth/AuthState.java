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

    /**
     * Timestamp (epoch millis) of the operator's last cashier-facing request,
     * maintained by the lock filter. Drives the automatic idle lockout
     * (LC-01-03-02); 0 = no activity recorded yet.
     */
    public long lastActivityAt = 0L;

    /** The display name of the logged-in operator, or an empty string. */
    public String operatorName = "";

    /** The database id of the logged-in operator, or null when locked. */
    public Long operatorId = null;

    /**
     * The badge (N° caissière) of the logged-in operator, or null when locked.
     * <p>
     * Held here so the lock filter can name the operator being paused when it
     * journals a {@code REGISTER_LOCKED} event, without a database read. Like
     * the rest of {@link AuthState}, it is purely in-memory register state:
     * never persisted, never carried by the referential pull.
     */
    public String operatorBadgeId = null;

    /** The last badge scanned while locked, or null (one-shot mailbox). */
    public String scannedBadgeId = null;

    /**
     * The database id of the operator who VERROUILLA (paused) the register, kept
     * across the pause so the lock screen prefills their code and only they may
     * resume (EXP-12/13). Null when the register is FREE — logged out or
     * fermée — which is what {@link #isPaused()} tells apart from a pause.
     *
     * <p>A pause and a fermeture are two different exits and the difference is
     * carried here: a pause keeps the operator (and the in-memory ticket) aside
     * for a short resume; a fermeture clears everything.
     */
    public Long lockedOperatorId = null;

    /** The display name of the operator who paused the register, or empty. */
    public String lockedOperatorName = "";

    /** The badge of the operator who paused the register, or null. */
    public String lockedOperatorBadgeId = null;

    /**
     * True when the next resume must force a PIN change: a supervisor forced the
     * unlock of a register whose cashier forgot their password (EXP-14), so the
     * cashier keeps their session and drawer but is made to choose a new code at
     * the reprise. Cleared by a successful PIN change.
     */
    public boolean passwordChangeRequired = false;

    /**
     * The badge PHYSICALLY PRESENTED at this register, kept until the unlock
     * that follows it.
     *
     * <p>Distinct from {@link #scannedBadgeId}, which the lock screen empties
     * as soon as it has prefilled its field. When the back office lets a badge
     * open the register on its own (LC-01-01-03), the server has to tell a
     * SCANNED identifier from a TYPED one, and by then the mailbox above is
     * already empty — so the proof of the physical gesture is kept here and
     * consumed by the unlock.
     */
    public String presentedBadgeId = null;

    /**
     * True when the operator in post scanned their OWN badge, which is how a
     * register is closed without keying anything (LC-01-02-03).
     *
     * <p>A one-shot request, not a state: the scan chain raises it, the sale
     * screen's poll sees it and goes to the closing page, and the closing page
     * consumes it. Scanning ANOTHER employee's badge never raises it — changing
     * hands still goes through an explicit close, which is what this flag asks
     * for in the first place.
     */
    public boolean closeRequestedByBadge = false;

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
     * @param badgeId the badge (N° caissière) of the operator, or null
     */
    public void login(Long id, String name, String badgeId) {
        this.operatorId = id;
        this.operatorName = name;
        this.operatorBadgeId = badgeId;
        this.isLocked = false;
        this.scannedBadgeId = null;
        this.presentedBadgeId = null;
        this.closeRequestedByBadge = false;
        this.lockedOperatorId = null;
        this.lockedOperatorName = "";
        this.lockedOperatorBadgeId = null;
        this.lastActivityAt = System.currentTimeMillis();
    }

    /**
     * Logs the operator out and returns the register to the lock page — the
     * FERMETURE and the free lock landing. Unlike {@link #pause()}, it forgets
     * who was there: the paused-operator identity is cleared too.
     */
    public void logout() {
        this.isLocked = true;
        this.operatorName = "";
        this.operatorId = null; // On nettoie l'ID
        this.operatorBadgeId = null;
        this.scannedBadgeId = null;
        this.presentedBadgeId = null;
        this.closeRequestedByBadge = false;
        this.lockedOperatorId = null;
        this.lockedOperatorName = "";
        this.lockedOperatorBadgeId = null;
        this.passwordChangeRequired = false;
    }

    /**
     * VERROUILLE (pauses) the register: keeps the operator identity aside so the
     * lock screen prefills their code and only they may resume (EXP-12/13), and
     * leaves the in-memory ticket untouched (a pause is a short interruption, not
     * an abandon). The register becomes locked and nobody is "logged in" while it
     * is paused — the active operator fields are moved to the {@code
     * lockedOperator*} ones.
     */
    public void pause() {
        this.lockedOperatorId = this.operatorId;
        this.lockedOperatorName = this.operatorName;
        this.lockedOperatorBadgeId = this.operatorBadgeId;
        this.isLocked = true;
        this.operatorId = null;
        this.operatorName = "";
        this.operatorBadgeId = null;
        this.scannedBadgeId = null;
        this.presentedBadgeId = null;
        this.closeRequestedByBadge = false;
    }

    /**
     * Tells a PAUSE (verrouillage) apart from a free lock (fermeture / never
     * logged in): the register is locked AND an operator is held aside for the
     * resume.
     *
     * @return true when the register is paused rather than free
     */
    public boolean isPaused() {
        return isLocked && lockedOperatorId != null;
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
    public void setScannedBadge(String badge) {
        this.scannedBadgeId = badge;
        this.presentedBadgeId = badge;
    }

    /**
     * Consumes the proof that a badge was physically presented.
     *
     * @return the presented badge, or null when none is pending
     */
    public String takePresentedBadge() {
        String badge = this.presentedBadgeId;
        this.presentedBadgeId = null;
        return badge;
    }

    /**
     * Clears the badge mailbox (called once the lock page consumed it).
     */
    public void clearScannedBadge() { this.scannedBadgeId = null; }

    /**
     * Consumes the close asked for by a badge scan.
     *
     * @return true when the operator scanned their own badge to close
     */
    public boolean takeCloseRequest() {
        boolean requested = this.closeRequestedByBadge;
        this.closeRequestedByBadge = false;
        return requested;
    }
}
