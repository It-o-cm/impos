package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.valuation.ValuationService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.scanner.ScanContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Ticket-building service: scan processing, item additions, price
 * modifications and ticket-level actions.
 * <p>
 * All monetary values are {@link BigDecimal} (phase 0). Weights coming from
 * the scale remain double at the hardware boundary and are converted when a
 * line is created.
 * <p>
 * Phase 0 lot 2: every cart mutation is followed by a draft synchronization
 * ({@link TicketPersistenceService#syncDraft}), so the sale survives a
 * register restart from the first article on.
 * <p>
 * {@code processScan} is the UNIFORM ENTRY of everything that names a
 * product or a code: scanner gun, simulator, manual typing, search-result
 * taps — one gate, one session guard (bypassed in training), one handler
 * chain assembled here from CDI in {@code @Priority} order (missing
 * annotation = 100). Uniformity is the guarantee that no path can invent
 * its own semantics: whatever adds to the cart went through the same
 * chain.
 */
@ApplicationScoped
public class TicketService {

    /** The back-office parameters (discount caps — LC-03-02). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** Internal scale used for intermediate unit-price divisions. */
    private static final int PRICE_SCALE = 4;

    /** Default VAT rate applied to unknown items without a catalog price (e.g. 0.20). */
    @ConfigProperty(name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /**
     * Technical EAN of the manual deconsignment line (parameterized).
     * <p>
     * Register-generated lines carry a technical EAN like every other line:
     * the valuation engine prices the WHOLE ticket and must be able to
     * resolve each line it receives — a line without an EAN does not exist.
     * The referential must declare these codes on the engine side too.
     */
    @ConfigProperty(name = "pos.ean.deconsignment")
    String deconsignmentEan;

    /** Technical EAN of a manually typed unknown article (parameterized). */
    @ConfigProperty(name = "pos.ean.unknown-item")
    String unknownItemEan;

    @Inject
    HardwareService hardwareService;

    @Inject
    TicketPersistenceService ticketPersistenceService;

    @Inject
    ValuationService valuationService;

    /**
     * The single post-mutation funnel (phase 7 lot 4): persists the draft,
     * then revalues the cart against the engine — every cart mutation of
     * this service ends here, so pricing follows the cart in real time
     * (synchronous local call; training, payment phase and open circuit
     * are guarded inside {@code revalue}).
     *
     * @param state the current POS state
     */
    private void syncAndRevalue(PosState state) {
        ticketPersistenceService.syncDraft(state);
        valuationService.revalue(state);
    }

    @Inject
    CashSessionService cashSessionService;

    @Inject
    Instance<ScanContext.ScanHandler> scanHandlers;

    @Inject
    PosState state;

    @Inject
    TicketPrinterService ticketPrinterService;

    @Inject
    com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /**
     * Applies (or clears, value 0) the ticket-level discount after its
     * manager endorsement (phase: global ticket discount). The base is what
     * the customer actually pays — AFTER the engine's offers — and the
     * amount is ventilated onto the lines at every recomputation (see
     * TicketState.allocateGlobalDiscount). Refused during an active payment.
     *
     * @param state the current POS state
     * @param type GLOBAL_REMISE (euros) or GLOBAL_DISCOUNT (percent)
     * @param value the endorsed value
     */
    public void applyGlobalDiscount(PosState state, String type, BigDecimal value) {
        if (state.payment.paymentInProgress) {
            state.ticket.setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
            return;
        }
        // The percentage cap is administered (LC-03-02-13); 100 stays the
        // absolute ceiling whatever the back office says.
        int cap = Math.min(100, posSettingsService.globalMaxDiscountPercent());
        if (value == null || value.signum() < 0
                || ("GLOBAL_DISCOUNT".equals(type) && value.compareTo(BigDecimal.valueOf(cap)) > 0)) {
            state.ticket.setError("VALEUR INVALIDE");
            return;
        }
        state.ticket.setGlobalDiscount(
                "GLOBAL_DISCOUNT".equals(type) ? "PERCENT" : "AMOUNT", value);
        recalculateTotal(state);
    }

    /**
     * Suspends the sale on an age-restricted product: the add is PARKED and
     * the server-rendered prompt asks the cashier to check the customer's ID
     * (phase: age control). Returns false — no prompt — when the product is
     * unrestricted or the ticket already carries a verification at this
     * threshold or above (one ID check covers the whole sale).
     *
     * @param state the current POS state
     * @param product the resolved product
     * @param kind the replay kind (SCAN, PLU or EAN_QTY)
     * @param code the parked code to replay on confirmation
     * @param quantity the parked quantity (EAN_QTY kind), or null
     * @return true when the add was suspended behind the prompt
     */
    public boolean suspendForAgeCheck(PosState state, Product product, String kind,
                                      String code, BigDecimal quantity) {
        if (product.ageRestriction == null
                || product.ageRestriction <= state.ticket.ageVerifiedThreshold) {
            return false;
        }
        state.ageCheck.active = true;
        state.ageCheck.productLabel = product.name.toUpperCase();
        state.ageCheck.threshold = product.ageRestriction;
        state.ageCheck.kind = kind;
        state.ageCheck.code = code;
        state.ageCheck.quantity = quantity;
        hardwareService.displayMessage("CONTROLE D'AGE EN COURS");
        state.touch();
        return true;
    }

    /**
     * Confirms the ID check: the verification is remembered for the whole
     * ticket (highest threshold wins), the decision is journalized, and the
     * parked add REPLAYS through its original path — the sale resumes as if
     * the product had just been presented.
     *
     * @param state the current POS state
     */
    public void confirmAgeCheck(PosState state) {
        PosState.AgeCheckState pending = state.ageCheck;
        if (!pending.active) return;
        state.ticket.ageVerifiedThreshold =
                Math.max(state.ticket.ageVerifiedThreshold, pending.threshold);
        technicalEventService.log(
                com.intermarche.pos.domain.ticket.TechnicalEvent.EventType.AGE_CHECK_CONFIRMED,
                pending.productLabel + " (" + pending.threshold + "+) - ID vérifiée par "
                        + state.auth.operatorName);
        String kind = pending.kind;
        String code = pending.code;
        BigDecimal quantity = pending.quantity;
        pending.clear();
        switch (kind) {
            case "PLU" -> addItemByPlu(state, code);
            case "EAN_QTY" -> addItemByEan(state, code, quantity);
            default -> processScan(code);
        }
    }

    /**
     * Refuses the sale of the restricted product: nothing is added, the
     * refusal is journalized (the legal trace), and the cashier gets the
     * transient refusal message.
     *
     * @param state the current POS state
     */
    public void refuseAgeCheck(PosState state) {
        PosState.AgeCheckState pending = state.ageCheck;
        if (!pending.active) return;
        technicalEventService.log(
                com.intermarche.pos.domain.ticket.TechnicalEvent.EventType.AGE_CHECK_REFUSED,
                pending.productLabel + " (" + pending.threshold + "+) - vente refusée par "
                        + state.auth.operatorName);
        pending.clear();
        state.ticket.setError("VENTE REFUSÉE - CONTRÔLE D'ÂGE");
        state.touch();
    }

    /**
     * Processes a scanned code by running it through the ordered scan handler chain.
     *
     * @param code the scanned code
     */
    public void processScan(String code) {
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        state.selectedTicketIndex = -1;

        // Phase 2 session gate: sale scans are refused while no session is
        // open (badge scans on the lock screen are unaffected).
        if (!state.isLocked() && !requireOpenSession(state)) {
            return;
        }

        ScanContext ctx = new ScanContext(code, state);
        List<ScanContext.ScanHandler> handlers = StreamSupport.stream(scanHandlers.spliterator(), false)
                .sorted(Comparator.comparing(this::getPriority)).toList();
        handlers.stream().forEach(handler -> handler.handle(ctx));


        // Lot 2: single sync point for every scan-driven mutation (EAN/PLU
        // handlers, fidelity card, vouchers). No-op cost only when locked or
        // before the first article.
        if (!state.isLocked() && (!state.ticket.items.isEmpty() || state.payment.ticketDbId != null)) {
            syncAndRevalue(state);
        }
    }

    /**
     * Processes a weight reported by the scale.
     *
     * @param weightStr the weight as text (comma or dot separator)
     */
    public void processWeight(String weightStr) {
        if (state.isLocked()) return;
        try {
            double w = Double.parseDouble(weightStr.replace(',', '.'));
            state.ticket.setWeight(w);
        } catch (NumberFormatException e) { System.err.println("Poids invalide: " + weightStr); }
    }

    /**
     * Resolves the {@link Priority} value of a scan handler, unwrapping CDI proxies.
     *
     * @param handler the scan handler
     * @return the priority value, or 100 when the annotation is absent
     */
    private int getPriority(ScanContext.ScanHandler handler) {
        Class<?> effectiveClass = handler.getClass();
        if (effectiveClass.getName().contains("$Proxy") || effectiveClass.getName().contains("_ClientProxy")) {
            effectiveClass = effectiveClass.getSuperclass();
        }
        Priority priority = effectiveClass.getAnnotation(Priority.class);
        return (priority != null) ? priority.value() : 100;
    }

    /**
     * Verifies that a cash session is open on this register before a sale
     * mutation; shows an error to the cashier otherwise.
     *
     * @param state the current POS state
     * @return true when a session is open, false otherwise (mutation refused)
     */
    private boolean requireOpenSession(PosState state) {
        if (state.trainingMode) return true; // training sells without a session
        if (cashSessionService.getOpenSession() != null) return true;
        state.ticket.setError("AUCUNE SESSION OUVERTE - MENU CAISSE");
        return false;
    }

    // --- CUSTOMER DISPLAY LOGIC ---

    /**
     * Shows a ticket line on the customer display, or the welcome message.
     *
     * @param item the line to show, or null for the welcome message
     */
    private void displayItem(TicketState.TicketItem item) {
        if (item == null) {
            hardwareService.displayMessage("INTERMARCHE");
            return;
        }
        String label = item.label;
        BigDecimal price = item.getTotalPrice();
        String msg = String.format("%s\t%.2fE", label, price);
        hardwareService.displayMessage(msg);
    }

    /**
     * Shows the last ticket line on the customer display, or the welcome
     * message when the ticket is empty.
     *
     * @param state the current POS state
     */
    private void displayLastItemOrWelcome(PosState state) {
        if (state.ticket.items.isEmpty()) {
            hardwareService.displayMessage("INTERMARCHE");
        } else {
            displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
        }
    }

    // --- PRICE MODIFICATIONS ---

    /**
     * Applies an absolute discount (euros) on the line total.
     *
     * @param item the target line
     * @param amount the discount amount in euros (strictly positive)
     */
    public void applyRemise(TicketState.TicketItem item, BigDecimal amount) {
        if (item == null || amount == null || amount.signum() <= 0) return;
        if (item.moneyProduct) return; // money products are never discounted
        if (item.originalUnitPrice.signum() == 0 || item.originalUnitPrice.compareTo(item.unitPrice) == 0) {
            item.originalUnitPrice = item.unitPrice;
        }
        BigDecimal currentTotal = item.unitPrice.multiply(item.quantity);
        BigDecimal newTotal = currentTotal.subtract(amount);
        if (newTotal.signum() < 0) newTotal = BigDecimal.ZERO;
        if (item.quantity.signum() != 0) {
            item.unitPrice = newTotal.divide(item.quantity, PRICE_SCALE, RoundingMode.HALF_UP);
        } else {
            item.unitPrice = newTotal;
        }
        item.modifierLabel = String.format("Remise -%.2f€", amount);
        item.modifierType = "REMISE";
        item.modifierValue = amount;
        displayItem(item);
    }

    /**
     * Applies a percentage discount on the unit price.
     *
     * @param item the target line
     * @param percent the discount percentage (0 exclusive to 100 inclusive)
     */
    public void applyDiscount(TicketState.TicketItem item, BigDecimal percent) {
        if (item != null && item.moneyProduct) return; // money products are never discounted
        // The line cap is administered (LC-03-02-07); 100 stays absolute.
        int lineCap = Math.min(100, posSettingsService.lineMaxDiscountPercent());
        if (item == null || percent == null || percent.signum() <= 0
                || percent.compareTo(BigDecimal.valueOf(lineCap)) > 0) return;
        if (item.originalUnitPrice.signum() == 0 || item.originalUnitPrice.compareTo(item.unitPrice) == 0) {
            item.originalUnitPrice = item.unitPrice;
        }
        BigDecimal reduction = item.unitPrice.multiply(percent)
                .divide(BigDecimal.valueOf(100), PRICE_SCALE, RoundingMode.HALF_UP);
        item.unitPrice = item.unitPrice.subtract(reduction);
        item.modifierLabel = String.format("Discount -%.2f%%", percent);
        item.modifierType = "DISCOUNT";
        item.modifierValue = percent;
        displayItem(item);
    }

    /**
     * Forces the line total to a new price.
     *
     * @param item the target line
     * @param newTotalPrice the new line total (zero or positive)
     */
    public void forcePrice(TicketState.TicketItem item, BigDecimal newTotalPrice) {
        if (item != null && item.moneyProduct) return; // money products are never discounted
        if (item == null || newTotalPrice == null || newTotalPrice.signum() < 0) return;
        if (item.originalUnitPrice.signum() == 0 || item.originalUnitPrice.compareTo(item.unitPrice) == 0) {
            item.originalUnitPrice = item.unitPrice;
        }
        BigDecimal oldTotalPrice = item.originalUnitPrice.multiply(item.quantity);
        if (item.quantity.signum() != 0) {
            item.unitPrice = newTotalPrice.divide(item.quantity, PRICE_SCALE, RoundingMode.HALF_UP);
        } else {
            item.unitPrice = newTotalPrice;
        }
        item.modifierLabel = String.format("Prix initial: %.2f€", oldTotalPrice);
        item.modifierType = "FORCE_PRICE";
        item.modifierValue = newTotalPrice;
        displayItem(item);
    }

    /**
     * Recomputes the ticket total (fiscal rule: sum of line totals rounded to
     * the cent) and synchronizes the draft (covers line cancellations and
     * endorsed price modifications).
     *
     * @param state the current POS state
     */
    public void recalculateTotal(PosState state) {
        state.ticket.recomputeTotal();
        state.ticket.onChange();
        syncAndRevalue(state);
    }

    // --- STANDARD ACTIONS ---

    /**
     * Adds a product to the ticket by its EAN code.
     *
     * @param state the current POS state
     * @param ean the EAN code
     * @param quantity the quantity to add
     */
    public void addItemByEan(PosState state, String ean, BigDecimal quantity) {
        if (ean == null || ean.isEmpty()) return;
        if (!requireOpenSession(state)) return;
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        Product p = Product.find("ean = ?1 and active = true", ean).firstResult();
        if (p != null) {
            if (p.forbiddenToSale) {
                state.ticket.setError("PRODUIT INTERDIT À LA VENTE");
                return;
            }
            if (suspendForAgeCheck(state, p, "EAN_QTY", ean, quantity)) {
                return;
            }
            Price price = Price.findCurrentPrice(p.id);
            BigDecimal finalPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
            BigDecimal vatRate = (price != null) ? price.vatRate : defaultVatRate;
            // PLU is null: unit sale
            state.ticket.addItem(ean, null, p.name.toUpperCase(), finalPrice, quantity, vatRate);
            if (p.giftCardAmount != null) {
                state.ticket.items.get(state.ticket.items.size() - 1).moneyProduct = true;
            }
            displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
            syncAndRevalue(state);
        } else {
            state.ticket.setError("PRODUIT INTROUVABLE");
        }
    }

    /**
     * Adds a weighed product to the ticket by its PLU code, requesting a weighing.
     *
     * @param state the current POS state
     * @param pluCode the PLU code
     */
    public void addItemByPlu(PosState state, String pluCode) {
        if (!requireOpenSession(state)) return;
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        Product p = Product.findActiveByPlu(pluCode);
        if (p != null) {
            if (p.forbiddenToSale) {
                state.ticket.setError("PRODUIT INTERDIT À LA VENTE");
                return;
            }
            // Age gate BEFORE weighing: the scale read is consumed on the
            // replay only, so a confirmed check weighs exactly once.
            if (suspendForAgeCheck(state, p, "PLU", pluCode, null)) {
                return;
            }
            double weight = hardwareService.requestWeighing();
            if (weight <= 0) { state.ticket.setError("POIDS INVALIDE"); return; }
            if (!Double.isNaN(state.ticket.lastRecordedWeight) && Double.compare(weight, state.ticket.lastRecordedWeight) == 0) { state.ticket.setError("ERREUR POIDS IDENTIQUE"); return; }

            state.ticket.lastRecordedWeight = weight;
            Price price = Price.findCurrentPrice(p.id);
            BigDecimal unitPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
            BigDecimal vatRate = (price != null) ? price.vatRate : defaultVatRate;
            BigDecimal quantityKg = BigDecimal.valueOf(weight).setScale(3, RoundingMode.HALF_UP);

            // Real PLU code carried by the line; EAN left null
            state.ticket.addItem(p.ean, pluCode, p.name.toUpperCase(), unitPrice, quantityKg, vatRate);

            displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
            syncAndRevalue(state);
        } else {
            state.ticket.setError("PLU INTROUVABLE");
        }
    }

    /**
     * Adds an unknown (unlisted) item with a typed label and price.
     *
     * @param state the current POS state
     * @param label the label typed by the cashier
     * @param priceStr the price typed by the cashier
     */
    public void addUnknownItem(PosState state, String label, String priceStr) {
        if (!requireOpenSession(state)) return;
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        try {
            BigDecimal price = new BigDecimal(priceStr.replace(',', '.').replace(" ", ""));
            if (price.signum() >= 0 && label != null && !label.isEmpty()) {
                state.ticket.addItem(unknownItemEan, null, label.toUpperCase(),
                        price, BigDecimal.ONE, defaultVatRate);
                displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
                syncAndRevalue(state);
            }
        } catch (Exception e) {
            state.ticket.setError("ERREUR PRIX SAISI");
        }
    }

    /**
     * Adds a deposit-return line to the ticket (out of VAT scope: zero rate).
     *
     * @param state the current POS state
     */
    public void addDeposit(PosState state) {
        if (!requireOpenSession(state)) return;
        state.selectedTicketIndex = -1;
        state.ticket.addItem(deconsignmentEan, null, "DECONSIGNATION",
                new BigDecimal("-1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
        syncAndRevalue(state);
    }

    /**
     * Cancels a ticket line by its uid and refreshes total and display.
     *
     * @param state the current POS state
     * @param uid the uid of the line to cancel
     */
    public void cancelItemById(PosState state, String uid) {
        state.ticket.removeItemById(uid);
        recalculateTotal(state);
        state.selectedTicketIndex = -1;
        displayLastItemOrWelcome(state);
    }

    /**
     * Cancels the whole ticket: marks the database draft as cancelled, then
     * clears the in-memory state and resets the customer display.
     *
     * @param state the current POS state
     */
    public void cancelTicket(PosState state) {
        // Cancel the draft before clearTicket() nulls its id
        if (state.payment.ticketDbId != null) {
            ticketPersistenceService.cancelDraft(state.payment.ticketDbId);
        }
        state.clearTicket();
        hardwareService.displayMessage("INTERMARCHE");
    }

    /**
     * Reprints a closed ticket by its database id.
     *
     * @param ticketId the ticket database id
     */
    public void reprintTicket(Long ticketId) {
        if (ticketId != null) {
            ticketPrinterService.printTicket(ticketId);
        }
    }
}
