package com.intermarche.pos.service.sync;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.sync.ConsumptionOutbox;
import com.intermarche.pos.ui.valuation.ValuationPayloads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives a campaign-consumption declaration from a finalized or cancelled
 * ticket and enqueues it for delivery to the engine.
 *
 * <p>It never blocks the sale: enqueuing is a single row insert in the caller's
 * transaction, and the delivery loop ({@link ConsumptionDeliveryService}) ships
 * it afterwards. The declaration is derived from the RETAINED valuation — the
 * verbatim {@code offerCode} and tax-included amounts the engine returned at
 * payment, which the per-line trace does not keep — so the capture reads the
 * engine JSON while it is still on the payment state.
 *
 * <p>Only engine advantages count: a LOCAL or DEGRADED valuation applied none,
 * so nothing is declared. The enqueue is idempotent on
 * {@code (storeCode, ticketNumber, direction)}, the same key the engine dedups
 * on.
 */
@ApplicationScoped
public class ConsumptionService {

    /** Logger for this service. */
    private static final Logger LOGGER = Logger.getLogger(ConsumptionService.class);

    /** Tolerant reader of the engine valuation response. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * One declared advantage: the offer that granted it, the amount granted in
     * absolute value, and — when the engine emitted them — the accounting key
     * and the per-article breakdown.
     *
     * <p>{@code accountingCode} and {@code lines} are omitted from the JSON when
     * null ({@link JsonInclude.Include#NON_NULL}): a declaration derived from a
     * response that carried neither is byte-for-byte the one this register
     * produced before the two fields existed, and the engine accepts it with or
     * without them.
     *
     * @param offerCode the engine offer code
     * @param amount the granted amount, tax included, absolute
     * @param accountingCode the downstream accounting key, or null (GB-01-06-05)
     * @param lines the per-article quote-parts of this advantage, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeclaredAdvantage(String offerCode, BigDecimal amount,
            String accountingCode, List<DeclaredLine> lines) {
    }

    /**
     * One article's quote-part of a declared advantage: which article, and how
     * much of the advantage fell on it, tax included, in absolute value.
     *
     * @param ean the article EAN
     * @param amount the share of the advantage on that article, absolute
     */
    public record DeclaredLine(String ean, BigDecimal amount) {
    }

    /**
     * A detached snapshot of one pending declaration, so the delivery thread
     * (which has no persistence context) can compose and POST it freely.
     *
     * @param id the row id, for the acknowledgement
     * @param ticketNumber the fiscal ticket number
     * @param storeCode the point-of-sale code
     * @param finalizedAt the ticket's closing date
     * @param direction the declaration direction
     * @param advantagesJson the advantages JSON array
     */
    public record PendingConsumption(Long id, String ticketNumber, String storeCode,
            java.time.LocalDateTime finalizedAt, ConsumptionOutbox.Direction direction,
            String advantagesJson) {
    }

    /**
     * Returns the declarations still awaiting engine acknowledgement, as
     * detached snapshots in enqueue order.
     *
     * @return the pending declarations, never null
     */
    @Transactional
    public List<PendingConsumption> pending() {
        LOGGER.info("Entering method pending");
        List<PendingConsumption> snapshots = new ArrayList<>();
        for (ConsumptionOutbox row : ConsumptionOutbox.pending()) {
            snapshots.add(new PendingConsumption(row.id, row.ticketNumber, row.storeCode,
                    row.finalizedAt, row.direction, row.advantagesJson));
        }
        LOGGER.info("Exiting method pending");
        return snapshots;
    }

    /**
     * Records the engine's acknowledgement of a declaration.
     *
     * @param id the declaration row id
     */
    @Transactional
    public void markApplied(Long id) {
        LOGGER.info("Entering method markApplied with id: " + id);
        ConsumptionOutbox row = ConsumptionOutbox.findById(id);
        if (row == null) {
            LOGGER.info("Exiting method markApplied: gone");
            return;
        }
        row.applied = true;
        row.lastError = null;
        row.persist();
        LOGGER.info("Exiting method markApplied");
    }

    /**
     * Records a failed delivery attempt on a declaration.
     *
     * @param id the declaration row id
     * @param error the failure description
     */
    @Transactional
    public void recordFailure(Long id, String error) {
        LOGGER.info("Entering method recordFailure with id: " + id);
        ConsumptionOutbox row = ConsumptionOutbox.findById(id);
        if (row == null) {
            LOGGER.info("Exiting method recordFailure: gone");
            return;
        }
        row.attempts = row.attempts + 1;
        row.lastError = error;
        row.persist();
        LOGGER.info("Exiting method recordFailure");
    }

    /**
     * Reads the engine advantages of a valuation response into the declaration
     * shape, keeping only those that name an offer and carry an amount.
     *
     * @param valuationJson the raw engine valuation JSON, possibly null
     * @return the advantages to declare, never null, empty when there are none
     */
    List<DeclaredAdvantage> parseAdvantages(String valuationJson) {
        LOGGER.info("Entering method parseAdvantages");
        List<DeclaredAdvantage> declared = new ArrayList<>();
        if (valuationJson == null || valuationJson.isBlank()) {
            LOGGER.info("Exiting method parseAdvantages: no json");
            return declared;
        }
        ValuationPayloads.ValuationResponseDto response;
        try {
            response = MAPPER.readValue(valuationJson, ValuationPayloads.ValuationResponseDto.class);
        } catch (Exception e) {
            LOGGER.warnf("Valorisation illisible pour la consommation: %s", e.getMessage());
            return declared;
        }
        if (response.advantages == null) {
            LOGGER.info("Exiting method parseAdvantages: no advantages");
            return declared;
        }
        for (ValuationPayloads.AdvantageDto advantage : response.advantages) {
            if (advantage.offerCode == null || advantage.discountAmount == null
                    || advantage.discountAmount.amountIncludingTax == null) {
                continue;
            }
            declared.add(new DeclaredAdvantage(advantage.offerCode,
                    advantage.discountAmount.amountIncludingTax.abs(),
                    advantage.accountingCode,
                    deriveLines(response, advantage)));
        }
        LOGGER.info("Exiting method parseAdvantages");
        return declared;
    }

    /**
     * Derives the per-article breakdown of an advantage from the ticket's own
     * detail: the advantage amount is split over the lines of its offer,
     * proportionally to each line's valued share, cent-rounded with the residual
     * on the largest share — the same doctrine the reconciler applies on the
     * cart, so the declared quote-parts match the article deltas the ticket
     * printed. Amounts are absolute, like the advantage total they sum back to.
     *
     * @param response the whole engine response (to resolve the offer)
     * @param advantage the advantage being declared
     * @return the per-article lines, or null when the offer has no resolvable
     *         per-line detail (the engine accepts a declaration without them)
     */
    private List<DeclaredLine> deriveLines(ValuationPayloads.ValuationResponseDto response,
            ValuationPayloads.AdvantageDto advantage) {
        ValuationPayloads.OfferDto offer = resolveOffer(response, advantage.offer);
        if (offer == null || offer.items == null || offer.items.isEmpty()) {
            return null;
        }
        Map<String, String> eanByLine = new LinkedHashMap<>();
        Map<String, BigDecimal> shareByLine = new LinkedHashMap<>();
        BigDecimal totalShare = BigDecimal.ZERO;
        for (ValuationPayloads.OfferItemDto item : offer.items) {
            if (item.lineId == null || item.amount == null
                    || item.amount.amountIncludingTax == null) {
                continue;
            }
            eanByLine.putIfAbsent(item.lineId, item.produceEan);
            shareByLine.merge(item.lineId, item.amount.amountIncludingTax, BigDecimal::add);
            totalShare = totalShare.add(item.amount.amountIncludingTax);
        }
        if (shareByLine.isEmpty() || totalShare.signum() == 0) {
            return null;
        }
        BigDecimal advantageAmount = advantage.discountAmount.amountIncludingTax.abs();
        BigDecimal totalShareAbs = totalShare.abs();
        List<DeclaredLine> lines = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        int largestIndex = 0;
        BigDecimal largestShare = BigDecimal.valueOf(-1);
        int index = 0;
        for (Map.Entry<String, BigDecimal> share : shareByLine.entrySet()) {
            BigDecimal part = advantageAmount.multiply(share.getValue().abs())
                    .divide(totalShareAbs, 2, RoundingMode.HALF_UP);
            lines.add(new DeclaredLine(eanByLine.get(share.getKey()), part));
            allocated = allocated.add(part);
            if (share.getValue().abs().compareTo(largestShare) > 0) {
                largestShare = share.getValue().abs();
                largestIndex = index;
            }
            index++;
        }
        // The rounding residual lands on the largest share so the per-line
        // quote-parts always sum back to the declared advantage amount.
        BigDecimal residual = advantageAmount.subtract(allocated);
        if (residual.signum() != 0) {
            DeclaredLine largest = lines.get(largestIndex);
            lines.set(largestIndex, new DeclaredLine(largest.ean(),
                    largest.amount().add(residual)));
        }
        return lines;
    }

    /**
     * Resolves an advantage's offer by stable id first, type string second — the
     * same join the reconciler uses to place the advantage on the cart.
     *
     * @param response the engine response
     * @param reference the advantage's offer reference
     * @return the offer, or null when unresolvable
     */
    private ValuationPayloads.OfferDto resolveOffer(
            ValuationPayloads.ValuationResponseDto response, String reference) {
        if (reference == null || response.offers == null) {
            return null;
        }
        for (ValuationPayloads.OfferDto offer : response.offers) {
            if (reference.equals(offer.offerId)) {
                return offer;
            }
        }
        for (ValuationPayloads.OfferDto offer : response.offers) {
            if (reference.equals(offer.type)) {
                return offer;
            }
        }
        return null;
    }

    /**
     * Serializes the declared advantages to the JSON array the engine reads.
     *
     * @param advantages the advantages to declare
     * @return the JSON array, or null when serialization fails
     */
    private String toJson(List<DeclaredAdvantage> advantages) {
        try {
            return MAPPER.writeValueAsString(advantages);
        } catch (Exception e) {
            LOGGER.warnf("Sérialisation de la consommation impossible: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Enqueues the SALE consumption of a finalized ticket, when it carries
     * engine advantages and has not already been enqueued.
     *
     * @param ticketNumber the fiscal ticket number
     * @param storeCode the point-of-sale code
     * @param finalizedAt the ticket's closing date
     * @param valuationJson the retained engine valuation JSON
     */
    @Transactional
    public void enqueueSale(String ticketNumber, String storeCode,
            java.time.LocalDateTime finalizedAt, String valuationJson) {
        LOGGER.info("Entering method enqueueSale with ticketNumber: " + ticketNumber);
        List<DeclaredAdvantage> advantages = parseAdvantages(valuationJson);
        if (advantages.isEmpty()) {
            LOGGER.info("Exiting method enqueueSale: nothing to declare");
            return;
        }
        if (ConsumptionOutbox.findByKey(storeCode, ticketNumber,
                ConsumptionOutbox.Direction.SALE) != null) {
            LOGGER.info("Exiting method enqueueSale: already enqueued");
            return;
        }
        ConsumptionOutbox row = new ConsumptionOutbox();
        row.ticketNumber = ticketNumber;
        row.storeCode = storeCode;
        row.finalizedAt = finalizedAt;
        row.direction = ConsumptionOutbox.Direction.SALE;
        row.advantagesJson = toJson(advantages);
        row.persist();
        LOGGER.info("Exiting method enqueueSale: enqueued");
    }

    /**
     * Enqueues the symmetric CANCELLATION of a ticket, but only when a SALE was
     * previously declared for it — an abandoned draft that never finalized has
     * no engine consumption to reverse. Idempotent on the cancellation key.
     *
     * @param ticketNumber the fiscal ticket number
     * @param storeCode the point-of-sale code
     * @param cancelledAt when the cancellation happened
     */
    @Transactional
    public void enqueueCancellation(String ticketNumber, String storeCode,
            java.time.LocalDateTime cancelledAt) {
        LOGGER.info("Entering method enqueueCancellation with ticketNumber: " + ticketNumber);
        ConsumptionOutbox sale = ConsumptionOutbox.findByKey(storeCode, ticketNumber,
                ConsumptionOutbox.Direction.SALE);
        if (sale == null) {
            LOGGER.info("Exiting method enqueueCancellation: no sale to reverse");
            return;
        }
        if (ConsumptionOutbox.findByKey(storeCode, ticketNumber,
                ConsumptionOutbox.Direction.CANCELLATION) != null) {
            LOGGER.info("Exiting method enqueueCancellation: already enqueued");
            return;
        }
        ConsumptionOutbox row = new ConsumptionOutbox();
        row.ticketNumber = ticketNumber;
        row.storeCode = storeCode;
        row.finalizedAt = cancelledAt;
        row.direction = ConsumptionOutbox.Direction.CANCELLATION;
        row.advantagesJson = sale.advantagesJson;
        row.persist();
        LOGGER.info("Exiting method enqueueCancellation: enqueued");
    }
}
