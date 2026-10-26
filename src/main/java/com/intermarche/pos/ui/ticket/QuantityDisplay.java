package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.UnitFormat;
import com.intermarche.pos.service.PosSettingsService;
import io.quarkus.arc.Unremovable;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Writes the quantities of the register's SCREENS with the administered
 * decimals and weight symbol (BO-10-08-28, BO-10-08-29), reached from the
 * templates as {@code {inject:units.html(item)}} like the labels are reached
 * through {@code inject:i18n}.
 *
 * <p>The parameters are read at every call: a value changed in the back
 * office shows on the next poll of the screen, with no cache to forget.
 */
@Named("units")
@ApplicationScoped
@TemplateData
@Unremovable
public class QuantityDisplay {

    /** The administered decimals and weight symbol. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Builds the bean the container injects.
     */
    public QuantityDisplay() {
    }

    /**
     * Builds the bean over given parameters, for a template rendered outside
     * the container.
     *
     * @param posSettingsService the administered parameters
     */
    public QuantityDisplay(PosSettingsService posSettingsService) {
        this.posSettingsService = posSettingsService;
    }

    /**
     * The writing of quantities the back office administers.
     *
     * @return the current writing, never null
     */
    public UnitFormat format() {
        return UnitFormat.orDefault(posSettingsService.unitFormat());
    }

    /**
     * The HTML fragment of a sale screen line, its quantity written as
     * administered.
     *
     * @param item the ticket line
     * @return the HTML fragment
     */
    public String html(TicketState.TicketItem item) {
        return item.getHtml(format());
    }

    /**
     * The decomposition line of a sale screen line, written as administered.
     *
     * @param item the ticket line
     * @return the decomposition line, possibly empty
     */
    public String detail(TicketState.TicketItem item) {
        return item.getDetailLine(format());
    }

    /**
     * The quantity of a recorded sale line on the reprint screens and the
     * digital receipt: a weighed line's weight written as administered, any
     * other quantity as the line writes it.
     *
     * @param line the recorded line
     * @return the written quantity, empty when the line carries none
     */
    public String line(com.intermarche.pos.domain.sale.TicketLine line) {
        if (line.quantity == null) {
            return "";
        }
        boolean weighed = line.product != null && line.product.plu != null && !line.product.plu.isEmpty();
        return weighed ? format().weight(line.quantity) : line.getFormattedQuantity();
    }

    /**
     * A weight written as administered, with its symbol.
     *
     * @param kilograms the weight, possibly null
     * @return the written weight, an empty string for null
     */
    public String weight(BigDecimal kilograms) {
        return kilograms == null ? "" : format().weight(kilograms);
    }

    /**
     * An amount written the register's way: two decimals, half-up, a comma —
     * the decimal separator of every register language — and no grouping,
     * {@code 2,76}.
     *
     * <p>Takes any object, an amount reaching the templates as a
     * {@code BigDecimal}, another number or a machine-readable text
     * ({@code "150.00"}); a value that is not a number shows itself.
     *
     * @param amount the amount, possibly null
     * @return the written amount, an empty string for null
     */
    public String money(Object amount) {
        BigDecimal value = decimal(amount);
        if (value == null) {
            return shown(amount);
        }
        return comma(value.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * A rate held as a fraction — a VAT rate {@code 0.2000} — written as a
     * percentage with two decimals and its sign: {@code 20,00 %}.
     *
     * @param fraction the rate, possibly null
     * @return the written percentage, an empty string for null
     */
    public String rate(Object fraction) {
        BigDecimal value = decimal(fraction);
        if (value == null) {
            return shown(fraction);
        }
        return comma(value.movePointRight(2).setScale(2, RoundingMode.HALF_UP)) + " %";
    }

    /**
     * A value already held in percent — a customer discount {@code 5.00} —
     * written with two decimals and its sign: {@code 5,00 %}.
     *
     * @param percent the percentage, possibly null
     * @return the written percentage, an empty string for null
     */
    public String percent(Object percent) {
        BigDecimal value = decimal(percent);
        if (value == null) {
            return shown(percent);
        }
        return comma(value.setScale(2, RoundingMode.HALF_UP)) + " %";
    }

    /**
     * A quantity sold by unit written as administered: a whole quantity
     * without decimals, any other with the administered decimals.
     *
     * @param quantity the quantity, possibly null
     * @return the written quantity, an empty string for null
     */
    public String quantity(Object quantity) {
        BigDecimal value = decimal(quantity);
        if (value == null) {
            return shown(quantity);
        }
        return format().measure(value);
    }

    /**
     * A number that is neither an amount nor a quantity — an exchange rate
     * {@code 1.0850} — with its own decimals, needless trailing zeros
     * dropped: {@code 1,085}.
     *
     * @param number the number, possibly null
     * @return the written number, an empty string for null
     */
    public String number(Object number) {
        BigDecimal value = decimal(number);
        if (value == null) {
            return shown(number);
        }
        return comma(value.stripTrailingZeros());
    }

    /**
     * Reads a value as a number: a {@code BigDecimal} as it is, another
     * number or a text through its decimal writing.
     *
     * @param value the value, possibly null
     * @return the number, or null when the value is null or not a number
     */
    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal number) {
            return number;
        }
        if (!(value instanceof Number) && !(value instanceof CharSequence)) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Shows a value that is not a number as it came.
     *
     * @param value the value, possibly null
     * @return its text, empty for null
     */
    private static String shown(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * Writes a number in full with a decimal comma.
     *
     * @param value the number
     * @return the written number
     */
    private static String comma(BigDecimal value) {
        return value.toPlainString().replace('.', ',');
    }

    /**
     * The administered weight symbol.
     *
     * @return the symbol, never blank
     */
    public String getWeightSymbol() {
        return posSettingsService.weightSymbol();
    }
}
