package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.catalog.attribute.RestrictedTender;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer-facing display (phase 4): a dedicated page opened on the second
 * screen, polling its own fragment with the same version mechanism as the
 * main screen. Shows the running cart, the totals, the payment progress and,
 * once complete, the change and the digital receipt link. No lock applies —
 * it is a display, not a control surface.
 * <p>
 * The screen is DERIVED, never commanded: {@code /customer-data} projects
 * the register's {@code PosState} into a display phase (welcome when locked
 * or empty, running cart, payment in progress, thank-you with change and
 * digital link, orange banner in training) and the page's versioned poll
 * redraws on change — the register never pushes anything to the display,
 * which is what lets it be a plain second browser window with zero setup.
 * Because it reads shared state without a lock gate, it must stay
 * READ-ONLY: any control added here would bypass the operator lock.
 * <p>
 * ONE EXCEPTION, and it moves no money: where the store lets the customer
 * choose the language of this display and of the receipt (LC-13-06-08 / -13),
 * the display offers one key per language and {@code /customer/language}
 * records the choice. It writes nothing but that language, only while a
 * register is signed in, and the choice dies with the transaction.
 */
@Path("/")
public class CustomerDisplayResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CustomerDisplayResource.class);

    @Inject @Location("customer") Template customer;

    /** The cash menu whose customer-screen model applies (BO-03-10-17). */
    @Inject com.intermarche.pos.ui.home.CashMenuService cashMenuService;
    @Inject PosState state;
    @Inject com.intermarche.pos.ui.Messages i18n;

    /** LC-10-01-17: localized fallback for tenders without an administered label. */
    private static final Map<String, String> TENDER_LABELS = Map.ofEntries(
            Map.entry("CASH", "paper.especes"), Map.entry("CARD", "paper.carteBancaire"),
            Map.entry("CHEQUE", "paper.cheque"), Map.entry("CHEQUE_DEFERRED", "pay.deferredCheque"),
            Map.entry("TR", "paper.ticketResto"), Map.entry("CREDIT", "paper.creditClient"),
            Map.entry("FIDELITY", "paper.cagnotte2"), Map.entry("VOUCHER", "paper.bonAchat"),
            Map.entry("ARRONDI", "paper.arrondi"), Map.entry("DEVISE", "paper.devise"),
            Map.entry("SECOURS", "paper.secoursMonetique"), Map.entry("TRD", "paper.carteTrd"),
            Map.entry("RESTOFLASH", "paper.restoFlash"), Map.entry("GIFTCARD", "paper.carteCadeau"),
            Map.entry("SOGEC", "paper.bonSogec"), Map.entry("ACOMPTE", "paper.repriseAcompte"),
            Map.entry("CAS", "paper.carteSociale"));

    /** The back-office parameters (EAN, welcome messages — LC-10-01-20/21). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** Decides, line by line, whether the article code is shown (BO-10-02-35). */
    @Inject
    com.intermarche.pos.ui.ticket.ArticleCodeService articleCodeService;

    /**
     * Shows the customer display page.
     *
     * <p>Rendered HERE, in the customer's language (LC-13-06-08), rather than
     * handed back as a template instance: the instance would be rendered after
     * this method returns, out of the customer's scope, and its fixed mentions
     * would come out in the cashier's language.
     *
     * @return the customer display page
     */
    @GET
    @Path("/customer")
    @Produces(MediaType.TEXT_HTML)
    public String customerPage() {
        LOGGER.info("Entering method customerPage");
        state.customerDisplaySeenAt = System.currentTimeMillis();
        LOGGER.info("Exiting method customerPage");
        // BO-03-10-17: the customer screen follows the model of the cash menu.
        io.quarkus.qute.RawString customerCss = new io.quarkus.qute.RawString(cashMenuService.current().customerCss());
        return i18n.forCustomerDisplay(() -> customer.data("state", state)
                .data("customerCss", customerCss).render());
    }

    /**
     * Records the language the customer tapped on the customer display, for the
     * display and the receipt of the current transaction (LC-13-06-08 / -13).
     *
     * <p>Ignored unless the store offers the choice, a register is signed in
     * and the language is one the register offers: a forged tag or a tap on a
     * closed register changes nothing. The display redraws itself in that
     * language at its next poll.
     *
     * @param tag the language tag of the key tapped
     * @return 204, whatever was decided — the display reads the outcome from its poll
     */
    @POST
    @Path("/customer/language")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response chooseLanguage(@FormParam("tag") String tag) {
        LOGGER.info("Entering method chooseLanguage with tag: " + tag);
        if (posSettingsService.customerLanguageChoice() && !state.isLocked() && tag != null
                && i18n.getAvailableLanguages().contains(tag)) {
            state.customerLanguage = tag;
            state.touch();
        }
        LOGGER.info("Exiting method chooseLanguage");
        return Response.noContent().build();
    }

    /**
     * Polling endpoint of the customer display, version-gated like the main
     * screen fragment.
     *
     * @param clientVersion the version known by the display, or null
     * @return a JSON map with the change flag and, when changed, the display data
     */
    @GET
    @Path("/customer-data")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> customerData(@QueryParam("v") Long clientVersion) {
        LOGGER.info("Entering method customerData with clientVersion: " + clientVersion);
        // LC-13-06-08: every mention of this payload faces the customer.
        Map<String, Object> result = i18n.forCustomerDisplay(() -> customerSnapshot(clientVersion));
        LOGGER.info("Exiting method customerData");
        return result;
    }

    /**
     * Builds the customer display payload, in the language the customer display
     * is rendered in.
     *
     * @param clientVersion the version known by the display, or null
     * @return a JSON map with the change flag and, when changed, the display data
     */
    private Map<String, Object> customerSnapshot(Long clientVersion) {
        // Every poll is the proof that a customer screen exists and is showing this
        // page: it is the only evidence there is, a screen being no device on any bus.
        // The hardware gate reads this timestamp.
        state.customerDisplaySeenAt = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        if (clientVersion != null && state.version == clientVersion) {
            result.put("changed", false);
            return result;
        }
        result.put("changed", true);
        result.put("version", state.version);
        result.put("locked", state.isLocked());
        // LC-10-01-20/21: the idle message is administered — one text for an
        // open register waiting for a sale, another for a locked one.
        // BO-10-07-17: on a bilingual display the administered translation
        // stands beside it, in the order the bilingual labels use.
        result.put("welcomeMessage", state.isLocked()
                ? bilingual(posSettingsService.customerClosedMessage(),
                        posSettingsService.customerClosedMessageSecond())
                : bilingual(posSettingsService.customerOpenMessage(),
                        posSettingsService.customerOpenMessageSecond()));
        result.put("training", state.trainingMode);
        // The one PUSHED value of this page: what a line display would have shown.
        // Everything else here is derived from the sale, but "PAIEMENT REFUSE" is an
        // event, and this screen is the only customer display this till has.
        result.put("message", state.customerMessage);
        result.put("empty", state.ticket.items.isEmpty());
        result.put("total", state.ticket.getTotalFormatted());
        result.put("remaining", state.getRemainingFormatted());
        // LC-10-01-07: show the eligible merchandise base, independently of tender caps or payments.
        java.math.BigDecimal mealEligible = "ENGINE".equals(state.payment.valuationStatus)
                && state.payment.valuationMealEligible != null
                ? state.payment.valuationMealEligible : state.ticket.mealVoucherEligibleTotal();
        result.put("mealEligible", mealEligible.max(java.math.BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ','));
        // LC-10-01-09/11: the eco-voucher and social-card bases, where the shop shows them.
        result.put("restrictedEligible", restrictedEligible());
        // LC-07-03-03: the customer display carries BOTH figures where the shop
        // rounds — what the sale owes, and what the customer hands over.
        result.put("roundedRemaining", state.getCashRoundedRemainingFormatted());
        result.put("rounding", state.isCashRoundingVisible());
        // BO-10-03-03: the holder's name on the CUSTOMER display, where the
        // shop asks for it — a separate decision from the cashier screen's,
        // because this one faces the queue.
        result.put("fidelityHolder",
                posSettingsService.fidelityShowHolderNameOnCustomerDisplay()
                        ? state.fidelity.getHolderName() : "");
        // LC-03-04-13: the card's available balance, a separate back-office choice.
        result.put("fidelityBalance", fidelityBalance());
        result.put("paying", state.payment.paymentInProgress);
        result.put("complete", state.payment.transactionComplete);
        result.put("change", state.payment.lastChangeAmount != null
                ? state.payment.lastChangeAmount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",")
                : "");
        // BO-10-07-02: the digital-receipt QR code is emitted only when the
        // back office activated it; disabled, the display shows no QR code.
        result.put("digitalPath", posSettingsService.customerQrEnabled() ? digitalPath() : "");

        List<Map<String, String>> items = new ArrayList<>();
        if (articleCodeService != null) {
            articleCodeService.resolve(state.ticket);
        }
        com.intermarche.pos.domain.catalog.UnitFormat format =
                com.intermarche.pos.domain.catalog.UnitFormat.orDefault(posSettingsService.unitFormat());
        for (TicketState.TicketItem item : state.ticket.items) {
            Map<String, String> row = new HashMap<>();
            row.put("label", item.label);
            if (item.getCodeShown()) {
                // LC-02-04-02 / BO-10-02-35: the code accompanies the label where
                // the article's flag — or the shop's default — asks for it.
                row.put("ean", item.ean);
            }
            row.put("qty", quantityDisplay(item, format));
            row.put("amount", item.getPriceFormatted());
            // P3-5 / LC-02-05-04: the decomposition line (qty × unit price) the
            // customer reads under the label, empty for a single-unit piece.
            row.put("detail", item.getDetailLine(format));
            // LC-10-01-03: expose applied labels, never stale cancelled advantages.
            row.put("promotion", item.cancelled ? "" : item.promotionLabel);
            row.put("modifier", item.cancelled ? "" : item.getModifierLabel());
            // LC-14-02-07: the software tare really applied to this weighing, when one was.
            if (item.getTareFormatted() != null) {
                row.put("tare", format.weight(item.appliedTareKg));
            }
            items.add(row);
        }
        result.put("items", items);
        List<Map<String, String>> payments = new ArrayList<>();
        for (com.intermarche.pos.ui.payment.PaymentState.PaymentEntry entry : state.payment.payments) {
            payments.add(Map.of("label", paymentLabel(entry), "amount",
                    entry.amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')));
        }
        result.put("payments", payments);
        // LC-07-15-02: what the customer has fed into the cash changer, refreshed
        // at each coin or note; empty when no deposit runs.
        result.put("cashChangerInserted", state.payment.getCashChangerInsertedFormatted());
        // LC-13-06-06: browser-generated mentions follow the same bilingual policy.
        result.put("labels", Map.of("paying", i18n.both("customer.paying"),
                "remaining", i18n.both("customer.remaining"), "cash", i18n.both("customer.cash"),
                "change", i18n.both("customer.change"), "digital", i18n.both("customer.digital"),
                "mealEligible", i18n.both("customer.mealEligible"),
                "tare", i18n.both("customer.tare"),
                "fidelityBalance", i18n.both("customer.fidelityBalance"),
                "inserted", i18n.both("customer.inserted")));
        // LC-10-01-03: the allocated ticket discount is information, not a second debit.
        result.put("discountLabel", customerDiscountLabel());
        result.put("discountAmount", state.ticket.getGlobalDiscountAppliedFormatted());
        // LC-13-06-08 / -13: the language the display is drawn in — the page
        // redraws itself when it moves — and the keys the customer may tap.
        result.put("displayLang", i18n.getLang());
        result.put("languages", customerLanguages());
        return result;
    }

    /**
     * Lists the language keys the customer display offers (LC-13-06-08 / -13):
     * each language the register offers, named in its own language.
     *
     * <p>Empty unless the store offers the choice and there are at least two
     * languages to choose from — a single key is no choice — and while the
     * register is locked, when no transaction can follow the choice.
     *
     * @return the keys, each with its tag and its name, in administered order
     */
    private List<Map<String, String>> customerLanguages() {
        List<String> tags = i18n.getAvailableLanguages();
        if (!posSettingsService.customerLanguageChoice() || state.isLocked() || tags.size() < 2) {
            return List.of();
        }
        List<Map<String, String>> keys = new ArrayList<>();
        for (String tag : tags) {
            keys.add(Map.of("tag", tag, "name", i18n.languageName(tag)));
        }
        return keys;
    }

    /**
     * Formats the attached card's available balance for the customer display
     * (LC-03-04-13).
     *
     * <p>Empty unless the back office shows it, a card is attached and the
     * fidelity service answered a balance: a display must not state a figure
     * nobody read, and a degraded fidelity service shows nothing rather than zero.
     *
     * @return the formatted balance, or an empty string
     */
    private String fidelityBalance() {
        if (!posSettingsService.fidelityShowBalanceOnCustomerDisplay() || !state.fidelity.active
                || state.fidelity.availableBalance == null) {
            return "";
        }
        return state.fidelity.availableBalance.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** LC-10-01-09: the eco-voucher eligibility, as the article attribute names it. */
    private static final RestrictedTender ECO_VOUCHER = new RestrictedTender(
            "ECO", ProductAttributeCatalog.ECO_VOUCHER_ELIGIBLE, "customer.ecoEligible");

    /** LC-10-01-11: the social-purchase-card eligibility, as the article attribute names it. */
    private static final RestrictedTender SOCIAL_CARD = new RestrictedTender(
            "CAS", ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE, "customer.socialEligible");

    /**
     * Builds the eligible bases of the restricted tenders the shop shows to the
     * customer (LC-10-01-09 eco-vouchers, LC-10-01-11 social purchase card).
     *
     * <p>Each one is a back-office choice, because each belongs to one country:
     * a total shown where the tender does not exist would only crowd the screen.
     * A shown base stays on screen at zero, like the meal-voucher base: the
     * customer reads it "en permanence jusqu'au paiement".
     *
     * <p>THE ENGINE KEEPS ITS RULES. imvaluation values the lines and answers no
     * eco-voucher or social-card base of its own; the register therefore adds up
     * the valued lines whose article carries the attribute, and computes nothing
     * else. Recomputed at every poll from the lines, the base follows every
     * entry, cancellation, quantity change and discount.
     *
     * @return the shown bases in a fixed order, each with its bilingual label and amount
     */
    private List<Map<String, String>> restrictedEligible() {
        List<Map<String, String>> rows = new ArrayList<>();
        if (posSettingsService.customerEcoVoucherEligible()) {
            rows.add(restrictedRow(ECO_VOUCHER));
        }
        if (posSettingsService.customerSocialCardEligible()) {
            rows.add(restrictedRow(SOCIAL_CARD));
        }
        return rows;
    }

    /**
     * Projects one restricted tender's eligible base for the customer display.
     *
     * @param tender the tender, its label being the message key of its mention
     * @return the bilingual label and the formatted amount
     */
    private Map<String, String> restrictedRow(RestrictedTender tender) {
        return Map.of("label", i18n.both(tender.label()), "amount",
                state.ticket.restrictedTenderEligibleTotal(tender).toPlainString().replace('.', ','));
    }

    /**
     * Renders the standard ticket-discount mention in both customer languages.
     * @return null without an allocated discount, otherwise its formatted label
     */
    private String customerDiscountLabel() {
        if (state.ticket.globalDiscountApplied == null) return null;
        if ("PERCENT".equals(state.ticket.globalDiscountType)) {
            return java.text.MessageFormat.format(i18n.both("paper.remiseTicketTaux"),
                    state.ticket.globalDiscountValue.stripTrailingZeros().toPlainString());
        }
        return i18n.both("paper.remiseTicket");
    }

    /**
     * Resolves an administered settlement wording or its bilingual fallback.
     * Voucher labels remain those of the registered voucher type (LC-10-01-17).
     * @param entry an actually registered payment
     * @return the label shown beside its applied amount
     */
    private String paymentLabel(com.intermarche.pos.ui.payment.PaymentState.PaymentEntry entry) {
        if (entry.voucher) return entry.method;
        // LC-07-04-07/08: the cash given back by a card cashback names itself.
        if (entry.cashback) return i18n.both("customer.cashback");
        var tender = com.intermarche.pos.domain.payment.TenderDefinition.findByCode(entry.method);
        if (tender != null && tender.label != null && !tender.label.isBlank()) return tender.label;
        String key = TENDER_LABELS.get(entry.method);
        return key == null ? entry.method : i18n.both(key);
    }

    /**
     * Builds the digital receipt path of the current draft, or an empty string.
     *
     * @return the digital receipt path, or ""
     */
    private String digitalPath() {
        if (state.payment.ticketDbId == null) return "";
        Ticket ticket = Ticket.findById(state.payment.ticketDbId);
        if (ticket == null || ticket.digitalKey == null) return "";
        return "/t/" + ticket.id + "/" + ticket.digitalKey;
    }

    /**
     * Formats an item quantity for the customer display: kilograms for
     * weighed lines, a unit count otherwise. A price-embedded sticker line
     * shows no quantity at all — the sticker fixed the price, the weight is
     * unknown at the register.
     *
     * @param item the ticket item
     * @param format the administered writing of quantities (BO-10-08-28, BO-10-08-29)
     * @return the formatted quantity, empty for price-embedded stickers
     */
    private String quantityDisplay(TicketState.TicketItem item,
            com.intermarche.pos.domain.catalog.UnitFormat format) {
        if (item.priceEmbedded) {
            return "";
        }
        if (item.plu != null && !item.plu.isEmpty()) {
            return format.weight(item.quantity);
        }
        // LC-02-03-02: an article sold by a unit of measure states it here too — the
        // customer reads "2,36 m", not "x2,36".
        if (item.unitName != null && !item.unitName.isBlank()) {
            return format.measure(item.quantity) + " " + item.unitName.trim();
        }
        return "x" + format.measure(item.quantity);
    }

    /**
     * Joins an administered message and its translation in the display's
     * second language (BO-10-07-17), the translation first as the bilingual
     * labels put it.
     *
     * @param first the message in the display's first language
     * @param second the administered translation, or null
     * @return both messages on a bilingual display, the first alone otherwise
     */
    private String bilingual(String first, String second) {
        if (second == null || i18n.getSecondLang() == null || second.equals(first)) {
            return first;
        }
        return second + " / " + first;
    }
}
