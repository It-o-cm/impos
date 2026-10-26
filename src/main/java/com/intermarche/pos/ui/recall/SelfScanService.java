package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The SELFSCANNING baskets at the till (LC-06-05-01 to -06): a basket the
 * customer built on an in-store selfscanning device is recalled from the store
 * node by its code — at any register, whatever device built it (LC-06-05-03) —
 * controlled, then loaded into the sale, where the cashier adds what the
 * customer could not scan (LC-06-05-01) and the vouchers the device recorded
 * are applied at payment (LC-06-05-02).
 *
 * <p>The control is decided when the basket is recalled: the TOTAL control the
 * device asks for, otherwise the PARTIAL control the device asks for or the
 * store's rate draws (LC-06-05-04, -05), otherwise none. The cashier may provoke
 * a total control at any moment before the basket is loaded (LC-06-05-06).
 */
@ApplicationScoped
public class SelfScanService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SelfScanService.class);

    /** The outcome of the loading of a controlled basket. */
    public enum Outcome {

        /** The basket was loaded into the sale. */
        LOADED,

        /** No basket waits for its control. */
        NO_BASKET,

        /** A partial control has not confirmed the articles it asks for. */
        PARTIAL_PENDING
    }

    /** The localised operator messages. */
    @Inject
    Messages i18n;

    /** The control rate and the partial control's size. */
    @Inject
    PosSettingsService posSettingsService;

    /** Loads the controlled basket into the sale. */
    @Inject
    TicketService ticketService;

    /** The rules of the self-scanning channel (BO-10-01-15 to -23). */
    @Inject
    ChannelGate channelGate;

    /** The electronic journal the self-scanning controls are traced in (BO-04-01-42/43). */
    @Inject
    com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /** The draw of the partial controls. */
    Random random = new Random();

    /**
     * Opens the control of a recalled basket (LC-06-05-03 to -05).
     *
     * <p>A basket its channel refuses (BO-10-01-15 to -18, -23) opens no
     * control: the reason is on the message band, which the selfscanning
     * screen shows in place of the basket.
     *
     * @param state the current POS state
     * @param basket the basket the store node holds
     * @return the control opened, or null when the channel refused the basket
     */
    public SelfScanControl start(PosState state, RecalledDocument basket) {
        LOGGER.info("Entering method start with basket: " + basket.code);
        String refusal = channelGate.refusal(state, basket);
        if (refusal != null) {
            state.selfScan = null;
            state.ticket.setError(refusal);
            state.touch();
            LOGGER.info("Exiting method start (refused by the channel)");
            return null;
        }
        String requested = basket.control == null ? "" : basket.control.trim();
        String mode;
        boolean drawn = false;
        if (RecalledDocument.CONTROL_TOTAL.equals(requested)) {
            mode = RecalledDocument.CONTROL_TOTAL;
        } else if (RecalledDocument.CONTROL_PARTIAL.equals(requested)) {
            mode = RecalledDocument.CONTROL_PARTIAL;
        } else if (random.nextInt(100) < posSettingsService.selfScanPartialControlRate()) {
            mode = RecalledDocument.CONTROL_PARTIAL;
            drawn = true;
        } else {
            mode = RecalledDocument.CONTROL_NONE;
        }
        int units = 0;
        for (RecalledDocument.Line line : basket.lines) {
            if (line != null && line.ean() != null && !line.ean().isBlank()) {
                units += line.soldQuantity().setScale(0, java.math.RoundingMode.CEILING).intValue();
            }
        }
        int target = Math.max(1, Math.min(posSettingsService.selfScanPartialControlItems(), units));
        SelfScanControl control = new SelfScanControl(basket, mode, target);
        control.drawn = drawn;
        state.selfScan = control;
        state.touch();
        LOGGER.info("Exiting method start (" + mode + ")");
        return control;
    }

    /**
     * Provokes the total control of the basket waiting at the till (LC-06-05-06).
     *
     * @param state the current POS state
     * @return true when a basket waits and its control is now total
     */
    public boolean provokeTotal(PosState state) {
        LOGGER.info("Entering method provokeTotal");
        SelfScanControl control = state.selfScan;
        if (control == null) {
            LOGGER.info("Exiting method provokeTotal (no basket)");
            return false;
        }
        control.mode = RecalledDocument.CONTROL_TOTAL;
        control.provoked = true;
        control.message = i18n.get("selfscan.controleTotalProvoque");
        control.messageOk = true;
        state.touch();
        LOGGER.info("Exiting method provokeTotal");
        return true;
    }

    /**
     * Counts an article re-scanned during the control (LC-06-05-04, -05).
     *
     * <p>In a partial control, an article the basket declares and that is not
     * yet confirmed up to its declared quantity is confirmed; any other article
     * is a discrepancy, and the control becomes total. In a total control, every
     * article re-scanned is counted, the declared ones and the others alike. A
     * code the register does not know is refused and counted nowhere.
     *
     * @param state the current POS state
     * @param code the code scanned
     * @return true when the scan was counted
     */
    public boolean rescan(PosState state, String code) {
        LOGGER.info("Entering method rescan with code: " + code);
        SelfScanControl control = state.selfScan;
        String ean = code.trim();
        if (!control.isPartial() && !control.isTotal()) {
            say(control, i18n.get("selfscan.pasDeControle"), false);
            LOGGER.info("Exiting method rescan (no control)");
            return false;
        }
        boolean declared = control.declared.containsKey(ean);
        String label = declared ? declaredLabel(control, ean) : registerLabel(ean);
        if (label == null) {
            say(control, i18n.format("selfscan.articleInconnu", ean), false);
            LOGGER.info("Exiting method rescan (unknown article)");
            return false;
        }
        if (control.isPartial()) {
            if (declared && control.controlledOf(ean).compareTo(control.declaredOf(ean)) < 0) {
                control.count(ean, label);
                control.partialChecked++;
                say(control, control.isPartialPassed() ? i18n.get("selfscan.partielReussi")
                        : i18n.format("selfscan.articleControle", label,
                        control.partialChecked, control.partialTarget), true);
            } else {
                control.count(ean, label);
                control.mode = RecalledDocument.CONTROL_TOTAL;
                say(control, i18n.format("selfscan.ecartTotal", label), false);
            }
        } else {
            control.count(ean, label);
            say(control, i18n.format("selfscan.articleCompte", label), true);
        }
        state.touch();
        LOGGER.info("Exiting method rescan (" + control.mode + ")");
        return true;
    }

    /**
     * Loads the controlled basket into the sale (LC-06-05-01, -02): as the device
     * declared it after no control or a passed partial control, as the cashier
     * re-scanned it after a total control. The vouchers the device recorded ride
     * along and are applied at payment.
     *
     * @param state the current POS state
     * @return the outcome
     */
    public Outcome load(PosState state) {
        LOGGER.info("Entering method load");
        SelfScanControl control = state.selfScan;
        if (control == null) {
            LOGGER.info("Exiting method load (no basket)");
            return Outcome.NO_BASKET;
        }
        if (!control.isLoadable()) {
            say(control, i18n.get("selfscan.partielInacheve"), false);
            state.touch();
            LOGGER.info("Exiting method load (partial control pending)");
            return Outcome.PARTIAL_PENDING;
        }
        RecalledDocument basket = control.basket;
        RecalledDocument sale = new RecalledDocument(basket.kind, basket.code,
                control.isTotal() ? controlledLines(control) : basket.lines, basket.acompteAlreadyPaid);
        sale.label = basket.label;
        sale.control = control.mode;
        sale.vouchers = basket.vouchers;
        // BO-10-01-16 / -18: how the basket was retrieved travels with it, so the
        // channel judges the load as it judged the recall.
        sale.keyed = basket.keyed;
        sale.byCustomer = basket.byCustomer;
        sale.customerNumber = basket.customerNumber;
        sale.orderDate = basket.orderDate;
        state.selfScan = null;
        state.selfScanRequested = false;
        // BO-04-01-41/42/43: the sale is a self-scanning one, and the journal
        // keeps who decided its control — the hostess who provoked it, or the
        // system that drew it or that the basket itself required.
        boolean wasSelfScanning = state.ticket.selfScanning;
        boolean wasControlled = state.ticket.selfScanControlled;
        boolean wasVerified = state.ticket.selfScanVerified;
        boolean controlled = control.provoked;
        boolean verified = control.drawn
                || (!control.provoked && !RecalledDocument.CONTROL_NONE.equals(control.mode));
        state.ticket.selfScanning = true;
        state.ticket.selfScanControlled = wasControlled || controlled;
        state.ticket.selfScanVerified = wasVerified || verified;
        int before = state.ticket.items.size();
        ticketService.loadRecalledDocument(state, sale);
        if (state.ticket.items.size() == before) {
            state.ticket.selfScanning = wasSelfScanning;
            state.ticket.selfScanControlled = wasControlled;
            state.ticket.selfScanVerified = wasVerified;
        } else {
            trace(state, basket.code, controlled, verified);
        }
        state.touch();
        LOGGER.info("Exiting method load (loaded)");
        return Outcome.LOADED;
    }

    /**
     * Traces the control of a loaded self-scanning basket in the journal
     * (BO-04-01-42/43): a control the hostess decided names her, one the system
     * decided names nobody.
     *
     * @param state the current POS state
     * @param code the basket's code
     * @param controlled whether the hostess decided the control
     * @param verified whether the system decided it
     */
    private void trace(PosState state, String code, boolean controlled, boolean verified) {
        if (technicalEventService == null) {
            return;
        }
        if (controlled) {
            state.ticket.awaitTicket(technicalEventService.record(
                    com.intermarche.pos.domain.session.TechnicalEvent.EventType.SELF_SCAN_CONTROLLED,
                    "Contrôle self-scanning décidé par l'hôtesse " + state.auth.operatorBadgeId,
                    state.auth.operatorBadgeId, null, null, code));
        }
        if (verified) {
            state.ticket.awaitTicket(technicalEventService.record(
                    com.intermarche.pos.domain.session.TechnicalEvent.EventType.SELF_SCAN_VERIFIED,
                    "Vérification self-scanning décidée par le système", null, null, null, code));
        }
    }

    /**
     * Puts the basket aside, the cashier having done with it: it stays on the
     * store node, recallable again.
     *
     * @param state the current POS state
     */
    public void abandon(PosState state) {
        LOGGER.info("Entering method abandon");
        state.selfScan = null;
        state.selfScanRequested = false;
        state.touch();
        LOGGER.info("Exiting method abandon");
    }

    /**
     * The rows the control screen shows: one per article met, its declared and
     * re-scanned quantities.
     *
     * @param control the control
     * @return the rows, in the basket's order then the scans' order
     */
    public List<Map<String, String>> rows(SelfScanControl control) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : control.labels.entrySet()) {
            String ean = entry.getKey();
            BigDecimal declared = control.declaredOf(ean);
            BigDecimal controlled = control.controlledOf(ean);
            rows.add(Map.of(
                    "label", entry.getValue() == null ? ean : entry.getValue(),
                    "ean", ean,
                    "declared", plain(declared),
                    "controlled", plain(controlled),
                    "gap", control.isTotal() && declared.compareTo(controlled) != 0 ? "true" : "false"));
        }
        return rows;
    }

    /**
     * The lines of the sale after a total control: the declared lines reduced to
     * what was re-scanned, then the surplus and the articles the basket did not
     * declare; lines without EAN cannot be re-scanned and stay as declared.
     *
     * @param control the total control
     * @return the lines to load
     */
    private List<RecalledDocument.Line> controlledLines(SelfScanControl control) {
        List<RecalledDocument.Line> lines = new ArrayList<>();
        Map<String, BigDecimal> remaining = new java.util.LinkedHashMap<>(control.controlled);
        Map<String, RecalledDocument.Line> declaredLine = new java.util.HashMap<>();
        for (RecalledDocument.Line line : control.basket.lines) {
            if (line == null) {
                continue;
            }
            if (line.ean() == null || line.ean().isBlank()) {
                lines.add(line);
                continue;
            }
            declaredLine.putIfAbsent(line.ean(), line);
            BigDecimal left = remaining.getOrDefault(line.ean(), BigDecimal.ZERO);
            BigDecimal taken = left.min(line.soldQuantity());
            if (taken.signum() > 0) {
                lines.add(new RecalledDocument.Line(line.ean(), line.label(), taken,
                        line.unitPrice(), line.vatRate()));
                remaining.put(line.ean(), left.subtract(taken));
            }
        }
        for (Map.Entry<String, BigDecimal> entry : remaining.entrySet()) {
            if (entry.getValue().signum() <= 0) {
                continue;
            }
            RecalledDocument.Line model = declaredLine.get(entry.getKey());
            lines.add(model != null
                    ? new RecalledDocument.Line(model.ean(), model.label(), entry.getValue(),
                            model.unitPrice(), model.vatRate())
                    : registerLine(entry.getKey(), control.labels.get(entry.getKey()), entry.getValue()));
        }
        return lines;
    }

    /**
     * A line for an article the basket did not declare, at the register's price.
     *
     * @param ean the article EAN
     * @param label the article label
     * @param quantity the quantity re-scanned
     * @return the line
     */
    private RecalledDocument.Line registerLine(String ean, String label, BigDecimal quantity) {
        Product product = Product.findByEan(ean);
        Price price = product == null ? null : Price.findCurrentPrice(product.id);
        return new RecalledDocument.Line(ean, label, quantity,
                price == null ? BigDecimal.ZERO : price.priceIncludingTax,
                price == null ? null : price.vatRate());
    }

    /**
     * The label of an article the basket declares, its EAN when the device
     * named it not.
     *
     * @param control the control
     * @param ean the article EAN
     * @return the label, never null
     */
    private static String declaredLabel(SelfScanControl control, String ean) {
        String label = control.labels.get(ean);
        return label == null ? ean : label;
    }

    /**
     * The register's label of an article, for an article the basket does not
     * declare.
     *
     * @param ean the code scanned
     * @return the label, or null when the register does not know the article
     */
    private String registerLabel(String ean) {
        Product product = Product.findByEan(ean);
        return product == null ? null : product.name;
    }

    /**
     * Sets the control's message.
     *
     * @param control the control
     * @param message the message
     * @param ok whether it reports a success
     */
    private static void say(SelfScanControl control, String message, boolean ok) {
        control.message = message;
        control.messageOk = ok;
    }

    /**
     * Writes a quantity without its trailing zeros, French comma.
     *
     * @param value the quantity
     * @return the written quantity
     */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
