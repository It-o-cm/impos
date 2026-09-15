package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.payment.Currency;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Takes a settlement in a foreign currency at the till ({@code LC-07-14}).
 *
 * <p>Border shops accept Swiss francs. The rate is the SHOP'S, administered in the
 * back office and pulled with the referential, and this service does nothing more
 * than apply it: it never asks a market, and a rate the register cannot read is a
 * currency the register does not offer.
 *
 * <p>Two things are shown BEFORE anything is validated ({@code LC-07-14-04}): the
 * rate itself, and what the sale costs in that currency. A customer holding foreign
 * notes is deciding how many to hand over, and the register is the only thing in
 * the shop that can tell them.
 *
 * <p>CHANGE COMES BACK IN EUROS, always: the drawer holds a euro float. That is why
 * the euro value is computed first and the ordinary change logic takes over from
 * there — there is no second currency arithmetic, and so no second place for a
 * rounding rule to disagree with the first.
 */
@ApplicationScoped
public class ForeignCurrencyService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ForeignCurrencyService.class);

    /** Refusal shown when the till offers no currency at all. */
    static final String NO_CURRENCY = "AUCUNE DEVISE PARAMETREE";

    /** Refusal shown when no currency has been selected yet. */
    static final String NO_SELECTION = "DEVISE NON SELECTIONNEE";

    /** Refusal shown when the amount typed is not a usable amount. */
    static final String BAD_AMOUNT = "MONTANT EN DEVISE INVALIDE";

    /** Registers the settlement once this service has converted it. */
    @Inject
    PaymentService paymentService;

    /**
     * Opens the foreign-currency panel over the payment screen.
     *
     * @param state the current POS state
     */
    public void openPanel(PosState state) {
        LOGGER.info("Entering method openPanel with state: " + state);
        // One popup at a time: both panels bind the shared on-screen keypad, and
        // two bindings would leave the second stealing the first one's buffer.
        state.payment.clearCreditPanel();
        state.payment.clearCurrencyPanel();
        state.payment.currencyPanelOpen = true;
        if (listCurrencies().isEmpty()) {
            state.payment.currencyError = NO_CURRENCY;
        }
        state.touch();
        LOGGER.info("Exiting method openPanel");
    }

    /**
     * Closes the panel without settling anything.
     *
     * @param state the current POS state
     */
    public void closePanel(PosState state) {
        LOGGER.info("Entering method closePanel with state: " + state);
        state.payment.clearCurrencyPanel();
        state.touch();
        LOGGER.info("Exiting method closePanel");
    }

    /**
     * Returns the currencies the till offers, in administered order.
     *
     * @return the active currencies, empty when the shop takes none
     */
    public List<Currency> listCurrencies() {
        LOGGER.info("Entering method listCurrencies");
        LOGGER.info("Exiting method listCurrencies");
        return Currency.listActive();
    }

    /**
     * Selects the currency the customer is paying in, which is what makes the rate
     * and the amount due in that currency displayable ({@code LC-07-14-04}).
     *
     * @param state the current POS state
     * @param code the ISO code posted by the screen
     */
    public void selectCurrency(PosState state, String code) {
        LOGGER.info("Entering method selectCurrency with state: " + state + ", code: " + code);
        state.payment.currencyError = null;
        Currency currency = code == null || code.isBlank()
                ? null : Currency.findActiveByCode(code.trim().toUpperCase());
        if (currency == null) {
            state.payment.selectedCurrency = null;
            state.payment.currencyError = NO_SELECTION;
            state.touch();
            LOGGER.info("Exiting method selectCurrency");
            return;
        }
        state.payment.selectedCurrency = currency;
        state.touch();
        LOGGER.info("Exiting method selectCurrency");
    }

    /**
     * Registers a settlement handed over in the selected currency
     * ({@code LC-07-14-03}).
     *
     * @param state the current POS state
     * @param foreignAmount the amount handed over, in the selected currency
     * @return true when the settlement was registered
     */
    public boolean processCurrency(PosState state, BigDecimal foreignAmount) {
        LOGGER.info("Entering method processCurrency with state: " + state + ", foreignAmount: " + foreignAmount);
        Currency currency = state.payment.selectedCurrency;
        if (currency == null) {
            state.payment.currencyError = NO_SELECTION;
            state.touch();
            LOGGER.info("Exiting method processCurrency");
            return false;
        }
        if (foreignAmount == null || foreignAmount.signum() <= 0) {
            state.payment.currencyError = BAD_AMOUNT;
            state.touch();
            LOGGER.info("Exiting method processCurrency");
            return false;
        }
        BigDecimal euroValue = currency.toEuro(foreignAmount);
        if (euroValue.signum() <= 0) {
            // A rate so small that the notes handed over are worth less than a cent
            // is a misconfigured rate, not a payment: refusing says so, whereas
            // registering a zero would silently swallow the customer's money.
            state.payment.currencyError = BAD_AMOUNT;
            state.touch();
            LOGGER.info("Exiting method processCurrency");
            return false;
        }
        paymentService.processForeignCurrency(state, currency, foreignAmount, euroValue);
        state.payment.clearCurrencyPanel();
        state.touch();
        LOGGER.info("Exiting method processCurrency");
        return true;
    }
}
