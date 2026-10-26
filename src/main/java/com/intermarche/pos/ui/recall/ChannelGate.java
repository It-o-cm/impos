package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the rules of the EXTERNAL CHANNELS (BO-10-01) to a basket the till
 * integrates — a self-scanning basket, a quote or an order of the commercial
 * management tool, a queue-busting basket, a mobility transaction, an
 * e-commerce basket, a sale voucher.
 *
 * <p>Three moments, one per method family:
 * <ul>
 *   <li>{@link #refusal} — may the basket come in at all: channel open, retrieved
 *       the way the channel admits, identified as the channel asks, a sale
 *       voucher of the day, no article forbidden to sale under a REJECT rule.
 *       Nothing is changed; the answer is the operator message or null.</li>
 *   <li>{@link #admit} — the refusal, then the basket as the channel wants it
 *       loaded: the unknown articles carried on the generic EAN and marked, the
 *       coupons dropped where the channel does not take them back, the
 *       promotion channel set on the ticket for the valuation.</li>
 *   <li>{@link #afterLoad} — once its lines are in the sale: the additions lock
 *       of a self-scanning basket, the settlements of a mobility transaction and
 *       its complementary tender, the operator notice.</li>
 * </ul>
 *
 * <p>A kind no channel rule applies to (the click-and-collect basket) passes
 * every gate untouched: the lot changes nothing for it.
 */
@ApplicationScoped
public class ChannelGate {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ChannelGate.class);

    /** The tender a mobility settlement naming none is restated under. */
    static final String DEFAULT_MOBILITY_TENDER = "CARD";

    /** The administered rules of the channels. */
    @Inject
    ChannelRules rules;

    /** The localised operator messages. */
    @Inject
    Messages i18n;

    /** Persists the settlements a mobility transaction brings along. */
    @Inject
    TicketPersistenceService ticketPersistenceService;

    /** The clock the day of a sale voucher is judged against. */
    Clock clock = Clock.systemDefaultZone();

    /**
     * Tells why a basket may not be integrated, changing nothing.
     *
     * @param state the current POS state
     * @param doc the recalled document, never null
     * @return the operator message, or null when the basket may come in
     */
    public String refusal(PosState state, RecalledDocument doc) {
        SalesChannel channel = SalesChannel.of(doc.kind);
        if (channel == null) {
            return null;
        }
        if (!rules.enabled(channel)) {
            return i18n.format("recall.canalInactif", i18n.get(doc.getKindKey()));
        }
        if (!rules.retrievalAllowed(channel, doc.keyed)) {
            return i18n.get(doc.keyed ? "recall.recuperationParScan" : "recall.recuperationParSaisie");
        }
        if (channel == SalesChannel.SELF_SCAN) {
            if (rules.selfScanByCustomer() && !doc.byCustomer) {
                return i18n.get("recall.identifiantClientAttendu");
            }
            if (!rules.selfScanAddsBefore() && !state.ticket.items.isEmpty()) {
                return i18n.get("recall.ajoutAvantPanierInterdit");
            }
        }
        if (channel == SalesChannel.SALES_SLIP && !issuedToday(doc.orderDate)) {
            return i18n.get("recall.bonDeVenteExpire");
        }
        return rules.rejectsForbidden(channel) ? forbiddenRefusal(doc) : null;
    }

    /**
     * The refusal of a basket carrying an article forbidden to sale under a
     * REJECT rule (BO-10-01-23, -24, -32, -37): the article's label, its code
     * and the message the article carries.
     *
     * @param doc the recalled document
     * @return the message naming the first forbidden article, or null
     */
    private String forbiddenRefusal(RecalledDocument doc) {
        for (RecalledDocument.Line line : doc.lines) {
            Product product = line == null || line.ean() == null ? null : Product.findByEan(line.ean());
            if (product != null && product.forbiddenToSale) {
                String label = product.checkoutLabel != null ? product.checkoutLabel : product.name;
                String message = product.infoMessage == null ? "" : product.infoMessage.trim();
                return i18n.format("recall.articleInterdit", label, product.ean, message).trim();
            }
        }
        return null;
    }

    /**
     * Whether a sale voucher was issued today (BO-10-01-40): its number is reset
     * every day, so yesterday's number names nothing any more. A voucher whose
     * date is unknown or unreadable is not refused on it.
     *
     * @param orderDate the issue date the store node states, ISO-8601, or null
     * @return true when issued today or undated
     */
    private boolean issuedToday(String orderDate) {
        if (orderDate == null || orderDate.length() < 10) {
            return true;
        }
        try {
            return LocalDate.parse(orderDate.substring(0, 10)).equals(LocalDate.now(clock));
        } catch (java.time.format.DateTimeParseException e) {
            return true;
        }
    }

    /**
     * Admits a basket into the sale: refuses it out loud, or prepares it the way
     * its channel wants it loaded.
     *
     * @param state the current POS state
     * @param doc the recalled document, never null
     * @return the document to load, or null when it was refused (the reason is
     *         on the ticket's message band)
     */
    public RecalledDocument admit(PosState state, RecalledDocument doc) {
        LOGGER.info("Entering method admit with document: " + doc.code);
        String refusal = refusal(state, doc);
        if (refusal != null) {
            state.ticket.setError(refusal);
            state.touch();
            LOGGER.info("Exiting method admit (refused)");
            return null;
        }
        SalesChannel channel = SalesChannel.of(doc.kind);
        String promotion = rules.promotionChannel(channel);
        if (promotion != null) {
            state.ticket.promotionChannel = promotion;
        }
        markUnknown(doc, rules.unknownItemEan(channel));
        if (channel == SalesChannel.SELF_SCAN && !rules.selfScanCoupons()
                && doc.vouchers != null && !doc.vouchers.isEmpty()) {
            doc.couponsDropped = doc.vouchers.size();
            doc.vouchers = new ArrayList<>();
        }
        LOGGER.info("Exiting method admit (admitted)");
        return doc;
    }

    /**
     * Carries the articles the register does not know on the channel's generic
     * EAN, their label kept and marked with their own code (BO-10-01-06, -21).
     * A line already on the generic EAN is left alone, so a basket admitted
     * twice is marked once.
     *
     * @param doc the document, changed in place
     * @param genericEan the generic EAN, empty when the lines keep their code
     */
    private void markUnknown(RecalledDocument doc, String genericEan) {
        if (genericEan.isEmpty()) {
            return;
        }
        List<RecalledDocument.Line> lines = new ArrayList<>();
        for (RecalledDocument.Line line : doc.lines) {
            if (line == null || line.ean() == null || line.ean().isBlank()
                    || genericEan.equals(line.ean()) || Product.findByEan(line.ean()) != null) {
                lines.add(line);
                continue;
            }
            lines.add(new RecalledDocument.Line(genericEan,
                    i18n.format("recall.libelleArticleInconnu", line.label() == null ? "" : line.label(), line.ean()).trim(),
                    line.quantity(), line.unitPrice(), line.vatRate(), line.deliveredQuantity()));
            doc.unknownMarked++;
        }
        doc.lines = lines;
    }

    /**
     * Completes the integration once the basket's lines are in the sale.
     *
     * @param state the current POS state
     * @param doc the document just loaded
     */
    public void afterLoad(PosState state, RecalledDocument doc) {
        LOGGER.info("Entering method afterLoad with document: " + doc.code);
        SalesChannel channel = SalesChannel.of(doc.kind);
        if (channel == null) {
            LOGGER.info("Exiting method afterLoad (no channel)");
            return;
        }
        state.ticket.recalledChannel = channel.name();
        List<String> notes = new ArrayList<>();
        if (channel == SalesChannel.SELF_SCAN && !rules.selfScanAddsAfter()) {
            state.ticket.additionsLocked = true;
        }
        if (channel == SalesChannel.MOBILITY) {
            settleMobility(state, doc, notes);
        }
        if (doc.unknownMarked > 0) {
            notes.add(i18n.format("recall.articlesInconnusMarques", String.valueOf(doc.unknownMarked)));
        }
        if (doc.couponsDropped > 0) {
            notes.add(i18n.format("recall.couponsNonRepris", String.valueOf(doc.couponsDropped)));
        }
        if (!notes.isEmpty()) {
            state.ticket.setWarning(String.join(" — ", notes));
        }
        state.touch();
        LOGGER.info("Exiting method afterLoad");
    }

    /**
     * Restates the settlements a mobility device already collected, then adds
     * the complementary tender for what remains due when the store administers
     * one (BO-10-01-39): a promotion granted on the device that the till did not
     * grant leaves the ticket paid short, and the difference is not the
     * customer's to pay again.
     *
     * @param state the current POS state
     * @param doc the mobility transaction
     * @param notes the operator notes, completed in place
     */
    private void settleMobility(PosState state, RecalledDocument doc, List<String> notes) {
        if (doc.payments != null) {
            for (RecalledDocument.Payment payment : doc.payments) {
                if (payment == null || payment.amount() == null || payment.amount().signum() <= 0) {
                    continue;
                }
                pay(state, payment.tender() == null || payment.tender().isBlank()
                        ? DEFAULT_MOBILITY_TENDER : payment.tender().trim(), payment.amount());
            }
        }
        String tender = rules.mobilityFictitiousTender();
        BigDecimal due = state.ticket.getTotalAmount().subtract(state.payment.paidAmount);
        if (!tender.isEmpty() && due.signum() > 0) {
            pay(state, tender, due);
            notes.add(i18n.format("recall.paiementComplementaire", tender,
                    due.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')));
        }
    }

    /**
     * Registers one settlement on the sale and persists it on the draft, like
     * every other settlement the register takes.
     *
     * @param state the current POS state
     * @param tender the settlement key
     * @param amount the amount, TTC
     */
    private void pay(PosState state, String tender, BigDecimal amount) {
        state.payment.addPayment(tender, amount);
        if (!state.trainingMode && state.payment.ticketDbId != null) {
            ticketPersistenceService.addPaymentToTicket(state.payment.ticketDbId,
                    state.payment.payments.get(state.payment.payments.size() - 1));
        }
    }

    /**
     * Refuses an article added after a self-scanning basket whose rule forbids
     * it (BO-10-01-17).
     *
     * @param state the current POS state
     * @return true when the addition is refused (the reason is on the band)
     */
    public boolean refusesAddition(PosState state) {
        if (!state.ticket.additionsLocked) {
            return false;
        }
        state.ticket.setError(i18n.get("recall.ajoutApresPanierInterdit"));
        state.touch();
        return true;
    }

    /**
     * Refuses a SCANNED article after a self-scanning basket whose rule forbids
     * additions (BO-10-01-17): a code naming an article of the referential, a
     * weighed-article label or a GS1 code carrying an article (AI 01), is refused; any other code — a loyalty card, a
     * voucher — goes on to the scan chain.
     *
     * @param state the current POS state
     * @param code the scanned code, never null
     * @return true when the scan is refused
     */
    public boolean refusesScannedArticle(PosState state, String code) {
        if (!state.ticket.additionsLocked) {
            return false;
        }
        if (code.matches("2[0-9]{12}") || code.matches("01[0-9]{14}.*") || Product.findByEan(code) != null) {
            return refusesAddition(state);
        }
        return false;
    }
}
