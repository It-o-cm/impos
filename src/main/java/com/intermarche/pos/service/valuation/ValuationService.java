package com.intermarche.pos.service.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the remote valuation of a cart (phase 7 lot 1): builds the
 * basket request from the in-memory ticket, calls the engine, and degrades
 * gracefully — a failure NEVER blocks the sale, the register falls back to
 * its local catalog totals with a visible banner.
 * <p>
 * Line eligibility, by construction of the engine contract
 * ({@code produceEan} required): only EAN-bearing positive lines travel.
 * Excluded and locally valued as today: unknown items, deposit returns,
 * the solidarity round-up (no EAN), and weight-embedded 2x lines carrying
 * only a PLU. Price-embedded 2x labels would need the surcharge trio and a
 * product EAN resolution — lot 2 refinement, excluded for now.
 * <p>
 * Manual gestures travel AS DATA on their line: REMISE →
 * {@code manualDiscountAmount} (euros off the line total), DISCOUNT →
 * {@code manualDiscountPercent}, FORCE_PRICE → {@code manualForcedPrice}
 * (the effective unit price, i.e. the forced line total divided by the
 * quantity — already what the cart holds as unit price). The engine
 * re-applies the gesture on the SAME base price the register resolved,
 * a coherence guaranteed by the centralized referentials plus
 * {@code priceDate}.
 */
@ApplicationScoped
public class ValuationService {

    private static final Logger LOG = Logger.getLogger(ValuationService.class);

    @Inject
    ValuationClient valuationClient;

    @Inject
    ValuationReconciler valuationReconciler;

    @Inject
    TicketPersistenceService ticketPersistenceService;

    @Inject
    ObjectMapper objectMapper;

    /** Seconds during which the engine is skipped after a failure (circuit breaker). */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "pos.valuation.retry-seconds", defaultValue = "10")
    long retrySeconds;

    /** Epoch millis until which engine calls are skipped (0 = circuit closed). */
    private volatile long engineSkipUntil = 0;

    /**
     * Announces the active valuation engine at startup, so a glance at the
     * boot log settles which engine this register will call.
     *
     * @param ev the startup event
     */
    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        if (valuationClient.isEnabled()) {
            LOG.infof("Moteur de valorisation DISTANT actif: %s (repli local en dégradé, disjoncteur %ds)",
                    valuationClient.targetUrl(), retrySeconds);
        } else {
            LOG.info("Moteur de valorisation: LOCAL seul (pos.valuation.url absent)");
        }
    }

    /**
     * Outcome of a valuation attempt.
     */
    public static class ValuationOutcome {
        /** Status: LOCAL (engine not configured), ENGINE (valued), DEGRADED (engine failed). */
        public final String status;
        /** The raw response JSON when valued (reparsed by lot 2), or null. */
        public final String responseJson;
        /** The engine's total including tax when valued (log comparison), or null. */
        public final BigDecimal engineTotalInclTax;

        /**
         * Creates an outcome.
         *
         * @param status the outcome status
         * @param responseJson the raw response JSON, or null
         * @param engineTotalInclTax the engine total, or null
         */
        public ValuationOutcome(String status, String responseJson, BigDecimal engineTotalInclTax) {
            this.status = status;
            this.responseJson = responseJson;
            this.engineTotalInclTax = engineTotalInclTax;
        }
    }

    /**
     * Actionable hints extracted from an engine response (phase 7 lot 3):
     * the meal-voucher base and the upsell suggestions.
     */
    public static class EngineHints {
        /** The meal-voucher eligible base (tax included), or null. */
        public BigDecimal mealEligible;
        /** The meal-voucher threshold (legal cap), or null. */
        public BigDecimal mealThreshold;
        /** Human-readable upsell suggestions, product labels resolved locally. */
        public List<String> upsells = new ArrayList<>();
    }

    /**
     * Extracts the actionable hints of a valuation response: the
     * MEAL_VOUCHER advantage (eligible base and threshold, feeding the
     * meal-ticket cap) and the upsell suggestions, with the product label
     * resolved from the local catalog so the cashier reads a name, not an
     * EAN.
     *
     * @param responseJson the raw engine response
     * @return the hints, empty-but-non-null on any parsing trouble
     */
    public EngineHints extractHints(String responseJson) {
        EngineHints hints = new EngineHints();
        if (responseJson == null) return hints;
        try {
            ValuationPayloads.ValuationResponseDto response =
                    objectMapper.readValue(responseJson, ValuationPayloads.ValuationResponseDto.class);
            for (ValuationPayloads.AdvantageDto advantage : response.advantages) {
                if ("MEAL_VOUCHER".equals(advantage.type)) {
                    hints.mealEligible = advantage.totalEligibleAmount;
                    hints.mealThreshold = advantage.threshold;
                } else if (advantage.suggestion != null && advantage.suggestion.ean != null) {
                    Product product = Product.find("ean", advantage.suggestion.ean).firstResult();
                    String label = product != null ? product.name : advantage.suggestion.ean;
                    String qty = advantage.suggestion.quantity != null
                            ? advantage.suggestion.quantity.stripTrailingZeros().toPlainString() : "1";
                    String offer = advantage.suggestion.offerCode != null
                            ? " (" + advantage.suggestion.offerCode + ")" : "";
                    hints.upsells.add("+" + qty + " x " + label + offer);
                }
            }
        } catch (Exception e) {
            LOG.warnf("Hints de valorisation illisibles (%s)", e.getMessage());
        }
        return hints;
    }

    /**
     * Tells whether the remote engine is configured on this register.
     *
     * @return true when remote valuation is active
     */
    public boolean isEnabled() {
        return valuationClient.isEnabled();
    }

    /**
     * CONTINUOUS VALUATION HOOK (phase 7 lot 4): revalues the cart after a
     * cart mutation. The engine runs on the same machine and serves only
     * this register, so the call is SYNCHRONOUS inside the scan gesture —
     * no races, no flicker, the versioned poll repaints a settled total.
     * Skipped in training, during an active payment (the payment entry has
     * its own final revaluation) and while the circuit breaker is open: a
     * hung engine must not charge its timeout to every scan, so a failure
     * opens the circuit for {@code pos.valuation.retry-seconds} and the
     * register runs on local totals until the next retry window.
     *
     * @param state the current POS state
     */
    public void revalue(PosState state) {
        if (state.trainingMode || state.payment.paymentInProgress) return;
        doRevalue(state);
    }

    /**
     * Final revaluation at payment entry — same machinery, the
     * payment-in-progress guard lifted: the figure fixed here is the one
     * the customer pays.
     *
     * @param state the current POS state
     */
    public void revalueForPayment(PosState state) {
        if (state.trainingMode) return;
        doRevalue(state);
    }

    /**
     * Revalues the cart: calls the engine (circuit permitting), applies or
     * reverts the line-borne valuation, recomputes the totals, persists the
     * draft and refreshes the payment-state valuation fields and hints.
     *
     * @param state the current POS state
     */
    private void doRevalue(PosState state) {
        if (!isEnabled()) return;
        Long ticketDbId = state.payment.ticketDbId;

        if (System.currentTimeMillis() < engineSkipUntil) {
            degradeInMemory(state, ticketDbId);
            return;
        }

        ValuationOutcome outcome = valuate(state.ticket,
                state.fidelity.active ? state.fidelity.label : null,
                java.time.LocalDateTime.now());
        state.payment.valuationStatus = outcome.status;
        state.payment.valuationJson = outcome.responseJson;
        state.payment.valuationEngineTotal = outcome.engineTotalInclTax;

        switch (outcome.status) {
            case "ENGINE" -> {
                state.payment.valuationAdjustment =
                        valuationReconciler.apply(state.ticket, ticketDbId, outcome.responseJson);
                state.ticket.recomputeTotal();
                ticketPersistenceService.syncDraft(state);
                EngineHints hints = extractHints(outcome.responseJson);
                state.payment.valuationMealEligible = hints.mealEligible;
                state.payment.valuationMealThreshold = hints.mealThreshold;
                state.payment.valuationUpsells = new java.util.ArrayList<>(hints.upsells);
                if (outcome.engineTotalInclTax != null) {
                    LOG.debugf("Revalorisation: caisse=%s moteur=%s",
                            state.ticket.totalAmount, outcome.engineTotalInclTax);
                }
            }
            case "DEGRADED" -> {
                engineSkipUntil = System.currentTimeMillis() + retrySeconds * 1000L;
                degradeInMemory(state, ticketDbId);
                valuationReconciler.markDegraded(ticketDbId);
            }
            default -> { // LOCAL: no eligible line — make sure nothing stale survives
                revertInMemory(state, ticketDbId);
            }
        }
        state.touch();
    }

    /**
     * Reverts any applied valuation and stamps the in-memory state degraded.
     *
     * @param state the current POS state
     * @param ticketDbId the draft database id, or null
     */
    private void degradeInMemory(PosState state, Long ticketDbId) {
        revertInMemory(state, ticketDbId);
        state.payment.valuationStatus = "DEGRADED";
    }

    /**
     * Reverts any applied valuation: local totals restored, hints cleared.
     *
     * @param state the current POS state
     * @param ticketDbId the draft database id, or null
     */
    private void revertInMemory(PosState state, Long ticketDbId) {
        boolean hadValuation = state.payment.valuationAdjustment != null;
        if (hadValuation) {
            valuationReconciler.revert(state.ticket, ticketDbId);
            state.ticket.recomputeTotal();
            ticketPersistenceService.syncDraft(state);
        }
        state.payment.valuationAdjustment = null;
        state.payment.valuationMealEligible = null;
        state.payment.valuationMealThreshold = null;
        state.payment.valuationUpsells = new java.util.ArrayList<>();
    }

    /**
     * Values the cart against the remote engine, degrading on any failure.
     *
     * @param ticket the in-memory cart
     * @param fidelityCard the attached fidelity card, or null
     * @param creationDate the ticket creation date (price lookup alignment)
     * @return the outcome: LOCAL, ENGINE (with the raw response) or DEGRADED
     */
    public ValuationOutcome valuate(TicketState ticket, String fidelityCard, LocalDateTime creationDate) {
        if (!isEnabled()) {
            return new ValuationOutcome("LOCAL", null, null);
        }
        try {
            ValuationPayloads.BasketDto basket = buildBasket(ticket, fidelityCard, creationDate);
            if (basket.items.isEmpty()) {
                // Nothing eligible (pure deposit/unknown cart): local math only
                LOG.debugf("Valorisation: aucune ligne éligible (%d ligne(s) au panier), moteur non appelé",
                        ticket.items.size());
                return new ValuationOutcome("LOCAL", null, null);
            }
            ValuationPayloads.ValuationResponseDto response = valuationClient.valuate(basket);
            BigDecimal engineTotal = response.totalPrice != null ? response.totalPrice.amountIncludingTax : null;
            String json = objectMapper.writeValueAsString(response);
            LOG.infof("Valorisation moteur: %d offre(s), %d advantage(s), total moteur=%s",
                    response.offers.size(), response.advantages.size(), engineTotal);
            return new ValuationOutcome("ENGINE", json, engineTotal);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Valorisation interrompue: mode dégradé prix catalogue");
            return new ValuationOutcome("DEGRADED", null, null);
        } catch (Exception e) {
            LOG.warnf("Valorisation indisponible (%s): mode dégradé prix catalogue", e.getMessage());
            return new ValuationOutcome("DEGRADED", null, null);
        }
    }

    /**
     * Builds the basket request from the eligible cart lines.
     *
     * @param ticket the in-memory cart
     * @param fidelityCard the attached fidelity card, or null
     * @param creationDate the ticket creation date
     * @return the basket request
     */
    private ValuationPayloads.BasketDto buildBasket(TicketState ticket, String fidelityCard,
                                                    LocalDateTime creationDate) {
        ValuationPayloads.BasketDto basket = new ValuationPayloads.BasketDto();
        basket.customerCode = (fidelityCard != null && !fidelityCard.isBlank()) ? fidelityCard : null;
        Store store = Store.findAll().firstResult();
        basket.storeCode = store != null ? store.code : "0000";
        String isoDate = (creationDate != null ? creationDate : LocalDateTime.now())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        basket.createdAt = isoDate;

        for (TicketState.TicketItem item : ticket.items) {
            if (item.ean == null || item.ean.isEmpty()) continue;          // no EAN: out of the engine
            if (item.getTotalPrice().signum() <= 0) continue;              // deposits/negatives: local
            ValuationPayloads.ItemDto dto = new ValuationPayloads.ItemDto();
            dto.lineId = item.uid;
            dto.produceEan = item.ean;
            dto.quantity = item.quantity;
            dto.priceDate = isoDate;
            applyGesture(dto, item);
            basket.items.add(dto);
        }
        return basket;
    }

    /**
     * Maps the line's structured manual gesture onto the request fields.
     *
     * @param dto the request line
     * @param item the cart line
     */
    private void applyGesture(ValuationPayloads.ItemDto dto, TicketState.TicketItem item) {
        if (item.modifierType == null || item.modifierValue == null) return;
        switch (item.modifierType) {
            case "REMISE" -> dto.manualDiscountAmount = item.modifierValue;
            case "DISCOUNT" -> dto.manualDiscountPercent = item.modifierValue;
            case "FORCE_PRICE" -> dto.manualForcedPrice =
                    item.quantity.signum() != 0
                            ? item.modifierValue.divide(item.quantity, 2, RoundingMode.HALF_UP)
                            : item.modifierValue;
            default -> LOG.warnf("Geste inconnu ignoré sur la ligne %s: %s", item.uid, item.modifierType);
        }
    }
}
