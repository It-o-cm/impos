package com.intermarche.pos.service.valuation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLineValuation;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reconciliation half of the remote valuation (phase 7 lot 2): turns the
 * engine's offer-oriented response into LINE-BORNE money.
 * <p>
 * Doctrine, in application order:
 * <ol>
 *   <li>Portions are aggregated by echoed {@code lineId} (a line may return
 *       in several portions — paid and free parts of a bundle); the
 *       tax-included amount is the only figure read, the register's own
 *       snapshot VAT rates rule the fiscal ventilation downstream.</li>
 *   <li>Discount advantages are ALLOCATED onto the lines of their offer,
 *       proportionally to each line's valued share, cent-rounded with the
 *       residual on the largest share — no money ever floats at ticket
 *       level, the lines stay the complete fiscal truth.</li>
 *   <li>The valued total is applied on the in-memory line
 *       ({@code TicketItem.valuedTotal}): every consumer of
 *       {@code getTotalPrice()} — screen totals, draft lines, VAT
 *       ventilation, printing — follows through the single override
 *       point.</li>
 *   <li>The per-line trace is persisted ({@link TicketLineValuation},
 *       rewritten wholesale) and the draft's {@code valuationStatus} is
 *       stamped.</li>
 * </ol>
 * Defensive stances: an engine line unknown to the cart is ignored with a
 * warning; an eligible cart line not covered by any offer keeps its local
 * total with a warning (coverage check); advantages whose offer cannot be
 * resolved (basket-level offers included) are skipped with a warning.
 * Advantage amounts are treated as deductions on GROSS offer amounts — the
 * assumed engine rule, logged against {@code totalPrice} for verification.
 */
@ApplicationScoped
public class ValuationReconciler {

    private static final Logger LOG = Logger.getLogger(ValuationReconciler.class);

    /** BigDecimal-safe, tolerant JSON mapper (same stance as the client). */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * Applies an engine valuation onto the cart and persists the trace.
     *
     * @param ticket the in-memory cart
     * @param ticketDbId the draft database id (trace attachment), or null
     * @param responseJson the raw engine response held by the payment state
     * @return the total adjustment (valued minus local, tax included) over
     *         the covered lines — zero when nothing applied
     */
    @Transactional
    public BigDecimal apply(TicketState ticket, Long ticketDbId, String responseJson) {
        ValuationPayloads.ValuationResponseDto response;
        try {
            response = objectMapper.readValue(responseJson, ValuationPayloads.ValuationResponseDto.class);
        } catch (Exception e) {
            LOG.warnf("Réponse de valorisation illisible (%s): totaux locaux conservés", e.getMessage());
            return BigDecimal.ZERO;
        }

        // 1. Aggregate valued portions and offer labels by lineUid
        Map<String, BigDecimal> valuedByLine = new LinkedHashMap<>();
        Map<String, String> offerLabelByLine = new HashMap<>();
        for (ValuationPayloads.OfferDto offer : response.offers) {
            String offerLabel = offer.offerId != null ? offer.offerId : offer.type;
            for (ValuationPayloads.OfferItemDto portion : offer.items) {
                if (portion.lineId == null || portion.amount == null
                        || portion.amount.amountIncludingTax == null) continue;
                valuedByLine.merge(portion.lineId, portion.amount.amountIncludingTax, BigDecimal::add);
                offerLabelByLine.merge(portion.lineId, offerLabel, (a, b) -> a.contains(b) ? a : a + "; " + b);
            }
        }

        // 2. Allocate discount advantages onto the lines of their offer
        Map<String, BigDecimal> advantageByLine = new HashMap<>();
        Map<String, String> advantageLabelByLine = new HashMap<>();
        for (ValuationPayloads.AdvantageDto advantage : response.advantages) {
            if (advantage.discountAmount == null
                    || advantage.discountAmount.amountIncludingTax == null) continue; // upsell, MEAL_VOUCHER...
            ValuationPayloads.OfferDto offer = resolveOffer(response, advantage.offer);
            if (offer == null || offer.items.isEmpty()) {
                LOG.warnf("Advantage sans offre résoluble, ignoré: %s", advantage.type);
                continue;
            }
            allocate(advantage, offer, advantageByLine, advantageLabelByLine);
        }

        // 3. Apply on the cart, cent-scaled, and build the trace
        if (ticketDbId != null) {
            TicketLineValuation.delete("ticket.id", ticketDbId);
        }
        Ticket draft = ticketDbId != null ? Ticket.findById(ticketDbId) : null;
        BigDecimal adjustment = BigDecimal.ZERO;
        for (TicketState.TicketItem item : ticket.items) {
            BigDecimal valued = valuedByLine.remove(item.uid);
            if (valued == null) {
                if (item.ean != null && !item.ean.isEmpty() && item.getTotalPrice().signum() > 0) {
                    LOG.warnf("Ligne éligible non couverte par le moteur, total local conservé: %s", item.label);
                }
                continue;
            }
            BigDecimal cut = advantageByLine.getOrDefault(item.uid, BigDecimal.ZERO);
            BigDecimal localTotal = item.getTotalPrice().setScale(2, RoundingMode.HALF_UP);
            BigDecimal valuedTotal = valued.subtract(cut).setScale(2, RoundingMode.HALF_UP);
            if (valuedTotal.signum() < 0) valuedTotal = BigDecimal.ZERO;
            item.valuedTotal = valuedTotal;
            adjustment = adjustment.add(valuedTotal.subtract(localTotal));

            if (draft != null) {
                TicketLineValuation trace = new TicketLineValuation();
                trace.ticket = draft;
                trace.lineUid = item.uid;
                trace.localTotal = localTotal;
                trace.valuedTotal = valuedTotal;
                trace.offerLabel = truncate(offerLabelByLine.get(item.uid), 200);
                trace.advantageLabel = truncate(advantageLabelByLine.get(item.uid), 200);
                trace.advantageAmount = cut.signum() > 0 ? cut.setScale(2, RoundingMode.HALF_UP) : null;
                trace.persist();
            }
        }
        valuedByLine.keySet().forEach(unknown ->
                LOG.warnf("Ligne moteur inconnue du panier, ignorée: %s", unknown));

        if (draft != null) {
            draft.valuationStatus = Ticket.ValuationStatus.VALUATED;
        }
        LOG.infof("Valorisation appliquée: ajustement total %s €", adjustment);
        return adjustment;
    }

    /**
     * Reverts an applied valuation: local totals restored, trace removed.
     *
     * @param ticket the in-memory cart
     * @param ticketDbId the draft database id, or null
     */
    @Transactional
    public void revert(TicketState ticket, Long ticketDbId) {
        for (TicketState.TicketItem item : ticket.items) {
            item.valuedTotal = null;
        }
        if (ticketDbId != null) {
            TicketLineValuation.delete("ticket.id", ticketDbId);
            Ticket draft = Ticket.findById(ticketDbId);
            if (draft != null) {
                draft.valuationStatus = Ticket.ValuationStatus.NOT_VALUATED;
            }
        }
    }

    /**
     * Stamps the draft as degraded (engine configured but unreachable).
     *
     * @param ticketDbId the draft database id, or null
     */
    @Transactional
    public void markDegraded(Long ticketDbId) {
        if (ticketDbId == null) return;
        Ticket draft = Ticket.findById(ticketDbId);
        if (draft != null) {
            draft.valuationStatus = Ticket.ValuationStatus.DEGRADED;
        }
    }

    /**
     * Resolves an advantage's offer by stable id first, type string second.
     *
     * @param response the engine response
     * @param reference the advantage's offer reference
     * @return the offer, or null when unresolvable
     */
    private ValuationPayloads.OfferDto resolveOffer(ValuationPayloads.ValuationResponseDto response,
                                                    String reference) {
        if (reference == null) return null;
        for (ValuationPayloads.OfferDto offer : response.offers) {
            if (reference.equals(offer.offerId)) return offer;
        }
        for (ValuationPayloads.OfferDto offer : response.offers) {
            if (reference.equals(offer.type)) return offer;
        }
        return null;
    }

    /**
     * Allocates a discount advantage onto the lines of its offer,
     * proportionally to each line's valued share, residual cent on the
     * largest share.
     *
     * @param advantage the discount advantage
     * @param offer the resolved offer
     * @param advantageByLine the per-line cut accumulator
     * @param advantageLabelByLine the per-line label accumulator
     */
    private void allocate(ValuationPayloads.AdvantageDto advantage,
                          ValuationPayloads.OfferDto offer,
                          Map<String, BigDecimal> advantageByLine,
                          Map<String, String> advantageLabelByLine) {
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        BigDecimal totalShare = BigDecimal.ZERO;
        for (ValuationPayloads.OfferItemDto portion : offer.items) {
            if (portion.lineId == null || portion.amount == null
                    || portion.amount.amountIncludingTax == null) continue;
            shares.merge(portion.lineId, portion.amount.amountIncludingTax, BigDecimal::add);
            totalShare = totalShare.add(portion.amount.amountIncludingTax);
        }
        if (shares.isEmpty() || totalShare.signum() == 0) return;

        BigDecimal discount = advantage.discountAmount.amountIncludingTax;
        BigDecimal allocated = BigDecimal.ZERO;
        String largestLine = null;
        BigDecimal largestShare = BigDecimal.valueOf(-1);
        for (Map.Entry<String, BigDecimal> share : shares.entrySet()) {
            BigDecimal cut = discount.multiply(share.getValue())
                    .divide(totalShare, 2, RoundingMode.HALF_UP);
            advantageByLine.merge(share.getKey(), cut, BigDecimal::add);
            advantageLabelByLine.merge(share.getKey(), advantage.type, (a, b) -> a.contains(b) ? a : a + "; " + b);
            allocated = allocated.add(cut);
            if (share.getValue().compareTo(largestShare) > 0) {
                largestShare = share.getValue();
                largestLine = share.getKey();
            }
        }
        BigDecimal residual = discount.subtract(allocated);
        if (residual.signum() != 0 && largestLine != null) {
            advantageByLine.merge(largestLine, residual, BigDecimal::add);
        }
    }

    /**
     * Truncates a label to a column width, tolerating null.
     *
     * @param value the label, or null
     * @param max the maximum length
     * @return the truncated label, or null
     */
    private String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}
