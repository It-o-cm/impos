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
     * The last Dallas key read while locked, or null (one-shot mailbox the
     * lock page empties, BO-01-02-07).
     */
    public String scannedDallasKey = null;

    /**
     * The Dallas key this register PHYSICALLY read, consumed by the one
     * sign-in it opens (BO-01-02-07): what keeps a key number typed in the
     * identifier field from opening anything.
     */
    public String presentedDallasKey = null;

    /**
     * The last badge scanned for a SUPERVISOR IDENTIFICATION, or null (one-shot
     * mailbox, the twin of {@link #scannedBadgeId} for the other identification
     * this register asks for).
     *
     * <p>PRESENTING A BADGE AND KEYING AN IDENTIFIER MUST HAVE THE SAME EFFECT,
     * everywhere an identifier is asked for. The register used to honour that on
     * the opening alone: the closing, the drawer-variance endorsement and the
     * cancellation of a paid ticket asked a supervisor for an identifier and
     * threw their badge away, so the only way through was to spell a login on an
     * alphabetic pad — on a till that may have no keyboard at all.
     */
    public String supervisorBadgeId = null;

    /**
     * The instant until which a screen is asking for a supervisor
     * identification, in epoch milliseconds; zero when none is.
     *
     * <p>A DEADLINE AND NOT A FLAG, because the thing being asked is a SCREEN and
     * a screen can be left without telling anyone — the browser goes to another
     * page, the till is walked away from, the session times out. A flag raised by
     * a page that never lowers it would leave this register routing every badge
     * to a panel nobody is looking at. The panel re-arms itself on each of its
     * own polls, so the arming lives exactly as long as the panel is on screen
     * and lapses by itself a breath after it leaves.
     */
    public long supervisorBadgeArmedUntil = 0L;

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
        this.supervisorBadgeId = null;
        this.supervisorBadgeArmedUntil = 0L;
        this.presentedBadgeId = null;
        this.scannedDallasKey = null;
        this.presentedDallasKey = null;
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
        this.supervisorBadgeId = null;
        this.supervisorBadgeArmedUntil = 0L;
        this.presentedBadgeId = null;
        this.scannedDallasKey = null;
        this.presentedDallasKey = null;
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
        this.supervisorBadgeId = null;
        this.supervisorBadgeArmedUntil = 0L;
        this.presentedBadgeId = null;
        this.scannedDallasKey = null;
        this.presentedDallasKey = null;
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
     * Deposits a Dallas key read while the register is locked (BO-01-02-07).
     *
     * @param key the normalized key identifier
     */
    public void setScannedDallasKey(String key) {
        this.scannedDallasKey = key;
        this.presentedDallasKey = key;
    }

    /**
     * Empties the Dallas key mailbox, for the lock page.
     *
     * @return the key read since the last call, or null
     */
    public String takeScannedDallasKey() {
        String key = this.scannedDallasKey;
        this.scannedDallasKey = null;
        return key;
    }

    /**
     * Consumes the proof that a Dallas key was physically read.
     *
     * @return the read key, or null when none is pending
     */
    public String takePresentedDallasKey() {
        String key = this.presentedDallasKey;
        this.presentedDallasKey = null;
        return key;
    }

    /**
     * Arms the supervisor-identification mailbox for a short while, which is how
     * a screen says "an identifier is being asked here, now".
     *
     * @param millis how long the arming lasts, in milliseconds
     */
    public void armSupervisorBadge(long millis) {
        this.supervisorBadgeArmedUntil = System.currentTimeMillis() + millis;
    }

    /**
     * Whether a screen is asking for a supervisor identification right now.
     *
     * @return true while the arming has not lapsed
     */
    public boolean supervisorBadgeArmed() {
        return this.supervisorBadgeArmedUntil > System.currentTimeMillis();
    }

    /**
     * Deposits a badge scanned while a supervisor identification is being asked
     * for. The arming is NOT consumed: the panel stays armed, so a supervisor
     * who presented the wrong badge may present another.
     *
     * @param badge the scanned badge identifier
     */
    public void setSupervisorBadge(String badge) {
        this.supervisorBadgeId = badge;
        this.presentedBadgeId = badge;
    }

    /**
     * Consumes the supervisor-identification mailbox.
     *
     * @return the scanned badge, or null when none is pending
     */
    public String takeSupervisorBadge() {
        String badge = this.supervisorBadgeId;
        this.supervisorBadgeId = null;
        return badge;
    }

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
