package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.payment.PaymentTypes;
import com.intermarche.pos.domain.payment.TenderDefinition;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

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
// The rules are also read from the payment terminal's own thread — a
// Verifone or bridge answer arrives outside any request — so every method
// activates the request context its queries need (BO-03-02-01, -44).
@jakarta.enterprise.context.control.ActivateRequestContext
@ApplicationScoped
public class TenderRulesService {

    /** French display format for the amounts quoted in a refusal. */
    private final DecimalFormat df =
            new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));


    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * The administered parameters, read for the island-level change prohibition
     * (BO-03-10-04) that primes over the per-tender rule.
     */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

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
                    i18n.format("op.montantSuperieurPlafond", df.format(tender.maxAmount)));
        }
        if (tender.exceedsSecondMaxAmount(amount)) {
            worst = worse(worst, tender.secondMaxAmountControl,
                    i18n.format("op.montantSuperieurSecondPlafond",
                            df.format(tender.secondMaxAmount)));
        }
        if (tender.belowMinAmount(amount)) {
            worst = worse(worst, tender.minAmountControl,
                    i18n.format("op.montantInferieurMinimum", df.format(tender.minAmount)));
        }
        if (tender.exceedsMaxCount(alreadyRegistered)) {
            worst = worse(worst, tender.maxCountControl,
                    i18n.format("op.nombreMaximumReglementsAtteint", String.valueOf(tender.maxCount)));
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
                i18n.format("op.renduSuperieurPlafond", df.format(tender.maxChangeAmount)));
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
        // BO-03-10-04: an island that forbids change PRIMES over the per-tender
        // rule — the island prohibition is not a default one may override, it is
        // a veto. When the island allows (the true default), the tender rule
        // decides exactly as before the option existed.
        if (!posSettingsService.islandChangeAllowed()) {
            return false;
        }
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
        // BO-03-02-01: a tender the store added answers for itself, under its code.
        for (TenderDefinition tender : additional()) {
            answers.put(tender.code, tender.defaultsToTotal);
        }
        return answers;
    }

    /**
     * Returns the administered tender of a code (BO-03-02-01).
     *
     * @param code the tender code, or null
     * @return the tender, or null when no row administers that code
     */
    public TenderDefinition tender(String code) {
        return TenderDefinition.findByCode(code);
    }

    /**
     * Returns the active tender a monetics answer selects (BO-03-02-44).
     *
     * @param primary the {@code D16} value, or null
     * @param alternate the {@code D46} value, or null
     * @return the tender, or null when no active row carries either answer
     */
    public TenderDefinition moneticTender(String primary, String alternate) {
        return TenderDefinition.findByMonetic(primary, alternate);
    }

    /**
     * Returns the tenders the store ADDED that the payment screen offers as
     * keys of their own (BO-03-02-01).
     *
     * @return the additional tenders in service, in administered order
     */
    public java.util.List<TenderDefinition> additional() {
        return TenderDefinition.listActiveAdditional();
    }

    /**
     * Tells whether the CASH key settles through the coin machine
     * (BO-03-02-36), given that this register runs one.
     *
     * @param machinePresent whether this register has a coin machine enabled
     * @return true when the cash tender is administered with the coin machine
     *         option and the register has one
     */
    public boolean cashViaCoinMachine(boolean machinePresent) {
        if (!machinePresent) {
            return false;
        }
        TenderDefinition cash = TenderDefinition.findByCode("CASH");
        return cash != null && cash.active && cash.coinMachine;
    }

    /**
     * Returns the tenders a cash movement — an expense, a down-payment — may
     * be recorded in (BO-03-02-20): the active tenders administered "apport /
     * dépense autorisé", cash excepted since cash is the default of every
     * movement.
     *
     * @return the movement tenders, in administered order, possibly empty
     */
    public java.util.List<String> movementTenders() {
        java.util.List<String> codes = new java.util.ArrayList<>();
        for (TenderDefinition tender : TenderDefinition.listAllOrdered()) {
            if (tender.active && tender.movementAllowed && !"CASH".equals(tender.code)) {
                codes.add(tender.code);
            }
        }
        return codes;
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
        public String displayMessage(com.intermarche.pos.ui.Messages i18n) {
            if (message == null) {
                return null;
            }
            return level.allowsOverride()
                    ? i18n.format("op.appelerSuperviseur", message) : message;
        }
    }
}
