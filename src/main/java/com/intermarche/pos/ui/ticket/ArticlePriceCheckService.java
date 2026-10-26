package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.balance.TareService;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.scanner.WeightedEanScanHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Read-only price checks for decimal quantities, labels and live weighing (LC-02-14-01/-17/-18). */
@ApplicationScoped
public class ArticlePriceCheckService {
    /** Existing label layout and prefix configuration. */
    @Inject WeightedEanScanHandler labels;
    /** Existing scale protocol; its returned weight is already net. */
    @Inject HardwareService hardware;
    /** Product/default tare, deliberately independent of a pending sale container. */
    @Inject TareService tares;

    /** A consultation result, with no writable sale state.
     * @param quantity the displayed quantity or net kilograms
     * @param total the displayed tax-inclusive total
     * @param weighted whether units are kilograms
     * @param editable whether quantity entry and a fresh weighing are available
     * @param errorKey an operator message key, or null on success
     */
    public record Check(String quantity, String total, boolean weighted, boolean editable, String errorKey) { }

    /** Reads a label without invoking sale admission or consuming its duplicate guard.
     * @param code the nonblank scanned code
     * @return a decoded label, or null for a plain identity or invalid label
     */
    public WeightedEanScanHandler.PriceCheckLabel decode(String code) {
        return labels.priceCheckLabel(code);
    }

    /** Computes the price of a quantity, a label or a fresh scale reading.
     * No operator quantity, container tare, recorded weight or ticket is consumed.
     * @param product the active article
     * @param price its current price, or null
     * @param typedQuantity an optional decimal quantity or net weight
     * @param label the decoded label, or null for a plain lookup
     * @return the display result including any validation failure
     */
    public Check calculate(Product product, Price price, String typedQuantity,
            WeightedEanScanHandler.PriceCheckLabel label) {
        boolean weighted = product.variableWeight || label != null;
        BigDecimal unit = price == null ? null : price.priceIncludingTax;
        boolean priced = unit != null && unit.signum() >= 0;
        BigDecimal quantity;
        BigDecimal total = null;
        if (label != null) {
            if (label.total() != null) {
                total = label.total();
                quantity = priced && unit.signum() > 0 ? total.divide(unit, 3, RoundingMode.HALF_UP) : null;
                if (total.signum() <= 0) return invalid(weighted, false, "articleinfo.invalidQuantity");
            } else {
                quantity = label.weight();
                if (quantity.signum() <= 0) return invalid(weighted, false, "articleinfo.invalidQuantity");
            }
        } else if (typedQuantity != null && !typedQuantity.isBlank()) {
            try {
                quantity = new BigDecimal(typedQuantity.trim().replace(',', '.'));
                if (quantity.signum() <= 0) return invalid(weighted, true, "articleinfo.invalidQuantity");
            } catch (NumberFormatException failure) {
                return invalid(weighted, true, "articleinfo.invalidQuantity");
            }
        } else if (product.variableWeight) {
            double net = hardware.awaitWeighing(tares.resolve(product, null));
            if (!Double.isFinite(net) || net <= 0) return invalid(true, true, "op.poidsInvalide");
            quantity = BigDecimal.valueOf(net).setScale(3, RoundingMode.HALF_UP);
            if (quantity.signum() <= 0) return invalid(true, true, "op.poidsInvalide");
        } else {
            quantity = BigDecimal.ONE;
        }
        if (total == null && priced) total = unit.multiply(quantity);
        String error = priced ? null : "articleinfo.unpriced";
        return new Check(number(quantity), money(total), weighted, label == null, error);
    }

    /** Keeps invalid input visible without inventing a price.
     * @param weighted whether kilograms are displayed
     * @param editable whether entry is available
     * @param key the refusal message
     * @return an empty result with the refusal
     */
    private Check invalid(boolean weighted, boolean editable, String key) {
        return new Check("—", "—", weighted, editable, key);
    }

    /** Formats a quantity without dropping significant decimals.
     * @param value the quantity, or null when it cannot be derived
     * @return French display text
     */
    private String number(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Rounds only the final total to the currency precision.
     * @param value the total, or null when no current price exists
     * @return French display text
     */
    private String money(BigDecimal value) {
        return value == null ? "—" : value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
