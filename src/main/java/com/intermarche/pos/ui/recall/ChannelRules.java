package com.intermarche.pos.ui.recall;

import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the administered rules of the EXTERNAL CHANNELS (BO-10-01) and
 * answers them per {@link SalesChannel}: one place where the families of keys
 * {@code channel.<channel>.*} are dispatched, so the register's recall, its
 * payment, its printing and its return all ask the same question the same way.
 *
 * <p>Every reading is tolerant: an empty or unknown word falls back on the
 * behaviour the register had before the rule existed — the global price source,
 * acceptance of the forbidden articles, retrieval by scan and by keyed code.
 */
@ApplicationScoped
public class ChannelRules {

    /** The administered parameters. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Builds the rules for CDI, which injects the parameters.
     */
    public ChannelRules() {
    }

    /**
     * Builds the rules over given parameters — for the callers that build their
     * collaborators by hand, the unit tests of the services reading them.
     *
     * @param posSettingsService the parameters to read
     */
    public ChannelRules(PosSettingsService posSettingsService) {
        this.posSettingsService = posSettingsService;
    }

    /**
     * Whether the channel is open on this point of sale (BO-10-01-01, -15): the
     * e-commerce and the self-scanning channels have an activation of their own,
     * every other channel is always open.
     *
     * @param channel the channel, possibly null
     * @return true when its baskets may be integrated
     */
    public boolean enabled(SalesChannel channel) {
        if (channel == SalesChannel.ECOMMERCE) {
            return posSettingsService.ecommerceEnabled();
        }
        if (channel == SalesChannel.SELF_SCAN) {
            return posSettingsService.selfScanEnabled();
        }
        return true;
    }

    /**
     * Whether the articles of the channel's baskets take the register's price
     * (BO-10-01-07, -19, -29, -33, -36, -41): POS says yes, TRANSACTION says no,
     * and anything else defers to the global rule of the recalled documents.
     *
     * @param channel the channel, possibly null
     * @return true when the register's current price applies
     */
    public boolean priceFromRegister(SalesChannel channel) {
        String source = word(priceSource(channel));
        if ("POS".equals(source)) {
            return true;
        }
        if ("TRANSACTION".equals(source)) {
            return false;
        }
        return posSettingsService.orderRecallPriceFromRegister();
    }

    /**
     * The administered price source of a channel.
     *
     * @param channel the channel, possibly null
     * @return the raw word, empty for a null channel
     */
    private String priceSource(SalesChannel channel) {
        if (channel == null) {
            return "";
        }
        return switch (channel) {
            case ECOMMERCE -> posSettingsService.ecommercePriceSource();
            case SELF_SCAN -> posSettingsService.selfScanPriceSource();
            case COMMERCIAL -> posSettingsService.commercialPriceSource();
            case QBUSTING -> posSettingsService.qbustingPriceSource();
            case MOBILITY -> posSettingsService.mobilityPriceSource();
            case SALES_SLIP -> posSettingsService.salesSlipPriceSource();
        };
    }

    /**
     * Whether a basket of the channel carrying an article forbidden to sale is
     * rejected whole (BO-10-01-14, -23, -24, -32, -37); a sale voucher follows
     * the commercial management tool's rule, which issues it.
     *
     * @param channel the channel, possibly null
     * @return true when the administered rule is REJECT
     */
    public boolean rejectsForbidden(SalesChannel channel) {
        if (channel == null) {
            return false;
        }
        String rule = switch (channel) {
            case ECOMMERCE -> posSettingsService.ecommerceForbiddenItems();
            case SELF_SCAN -> posSettingsService.selfScanForbiddenItems();
            case COMMERCIAL, SALES_SLIP -> posSettingsService.commercialForbiddenItems();
            case QBUSTING -> posSettingsService.qbustingForbiddenItems();
            case MOBILITY -> posSettingsService.mobilityForbiddenItems();
        };
        return "REJECT".equals(word(rule));
    }

    /**
     * The channel the promotion engine is told the basket comes from
     * (BO-10-01-13, -22, -28, -31, -34); a sale voucher follows the commercial
     * management tool's channel.
     *
     * @param channel the channel, possibly null
     * @return POINT_OF_SALE, WEB or OTHER, or null when none is administered
     */
    public String promotionChannel(SalesChannel channel) {
        if (channel == null) {
            return null;
        }
        String value = word(switch (channel) {
            case ECOMMERCE -> posSettingsService.ecommercePromotionChannel();
            case SELF_SCAN -> posSettingsService.selfScanPromotionChannel();
            case COMMERCIAL, SALES_SLIP -> posSettingsService.commercialPromotionChannel();
            case QBUSTING -> posSettingsService.qbustingPromotionChannel();
            case MOBILITY -> posSettingsService.mobilityPromotionChannel();
        });
        return value.isEmpty() ? null : value;
    }

    /**
     * The generic EAN carrying the articles of the channel the register does not
     * know (BO-10-01-06, -21).
     *
     * @param channel the channel, possibly null
     * @return the EAN, empty when the lines keep their own code
     */
    public String unknownItemEan(SalesChannel channel) {
        if (channel == SalesChannel.ECOMMERCE) {
            return text(posSettingsService.ecommerceUnknownItemEan());
        }
        if (channel == SalesChannel.SELF_SCAN) {
            return text(posSettingsService.selfScanUnknownItemEan());
        }
        return "";
    }

    /**
     * Whether a basket of the channel may be retrieved the way it was
     * (BO-10-01-16, -25, -30, -40): SCAN admits the read barcode only, KEYED the
     * code keyed on the recall screen only, anything else both.
     *
     * @param channel the channel, possibly null
     * @param keyed whether the code was keyed rather than scanned
     * @return true when the retrieval is admitted
     */
    public boolean retrievalAllowed(SalesChannel channel, boolean keyed) {
        String mode = word(retrievalMode(channel));
        if ("SCAN".equals(mode)) {
            return !keyed;
        }
        if ("KEYED".equals(mode)) {
            return keyed;
        }
        return true;
    }

    /**
     * The administered retrieval mode of a channel.
     *
     * @param channel the channel, possibly null
     * @return the raw word, empty for a channel that has none
     */
    private String retrievalMode(SalesChannel channel) {
        if (channel == SalesChannel.SELF_SCAN) {
            return posSettingsService.selfScanRetrievalMode();
        }
        if (channel == SalesChannel.COMMERCIAL) {
            return posSettingsService.commercialRetrievalMode();
        }
        if (channel == SalesChannel.QBUSTING) {
            return posSettingsService.qbustingRetrievalMode();
        }
        if (channel == SalesChannel.SALES_SLIP) {
            return posSettingsService.salesSlipRetrievalMode();
        }
        return "";
    }

    /**
     * Whether articles may be keyed BEFORE a self-scanning basket is retrieved
     * (BO-10-01-17): refused by AFTER and NONE.
     *
     * @return true when the basket may join a sale already holding articles
     */
    public boolean selfScanAddsBefore() {
        String mode = word(posSettingsService.selfScanAddItems());
        return !"AFTER".equals(mode) && !"NONE".equals(mode);
    }

    /**
     * Whether articles may be keyed AFTER a self-scanning basket is retrieved
     * (BO-10-01-17): refused by BEFORE and NONE.
     *
     * @return true when the sale stays open to additions after the basket
     */
    public boolean selfScanAddsAfter() {
        String mode = word(posSettingsService.selfScanAddItems());
        return !"BEFORE".equals(mode) && !"NONE".equals(mode);
    }

    /**
     * Whether a self-scanning basket is identified by the CUSTOMER NUMBER rather
     * than by its transaction barcode (BO-10-01-18).
     *
     * @return true when the administered identifier is CUSTOMER
     */
    public boolean selfScanByCustomer() {
        return "CUSTOMER".equals(word(posSettingsService.selfScanIdentifier()));
    }

    /**
     * Whether the coupons of a self-scanning basket are taken back at the till
     * (BO-10-01-20).
     *
     * @return true when they are
     */
    public boolean selfScanCoupons() {
        return posSettingsService.selfScanCoupons();
    }

    /**
     * The tender added for the difference to a mobility transaction paid short
     * (BO-10-01-39).
     *
     * @return the settlement key, upper-cased, empty when none is administered
     */
    public String mobilityFictitiousTender() {
        return word(posSettingsService.mobilityFictitiousTender());
    }

    /**
     * The tender the deposits on a commercial-management document are recorded
     * under (BO-10-01-27).
     *
     * @return the settlement key, upper-cased, or null for cash
     */
    public String commercialDepositTender() {
        String tender = word(posSettingsService.commercialDepositTender());
        return tender.isEmpty() ? null : tender;
    }

    /**
     * The code of the document template printed after a commercial-management
     * document is settled (BO-10-01-26).
     *
     * @return the code, empty when none is administered
     */
    public String commercialAutoPrintTemplate() {
        return text(posSettingsService.commercialAutoPrintTemplate());
    }

    /**
     * The identifier of the virtual register the e-commerce transactions are
     * booked on (BO-10-01-08).
     *
     * @return the identifier, empty when none is administered
     */
    public String ecommerceTerminal() {
        return text(posSettingsService.ecommerceTerminal());
    }

    /**
     * The return types allowed on an e-commerce ticket (BO-10-01-04, -05).
     *
     * @param wholeTicket true for the return of the whole ticket, false for the
     *        return of part of its articles
     * @return the allowed labels, empty when every return is allowed
     */
    public List<String> ecommerceReturnTypes(boolean wholeTicket) {
        String raw = text(wholeTicket ? posSettingsService.ecommerceReturnTicketTypes()
                : posSettingsService.ecommerceReturnArticleTypes());
        List<String> types = new ArrayList<>();
        for (String part : raw.split(";")) {
            String label = part.trim();
            if (!label.isEmpty()) {
                types.add(label);
            }
        }
        return types;
    }

    /**
     * Reads a free value tolerantly.
     *
     * @param value the administered value, possibly null
     * @return the trimmed value, empty for null
     */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Reads a closed-set word tolerantly.
     *
     * @param value the administered value, possibly null
     * @return the trimmed, upper-cased word, empty for null
     */
    private static String word(String value) {
        return text(value).toUpperCase(Locale.ROOT);
    }
}
