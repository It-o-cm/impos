package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.PosState;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Attaches a fidelity card to the current ticket.
 * <p>
 * Two callers share this single entry point: the fidelity SCAN handler
 * (primary path — card recognized by the {@code scan.pattern.fidelity}
 * regex anywhere during the sale) and the manual-entry page (fallback for
 * an unreadable card). The next draft sync persists the card.
 */
@ApplicationScoped
public class FidelityService {

    private static final Logger LOGGER = Logger.getLogger(FidelityService.class);

    /** The loyalty-service client (phase: imfid integration, lot 1). */
    @Inject
    ImfidClient imfidClient;

    /** Administered settings — carries the holder-name display flag. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Circuit breaker on imfid, mirroring the valuation engine's: after a
     * failure, no further call for {@code retrySeconds} — a dead loyalty
     * service never taxes every scan with its timeout (spec §2.2: the sale
     * is never blocked).
     */
    private volatile long fidSkipUntil = 0L;

    /** Breaker window, seconds. */
    private static final int RETRY_SECONDS = 10;

    /**
     * Announces the loyalty wiring once at boot, like the valuation engine.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (imfidClient.isConfigured()) {
            LOGGER.infof("Service fidélité imfid ACTIF: %s", imfidClient.targetUrl());
        } else {
            LOGGER.info("Service fidélité imfid NON CONFIGURÉ: earn et paiement fidélité désactivés");
        }
    }

    /** Revaluation trigger on card attachment (fresh couple with the card). */
    @Inject
    com.intermarche.pos.ui.ticket.TicketService ticketService;

    /**
     * Attaches the card to the in-memory state and wakes the polling — then
     * REVALUES the cart: the engine request must carry the customerCode, and
     * the earn projection must be fed a couple THAT SEES THE CARD (a couple
     * serialized before the attachment projects a spec-correct but useless
     * "no card = zero"). The revaluation path itself refreshes the earn
     * (onValuation). Found by the demo pre-flight (fid01).
     *
     * @param state the current POS state
     * @param card the card number, scanned or typed
     */
    public void validateCard(PosState state, String card) {
        LOGGER.info("Entering method validateCard with state: " + state + ", card: " + card);
        state.fidelity.assignCard(card);
        refreshAccountDisplay(state);
        if (!state.ticket.items.isEmpty()) {
            ticketService.recalculateTotal(state);
        }
        state.touch(); // Indispensable pour le polling
        LOGGER.info("Exiting method validateCard");
    }

    /**
     * Reads the attached card's account once to feed the main-screen summary
     * (status + available balance). Best effort under the breaker: a silent
     * degraded leaves the fields null and the screen simply shows the bare
     * card number — the sale is never blocked (spec §2.2).
     *
     * @param state the current POS state
     */
    private void refreshAccountDisplay(PosState state) {
        if (!state.fidelity.active || !imfidClient.isConfigured()
                || System.currentTimeMillis() < fidSkipUntil) {
            return;
        }
        try {
            ImfidClient.AccountInfo account = imfidClient.account(state.fidelity.label);
            if (account != null) {
                state.fidelity.accountStatus = account.status;
                state.fidelity.availableBalance = account.availableBalance;
            }
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            LOGGER.warnf("Lecture compte fidélité indisponible (%s): affichage sans solde", e.getMessage());
        }
    }

    /**
     * Searches cards by holder identity (addendum §3) and returns a
     * ready-to-render outcome. ONE criterion per call, by the addendum's
     * priority: phone, else e-mail, else name (+ optional first name) —
     * phone and e-mail match EXACTLY after imfid's normalization, name and
     * first name by PREFIX (addendum v1.1); "contient" never exists.
     *
     * @param phone the typed phone, or null
     * @param email the typed e-mail, or null
     * @param name the typed last name, or null
     * @param firstName the typed first name, or null
     * @return the lookup view (matches, or a display message)
     */
    public LookupView lookupCards(String phone, String email, String name, String firstName) {
        LOGGER.info("Entering method lookupCards with phone: " + phone + ", email: " + email + ", name: " + name + ", firstName: " + firstName);
        LookupView view = new LookupView();
        if (!imfidClient.isConfigured() || System.currentTimeMillis() < fidSkipUntil) {
            view.message = "SERVICE FIDÉLITÉ INDISPONIBLE";
            LOGGER.info("Exiting method lookupCards");
            return view;
        }
        boolean hasCriterion = notBlank(phone) || notBlank(email) || notBlank(name);
        if (!hasCriterion) {
            view.message = "SAISISSEZ UN TÉLÉPHONE, UN E-MAIL OU UN NOM";
            LOGGER.info("Exiting method lookupCards");
            return view;
        }
        try {
            ImfidClient.LookupResult result = imfidClient.lookup(phone, email, name, firstName);
            if (result.crmManaged) {
                // Addendum §2: identity lives in the CRM — out of imfid's
                // contract, and no CRM connector exists on this register.
                view.message = "IDENTITÉS GÉRÉES PAR LE CRM - RECHERCHE INDISPONIBLE EN CAISSE";
                LOGGER.info("Exiting method lookupCards");
                return view;
            }
            if (result.matches == null) {
                LOGGER.warnf("Lookup fidélité refusé: %s", result.refusalReason);
                view.message = "RECHERCHE REFUSÉE PAR LE SERVICE FIDÉLITÉ";
                LOGGER.info("Exiting method lookupCards");
                return view;
            }
            view.matches = result.matches;
            if (result.matches.isEmpty()) {
                view.message = "AUCUNE CARTE TROUVÉE - ESSAYEZ UN AUTRE CRITÈRE";
            } else if (result.matches.size() >= 20) {
                // Addendum §3.3: the list is CAPPED at 20 — a full page means
                // truncation, ask for a more discriminating criterion.
                view.message = "TROP DE CORRESPONDANCES - PRÉCISEZ LE CRITÈRE (TÉLÉPHONE)";
            }
            LOGGER.info("Exiting method lookupCards");
            return view;
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            LOGGER.warnf("Lookup fidélité indisponible (%s)", e.getMessage());
            view.message = "SERVICE FIDÉLITÉ INDISPONIBLE";
            LOGGER.info("Exiting method lookupCards");
            return view;
        }
    }

    /**
     * Attaches a card picked in the lookup list (addendum §4): RESILIATED is
     * refused (front desk, never the register), PENDING_ACTIVATION attaches
     * with the earn-only warning, ACTIVE attaches silently. The attachment
     * itself is the SAME single entry point as a scan — the found number is
     * used exactly like a scanned one, holder identity added on top for the
     * operator's verbal check.
     *
     * @param state the current POS state
     * @param card the selected card number
     * @param lastName the holder's last name from the lookup
     * @param firstName the holder's first name from the lookup
     * @param status the account status from the lookup
     * @param email the holder's e-mail from the lookup, or null when the referential
     *        holds none (LC-08-02-09)
     * @return null on success, or the refusal message to display
     */
    public String attachLookedUpCard(PosState state, String card, String lastName,
                                     String firstName, String status, String email) {
        LOGGER.info("Entering method attachLookedUpCard with state: " + state + ", card: " + card + ", lastName: " + lastName + ", firstName: " + firstName + ", status: " + status + ", email: " + email);
        if ("RESILIATED".equals(status)) {
            LOGGER.info("Exiting method attachLookedUpCard");
            return "CARTE RÉSILIÉE - INVITER LE CLIENT À PASSER À L'ACCUEIL";
        }
        validateCard(state, card);
        // BO-10-03-04: the holder's name is carried onto the register only when
        // the store administers it. Off, the register keeps the pseudonymity of
        // a scanned card even after a lookup — nothing to show, nothing stored.
        boolean showHolderName = posSettingsService.fidelityShowHolderName();
        state.fidelity.holderLastName = showHolderName ? lastName : null;
        state.fidelity.holderFirstName = showHolderName ? firstName : null;
        // LC-08-02-09: the address the register offers before sending the receipt.
        // Blank means the referential holds none — the operator types one.
        state.fidelity.holderEmail = email == null || email.isBlank() ? null : email.trim();
        if (state.fidelity.accountStatus == null) {
            state.fidelity.accountStatus = status;
        }
        if ("PENDING_ACTIVATION".equals(status)) {
            // The card earns but cannot burn (reservation would answer 422
            // ACCOUNT_STATUS) — tell the cashier so the client is told.
            state.ticket.setError("CARTE EN ATTENTE D'ACTIVATION - CAGNOTTE SANS UTILISATION");
        }
        LOGGER.info("Exiting method attachLookedUpCard");
        return null;
    }

    /**
     * Returns true when the value carries text.
     *
     * @param s the value
     * @return true when non-null and non-blank
     */
    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Render-ready outcome of a holder lookup: the matches and/or the
     * message replacing or annotating them.
     */
    public static class LookupView {
        /** The matches to list, or null when the search did not run. */
        public java.util.List<ImfidClient.LookupMatch> matches;
        /** The message to display (empty result, degraded, CRM mode), or null. */
        public String message;
    }

    /**
     * Valuation-path hook — an ENGINE outcome was applied: the couple is
     * captured VERBATIM (spec §1) and the projection refreshes (spec §3:
     * /api/earn after each revaluation, pure read, as often as needed).
     *
     * @param state the current POS state
     * @param requestJson the raw /valuation request JSON, as sent
     * @param responseJson the raw /valuation response JSON, as received
     */
    public void onValuation(PosState state, String requestJson, String responseJson) {
        LOGGER.info("Entering method onValuation with state: " + state + ", requestJson: " + requestJson + ", responseJson: " + responseJson);
        state.fidelity.lastValuationRequestJson = requestJson;
        state.fidelity.lastValuationResponseJson = responseJson;
        refreshEarn(state);
        LOGGER.info("Exiting method onValuation");
    }

    /**
     * Valuation-path hook — no engine couple exists (LOCAL or DEGRADED
     * valuation): without a verbatim couple there is no projection to ask
     * for; the display clears and the stale couple is dropped.
     *
     * @param state the current POS state
     */
    public void onValuationUnavailable(PosState state) {
        LOGGER.info("Entering method onValuationUnavailable with state: " + state);
        state.fidelity.lastValuationRequestJson = null;
        state.fidelity.lastValuationResponseJson = null;
        state.fidelity.clearEarn();
        LOGGER.info("Exiting method onValuationUnavailable");
    }

    /**
     * Reserves the fidelity-payment lease for the requested amount (spec
     * §5.1). The POS-side cap is applied FIRST: {@code min(requested,
     * remaining, burnableBase, availableBalance)} — the account is read live
     * for its available balance. Business refusals come back as display
     * messages; only a granted lease returns an amount.
     *
     * @param state the current POS state
     * @param requested the amount asked by the cashier (null/zero = max)
     * @param remaining the remaining due of the ticket
     * @return the verdict: granted amount, or the refusal message
     */
    public BurnVerdict reserveLease(PosState state, java.math.BigDecimal requested,
                                    java.math.BigDecimal remaining) {
        LOGGER.info("Entering method reserveLease with state: " + state + ", requested: " + requested + ", remaining: " + remaining);
        if (!state.fidelity.active) {
            LOGGER.info("Exiting method reserveLease");
            return BurnVerdict.refuse("CARTE FIDÉLITÉ REQUISE");
        }
        if (!imfidClient.isConfigured() || System.currentTimeMillis() < fidSkipUntil) {
            LOGGER.info("Exiting method reserveLease");
            return BurnVerdict.refuse("SERVICE FIDÉLITÉ INDISPONIBLE");
        }
        try {
            ImfidClient.AccountInfo account = imfidClient.account(state.fidelity.label);
            if (account == null) {
                LOGGER.info("Exiting method reserveLease");
                return BurnVerdict.refuse("CARTE FIDÉLITÉ INCONNUE");
            }
            java.math.BigDecimal cap = remaining;
            if (state.fidelity.burnableBase != null) {
                cap = cap.min(state.fidelity.burnableBase);
            }
            if (account.availableBalance != null) {
                cap = cap.min(account.availableBalance);
            }
            if (cap.signum() <= 0) {
                LOGGER.info("Exiting method reserveLease");
                return BurnVerdict.refuse("CAGNOTTE: AUCUN MONTANT UTILISABLE");
            }
            java.math.BigDecimal amount = (requested == null || requested.signum() <= 0)
                    ? cap : requested.min(cap);
            String ticketRef = leaseTicketRef(state);
            ImfidClient.ReservationResult result =
                    imfidClient.reserve(state.fidelity.label, amount, ticketRef);
            switch (result.httpStatus) {
                case 201, 200 -> {
                    state.payment.fidReservationId = result.reservationId;
                    state.payment.fidLeaseExpiresAt = parseLocal(result.expiresAt);
                    if (state.payment.fidLeaseExpiresAt != null) {
                        state.payment.fidLeaseSeconds = java.time.Duration
                                .between(java.time.LocalDateTime.now(),
                                        state.payment.fidLeaseExpiresAt).getSeconds();
                    }
                    LOGGER.info("Exiting method reserveLease");
                    return BurnVerdict.grant(amount);
                }
                case 404 -> { LOGGER.info("Exiting method reserveLease"); return BurnVerdict.refuse("CARTE FIDÉLITÉ INCONNUE"); }
                case 409 -> { LOGGER.info("Exiting method reserveLease"); return BurnVerdict.refuse("CAGNOTTE RÉSERVÉE SUR UNE AUTRE CAISSE"); }
                case 422 -> {
                    LOGGER.info("Exiting method reserveLease");
                    return BurnVerdict.refuse(switch (result.reason == null ? "" : result.reason) {
                        case "INSUFFICIENT_BALANCE" -> "SOLDE CAGNOTTE INSUFFISANT";
                        case "DAILY_RULE" -> "CAGNOTTE DÉJÀ UTILISÉE AUJOURD'HUI";
                        case "ACCOUNT_STATUS" -> "COMPTE FIDÉLITÉ INACTIF";
                        default -> "PAIEMENT FIDÉLITÉ REFUSÉ";
                    });
                }
                default -> { LOGGER.info("Exiting method reserveLease"); return BurnVerdict.refuse("PAIEMENT FIDÉLITÉ REFUSÉ"); }
            }
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            LOGGER.warnf("imfid indisponible au burn (%s)", e.getMessage());
            LOGGER.info("Exiting method reserveLease");
            return BurnVerdict.refuse("SERVICE FIDÉLITÉ INDISPONIBLE");
        }
    }

    /**
     * Renews the active lease at half-life (spec §5.1 recommendation) — an
     * identical re-POST refreshes {@code expiresAt}. Called opportunistically
     * from the payment screen; a miss is harmless (the 410 at confirm is
     * tolerated by design).
     *
     * @param state the current POS state
     */
    public void maybeRenewLease(PosState state) {
        LOGGER.info("Entering method maybeRenewLease with state: " + state);
        if (state.payment.fidReservationId == null
                || state.payment.fidLeaseExpiresAt == null
                || state.payment.fidLeaseSeconds <= 0) {
            LOGGER.info("Exiting method maybeRenewLease");
            return;
        }
        long remainingSeconds = java.time.Duration.between(java.time.LocalDateTime.now(),
                state.payment.fidLeaseExpiresAt).getSeconds();
        if (remainingSeconds > state.payment.fidLeaseSeconds / 2) {
            LOGGER.info("Exiting method maybeRenewLease");
            return;
        }
        try {
            java.math.BigDecimal reserved = state.payment.payments.stream()
                    .filter(p -> "FIDELITY".equals(p.method))
                    .map(p -> p.amount)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            ImfidClient.ReservationResult result = imfidClient.reserve(
                    state.fidelity.label, reserved, leaseTicketRef(state));
            if (result.httpStatus == 200 || result.httpStatus == 201) {
                state.payment.fidLeaseExpiresAt = parseLocal(result.expiresAt);
            }
        } catch (Exception e) {
            LOGGER.debugf("Renouvellement de bail fidélité manqué (%s) — le 410 au confirm est toléré",
                    e.getMessage());
        }
        LOGGER.info("Exiting method maybeRenewLease");
    }

    /**
     * Releases the active lease (payment cancelled — spec §5.3) and forgets
     * it. Idempotent and failure-tolerant: an unreleased lease expires by
     * TTL.
     *
     * @param state the current POS state
     */
    public void releaseLease(PosState state) {
        LOGGER.info("Entering method releaseLease with state: " + state);
        if (state.payment.fidReservationId != null) {
            imfidClient.release(state.payment.fidReservationId);
            state.payment.fidReservationId = null;
            state.payment.fidLeaseExpiresAt = null;
            state.payment.fidLeaseSeconds = 0L;
        }
        LOGGER.info("Exiting method releaseLease");
    }

    /**
     * Confirms the lease at the fiscal moment (spec §5.2). A 410 (lease
     * expired) NEVER blocks the closing: the reservation id still travels in
     * the ticket-closed event and the ingestion, authoritative, creates the
     * BURN with its warning.
     *
     * @param state the current POS state
     * @param fiscalDate the ticket's fiscal date
     * @return the reservation id to carry in the ticket-closed event, or null
     */
    public Long confirmLease(PosState state, java.time.LocalDate fiscalDate) {
        LOGGER.info("Entering method confirmLease with state: " + state + ", fiscalDate: " + fiscalDate);
        Long reservationId = state.payment.fidReservationId;
        if (reservationId == null) {
            LOGGER.info("Exiting method confirmLease");
            return null;
        }
        try {
            int status = imfidClient.confirm(reservationId, fiscalDate.toString());
            if (status == 410) {
                LOGGER.warn("Bail fidélité expiré au moment fiscal: l'ingestion tranchera "
                        + "(EXPIRED_LEASE_CONFIRMED)");
            } else if (status != 200) {
                LOGGER.warnf("Confirmation de bail fidélité inattendue: HTTP %d", status);
            }
        } catch (Exception e) {
            LOGGER.warnf("Confirmation de bail fidélité injoignable (%s): l'ingestion tranchera",
                    e.getMessage());
        }
        LOGGER.info("Exiting method confirmLease");
        return reservationId;
    }

    /**
     * Composes the ticket-closed event payload (spec §6): the verbatim
     * couple, the displayed earn (trace only), the card, the fiscal date and
     * the reservation id — by string composition, the couple never
     * reserialized.
     *
     * @param state the current POS state (couple + displayed entries)
     * @param ticketRef the closed ticket's unique reference
     * @param card the attached card number
     * @param fiscalDate the ticket's fiscal date
     * @param reservationId the confirmed lease id, or null
     * @return the composed JSON body, or null when no couple exists
     */
    public String buildTicketClosedPayload(PosState state, String ticketRef, String card,
                                           java.time.LocalDate fiscalDate, Long reservationId) {
        LOGGER.info("Entering method buildTicketClosedPayload with state: " + state + ", ticketRef: " + ticketRef + ", card: " + card + ", fiscalDate: " + fiscalDate + ", reservationId: " + reservationId);
        if (state.fidelity.lastValuationRequestJson == null
                || state.fidelity.lastValuationResponseJson == null) {
            LOGGER.info("Exiting method buildTicketClosedPayload");
            return null;
        }
        StringBuilder displayed = new StringBuilder("[");
        for (int i = 0; i < state.fidelity.earnEntries.size(); i++) {
            FidelityState.EarnLine line = state.fidelity.earnEntries.get(i);
            if (i > 0) displayed.append(',');
            displayed.append("{\"ruleCode\":\"").append(line.ruleCode)
                    .append("\",\"amount\":").append(line.amount.toPlainString()).append('}');
        }
        displayed.append(']');
        LOGGER.info("Exiting method buildTicketClosedPayload");
        return "{\"ticketRef\":\"" + ticketRef + "\","
                + "\"card\":\"" + card + "\","
                + "\"fiscalDate\":\"" + fiscalDate + "\","
                + "\"valuationRequest\":" + state.fidelity.lastValuationRequestJson + ","
                + "\"valuationResponse\":" + state.fidelity.lastValuationResponseJson + ","
                + "\"displayedEarn\":" + displayed
                + (reservationId != null ? ",\"reservationId\":" + reservationId : "")
                + "}";
    }

    /**
     * Loads the in-store consultation of the attached card (spec §4):
     * status, balance, AVAILABLE balance and the recent movements — rendered
     * server-side as display rows (date, label, signed amount), the type
     * nomenclature translated once here. Degraded imfid = a message, never
     * an error page.
     *
     * @param state the current POS state
     * @return the consultation view (either populated, or carrying a message)
     */
    public Consultation loadConsultation(PosState state) {
        LOGGER.info("Entering method loadConsultation with state: " + state);
        Consultation view = new Consultation();
        if (!state.fidelity.active) {
            view.message = "AUCUNE CARTE ATTACHÉE — SCANNEZ OU SAISISSEZ LA CARTE";
            LOGGER.info("Exiting method loadConsultation");
            return view;
        }
        if (!imfidClient.isConfigured()) {
            view.message = "SERVICE FIDÉLITÉ NON CONFIGURÉ";
            LOGGER.info("Exiting method loadConsultation");
            return view;
        }
        try {
            ImfidClient.AccountInfo account = imfidClient.account(state.fidelity.label);
            if (account == null) {
                view.message = "CARTE FIDÉLITÉ INCONNUE";
                LOGGER.info("Exiting method loadConsultation");
                return view;
            }
            view.status = switch (account.status == null ? "" : account.status) {
                case "ACTIVE" -> "ACTIF";
                case "PENDING_ACTIVATION" -> "EN ATTENTE D'ACTIVATION";
                case "RESILIATED" -> "RÉSILIÉ";
                default -> account.status;
            };
            view.balance = account.balance;
            view.available = account.availableBalance;
            ImfidClient.MovementsPage movementsPage =
                    imfidClient.movements(state.fidelity.label, 0, 15);
            if (movementsPage != null && movementsPage.items != null) {
                for (ImfidClient.Movement movement : movementsPage.items) {
                    String label = switch (movement.type == null ? "" : movement.type) {
                        case "EARN" -> "CAGNOTTE"
                                + (movement.ruleCode != null ? " (" + movement.ruleCode + ")" : "");
                        case "BURN" -> "UTILISATION EN CAISSE";
                        case "RETURN_DEBIT" -> "REPRISE SUR RETOUR";
                        case "REFUND_CREDIT" -> "REMBOURSEMENT EN CAGNOTTE";
                        case "ADJUSTMENT" -> "AJUSTEMENT";
                        case "EXPIRY" -> "PÉREMPTION";
                        case "ACTIVATION_VOID" -> "ANNULATION D'ACTIVATION";
                        default -> movement.type;
                    };
                    String date = movement.fiscalDate != null ? movement.fiscalDate
                            : (movement.createdAt != null && movement.createdAt.length() >= 10
                                    ? movement.createdAt.substring(0, 10) : "");
                    String amount = (movement.amount != null && movement.amount.signum() > 0 ? "+" : "")
                            + (movement.amount != null ? movement.amount.toPlainString() : "") + " €";
                    view.rows.add(new String[] { date, label, amount });
                }
            }
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            view.message = "SERVICE FIDÉLITÉ INDISPONIBLE";
        }
        LOGGER.info("Exiting method loadConsultation");
        return view;
    }

    /** The in-store consultation view, assembled server-side. */
    public static class Consultation {
        /** The translated account status, or null. */
        public String status;
        /** The book balance, or null. */
        public java.math.BigDecimal balance;
        /** The available balance (net of leases), or null. */
        public java.math.BigDecimal available;
        /** Display rows: date, label, signed amount. */
        public java.util.List<String[]> rows = new java.util.ArrayList<>();
        /** The message shown instead of data (degraded, unknown, no card). */
        public String message;

        /**
         * Tells whether account figures are present.
         *
         * @return true when the account was read
         */
        public boolean hasAccount() { return balance != null; }
    }

    /**
     * The stable per-draft lease reference (renewal key). The CLOSED ticket
     * reference differs — the lease links to the close through the
     * reservation id carried by the ticket-closed event, per spec §6.
     *
     * @param state the current POS state
     * @return the lease ticketRef
     */
    private String leaseTicketRef(PosState state) {
        return "D-" + (state.payment.ticketDbId != null ? state.payment.ticketDbId : 0);
    }

    /**
     * Parses an ISO local date-time, null-safe.
     *
     * @param iso the ISO string, or null
     * @return the parsed instant, or null
     */
    private java.time.LocalDateTime parseLocal(String iso) {
        try {
            return iso == null ? null : java.time.LocalDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    /** The verdict of a lease reservation. */
    public static class BurnVerdict {
        /** The granted amount, or null when refused. */
        public java.math.BigDecimal grantedAmount;
        /** The refusal display message, or null when granted. */
        public String refusalMessage;

        /**
         * Builds a granted verdict.
         *
         * @param amount the granted amount
         * @return the verdict
         */
        static BurnVerdict grant(java.math.BigDecimal amount) {
            BurnVerdict verdict = new BurnVerdict();
            verdict.grantedAmount = amount;
            return verdict;
        }

        /**
         * Builds a refusal verdict.
         *
         * @param message the display message
         * @return the verdict
         */
        static BurnVerdict refuse(String message) {
            BurnVerdict verdict = new BurnVerdict();
            verdict.refusalMessage = message;
            return verdict;
        }
    }

    /**
     * Refreshes the displayed earn projection from imfid, under the breaker.
     * Degraded (unconfigured, breaker open, transport failure, 4xx) = the
     * projection is HIDDEN (spec §2.2), never a blocking error.
     *
     * @param state the current POS state
     */
    private void refreshEarn(PosState state) {
        FidelityState fid = state.fidelity;
        if (!fid.active || !imfidClient.isConfigured()
                || fid.lastValuationRequestJson == null || fid.lastValuationResponseJson == null) {
            fid.clearEarn();
            return;
        }
        if (System.currentTimeMillis() < fidSkipUntil) {
            fid.clearEarn();
            return;
        }
        try {
            ImfidClient.EarnProjection projection =
                    imfidClient.earn(fid.lastValuationRequestJson, fid.lastValuationResponseJson);
            fid.clearEarn();
            fid.earnTotal = projection.total;
            fid.burnableBase = projection.burnableBase;
            if (projection.entries != null) {
                for (ImfidClient.EarnEntry entry : projection.entries) {
                    fid.earnEntries.add(
                            new FidelityState.EarnLine(entry.ruleCode, entry.label, entry.amount));
                }
            }
        } catch (Exception e) {
            fidSkipUntil = System.currentTimeMillis() + RETRY_SECONDS * 1000L;
            fid.clearEarn();
            LOGGER.warnf("imfid indisponible (%s): projection d'earn masquée, prochain essai dans %d s",
                    e.getMessage(), RETRY_SECONDS);
        }
    }
}