package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.auth.AuthService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * Manager endorsement service: credential check and endorsement request
 * lifecycle.
 * <p>
 * Phase 2: the check verifies the MANAGER or ADMIN role (any active employee
 * used to pass), goes through the shared lockout-aware credential check, and
 * every granted or refused endorsement is journaled with its action code.
 * <p>
 * Consequences of sharing the credential check with the register unlock:
 * ONE failure counter per account (a manager burning attempts on an
 * endorsement locks their own login too — deliberate, one credential = one
 * lockout), and the endorsing manager does NOT need to be the logged-in
 * operator: a cashier stays logged in while a manager validates over their
 * shoulder, which is exactly the four-eyes gesture the pattern models. The
 * journal entry carries the ACTION CODE, so the audit trail says what was
 * endorsed, by whom, granted or refused.
 */
@ApplicationScoped
public class EndorsementService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EndorsementService.class);

    @Inject
    AuthService authService;

    @Inject
    TechnicalEventService technicalEventService;

    /** Tells the node a request was settled at the till (BO-04-02-03). */
    @Inject
    com.intermarche.pos.service.SupervisorSettleService supervisorSettleService;

    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** This register's checkout point and its technical hostess (BO-03-09-08). */
    @Inject
    com.intermarche.pos.service.CheckoutPointService checkoutPointService;

    /** LC-01-05-01: one function's explicit policy or consumed approval, never an operator role. */
    @Inject FunctionApprovalContext functionApproval = new FunctionApprovalContext();

    /** The gesture held now, read for the profiles it requires (BO-04-02-01). */
    @Inject
    PosState posState;

    /** The operator messages the control points speak with (BO-10-02-01 to -10). */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * Whether a negative gesture may run WITHOUT opening the endorsement modal
     * (EXP-32/33): true when the gesture's administered flag is off, OR the logged
     * operator is themselves a supervisor (LC-01-05-07), OR a supervisor session is
     * currently open on the ticket (EXP-33). Otherwise the gesture must be endorsed.
     *
     * @param state the current POS state
     * @param gestureKey the gesture suffix (e.g. "cancel-line", "abandon", "refund")
     * @return true when the gesture is already authorized and needs no modal
     */
    public boolean gestureAuthorized(PosState state, String gestureKey) {
        LOGGER.info("Entering method gestureAuthorized with gestureKey: " + gestureKey);
        boolean authorized = functionApproval.authorized() || !posSettingsService.gestureEndorsementRequired(gestureKey)
                || operatorIsSupervisor(state)
                || state.supervisorSessionBadge != null;
        LOGGER.info("Exiting method gestureAuthorized");
        return authorized;
    }

    /**
     * Reports actual approval of this exact function, excluding a store NONE exemption.
     * @return true only for a consumed supervisor continuation (LC-01-05-01)
     */
    public boolean functionApproved() {
        return functionApproval.approved();
    }

    /**
     * The single decision point for a gesture that ALWAYS needs an endorsement —
     * one carrying no administered on/off flag (training toggle, operator-badge
     * print): a modal is needed UNLESS the logged operator is themselves a
     * supervisor (P6, LC-01-05-07) or a supervisor session is open (EXP-33). A
     * connected supervisor never gets asked for their own aval.
     *
     * @param state the current POS state
     * @return true when the gesture is already authorized and needs no modal
     */
    public boolean selfAuthorized(PosState state) {
        LOGGER.info("Entering method selfAuthorized");
        boolean authorized = functionApproval.authorized() || operatorIsSupervisor(state) || state.supervisorSessionBadge != null;
        LOGGER.info("Exiting method selfAuthorized");
        return authorized;
    }

    /**
     * Opens a supervisor session on the ticket when the administered scope is
     * PER_TICKET (EXP-33): the endorsing manager's badge stays attached to the sale
     * so the following guarded gestures proceed without re-asking, until FIN
     * SUPERVISEUR or the end of the ticket. A no-op in PER_OP mode.
     *
     * @param state the current POS state
     * @param endorserBadge the badge (or login) of the manager who just endorsed
     */
    public void openSupervisorSessionIfPerTicket(PosState state, String endorserBadge) {
        LOGGER.info("Entering method openSupervisorSessionIfPerTicket");
        if ("PER_TICKET".equals(posSettingsService.endorsementScope())) {
            state.supervisorSessionBadge = endorserBadge;
        }
        LOGGER.info("Exiting method openSupervisorSessionIfPerTicket");
    }

    /**
     * Ends an open supervisor session (EXP-33): the FIN SUPERVISEUR gesture.
     *
     * @param state the current POS state
     */
    public void endSupervisorSession(PosState state) {
        LOGGER.info("Entering method endSupervisorSession");
        state.supervisorSessionBadge = null;
        LOGGER.info("Exiting method endSupervisorSession");
    }

    /**
     * The administered level of a CONTROL POINT (BO-10-02-01 to -10), as the
     * back office states it and before anyone is looked at.
     *
     * @param point the control point
     * @return its administered level, its own default when the value cannot be
     *         read
     */
    public ControlLevel level(ControlPoint point) {
        LOGGER.info("Entering method level with point: " + point);
        ControlLevel level = ControlLevel.of(
                posSettingsService.controlLevel(point.getSettingKey()),
                point.getDefaultLevel());
        LOGGER.info("Exiting method level");
        return level;
    }

    /**
     * What a control point does HERE AND NOW, the operator included
     * (BO-10-02-01 to -10).
     * <p>
     * The administered level, with ONE resolution applied: a gesture awaiting a
     * supervisor's endorsement needs none when a supervisor is the one
     * operating (LC-01-05-07) or when a supervisor session is open on the
     * ticket (EXP-33) — the same shortcut {@link #selfAuthorized} already gives
     * every other guarded gesture, said once here so that eight call sites do
     * not each say it their own way.
     * <p>
     * A refusal is NOT resolved away: {@link ControlLevel#BLOCK} means the shop
     * decided the gesture does not happen at this register, and a supervisor
     * standing there does not change that.
     *
     * @param state the current POS state
     * @param point the control point
     * @return what the caller must do, never null
     */
    public ControlLevel check(PosState state, ControlPoint point) {
        LOGGER.info("Entering method check with point: " + point);
        ControlLevel level = level(point);
        if (level == ControlLevel.ENDORSE && selfAuthorized(state)) {
            LOGGER.info("Exiting method check");
            return ControlLevel.NONE;
        }
        if (level == ControlLevel.ENDORSE && !point.isEndorsable()) {
            // Said once, here: a point that fires where no ceremony exists
            // refuses rather than promising an aval nobody can give.
            LOGGER.info("Exiting method check");
            return ControlLevel.BLOCK;
        }
        LOGGER.info("Exiting method check");
        return level;
    }

    /**
     * Whether the drawer may open OUTSIDE a sale (BO-10-02-04), and tells the
     * operator when it may not.
     * <p>
     * Two screens open the drawer without settling anything — the register
     * taken over at the lock screen, and the Z count — and both ask this one
     * question rather than each writing the control their own way. The point is
     * not endorsable, so a shop administering an aval gets a refusal here; see
     * {@link ControlPoint#isEndorsable()}.
     *
     * @param state the current POS state
     * @return true when the drawer may open
     */
    public boolean drawerOutOfSaleAllowed(PosState state) {
        LOGGER.info("Entering method drawerOutOfSaleAllowed");
        ControlLevel level = check(state, ControlPoint.DRAWER_OUT_OF_SALE);
        if (level == ControlLevel.WARN) {
            state.ticket.setWarning(controlMessage(ControlPoint.DRAWER_OUT_OF_SALE, level));
        } else if (!level.allows()) {
            state.ticket.setError(controlMessage(ControlPoint.DRAWER_OUT_OF_SALE, level));
        }
        LOGGER.info("Exiting method drawerOutOfSaleAllowed");
        return level.allows();
    }

    /**
     * The sentence the operator reads when a control point speaks
     * (BO-10-02-01 to -10).
     *
     * <p>Two wordings and eight gestures, not sixteen sentences: the gesture
     * names itself and the level says what happened to it. An operator reads
     * the same two sentences wherever a control fires, which is what makes them
     * recognisable at a glance.
     *
     * @param point the control point speaking
     * @param level what it decided
     * @return the sentence, never null
     */
    public String controlMessage(ControlPoint point, ControlLevel level) {
        LOGGER.info("Entering method controlMessage with point: " + point + ", level: " + level);
        String sentence = i18n.format(
                level == ControlLevel.BLOCK ? "op.controleRefus" : "op.controleAlerte",
                i18n.get(point.getLabelKey()));
        LOGGER.info("Exiting method controlMessage");
        return sentence;
    }

    /**
     * Verifies that the presented credentials belong to an active employee
     * holding the MANAGER or ADMIN role, PIN lockout enforced.
     *
     * @param login the badge id or login name
     * @param password the raw PIN
     * @return true if the credentials are valid and the role is sufficient
     */
    public boolean isManager(String login, String password) {
        LOGGER.info("Entering method isManager with login: " + login + ", password: ***");
        AuthService.CredentialStatus status = authService.checkCredentials(login, password);
        LOGGER.info("Exiting method isManager");
        return status.isSuccess()
                && (status.employee.role == Employee.EmployeeRole.MANAGER
                    || status.employee.role == Employee.EmployeeRole.ADMIN);
    }

    /**
     * Whether an employee is the TECHNICAL HOSTESS attached to this register
     * (BO-03-09-08): on a self-service point, the hostess the back office chose
     * in the list of the technical hostesses endorses the interventions of her
     * lane whatever the endorsing roles.
     *
     * @param employee the holder presenting their credentials
     * @return true on a self-service point whose administered hostess is that
     *         employee
     */
    boolean isLaneHostess(Employee employee) {
        if (checkoutPointService == null || employee.badgeId == null) {
            return false;
        }
        com.intermarche.pos.domain.store.CheckoutPoint point = checkoutPointService.own();
        return point != null && point.isSelfService()
                && employee.role == Employee.EmployeeRole.TECHNICAL_HOSTESS
                && employee.badgeId.equals(point.technicalHostessBadge);
    }

    /**
     * Whether a holder may endorse the gesture held now ({@code BO-04-02-01}).
     *
     * <p>A gesture whose rule names its own validating PROFILES is endorsed by
     * a holder of one of them and nobody else. Otherwise the endorsing roles,
     * the supervisor card (BO-03-06-51), the lane's technical hostess
     * (BO-03-09-08) and the administered endorsing profiles all endorse.
     *
     * @param holder the active holder presenting their credentials
     * @return true when that holder endorses
     */
    boolean mayEndorse(Employee holder) {
        java.util.List<String> required = posState == null || posState.endorsement == null
                ? java.util.List.of() : posState.endorsement.requiredProfiles;
        if (!required.isEmpty()) {
            return holdsProfile(holder, required);
        }
        return endorses(holder.role) || holder.supervisorCard || isLaneHostess(holder)
                || holdsProfile(holder, administeredProfiles());
    }

    /**
     * The profiles the store administered as endorsing whatever the role
     * ({@code BO-04-02-01}).
     *
     * @return the profile names, empty on a service built without its parameters
     */
    java.util.List<String> administeredProfiles() {
        return posSettingsService == null ? java.util.List.of()
                : names(posSettingsService.endorsementProfiles());
    }

    /**
     * Splits a semicolon- or comma-separated list of profile names, dropping
     * the blanks.
     *
     * @param raw the list, possibly null
     * @return the trimmed names, never null
     */
    static java.util.List<String> names(String raw) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (raw == null) {
            return names;
        }
        for (String part : raw.split("[;,]")) {
            if (!part.isBlank()) {
                names.add(part.trim());
            }
        }
        return names;
    }

    /**
     * Whether a holder is bound to one of the named profiles
     * ({@code BO-04-02-01}), compared without regard to case.
     *
     * @param holder the holder
     * @param profiles the profile names
     * @return true when one of the holder's profiles is named
     */
    static boolean holdsProfile(Employee holder, java.util.List<String> profiles) {
        for (String held : names(holder.profileNames)) {
            for (String wanted : profiles) {
                if (held.equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a role may endorse a guarded gesture ({@code BO-04-02-01}).
     *
     * <p>ADMINISTERED, where it used to be written in this method: a shop
     * that wants its head cashiers to endorse a line cancellation names
     * their role instead of asking for a release. The two roles that
     * endorsed before the list existed remain the default, so a shop that
     * administers nothing behaves exactly as it did.
     *
     * <p>A list naming nothing readable falls back on those two rather than
     * forbidding everything: an administered list is a typo away from
     * locking a shop out of its own endorsements, and the fallback is what
     * makes that typo survivable.
     *
     * @param role the role of the holder presenting their credentials
     * @return true when that role endorses
     */
    boolean endorses(Employee.EmployeeRole role) {
        if (role == null) {
            return false;
        }
        String administered = posSettingsService == null ? null
                : posSettingsService.endorsementRoles();
        if (administered == null || administered.isBlank()) {
            return role == Employee.EmployeeRole.MANAGER || role == Employee.EmployeeRole.ADMIN;
        }
        boolean named = false;
        for (String part : administered.split(";")) {
            String typed = part.trim();
            if (typed.isEmpty()) {
                continue;
            }
            named = true;
            if (typed.equalsIgnoreCase(role.name())) {
                return true;
            }
        }
        // A list that names nothing readable is not a list that forbids
        // everything: it is a typo, and a typo must not lock a shop out of
        // its own supervisor endorsements.
        return !named
                && (role == Employee.EmployeeRole.MANAGER
                    || role == Employee.EmployeeRole.ADMIN);
    }

    /**
     * The back office's AUTHORISATION of the gesture this lane is holding
     * ({@code BO-04-02-02}), journalled the moment it is consumed, or null
     * when nobody authorised anything.
     *
     * <p>Journalled HERE and not when the order landed, because the journal
     * of a register states what the register DID: an authorisation nobody
     * used is a message, not an endorsement. Consuming it leaves the same
     * event a typed code leaves, naming the back office instead of a badge.
     *
     * @param state the current POS state, possibly null
     * @return who granted it, or null when nobody did
     */
    public String remotelyGranted(PosState state) {
        LOGGER.info("Entering method remotelyGranted");
        String granted = state == null || state.endorsement == null
                ? null : state.endorsement.remoteGrant;
        if (granted != null && !granted.isBlank()) {
            technicalEventService.log(TechnicalEvent.EventType.ENDORSEMENT_GRANTED,
                    "Autorisation accordée par le back-office (" + granted.trim() + ")");
            if (supervisorSettleService != null) {
                // LC-01-05-08: the lane's other open requests no longer need anybody.
                supervisorSettleService.settledAtTill();
            }
            LOGGER.info("Exiting method remotelyGranted");
            return granted.trim();
        }
        LOGGER.info("Exiting method remotelyGranted");
        return null;
    }

    /**
     * Verifies the credentials for an endorsement and journals the outcome
     * with the endorsed action code and the presented login.
     *
     * @param login the badge id or login name
     * @param password the raw PIN
     * @param actionCode the action code requiring authorization
     * @return true if the endorsement is granted
     */
    public boolean authorize(String login, String password, String actionCode) {
        LOGGER.info("Entering method authorize with login: " + login + ", password: ***" + ", actionCode: " + actionCode);
        boolean granted = authorizeOutcome(login, password, actionCode)
                == AuthService.CheckOutcome.GRANTED;
        LOGGER.info("Exiting method authorize");
        return granted;
    }

    /**
     * Verifies the credentials for an endorsement and journals the outcome,
     * telling a REFUSAL from a LOCKOUT.
     *
     * <p>Same check and same journal entry as {@link #authorize}, which delegates
     * here: this form exists for the screens that must name the lockout instead of
     * answering "autorisation refusée" to a supervisor whose account is blocked —
     * the refusal that cannot be acted upon by retyping.
     *
     * @param login the badge id or login name
     * @param password the raw PIN
     * @param actionCode the action code requiring authorization
     * @return which of the three outcomes the presented credentials got
     */
    public AuthService.CheckOutcome authorizeOutcome(String login, String password, String actionCode) {
        LOGGER.info("Entering method authorizeOutcome with login: " + login + ", password: ***" + ", actionCode: " + actionCode);
        AuthService.CredentialStatus status = authService.checkCredentials(login, password);
        // BO-03-06-51: a holder of a supervisor card endorses whatever their role.
        boolean granted = status.isSuccess() && mayEndorse(status.employee);
        // BO-04-01-39: name the endorsing operator in the first-class badge
        // column, not only inside the free-text detail — otherwise the journal's
        // "N° caissière" range filter (matched on operatorBadgeId) never returns
        // an endorsement event. A badge-based endorsement then filters exactly;
        // one keyed by login name carries that login as its identifier.
        technicalEventService.log(
                granted ? TechnicalEvent.EventType.ENDORSEMENT_GRANTED
                        : TechnicalEvent.EventType.ENDORSEMENT_DENIED,
                actionCode + " par " + (login != null ? login : "?"),
                login);
        if (granted && supervisorSettleService != null) {
            // BO-04-02-03: a supervisor settled it HERE, so the back office
            // marks the demand « déjà traitée en caisse » rather than sending
            // somebody to a lane that no longer needs anyone. Fire and forget:
            // the endorsement is already granted and journalled.
            supervisorSettleService.settledAtTill();
        }
        LOGGER.info("Exiting method authorizeOutcome");
        if (granted) {
            return AuthService.CheckOutcome.GRANTED;
        }
        // A LOCKED account that presents the right PIN is still locked: the
        // lockout wins over the credential, and over the role — an account under
        // lockout is not told whether it would have been manager enough.
        return status.locked
                ? AuthService.CheckOutcome.LOCKED : AuthService.CheckOutcome.DENIED;
    }

    /**
     * The supervisor validation mode the shop administered (BO-10-02-31).
     *
     * @return the mode, code and password on a service built without its
     *         parameters
     */
    public SupervisorValidationMode validationMode() {
        LOGGER.info("Entering method validationMode");
        SupervisorValidationMode mode = SupervisorValidationMode.of(
                posSettingsService == null ? null : posSettingsService.supervisorValidationMode());
        LOGGER.info("Exiting method validationMode");
        return mode;
    }

    /**
     * Verifies the endorsement panel under a mode that involves the barcode or
     * the Dallas key (BO-10-02-31), and journals the outcome like
     * {@link #authorizeOutcome}.
     *
     * <p>The barcode and the key count only as the register READ them during
     * this request ({@link EndorsementState#presentedBadge},
     * {@link EndorsementState#presentedDallasKey}): what the panel posts names
     * nothing by itself. A barcode with a code requires the two to designate the
     * same holder; a barcode or a key alone endorses for a holder who may
     * endorse and whose account is not locked.
     *
     * @param state the current POS state
     * @param mode the administered mode, not {@code CODE_PASSWORD}
     * @param code the supervisor code typed on the panel, possibly null
     * @param password the password typed on the panel, possibly null
     * @param actionCode the action code requiring authorization
     * @return true if the endorsement is granted
     */
    public boolean authorizePanel(PosState state, SupervisorValidationMode mode, String code,
            String password, String actionCode) {
        LOGGER.info("Entering method authorizePanel with mode: " + mode + ", actionCode: " + actionCode);
        boolean granted;
        // A proof serves ONE validation: a refused key or barcode must be presented
        // again, or a panel that resubmits by itself would replay it forever.
        String key = state.endorsement.presentedDallasKey;
        String badge = state.endorsement.presentedBadge;
        state.endorsement.presentedDallasKey = null;
        state.endorsement.presentedBadge = null;
        if (mode == SupervisorValidationMode.DALLAS) {
            granted = key != null && grantedWithoutSecret(Employee.findActiveDallasKey(key), key, actionCode);
        } else {
            if (badge == null) {
                granted = false;
                technicalEventService.log(TechnicalEvent.EventType.ENDORSEMENT_DENIED,
                        actionCode + " par ? (code-barres superviseur non lu)", null);
            } else if (!mode.needsPassword()) {
                granted = grantedWithoutSecret(Employee.findActiveLogin(badge), badge, actionCode);
            } else if (mode.needsCode() && !sameHolder(badge, code)) {
                granted = false;
                technicalEventService.log(TechnicalEvent.EventType.ENDORSEMENT_DENIED,
                        actionCode + " par " + badge + " (code superviseur différent)", badge);
            } else {
                granted = authorize(badge, password, actionCode);
            }
        }
        LOGGER.info("Exiting method authorizePanel");
        return granted;
    }

    /**
     * Whether a typed supervisor code designates the holder of the barcode the
     * register read (BO-10-02-31).
     *
     * @param badge the barcode read
     * @param code the code typed, possibly null
     * @return true when both name the same active employee
     */
    private boolean sameHolder(String badge, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        Employee byBadge = Employee.findActiveLogin(badge);
        Employee byCode = Employee.findActiveLogin(code.trim().toLowerCase());
        return byBadge != null && byCode != null && java.util.Objects.equals(byBadge.id, byCode.id);
    }

    /**
     * Grants an endorsement on a physical proof alone — the barcode or the
     * Dallas key — and journals it (BO-10-02-31).
     *
     * @param holder the active employee the proof designates, or null
     * @param presented the proof as read, for the journal
     * @param actionCode the action code requiring authorization
     * @return true when the holder may endorse and is not locked out
     */
    private boolean grantedWithoutSecret(Employee holder, String presented, String actionCode) {
        boolean granted = holder != null && !holder.isCurrentlyLocked() && mayEndorse(holder);
        technicalEventService.log(
                granted ? TechnicalEvent.EventType.ENDORSEMENT_GRANTED
                        : TechnicalEvent.EventType.ENDORSEMENT_DENIED,
                actionCode + " par " + presented, presented);
        if (granted && supervisorSettleService != null) {
            supervisorSettleService.settledAtTill();
        }
        return granted;
    }

    /**
     * Opens an endorsement request for the given action.
     *
     * @param state the current POS state
     * @param actionCode the action code requiring authorization
     */
    public void requestAuthorization(PosState state, String actionCode) {
        LOGGER.info("Entering method requestAuthorization with state: " + state + ", actionCode: " + actionCode);
        state.endorsement.request(actionCode);
        sendToBackOffice(state);
        state.touch();
        LOGGER.info("Exiting method requestAuthorization");
    }

    /**
     * Opens an endorsement request for a gesture that carries a form and
     * belongs to a screen of its own.
     *
     * <p>This is what lets the drawer screens use the shared modal instead of
     * their own badge and PIN boxes: the typed form waits here, inert, and the
     * dispatch replays it once a manager credential passes.
     *
     * @param state the current POS state
     * @param actionCode the action code requiring authorization
     * @param form the gesture's fields, copied as parked values
     * @param returnPath where to land once the gesture ran
     */
    public void requestAuthorization(PosState state, String actionCode,
            java.util.Map<String, String> form, String returnPath) {
        LOGGER.info("Entering method requestAuthorization with state: " + state + ", actionCode: " + actionCode
                + ", returnPath: " + returnPath);
        state.endorsement.request(actionCode, form, returnPath);
        sendToBackOffice(state);
        state.touch();
        LOGGER.info("Exiting method requestAuthorization");
    }

    /**
     * Returns true when the LOGGED operator already holds a supervising role
     * (MANAGER or ADMIN, the same roles that validate endorsements) — the
     * connected-supervisor shortcut of LC-01-05-07. Looked up live in the
     * referential: a role revoked by a pull is effective immediately.
     *
     * @param state the current POS state
     * @return true when the logged operator can approve without a credential
     */
    public boolean operatorIsSupervisor(PosState state) {
        LOGGER.info("Entering method operatorIsSupervisor with state: " + state);
        Long operatorId = state.auth.getOperatorId();
        if (operatorId == null) {
            LOGGER.info("Exiting method operatorIsSupervisor");
            return false;
        }
        com.intermarche.pos.domain.people.Employee operator =
                com.intermarche.pos.domain.people.Employee.findById(operatorId);
        LOGGER.info("Exiting method operatorIsSupervisor");
        return operator != null && operator.active
                && (operator.role == com.intermarche.pos.domain.people.Employee.EmployeeRole.MANAGER
                    || operator.role == com.intermarche.pos.domain.people.Employee.EmployeeRole.ADMIN);
    }

    /**
     * Opens an endorsement request for a price modification.
     *
     * @param state the current POS state
     * @param type the modification type (REMISE, DISCOUNT, FORCE_PRICE)
     * @param uid the uid of the targeted ticket line
     * @param value the modification value (euros or percent depending on the type)
     */
    public void requestPriceModification(PosState state, PriceModType type, String uid, BigDecimal value) {
        LOGGER.info("Entering method requestPriceModification with state: " + state + ", type: " + type + ", uid: " + uid + ", value: " + value);
        state.endorsement.requestPriceModification(type, uid, value);
        sendToBackOffice(state);
        state.touch();
        LOGGER.info("Exiting method requestPriceModification");
    }

    /**
     * Sends the request that just opened to the back office ({@code LC-01-05-08}),
     * where it can be authorised or refused without anybody walking to the lane.
     *
     * <p>Fire and forget, like the other upward notices: the cashier can still
     * have it endorsed here with a code. A register without a store node sends
     * nothing and its request is not marked as sent, so no refusal can ever be
     * taken for its answer.
     *
     * @param state the current POS state, its request already open
     */
    private void sendToBackOffice(PosState state) {
        if (supervisorSettleService != null) {
            state.endorsement.remoteRequested = supervisorSettleService.authorizationRequested(
                    describe(state.endorsement), state.getOperatorName());
        }
    }

    /**
     * Names the pending gesture the way the back office board shows it
     * ({@code LC-01-05-08}): a price modification by its type and value, any other
     * gesture by its action code, without the technical line identifier a line
     * cancellation carries.
     *
     * @param endorsement the open request
     * @return the gesture's description
     */
    static String describe(EndorsementState endorsement) {
        if (endorsement.pendingPriceType != null) {
            return endorsement.pendingPriceType.name() + " "
                    + endorsement.pendingValue.stripTrailingZeros().toPlainString();
        }
        return endorsement.requestedAction.replaceFirst("_[0-9a-fA-F-]{36}$", "");
    }

    /**
     * Abandons the pending request at the cashier's demand ({@code LC-01-05-08}):
     * a request that went to the back office is withdrawn from its board, unless
     * the lane also holds a service request the cashier did not abandon.
     *
     * @param state the current POS state
     */
    public void abandonRequest(PosState state) {
        LOGGER.info("Entering method abandonRequest");
        if (state.endorsement.remoteRequested
                && state.serviceRequest == PosState.ServiceRequestStatus.NONE) {
            supervisorSettleService.authorizationWithdrawn();
        }
        clearRequest(state);
        LOGGER.info("Exiting method abandonRequest");
    }

    /**
     * Clears the current endorsement request.
     *
     * @param state the current POS state
     */
    public void clearRequest(PosState state) {
        LOGGER.info("Entering method clearRequest with state: " + state);
        state.endorsement.clear();
        // LC-02-11-04: an abandoned endorsement must not donate its reason to the next gesture.
        state.ticket.pendingCancelUid = null;
        state.ticket.pendingCancelReasonCode = null;
        state.ticket.pendingCancelReasonLabel = null;
        state.touch();
        LOGGER.info("Exiting method clearRequest");
    }
}
