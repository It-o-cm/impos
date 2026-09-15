package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.payment.TenderDefinition;
import com.intermarche.pos.ui.journal.PaymentTypes;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the administered TENDER rules on behalf of the payment screen
 * (BO-03-02-03/10/11/12/13/14/16/19/23).
 *
 * <p>The whole class rests on ONE rule, and every method restates it: a
 * settlement key NO row administers keeps the register's own behaviour. That is
 * what lets the referential be installed on a running shop without a migration
 * day, and it is why every reader here takes the register's current answer as a
 * fallback rather than inventing a default of its own.
 *
 * <p>Package placement: it serves the payment screen and nothing else, so it
 * lives beside it rather than at the service root.
 */
@ApplicationScoped
public class TenderRulesService {

    /** French display format for the amounts quoted in a refusal. */
    private final DecimalFormat df =
            new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));

    /**
     * Checks one settlement about to be registered against the bounds the back
     * office administered for its tender (BO-03-02-10/12/13/14).
     *
     * <p>The bounds are ALL evaluated and the STRICTEST outcome wins: a store
     * that administers an informative floor and a blocking ceiling gets the
     * block when the amount breaks both, not whichever rule happens to be
     * checked first.
     *
     * @param methodKey the settlement key, or null
     * @param amount the amount of the settlement, or null
     * @param alreadyRegistered how many settlements of this tender the sale carries
     * @return the strictest violated rule, or a silent verdict when none is
     */
    public Verdict check(String methodKey, BigDecimal amount, int alreadyRegistered) {
        TenderDefinition tender = TenderDefinition.findByCode(methodKey);
        if (tender == null) {
            return Verdict.silent();
        }
        Verdict worst = Verdict.silent();
        if (tender.exceedsMaxAmount(amount)) {
            worst = worse(worst, tender.maxAmountControl,
                    "MONTANT SUPERIEUR AU PLAFOND (" + df.format(tender.maxAmount) + " E)");
        }
        if (tender.exceedsSecondMaxAmount(amount)) {
            worst = worse(worst, tender.secondMaxAmountControl,
                    "MONTANT SUPERIEUR AU SECOND PLAFOND ("
                            + df.format(tender.secondMaxAmount) + " E)");
        }
        if (tender.belowMinAmount(amount)) {
            worst = worse(worst, tender.minAmountControl,
                    "MONTANT INFERIEUR AU MINIMUM (" + df.format(tender.minAmount) + " E)");
        }
        if (tender.exceedsMaxCount(alreadyRegistered)) {
            worst = worse(worst, tender.maxCountControl,
                    "NOMBRE MAXIMUM DE REGLEMENTS ATTEINT (" + tender.maxCount + ")");
        }
        return worst;
    }

    /**
     * Checks the change about to be given back against the administered change
     * ceiling (BO-03-02-11).
     *
     * @param methodKey the settlement key, or null
     * @param change the change about to be given back, or null
     * @return the violated rule, or a silent verdict when none is
     */
    public Verdict checkChange(String methodKey, BigDecimal change) {
        TenderDefinition tender = TenderDefinition.findByCode(methodKey);
        if (tender == null || !tender.exceedsMaxChange(change)) {
            return Verdict.silent();
        }
        return worse(Verdict.silent(), tender.maxChangeControl,
                "RENDU SUPERIEUR AU PLAFOND (" + df.format(tender.maxChangeAmount) + " E)");
    }

    /**
     * Tells whether the payment screen offers this tender (BO-03-02-03).
     *
     * @param methodKey the settlement key, or null
     * @return true unless an administered row takes the tender out of service
     */
    public boolean isOffered(String methodKey) {
        return TenderDefinition.isActive(methodKey);
    }

    /**
     * Returns the settlement keys the payment screen offers, in the order the
     * register names them (BO-03-02-03).
     *
     * <p>Answering a SET rather than a predicate per button is what keeps the
     * page from running one query per tender on every refresh.
     *
     * @return the keys in service, never null
     */
    public Set<String> offered() {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : PaymentTypes.keys()) {
            if (isOffered(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Tells whether an overpayment on this tender gives change back
     * (BO-03-02-16).
     *
     * @param methodKey the settlement key, or null
     * @param fallback what the register does when no row administers the tender
     * @return the administered answer, or the fallback
     */
    public boolean changeAllowed(String methodKey, boolean fallback) {
        TenderDefinition tender = TenderDefinition.findByCode(methodKey);
        return tender == null ? fallback : tender.changeAllowed;
    }

    /**
     * Returns the tender the change of this one is given in (BO-03-02-16).
     *
     * @param methodKey the settlement key, or null
     * @return the administered change tender, or the key itself when no row
     *         administers the tender
     */
    public String changeTender(String methodKey) {
        TenderDefinition tender = TenderDefinition.findByCode(methodKey);
        return tender == null ? methodKey : tender.changeTender();
    }

    /**
     * Tells whether the drawer opens for this tender at the moment the caller
     * describes (BO-03-02-19).
     *
     * @param methodKey the settlement key, or null
     * @param changeDue true when change is owed to the customer
     * @param receiptPrinted true when the customer receipt has just been printed
     * @param fallback what the register does when no row administers the tender
     * @return the administered answer, or the fallback
     */
    public boolean opensDrawer(String methodKey, boolean changeDue, boolean receiptPrinted,
            boolean fallback) {
        TenderDefinition tender = TenderDefinition.findByCode(methodKey);
        return tender == null ? fallback : tender.opensDrawer(changeDue, receiptPrinted);
    }

    /**
     * Tells whether picking this tender pre-fills the remaining due
     * (BO-03-02-23).
     *
     * @param methodKey the settlement key, or null
     * @param fallback what the register does when no row administers the tender
     * @return the administered answer, or the fallback
     */
    public boolean defaultsToTotal(String methodKey, boolean fallback) {
        TenderDefinition tender = TenderDefinition.findByCode(methodKey);
        return tender == null ? fallback : tender.defaultsToTotal;
    }

    /**
     * Returns the pre-fill answer of the ADMINISTERED tenders only
     * (BO-03-02-23).
     *
     * <p>Administered rows only, deliberately: the payment screen already has a
     * rule of its own — the card, the cheque and the purse open on the remaining
     * due, the meal voucher opens empty — and a key missing from this map is a
     * key that keeps it. Answering a complete map would have replaced that rule
     * with this class's own default for every untouched tender.
     *
     * @return the administered pre-fill answers, keyed by settlement key
     */
    public Map<String, Boolean> administeredDefaultAmounts() {
        Map<String, Boolean> answers = new LinkedHashMap<>();
        for (String key : PaymentTypes.keys()) {
            TenderDefinition tender = TenderDefinition.findByCode(key);
            if (tender != null) {
                answers.put(key, tender.defaultsToTotal);
            }
        }
        return answers;
    }

    /**
     * Keeps whichever of two outcomes the cashier must obey.
     *
     * <p>The comparison is on the declaration order of {@code ControlLevel},
     * which runs from the silent level to the blocking one: that order IS the
     * severity scale, and the enumeration says so.
     *
     * @param current the strictest outcome so far
     * @param level the level of the rule just violated, or null when unset
     * @param message what the cashier is told
     * @return the stricter of the two
     */
    private Verdict worse(Verdict current, TenderDefinition.ControlLevel level, String message) {
        if (level == null || level.ordinal() <= current.level.ordinal()) {
            return current;
        }
        return new Verdict(level, message);
    }

    /**
     * What the administered rules answer about one settlement.
     */
    public static class Verdict {

        /** The strictest level violated, never null. */
        public final TenderDefinition.ControlLevel level;

        /** What the cashier is told, or null when nothing was violated. */
        public final String message;

        /**
         * Builds a verdict.
         *
         * @param level the strictest level violated
         * @param message what the cashier is told, or null
         */
        public Verdict(TenderDefinition.ControlLevel level, String message) {
            this.level = level;
            this.message = message;
        }

        /**
         * Builds the verdict of a settlement that broke nothing.
         *
         * @return a silent verdict
         */
        public static Verdict silent() {
            return new Verdict(TenderDefinition.ControlLevel.NONE, null);
        }

        /**
         * Tells whether the settlement must be refused as it stands.
         *
         * @return true when the violated level blocks
         */
        public boolean refuses() {
            return level.blocks();
        }

        /**
         * Tells whether the cashier must be shown something.
         *
         * @return true when a rule was violated at a level that speaks
         */
        public boolean speaks() {
            return !level.isSilent();
        }

        /**
         * Returns what the cashier is told, a blocking refusal naming the
         * supervisor the store asked for (BO-03-02-10/12/13/14).
         *
         * @return the message, or null when nothing was violated
         */
        public String displayMessage() {
            if (message == null) {
                return null;
            }
            return level.allowsOverride() ? message + " - APPELER UN SUPERVISEUR" : message;
        }
    }
}
