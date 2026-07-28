package com.intermarche.pos.service.valuation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Payloads of the remote valuation engine (phase 7 lot 1).
 * <p>
 * Request side: natural identities only — {@code lineId} carries the line's
 * {@code lineUid} verbatim (the engine echoes it back, closing the identity
 * loop), manual gestures travel AS DATA ({@code manualDiscountAmount} in
 * euros off the LINE total, {@code manualDiscountPercent},
 * {@code manualForcedPrice} as the effective UNIT price tax included), and
 * the surcharge trio remains the vehicle of price-embedded scale labels.
 * <p>
 * Response side: TOLERANT READER — every class ignores unknown fields, every
 * field may be absent, and amounts are read as {@link BigDecimal}
 * (configured on the client's mapper). Known engine quirks absorbed here by
 * doctrine rather than fields: a line may come back in SEVERAL portions
 * (aggregate by lineId), the per-item {@code vatRate} may be a blended
 * decoration (the register derives tax-excluded amounts from its own
 * snapshot rates), and advantages join offers by the {@code offer} type
 * string until a stable {@code offerId} exists.
 */
public final class ValuationPayloads {

    /**
     * Non-instantiable payload container.
     */
    private ValuationPayloads() {}

    // --------------------------------------------------
    // Request
    // --------------------------------------------------

    /**
     * The basket submitted for valuation.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BasketDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The fidelity card of the ticket, or null. */
        public String customerCode;
        /** The store code of this node. */
        public String storeCode;
        /** The ticket creation timestamp, ISO-8601. */
        public String createdAt;
        /** Always IN_STORE for a register sale. */
        public String deliveryMode = "IN_STORE";
        /** The eligible lines of the cart. */
        public List<ItemDto> items = new ArrayList<>();
    }

    /**
     * One basket line.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The line's lineUid, echoed back by the engine. */
        public String lineId;
        /** The product EAN. */
        public String produceEan;
        /** Units for a UNIT product, kilograms otherwise. */
        public BigDecimal quantity;
        /** Surcharge trio: unit price excluding tax (price-embedded labels). */
        public BigDecimal pricePerUnitExclTax;
        /** Surcharge trio: unit price including tax (price-embedded labels). */
        public BigDecimal pricePerUnitInclTax;
        /** Surcharge trio: VAT rate (price-embedded labels). */
        public BigDecimal vatRate;
        /** Manual gesture: euros off the LINE total (REMISE). */
        public BigDecimal manualDiscountAmount;
        /** Manual gesture: percentage reduction (DISCOUNT). */
        public BigDecimal manualDiscountPercent;
        /** Manual gesture: effective unit price tax included (FORCE_PRICE). */
        public BigDecimal manualForcedPrice;
        /** Price lookup date, ISO-8601 (the register sends the ticket creation date). */
        public String priceDate;
    }

    // --------------------------------------------------
    // Response (tolerant reader)
    // --------------------------------------------------

    /**
     * An amount triple of the engine.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AmountDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The amount excluding tax. */
        public BigDecimal amountExcludingTax;
        /** The amount including tax — the authoritative figure for the register. */
        public BigDecimal amountIncludingTax;
        /** The rate — possibly blended on bundles, decorative for the register. */
        public BigDecimal vatRate;
    }

    /**
     * One valued portion of a request line inside an offer.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OfferItemDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The echoed lineUid of the originating line. */
        public String lineId;
        /** The product EAN. */
        public String produceEan;
        /** The portion quantity. */
        public BigDecimal quantity;
        /** The valued amounts of the portion. */
        public AmountDto amount;
    }

    /**
     * One offer of the valuation.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OfferDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The stable offer identifier, when the engine emits one. */
        public String offerId;
        /** The human-readable offer label (also the advantages' join key today). */
        public String type;
        /** The valued amounts of the whole offer. */
        public AmountDto amount;
        /** The valued portions, empty for basket-level offers (delivery, deposit). */
        public List<OfferItemDto> items = new ArrayList<>();
    }

    /**
     * An upsell suggestion carried by an advantage.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SuggestionDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The EAN to add. */
        public String ean;
        /** The quantity to add. */
        public BigDecimal quantity;
        /** The offer the addition would complete. */
        public String offerCode;
    }

    /**
     * One advantage of the valuation (heterogeneous by type).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdvantageDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The advantage label/type. */
        public String type;
        /** The joined offer: its offerId when emitted, its type string today. */
        public String offer;
        /** The offer code (MEAL_VOUCHER and upsells). */
        public String offerCode;
        /** The discount amounts, on discount advantages. */
        public AmountDto discountAmount;
        /** The upsell suggestion, on upsell advantages. */
        public SuggestionDto suggestion;
        /** The meal-voucher eligible base, on MEAL_VOUCHER. */
        public BigDecimal totalEligibleAmount;
        /** The meal-voucher threshold, on MEAL_VOUCHER. */
        public BigDecimal threshold;
    }

    /**
     * The whole valuation response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValuationResponseDto implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The offers of the valuation. */
        public List<OfferDto> offers = new ArrayList<>();
        /** The advantages of the valuation. */
        public List<AdvantageDto> advantages = new ArrayList<>();
        /** The engine's own total — compared in logs, never charged as-is (lot 1). */
        public AmountDto totalPrice;
    }
}
