package com.intermarche.pos.ui.resource;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.valuation.ValuationPayloads;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Embedded VALUATION-ENGINE SIMULATOR for the autonomous e2e runs — the
 * pricing sibling of {@link MockHardwareResource}: the register under test
 * must never depend on an externally running imvaluation, so
 * {@code E2eTestProfile} points {@code pos.valuation.url} at the test
 * application itself ({@code http://localhost:8081/api}) and this endpoint
 * answers the client's {@code POST {url}/valuation}.
 * <p>
 * The simulation is deliberately NEUTRAL: no offers, no discounts, no
 * engine total — the reconciler keeps the catalog prices untouched, so
 * every scenario that ignores the engine behaves exactly as in degraded
 * mode. The single simulated DECISION is the restaurant-voucher base (the
 * one engine verdict an e2e scenario asserts): items whose EAN is in the
 * decision table are summed at their catalog price and granted as a
 * {@code MEAL_VOUCHER} advantage with the demo threshold.
 */
@Path("/api/valuation")
public class MockValuationResource {

    /** EANs the simulated engine grants a restaurant-voucher base for. */
    private static final Set<String> MEAL_ELIGIBLE_EANS = Set.of("3300000000001");

    /** Cap of the simulated meal-voucher offer (mirrors the demo offer). */
    private static final BigDecimal MEAL_THRESHOLD = new BigDecimal("25.00");

    /**
     * Values a basket: neutral response, plus the simulated meal-voucher
     * advantage when the basket holds eligible items.
     *
     * @param basket the basket posted by the register's valuation client
     * @return the simulated valuation response
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ValuationPayloads.ValuationResponseDto valuate(ValuationPayloads.BasketDto basket) {
        ValuationPayloads.ValuationResponseDto response = new ValuationPayloads.ValuationResponseDto();
        BigDecimal eligible = BigDecimal.ZERO;
        for (ValuationPayloads.ItemDto item : basket.items) {
            if (item.produceEan == null || !MEAL_ELIGIBLE_EANS.contains(item.produceEan)) {
                continue;
            }
            BigDecimal unit = item.pricePerUnitInclTax != null
                    ? item.pricePerUnitInclTax : catalogPriceInclTax(item.produceEan);
            BigDecimal quantity = item.quantity != null ? item.quantity : BigDecimal.ONE;
            eligible = eligible.add(unit.multiply(quantity));
        }
        if (eligible.signum() > 0) {
            ValuationPayloads.AdvantageDto advantage = new ValuationPayloads.AdvantageDto();
            advantage.type = "MEAL_VOUCHER";
            advantage.offerCode = "MEAL_VOUCHER_SIM";
            advantage.totalEligibleAmount = eligible.setScale(2, RoundingMode.HALF_UP);
            advantage.threshold = MEAL_THRESHOLD;
            response.advantages.add(advantage);
        }
        return response;
    }

    /**
     * Reads the register's own catalog price for an EAN — the simulator
     * lives inside the test application, so the catalog IS the test seed.
     *
     * @param ean the product EAN
     * @return the current tax-included price, or ZERO when unknown
     */
    private BigDecimal catalogPriceInclTax(String ean) {
        Product product = Product.find("ean = ?1 and active = true", ean).firstResult();
        if (product == null) {
            return BigDecimal.ZERO;
        }
        Price price = Price.findCurrentPrice(product.id);
        return price != null ? price.priceIncludingTax : BigDecimal.ZERO;
    }
}
