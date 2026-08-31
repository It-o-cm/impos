package com.intermarche.pos.ui.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;

/**
 * Home screen service: navigation, line selection, ticket-level actions and
 * the price-modification modal lifecycle.
 * <p>
 * Phase 0: price-modification values flow as {@link BigDecimal}.
 * <p>
 * Orchestrator of the main screen's gestures, and the place where the
 * line-modification family SPLITS: {@code submitPriceMod} routes the three
 * price types through the manager endorsement (parked gesture), while
 * QUANTITY applies directly — multiplying a scanned line is a normal sale
 * action, not an exception. The training toggle keeps the same two-step
 * shape as every guarded gesture (request parks, endorsed dispatch
 * performs), and the supervisor call is the register's only REAL-TIME
 * outbound HTTP (3-second timeout, outcome dropped into the message zone):
 * a call is a signal, not a document, hence no outbox.
 */
@ApplicationScoped
public class HomeService {

    @Inject
    PosState state;

    @Inject
    TicketService ticketService;

    /** The back-office parameters (endorsement policy — LC-03-02-08). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** Printer service — used by the endorsed operator-badge reprint. */
    @Inject
    com.intermarche.pos.ui.hardware.TicketPrinterService ticketPrinterService;

    @Inject
    EndorsementService endorsementService;

    @Inject
    TechnicalEventService technicalEventService;

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    SyncOutboxService syncOutboxService;

    @Inject
    ObjectMapper objectMapper;

    /** Shared token sent with the supervisor call; absent = none. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "pos.sync.token")
    java.util.Optional<String> supervisorToken;

    /** HTTP client of the real-time supervisor call. */
    private static final java.net.http.HttpClient SUPERVISOR_CLIENT = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build();

    // --- Navigation ---

    /**
     * Shows or hides the secondary menu.
     *
     * @param show true to show the secondary menu
     */
    public void toggleSecondaryMenu(boolean show) {
        state.showSecondaryMenu = show;
        state.touch();
    }

    /**
     * Toggles the selection of a ticket line by its index.
     *
     * @param index the index of the line in the full ticket
     */
    public void selectLine(int index) {
        if (state.selectedTicketIndex == index) {
            state.selectedTicketIndex = -1;
        } else {
            state.selectedTicketIndex = index;
        }
        state.touch();
    }

    // --- Ticket actions ---

    /**
     * Cancels the targeted line: directly when it is the last entered line,
     * otherwise through a manager endorsement.
     */
    public void cancelLine() {
        String targetUid = null;
        if (state.selectedTicketIndex >= 0 && state.selectedTicketIndex < state.ticket.items.size()) {
            targetUid = state.ticket.items.get(state.selectedTicketIndex).uid;
        } else if (!state.ticket.items.isEmpty()) {
            targetUid = state.ticket.items.get(state.ticket.items.size() - 1).uid;
        }

        if (targetUid == null) return;

        if (targetUid.equals(state.lastEnteredItemId)) {
            ticketService.cancelItemById(state, targetUid);
            state.lastEnteredItemId = null;
        } else {
            endorsementService.requestAuthorization(state, "CANCEL_LINE_" + targetUid);
        }

        state.selectedTicketIndex = -1;
        state.touch();
    }

    /**
     * Requests a manager endorsement to cancel the whole ticket.
     */
    public void cancelTicket() {
        endorsementService.requestAuthorization(state, "CANCEL_TICKET");
    }

    /**
     * Reprints the last closed ticket, if any.
     */
    public void printLastTicket() {
        if (state.trainingMode) {
            // Real documents are untouchable in training: no duplicata, no counter bump
            state.ticket.setError("RÉIMPRESSION INDISPONIBLE EN FORMATION");
            state.touch();
            return;
        }
        if (state.lastClosedTicketId != null) {
            ticketService.reprintTicket(state.lastClosedTicketId);
        }
    }

    // --- Price modifications ---

    /**
     * Opens the price-modification modal for the targeted line.
     *
     * @param type the modification type (remise, discount, force_price)
     */
    public void openPriceMod(String type) {
        String upper = type.toUpperCase();
        // Ticket-level gestures target the whole sale: no line selection
        // required (phase: global ticket discount).
        if (upper.startsWith("GLOBAL_")) {
            state.priceModState.set(upper, null, "TICKET COMPLET");
            state.touch();
            return;
        }
        TicketState.TicketItem target = state.getTargetItem();
        if (target == null) {
            state.ticket.setError("AUCUNE LIGNE SÉLECTIONNÉE");
        } else {
            state.priceModState.set(upper, target.uid, target.label);
        }
        state.touch();
    }

    /**
     * Closes the price-modification modal without applying anything.
     */
    public void cancelPriceMod() {
        state.priceModState.clear();
        state.touch();
    }

    /**
     * Submits a price modification, which is routed through a manager endorsement.
     *
     * @param type the modification type (REMISE, DISCOUNT, FORCE_PRICE)
     * @param uid the uid of the targeted ticket line
     * @param value the modification value (euros or percent depending on the type)
     */
    /**
     * Calls a supervisor: pushes the register, operator and reason to the
     * store node in real time, and journals the call locally. The message
     * zone tells the cashier the outcome.
     *
     * @param reason the call reason chosen on the supervisor page
     */
    /**
     * Requests the manager endorsement toggling the training mode; refused
     * over a non-empty cart or an active payment (no mixing of training and
     * real transactions).
     */
    public void requestTrainingToggle() {
        if (!state.ticket.items.isEmpty() || state.payment.paymentInProgress) {
            state.ticket.setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
            state.touch();
            return;
        }
        endorsementService.requestAuthorization(state, "TRAINING_TOGGLE");
        state.touch();
    }

    /**
     * Performs the endorsed training toggle: flips the mode, re-checks the
     * empty-cart guard and journals the transition.
     */
    public void performTrainingToggle() {
        if (!state.ticket.items.isEmpty() || state.payment.paymentInProgress) {
            state.ticket.setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
            return;
        }
        state.trainingMode = !state.trainingMode;
        technicalEventService.log(state.trainingMode
                ? TechnicalEvent.EventType.TRAINING_STARTED
                : TechnicalEvent.EventType.TRAINING_ENDED, null);
        state.ticket.setError(state.trainingMode ? "MODE FORMATION ACTIVÉ" : "MODE FORMATION TERMINÉ");
    }

    public void callSupervisor(String reason) {
        technicalEventService.log(TechnicalEvent.EventType.SUPERVISOR_CALLED, reason);
        if (!syncOutboxService.isEnabled()) {
            state.ticket.setError("SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE");
            state.touch();
            return;
        }
        try {
            java.util.Map<String, String> payload = new java.util.HashMap<>();
            payload.put("terminalId", ticketNumberService.getTerminalId());
            payload.put("operator", state.getOperatorName());
            payload.put("reason", reason);
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(syncOutboxService.getStoreUrl() + "/api/supervisor/call"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            String sharedToken = supervisorToken.orElse("");
            if (!sharedToken.isBlank()) {
                builder.header("X-Sync-Token", sharedToken);
            }
            java.net.http.HttpResponse<String> response = SUPERVISOR_CLIENT.send(
                    builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                state.ticket.setError("SUPERVISEUR PRÉVENU");
            } else {
                state.ticket.setError("APPEL SUPERVISEUR REFUSÉ (" + response.statusCode() + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.ticket.setError("APPEL SUPERVISEUR INTERROMPU");
        } catch (Exception e) {
            state.ticket.setError("APPEL SUPERVISEUR IMPOSSIBLE");
        }
        state.touch();
    }

    public void submitPriceMod(String type, String uid, BigDecimal value) {
        if ("QUANTITY".equals(type)) {
            // Multiplying a scanned line is a normal sale action: no endorsement
            applyLineQuantity(uid, value);
        } else if (posSettingsService.gestureEndorsementRequired()) {
            endorsementService.requestPriceModification(state, type, uid, value);
        } else {
            // Administered WITHOUT endorsement (LC-03-02-08): the cashier
            // applies the gesture directly — same execution as the approved
            // endorsement dispatch, ceremony skipped.
            applyGestureDirectly(type, uid, value);
        }
        state.priceModState.clear();
        state.touch();
    }

    /**
     * Applies a price gesture without the endorsement ceremony — the exact
     * mirror of the approved-endorsement dispatch, used when the back office
     * administered the gestures as free (LC-03-02-08).
     *
     * @param type the gesture type (REMISE, DISCOUNT, FORCE_PRICE, GLOBAL_*)
     * @param uid the targeted line uid, or null for a global gesture
     * @param value the typed value
     */
    private void applyGestureDirectly(String type, String uid, BigDecimal value) {
        if (type != null && type.startsWith("GLOBAL_")) {
            ticketService.applyGlobalDiscount(state, type, value);
            return;
        }
        TicketState.TicketItem item = state.ticket.items.stream()
                .filter(i -> i.uid.equals(uid))
                .findFirst()
                .orElse(null);
        if (item == null) {
            state.ticket.setError("LIGNE INTROUVABLE");
            return;
        }
        if ("REMISE".equals(type)) ticketService.applyRemise(item, value);
        else if ("DISCOUNT".equals(type)) ticketService.applyDiscount(item, value);
        else if ("FORCE_PRICE".equals(type)) ticketService.forcePrice(item, value);
        ticketService.recalculateTotal(state);
    }

    /**
     * Applies a typed quantity on a ticket line. Two families (LC-02-13-02):
     * unit EAN lines take a WHOLE quantity between 1 and 999; weighed lines
     * (PLU carried, catalog price per kilogram) take a DECIMAL weight in
     * kilograms between 0.001 and 99.999. Price-embedded sticker lines stay
     * untouchable — one physical sticker is one object at its printed total —
     * as do money products and negative (deposit) lines.
     *
     * @param uid the uid of the targeted line
     * @param value the typed quantity (units, or kg for a weighed line)
     */
    private void applyLineQuantity(String uid, BigDecimal value) {
        TicketState.TicketItem item = state.ticket.items.stream()
                .filter(i -> i.uid.equals(uid))
                .findFirst()
                .orElse(null);
        if (item == null) {
            state.ticket.setError("LIGNE INTROUVABLE");
            return;
        }
        boolean hasEan = item.ean != null && !item.ean.isEmpty();
        boolean weighedLine = item.plu != null && !item.plu.isEmpty()
                && hasEan && !item.priceEmbedded;
        boolean unitLine = (item.plu == null || item.plu.isEmpty()) && hasEan;
        if ((!unitLine && !weighedLine) || item.getTotalPrice().signum() < 0
                || item.moneyProduct) {
            state.ticket.setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
            return;
        }
        if (weighedLine) {
            if (value == null || value.scale() > 3
                    || value.compareTo(new BigDecimal("0.001")) < 0
                    || value.compareTo(new BigDecimal("99.999")) > 0) {
                state.ticket.setError("POIDS INVALIDE (0,001-99,999 KG)");
                return;
            }
            item.quantity = value.setScale(3, java.math.RoundingMode.HALF_UP);
        } else {
            if (value == null || value.stripTrailingZeros().scale() > 0
                    || value.compareTo(BigDecimal.ONE) < 0
                    || value.compareTo(BigDecimal.valueOf(999)) > 0) {
                state.ticket.setError("QUANTITÉ INVALIDE (1-999)");
                return;
            }
            item.quantity = BigDecimal.valueOf(value.intValueExact());
        }
        ticketService.recalculateTotal(state);
    }
    /**
     * (Re)prints an operator's badge number (LC-01-06-01) — executed ONLY
     * through the endorsement dispatch, after a manager approval. The
     * operator is resolved by badge id first, then by login name; an unknown
     * number lands in the message zone, never on paper.
     *
     * @param operatorNumber the typed badge id or login name
     */
    public void printOperatorBadge(String operatorNumber) {
        com.intermarche.pos.domain.Employee employee = com.intermarche.pos.domain.Employee
                .<com.intermarche.pos.domain.Employee>find("badgeId", operatorNumber).firstResult();
        if (employee == null) {
            employee = com.intermarche.pos.domain.Employee
                    .<com.intermarche.pos.domain.Employee>find("loginName", operatorNumber).firstResult();
        }
        if (employee == null || !employee.active) {
            state.ticket.setError("OPÉRATEUR INTROUVABLE (" + operatorNumber + ")");
            return;
        }
        ticketPrinterService.printOperatorBadge(employee);
        state.ticket.setError("BADGE OPÉRATEUR IMPRIMÉ");
    }
}