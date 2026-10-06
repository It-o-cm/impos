package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.ui.valuation.ValuationClient;
import com.intermarche.pos.ui.valuation.ValuationPayloads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-prices the basket a return LEAVES BEHIND against the external promotion
 * engine, so the generosity the sale granted on a basket that no longer exists
 * is taken back (LC-05-02-13).
 *
 * <p>WHY A RE-VALUATION AND NOT A STORED ADVANTAGE LIST. The engine's offers
 * are not frozen on the closed ticket: what a promotion did lives in the line
 * totals, never in a list the register could subtract from. So the register asks
 * the engine the only question it can answer honestly — what is the basket the
 * customer KEEPS worth today? — and compares that with what the customer
 * actually paid for those same lines. The difference is the advantage the kept
 * basket no longer deserves, and it is exact: both terms come from the same two
 * authorities (the engine for the worth, the closed ticket for the price paid).
 *
 * <p>SIGN. The difference is clamped at zero. A kept basket worth MORE than it
 * was paid means the return cost the customer an advantage, and that is what the
 * store takes back; a kept basket worth LESS would mean handing the customer a
 * new advantage on the way out, which a return is not for.
 *
 * <p>NEVER BLOCKING. The customer is standing at the till: an engine that is
 * switched off, unreachable or in error yields {@code UNAVAILABLE} and the
 * refund goes through all the same, with the paper saying the recalculation
 * could not be done. The one thing this service may not do is throw.
 *
 * <p>The loyalty half of LC-05-02-13 — the décagnottage and the coupons — is NOT
 * here: it belongs to the external loyalty service, which the refund already
 * notifies with its {@code TICKET_RETURN} event. This service answers for the
 * promotion engine alone.
 *
 * <p>Placement: ui.returnprocess — the return flow is its only consumer
 * (consumer-exclusivity rule).
 */
@ApplicationScoped
public class ReturnValuationService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ReturnValuationService.class);

    /** Store code sent when the ticket names no store, as the sale valuation does. */
    private static final String DEFAULT_STORE_CODE = "0000";

    /** How far the recalculation of the kept basket got. */
    public enum RecalculationStatus {
        /** No recalculation was called for: no engine, or nothing kept. */
        NOT_APPLICABLE,
        /** The engine answered and the advantage to withdraw is known. */
        RECOMPUTED,
        /** The engine was asked and could not answer; the refund went through. */
        UNAVAILABLE
    }

    /**
     * What one recalculation of the kept basket establishes.
     */
    public static final class Recalculation {

        /** How far the recalculation got. */
        public final RecalculationStatus status;

        /**
         * The generosity the kept basket no longer deserves, in euros, tax
         * included, never negative; ZERO on any status other than
         * {@code RECOMPUTED}.
         */
        public final BigDecimal withdrawnGenerosity;

        /**
         * The labels of the advantages the kept basket STILL earns, in the order
         * the engine listed them, without duplicates; empty on any status other
         * than {@code RECOMPUTED}.
         */
        public final List<String> keptAdvantages;

        /**
         * Builds a recalculation outcome.
         *
         * @param status how far the recalculation got
         * @param withdrawnGenerosity the generosity to take back, never null
         * @param keptAdvantages the labels of the surviving advantages, never null
         */
        public Recalculation(RecalculationStatus status, BigDecimal withdrawnGenerosity,
                             List<String> keptAdvantages) {
            this.status = status;
            this.withdrawnGenerosity = withdrawnGenerosity;
            this.keptAdvantages = keptAdvantages;
        }

        /**
         * Builds the outcome of a recalculation that did not happen.
         *
         * @param status the reason it did not happen
         * @return an outcome carrying no amount and no advantage
         */
        public static Recalculation none(RecalculationStatus status) {
            return new Recalculation(status, BigDecimal.ZERO, List.of());
        }
    }

    /** The transport toward the promotion engine, shared with the sale valuation. */
    @Inject
    ValuationClient valuationClient;

    /**
     * Re-prices what the customer keeps and says what generosity the return
     * takes back (LC-05-02-13).
     *
     * @param origin the closed ticket being returned against, or null
     * @param keptQuantities the quantity each original line KEEPS after this
     *        return and every past one, keyed by original line id; a line absent
     *        from the map or mapped to a non-positive quantity is fully returned
     * @return the recalculation outcome, never null and never throwing
     */
    public Recalculation recompute(Ticket origin, Map<Long, BigDecimal> keptQuantities) {
        LOGGER.info("Entering method recompute");
        if (origin == null || origin.lines == null || keptQuantities == null) {
            LOGGER.info("Exiting method recompute");
            return Recalculation.none(RecalculationStatus.NOT_APPLICABLE);
        }
        if (!valuationClient.isEnabled()) {
            // No remote engine administered: the sale was priced on catalog
            // prices alone, so a return takes no advantage back.
            LOGGER.info("Exiting method recompute");
            return Recalculation.none(RecalculationStatus.NOT_APPLICABLE);
        }
        ValuationPayloads.BasketDto basket = buildKeptBasket(origin, keptQuantities);
        if (basket.items.isEmpty()) {
            // The whole ticket came back: there is no kept basket to re-price,
            // and no advantage survives on a basket that no longer exists.
            LOGGER.info("Exiting method recompute");
            return Recalculation.none(RecalculationStatus.NOT_APPLICABLE);
        }
        try {
            ValuationClient.ValuationResult result = valuationClient.valuate(basket);
            ValuationPayloads.ValuationResponseDto response = result.response();
            if (response == null || response.totalPrice == null
                    || response.totalPrice.amountIncludingTax == null) {
                LOGGER.warn("Recalcul retour : le moteur n'a pas chiffré le panier conservé");
                LOGGER.info("Exiting method recompute");
                return Recalculation.none(RecalculationStatus.UNAVAILABLE);
            }
            BigDecimal paid = paidForKeptLines(origin, keptQuantities);
            BigDecimal worth = response.totalPrice.amountIncludingTax
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal withdrawn = worth.subtract(paid);
            if (withdrawn.signum() < 0) {
                withdrawn = BigDecimal.ZERO.setScale(2);
            }
            LOGGER.infof("Recalcul retour : panier conservé payé %s, valorisé %s, avantage repris %s",
                    paid, worth, withdrawn);
            LOGGER.info("Exiting method recompute");
            return new Recalculation(RecalculationStatus.RECOMPUTED, withdrawn,
                    advantageLabels(response));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Recalcul retour interrompu : remboursement sans recalcul");
            LOGGER.info("Exiting method recompute");
            return Recalculation.none(RecalculationStatus.UNAVAILABLE);
        } catch (Exception e) {
            LOGGER.warnf("Recalcul retour indisponible (%s: %s) : remboursement sans recalcul",
                    e.getClass().getSimpleName(), e.getMessage());
            LOGGER.info("Exiting method recompute");
            return Recalculation.none(RecalculationStatus.UNAVAILABLE);
        }
    }

    /**
     * Sums what the customer actually PAID for the lines they keep, rounded to
     * the centime line by line (LC-05-02-12 price, applied to the kept
     * quantities).
     *
     * @param origin the closed ticket being returned against
     * @param keptQuantities the kept quantity of each original line
     * @return the amount paid for what is kept, tax included
     */
    private BigDecimal paidForKeptLines(Ticket origin, Map<Long, BigDecimal> keptQuantities) {
        BigDecimal paid = BigDecimal.ZERO.setScale(2);
        for (TicketLine line : origin.lines) {
            BigDecimal kept = keptOf(line, keptQuantities);
            if (kept.signum() <= 0) {
                continue;
            }
            paid = paid.add(line.getEffectiveUnitPrice().multiply(kept)
                    .setScale(2, RoundingMode.HALF_UP));
        }
        return paid;
    }

    /**
     * Builds the engine request for the lines the customer keeps.
     *
     * <p>It is NOT the sale's basket builder: that one reads the in-memory cart
     * at full quantities, this one reads persisted lines at reduced ones. The
     * contract carried is the same — line id, article, quantity, price date, the
     * all-or-nothing surcharge trio of a price-embedded sticker, the short date
     * and the manual gesture — because the engine must price the kept basket
     * exactly as it priced the sale, or the comparison means nothing.
     *
     * @param origin the closed ticket being returned against
     * @param keptQuantities the kept quantity of each original line
     * @return the basket request, possibly with no item
     */
    private ValuationPayloads.BasketDto buildKeptBasket(Ticket origin,
                                                        Map<Long, BigDecimal> keptQuantities) {
        ValuationPayloads.BasketDto basket = new ValuationPayloads.BasketDto();
        basket.customerCode = origin.fidelityCard;
        basket.storeCode = origin.store != null && origin.store.code != null
                ? origin.store.code : DEFAULT_STORE_CODE;
        String isoDate = (origin.creationDate != null ? origin.creationDate
                : java.time.LocalDateTime.now()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        basket.createdAt = isoDate;
        for (TicketLine line : origin.lines) {
            BigDecimal kept = keptOf(line, keptQuantities);
            if (kept.signum() <= 0) {
                continue;
            }
            ValuationPayloads.ItemDto dto = new ValuationPayloads.ItemDto();
            dto.lineId = line.lineUid;
            dto.produceEan = line.ean;
            dto.quantity = kept;
            dto.priceDate = isoDate;
            if (line.gs1ExpiryDate != null) {
                dto.bestBeforeDate = line.gs1ExpiryDate.toString();
            }
            if (line.priceEmbedded) {
                dto.pricePerUnitInclTax = line.unitPrice;
                dto.vatRate = line.vatRate;
                dto.pricePerUnitExclTax = line.unitPrice.divide(
                        BigDecimal.ONE.add(line.vatRate), 2, RoundingMode.HALF_UP);
            }
            applyGesture(dto, line, kept);
            basket.items.add(dto);
        }
        return basket;
    }

    /**
     * The quantity a line keeps, as a number this class can reason about: a line
     * the sale cancelled or rang as a return is never part of the kept basket,
     * and neither is one the map does not name.
     *
     * @param line the original ticket line
     * @param keptQuantities the kept quantity of each original line
     * @return the kept quantity, ZERO when the line is not part of the basket
     */
    private BigDecimal keptOf(TicketLine line, Map<Long, BigDecimal> keptQuantities) {
        if (line == null || line.cancelled || line.returnLine || line.id == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal kept = keptQuantities.get(line.id);
        return kept == null ? BigDecimal.ZERO : kept;
    }

    /**
     * Maps the line's persisted manual gesture onto the request fields, as the
     * sale valuation does: a remise travels as the euros removed from the LINE
     * total, a discount as its percentage, a forced price as the new unit price.
     *
     * <p>The remise is scaled on the KEPT quantity, not the sold one. The stored
     * value is a UNIT remise; sending the euros it removed from the whole line
     * would discount a basket that no longer holds those articles, make the kept
     * basket look cheaper than it is, and understate the advantage the return has
     * to take back.
     *
     * @param dto the request line
     * @param line the persisted ticket line
     * @param keptQuantity the quantity of that line the customer keeps, strictly
     *        positive
     */
    private void applyGesture(ValuationPayloads.ItemDto dto, TicketLine line,
                              BigDecimal keptQuantity) {
        if (line.modifierType == null || line.modifierValue == null) {
            return;
        }
        switch (line.modifierType) {
            case REMISE -> dto.manualDiscountAmount = line.modifierValue.multiply(keptQuantity);
            case DISCOUNT -> dto.manualDiscountPercent = line.modifierValue;
            case FORCE_PRICE -> dto.manualForcedPrice = line.modifierValue;
            default -> {
                // Every other gesture is a ticket-level one: it has no line
                // field in the contract, and the kept basket carries it through
                // the price paid, not through the request.
            }
        }
    }

    /**
     * The labels of the advantages the kept basket still earns, in the engine's
     * own order, without duplicates and without blanks.
     *
     * @param response the engine's answer on the kept basket
     * @return the surviving advantage labels, possibly empty
     */
    private List<String> advantageLabels(ValuationPayloads.ValuationResponseDto response) {
        Set<String> labels = new LinkedHashSet<>();
        if (response.advantages != null) {
            for (ValuationPayloads.AdvantageDto advantage : response.advantages) {
                String label = advantage.type != null && !advantage.type.isBlank()
                        ? advantage.type : advantage.offer;
                if (label != null && !label.isBlank()) {
                    labels.add(label.trim());
                }
            }
        }
        return new ArrayList<>(labels);
    }
}
