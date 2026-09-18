package com.intermarche.pos.ui.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.catalog.attribute.RestrictedTender;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

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

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(HomeService.class);

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
     * Shows one of the five button menus.
     *
     * @param menu the menu to show, never null
     */
    public void selectMenu(com.intermarche.pos.ui.PosMenu menu) {
        LOGGER.info("Entering method selectMenu with menu: " + menu);
        state.menu = menu;
        state.touch();
        LOGGER.info("Exiting method selectMenu");
    }

    /**
     * Toggles the selection of a ticket line by its index.
     *
     * @param index the index of the line in the full ticket
     */
    public void selectLine(int index) {
        LOGGER.info("Entering method selectLine with index: " + index);
        if (state.selectedTicketIndex == index) {
            state.selectedTicketIndex = -1;
        } else {
            state.selectedTicketIndex = index;
        }
        state.touch();
        LOGGER.info("Exiting method selectLine");
    }

    /**
     * One restricted tender and the part of the running basket it may pay
     * ({@code LC-09-01-12}, {@code -14}, {@code -16}, {@code -18}).
     *
     * @param label  the tender's wording, as the cashier reads it
     * @param amount the eligible total, formatted for display
     */
    public record RestrictedTenderRow(String label, String amount) {
    }

    /**
     * Totals what the running basket may be paid with, tender by tender.
     *
     * <p>RECOMPUTED FROM THE LINES AT EVERY CALL, never kept as a field the sale
     * updates. The screen asks for a total that is "réactualisé à chaque nouvel
     * enregistrement d'article", and a cart also loses lines, changes quantities and
     * takes discounts — a running field would have to be corrected in every one of
     * those places, and the first one forgotten would be a wrong figure on screen with
     * nothing to show it was wrong.
     *
     * <p>A tender no line is eligible for is DROPPED rather than shown at zero: the
     * indicator exists to tell the cashier what the customer can use, and three
     * permanent zeroes beside the total teach them to stop reading it.
     *
     * @return the tenders with an eligible amount, in administered order, possibly empty
     */
    public List<RestrictedTenderRow> restrictedTenderRows() {
        LOGGER.info("Entering method restrictedTenderRows");
        List<RestrictedTenderRow> rows = new ArrayList<>();
        for (RestrictedTender tender
                : RestrictedTender.administered(posSettingsService.restrictedTenders())) {
            BigDecimal eligible = BigDecimal.ZERO;
            for (TicketState.TicketItem item : state.ticket.items) {
                if (tender.covers(item.restrictedTenders)) {
                    eligible = eligible.add(item.getTotalPrice());
                }
            }
            if (eligible.signum() > 0) {
                rows.add(new RestrictedTenderRow(tender.label(),
                        String.format("%.2f", eligible.setScale(2, RoundingMode.HALF_UP))
                                .replace('.', ',')));
            }
        }
        LOGGER.info("Exiting method restrictedTenderRows");
        return rows;
    }

    /**
     * Arms a quantity for the NEXT article named ({@code LC-02-13-04/05/06}).
     *
     * <p>The quantity key has always applied a quantity to a line already registered.
     * This is the other order the questionnaire asks for — key the quantity, then
     * scan, key the EAN or key the internal code — and the three are covered at once
     * because the three end in the same {@code addItem}.
     *
     * @param quantity the quantity to arm
     */
    public void armQuantity(BigDecimal quantity) {
        LOGGER.info("Entering method armQuantity with quantity: " + quantity);
        if (quantity == null || quantity.signum() <= 0) {
            state.ticket.setError("QUANTITÉ INVALIDE");
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method armQuantity");
            return;
        }
        state.armedQuantity = quantity;
        state.priceModState.clear();
        state.ticket.setNotice("QUANTITÉ " + quantity.stripTrailingZeros().toPlainString()
                + " — SAISISSEZ L'ARTICLE");
        state.touch();
        LOGGER.info("Exiting method armQuantity");
    }

    /**
     * Repeats the registration of the last article ({@code LC-02-13-12/13}).
     *
     * <p>IT ADDS ONE TO THE LINE, it does not ring a second one: the register groups
     * identical articles, and {@code LC-02-13-17} says that where grouping is on the
     * repetition raises the quantity of the existing line. Adding through the ordinary
     * path would also re-ask a "prix requis" article for its price, which
     * {@code LC-02-13-14} forbids — the line already knows what was paid.
     *
     * <p>TWO KINDS OF ARTICLE ARE REFUSED, and refused OUT LOUD. A weighed line is a
     * measurement ({@code LC-02-13-15}) and a price-embedded label names one physical
     * object ({@code LC-02-13-16}); repeating either would invent a weight nobody put
     * on the scale.
     */
    public void repeatLastItem() {
        LOGGER.info("Entering method repeatLastItem");
        TicketState.TicketItem last = lastEnteredItem();
        if (last == null) {
            state.ticket.setError("AUCUN ARTICLE À RÉPÉTER");
            state.touch();
            LOGGER.info("Exiting method repeatLastItem");
            return;
        }
        if (last.plu != null && !last.plu.isEmpty()) {
            state.ticket.setError("RÉPÉTITION IMPOSSIBLE : ARTICLE EN PESÉE");
            state.touch();
            LOGGER.info("Exiting method repeatLastItem");
            return;
        }
        if (last.priceEmbedded) {
            state.ticket.setError("RÉPÉTITION IMPOSSIBLE : ARTICLE PRIX EMBARQUÉ");
            state.touch();
            LOGGER.info("Exiting method repeatLastItem");
            return;
        }
        last.quantity = last.quantity.add(BigDecimal.ONE);
        ticketService.recalculateTotal(state);
        state.ticket.setNotice("ARTICLE RÉPÉTÉ : " + last.label);
        state.touch();
        LOGGER.info("Exiting method repeatLastItem");
    }

    /**
     * Returns the line the last registration created, when it is still on the ticket.
     *
     * @return the line, or null when nothing was registered or it has been cancelled
     */
    private TicketState.TicketItem lastEnteredItem() {
        if (state.lastEnteredItemId == null) {
            return null;
        }
        for (TicketState.TicketItem item : state.ticket.items) {
            if (state.lastEnteredItemId.equals(item.uid)) {
                return item;
            }
        }
        return null;
    }

    /**
     * The "à enlever" key ({@code LC-02-08-01}).
     *
     * <p>ONE KEY, TWO GESTURES, told apart by whether a line is EXPLICITLY selected.
     * With a line selected, it marks or unmarks that line ({@code LC-02-08-03}) —
     * the article was already rung and the cashier changes their mind about where it
     * goes. With nothing selected, it arms the next article ({@code LC-02-08-02}) —
     * the cashier knows before scanning that this one goes to the desk.
     *
     * <p>The implicit "last line" fallback the other line gestures use is
     * deliberately NOT applied here: it would make the key unable to arm anything as
     * soon as the ticket had a line, which is every moment but the first.
     */
    public void toggleCollect() {
        LOGGER.info("Entering method toggleCollect");
        TicketState.TicketItem selected = state.getSelectedItem();
        if (selected != null) {
            selected.toCollect = !selected.toCollect;
            state.selectedTicketIndex = -1;
            state.ticket.setNotice(selected.toCollect
                    ? "ARTICLE MARQUÉ À ENLEVER" : "MARQUAGE À ENLEVER RETIRÉ");
            state.touch();
            LOGGER.info("Exiting method toggleCollect");
            return;
        }
        state.collectArmed = !state.collectArmed;
        state.ticket.setNotice(state.collectArmed
                ? "PROCHAIN ARTICLE À ENLEVER" : "MARQUAGE À ENLEVER ANNULÉ");
        state.touch();
        LOGGER.info("Exiting method toggleCollect");
    }

    // --- Ticket actions ---

    /**
     * Cancels the targeted line: directly when it is the last entered line,
     * otherwise through a manager endorsement.
     */
    public void cancelLine() {
        LOGGER.info("Entering method cancelLine");
        String targetUid = null;
        if (state.selectedTicketIndex >= 0 && state.selectedTicketIndex < state.ticket.items.size()) {
            targetUid = state.ticket.items.get(state.selectedTicketIndex).uid;
        } else if (!state.ticket.items.isEmpty()) {
            targetUid = state.ticket.items.get(state.ticket.items.size() - 1).uid;
        }

        if (targetUid == null) { LOGGER.info("Exiting method cancelLine"); return; }

        if (targetUid.equals(state.lastEnteredItemId)) {
            ticketService.cancelItemById(state, targetUid);
            state.lastEnteredItemId = null;
        } else {
            endorsementService.requestAuthorization(state, "CANCEL_LINE_" + targetUid);
        }

        state.selectedTicketIndex = -1;
        state.touch();
        LOGGER.info("Exiting method cancelLine");
    }

    /**
     * Requests a manager endorsement to cancel the whole ticket.
     */
    public void cancelTicket() {
        LOGGER.info("Entering method cancelTicket");
        endorsementService.requestAuthorization(state, "CANCEL_TICKET");
        LOGGER.info("Exiting method cancelTicket");
    }

    /**
     * Reprints the last closed ticket.
     *
     * <p>Refusals go through {@link PosState#requireLastClosedTicket()}: this
     * function used to be the one that printed nothing and said nothing when
     * the register had closed no sale yet, which on a touch screen is a broken
     * button.
     */
    public void printLastTicket() {
        LOGGER.info("Entering method printLastTicket");
        // RECOVER FIRST. The DERNIER keys work on the last sale THIS REGISTER
        // closed, not on the last sale this PROCESS closed: a restart emptied
        // PosState and the four keys answered "AUCUN TICKET" over a ticket that
        // was still in the database and still on the customer's hands.
        ticketService.resolveLastClosedTicketId(state);
        if (!state.requireLastClosedTicket()) {
            LOGGER.info("Exiting method printLastTicket");
            return;
        }
        ticketService.reprintTicket(state.lastClosedTicketId);
        // ACKNOWLEDGE. The three DERNIER keys redirect to the sale screen and,
        // when they succeeded, changed nothing on it: the register printed and
        // said nothing, which on a till whose printer is a remote bridge is
        // indistinguishable from a dead button.
        state.ticket.setNotice("TICKET RÉIMPRIMÉ");
        LOGGER.info("Exiting method printLastTicket");
    }

    /**
     * Prints the identification barcode of the last closed ticket, alone
     * (LC-08-01-04).
     *
     * <p>Refusals go through {@link PosState#requireLastClosedTicket()}. The slip
     * states no amount, but it carries a real ticket number, and a number that
     * leaves the register must name a sale that happened.
     */
    public void printLastTicketBarcode() {
        LOGGER.info("Entering method printLastTicketBarcode");
        ticketService.resolveLastClosedTicketId(state);
        if (!state.requireLastClosedTicket()) {
            LOGGER.info("Exiting method printLastTicketBarcode");
            return;
        }
        ticketService.printTicketIdentityBarcode(state.lastClosedTicketId);
        state.ticket.setNotice("CODE-BARRES IMPRIMÉ");
        LOGGER.info("Exiting method printLastTicketBarcode");
    }

    /**
     * Prints a duplicate of the last closed ticket's card receipt (LC-08-05-09).
     *
     * <p>The register builds the slip from the traces it holds — amount, authorization
     * number, degraded acceptance. A monetique that returns its own print frames will
     * replace that body; the DUPLICATA mention and the moment it is asked for do not
     * change with it. Refusals go through {@link PosState#requireLastClosedTicket()};
     * a ticket settled without a card prints nothing, which is not a refusal.
     */
    public void printLastCardReceiptDuplicate() {
        LOGGER.info("Entering method printLastCardReceiptDuplicate");
        ticketService.resolveLastClosedTicketId(state);
        if (!state.requireLastClosedTicket()) {
            LOGGER.info("Exiting method printLastCardReceiptDuplicate");
            return;
        }
        // A sale settled without a card prints nothing. That is not a failure,
        // but pressing the key and getting silence is: the operator is told the
        // ticket has no card slip to duplicate.
        if (ticketService.printCardReceiptDuplicate(state.lastClosedTicketId) > 0) {
            state.ticket.setNotice("DUPLICATA CB IMPRIMÉ");
        } else {
            state.ticket.setError("AUCUN PAIEMENT CARTE SUR CE TICKET");
        }
        LOGGER.info("Exiting method printLastCardReceiptDuplicate");
    }

    // --- Price modifications ---

    /**
     * Opens the price-modification modal on the named mode.
     *
     * <p>A word that names no mode opens nothing: the key's URL is the only place
     * where the mode is still a string, and a stale page or a hand-typed address must
     * not open a modal whose title and buttons nobody can decide.
     *
     * @param type the mode's name, as the key's URL spells it
     */
    public void openPriceMod(String type) {
        LOGGER.info("Entering method openPriceMod with type: " + type);
        PriceModType mode = PriceModType.of(type == null ? null : type.toUpperCase());
        if (mode == null) {
            state.ticket.setError("MODIFICATION INCONNUE");
            state.touch();
            LOGGER.info("Exiting method openPriceMod");
            return;
        }
        // Ticket-level gestures target the whole sale: no line selection
        // required (phase: global ticket discount).
        if (mode.isTicketLevel()) {
            state.priceModState.set(mode, null, "TICKET COMPLET");
            state.touch();
            LOGGER.info("Exiting method openPriceMod");
            return;
        }
        TicketState.TicketItem target = state.getTargetItem();
        if (target == null) {
            state.ticket.setError("AUCUNE LIGNE SÉLECTIONNÉE");
        } else {
            // The line is captured HERE, at opening, and not read again while the
            // modal is up: what the operator is about to modify is the line as it
            // was when the gesture started.
            state.priceModState.set(mode, target.uid, target.label, target.getHtml(),
                    target.getPriceFormatted(), target.getModifierLabel());
        }
        state.touch();
        LOGGER.info("Exiting method openPriceMod");
    }

    /**
     * Closes the price-modification modal without applying anything.
     */
    public void cancelPriceMod() {
        LOGGER.info("Entering method cancelPriceMod");
        state.priceModState.clear();
        state.touch();
        LOGGER.info("Exiting method cancelPriceMod");
    }

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
        LOGGER.info("Entering method requestTrainingToggle");
        if (!state.ticket.items.isEmpty() || state.payment.paymentInProgress) {
            state.ticket.setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
            state.touch();
            LOGGER.info("Exiting method requestTrainingToggle");
            return;
        }
        endorsementService.requestAuthorization(state, "TRAINING_TOGGLE");
        state.touch();
        LOGGER.info("Exiting method requestTrainingToggle");
    }

    /**
     * Performs the endorsed training toggle: flips the mode, re-checks the
     * empty-cart guard and journals the transition.
     */
    public void performTrainingToggle() {
        LOGGER.info("Entering method performTrainingToggle");
        if (!state.ticket.items.isEmpty() || state.payment.paymentInProgress) {
            state.ticket.setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
            LOGGER.info("Exiting method performTrainingToggle");
            return;
        }
        state.trainingMode = !state.trainingMode;
        technicalEventService.log(state.trainingMode
                ? TechnicalEvent.EventType.TRAINING_STARTED
                : TechnicalEvent.EventType.TRAINING_ENDED, null);
        state.ticket.setError(state.trainingMode ? "MODE FORMATION ACTIVÉ" : "MODE FORMATION TERMINÉ");
        LOGGER.info("Exiting method performTrainingToggle");
    }

    public void callSupervisor(String reason) {
        LOGGER.info("Entering method callSupervisor with reason: " + reason);
        technicalEventService.log(TechnicalEvent.EventType.SUPERVISOR_CALLED, reason);
        if (!syncOutboxService.isEnabled()) {
            state.ticket.setError("SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE");
            state.touch();
            LOGGER.info("Exiting method callSupervisor");
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
                // A CONFIRMATION, not a refusal: the zone is shared, so the
                // setter is what decides whether it is drawn red with an ERR
                // marker or as an acknowledgement.
                state.ticket.setNotice("SUPERVISEUR PRÉVENU");
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
        LOGGER.info("Exiting method callSupervisor");
    }

    /**
     * Submits a price modification, which is routed through a manager endorsement.
     *
     * @param type the modification mode, or null when the submitted word named none
     * @param uid the uid of the targeted ticket line
     * @param value the modification value (euros or percent depending on the type)
     */
    public void submitPriceMod(PriceModType type, String uid, BigDecimal value) {
        LOGGER.info("Entering method submitPriceMod with type: " + type + ", uid: " + uid + ", value: " + value);
        if (type == PriceModType.QUANTITY) {
            // Multiplying a scanned line is a normal sale action: no endorsement
            applyLineQuantity(uid, value);
        } else if (isDiscountGesture(type) && !posSettingsService.discountEnabled()) {
            // BO-03-07-01: remises and rabais deactivated at the back office —
            // the gesture is refused before any endorsement or application.
            state.ticket.setError("REMISES DÉSACTIVÉES");
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
        LOGGER.info("Exiting method submitPriceMod");
    }

    /**
     * Whether a price-gesture type is a discount or rebate — the family the
     * back office can deactivate (BO-03-07-01). Price forcing is excluded: it
     * is not a discount and answers to its own administration.
     *
     * @param type the gesture type
     * @return true for a line or global remise/discount
     */
    private boolean isDiscountGesture(PriceModType type) {
        return type == PriceModType.REMISE || type == PriceModType.DISCOUNT
                || type == PriceModType.GLOBAL_REMISE || type == PriceModType.GLOBAL_DISCOUNT;
    }

    /**
     * Applies a price gesture without the endorsement ceremony — the exact
     * mirror of the approved-endorsement dispatch, used when the back office
     * administered the gestures as free (LC-03-02-08).
     *
     * @param type the gesture mode
     * @param uid the targeted line uid, or null for a global gesture
     * @param value the typed value
     */
    private void applyGestureDirectly(PriceModType type, String uid, BigDecimal value) {
        if (type != null && type.isTicketLevel()) {
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
        if (type == PriceModType.REMISE) ticketService.applyRemise(item, value);
        else if (type == PriceModType.DISCOUNT) ticketService.applyDiscount(item, value);
        else if (type == PriceModType.FORCE_PRICE) ticketService.forcePrice(item, value);
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
        LOGGER.info("Entering method printOperatorBadge with operatorNumber: " + operatorNumber);
        com.intermarche.pos.domain.people.Employee employee = com.intermarche.pos.domain.people.Employee
                .<com.intermarche.pos.domain.people.Employee>find("badgeId", operatorNumber).firstResult();
        if (employee == null) {
            employee = com.intermarche.pos.domain.people.Employee
                    .<com.intermarche.pos.domain.people.Employee>find("loginName", operatorNumber).firstResult();
        }
        if (employee == null || !employee.active) {
            state.ticket.setError("OPÉRATEUR INTROUVABLE (" + operatorNumber + ")");
            LOGGER.info("Exiting method printOperatorBadge");
            return;
        }
        ticketPrinterService.printOperatorBadge(employee);
        state.ticket.setError("BADGE OPÉRATEUR IMPRIMÉ");
        LOGGER.info("Exiting method printOperatorBadge");
    }
}