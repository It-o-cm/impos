package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.sync.RefPullService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Names the debtor of a customer-credit settlement and decides whether the shop
 * accepts it ({@code LC-07-09}).
 *
 * <p>Customer credit is the one payment method where the register hands the goods
 * over WITHOUT taking anything: the sale becomes a debt settled monthly in the
 * commercial management. Everything here follows from that. The account must be
 * named before the amount is registered ({@code LC-07-09-02}), it can be reached by
 * number or by name ({@code -07}, {@code -08}), and what the account still owes plus
 * what this sale would add must stay under the administered ceiling ({@code -03}).
 * A shop that wants to serve a customer anyway can, through a supervisor, and that
 * moves the BALANCE, never the ceiling ({@code -04}) — the ceiling is the back
 * office's decision, and a register that could raise it would not be a control.
 *
 * <p>It lives in {@code ui.payment} and not in the general services: it reads and
 * writes {@link PosState}, it exists to serve one panel of the payment screen, and
 * nothing outside that screen has any use for it.
 */
@ApplicationScoped
public class CreditClientService {

    private static final Logger LOGGER = Logger.getLogger(CreditClientService.class);

    /** How many matching accounts a name search brings back at most. */
    private static final int SEARCH_LIMIT = 20;

    /** The shortest name fragment worth searching on. */
    private static final int MIN_SEARCH_LENGTH = 2;

    /** Refusal shown when the account has no administered ceiling. */
    static final String NO_CREDIT_GRANTED = "COMPTE SANS AUTORISATION DE CREDIT";

    /** Refusal shown when no account has been named yet. */
    static final String NO_ACCOUNT = "COMPTE CLIENT NON IDENTIFIE";

    /**
     * Refusal shown when the client referential is too old to be trusted.
     *
     * <p>Kept as the fallback of the administered message ({@code BO-10-04-12}):
     * a shop that empties the parameter must still see a refusal, not a blank
     * panel that looks like nothing happened.
     */
    static final String DEGRADED = "REFERENTIEL CLIENT NON A JOUR - CREDIT REFUSE";

    /** The action code journalled when a supervisor allows the ceiling to be passed. */
    static final String OVER_LIMIT_ACTION = "CREDIT_OVER_LIMIT";

    /** Refusal shown when the shop does not accept a partial credit settlement. */
    static final String PARTIAL_REFUSED = "PAIEMENT PARTIEL EN CREDIT CLIENT NON AUTORISE";

    /** Registers the settlement once this service has allowed it. */
    @Inject
    PaymentService paymentService;

    /** The back-office parameters governing the degraded rule. */
    @Inject
    PosSettingsService posSettingsService;

    /** Tells whether a store node is configured at all. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** Carries the age of the last successful referential pull. */
    @Inject
    RefPullService refPullService;

    /** Checks the supervisor credential and journals the decision. */
    @Inject
    EndorsementService endorsementService;

    /**
     * Opens the customer-credit panel over the payment screen.
     *
     * @param state the current POS state
     */
    public void openPanel(PosState state) {
        LOGGER.info("Entering method openPanel with state: " + state);
        // One popup at a time: both panels bind the shared on-screen keypad, and
        // two bindings would leave the second stealing the first one's buffer.
        state.payment.clearCurrencyPanel();
        state.payment.clearCreditPanel();
        state.payment.creditPanelOpen = true;
        state.touch();
        LOGGER.info("Exiting method openPanel");
    }

    /**
     * Closes the panel and forgets the account and any pending authorization.
     *
     * @param state the current POS state
     */
    public void closePanel(PosState state) {
        LOGGER.info("Entering method closePanel with state: " + state);
        state.payment.clearCreditPanel();
        state.touch();
        LOGGER.info("Exiting method closePanel");
    }

    /**
     * Names the account by its number ({@code LC-07-09-02}): the name comes back for
     * the operator to confirm, it is not settled on the spot.
     *
     * @param state the current POS state
     * @param accountNumber the account number as typed
     */
    public void selectByNumber(PosState state, String accountNumber) {
        LOGGER.info("Entering method selectByNumber with state: " + state + ", accountNumber: " + accountNumber);
        state.payment.creditError = null;
        state.payment.creditPendingAmount = null;
        String typed = accountNumber == null ? "" : accountNumber.trim();
        if (typed.isEmpty()) {
            state.payment.creditError = "NUMERO DE COMPTE REQUIS";
            state.touch();
            LOGGER.info("Exiting method selectByNumber");
            return;
        }
        // BO-03-06-52: the store states which numbers are account numbers at
        // all. One outside the administered range is refused HERE, before the
        // database is asked: an operator mistyping a loyalty card into the
        // account box gets told what is wrong, not "compte introuvable".
        if (!matchesAccountRange(typed)) {
            state.payment.creditError = "NUMERO DE COMPTE HORS PLAGE : " + typed;
            state.touch();
            LOGGER.info("Exiting method selectByNumber");
            return;
        }
        AccountCustomer found = AccountCustomer.find("accountNumber", typed).firstResult();
        if (found == null) {
            state.payment.creditError = "COMPTE INTROUVABLE : " + typed;
            state.touch();
            LOGGER.info("Exiting method selectByNumber");
            return;
        }
        confirm(state, found);
        LOGGER.info("Exiting method selectByNumber");
    }

    /**
     * Tells whether a number belongs to the administered range of account
     * numbers (BO-03-06-52). A store administering no range accepts every
     * number, which is what every existing deployment does today.
     *
     * @param number the number as typed or scanned, never null
     * @return true when the number is acceptable as an account number
     */
    public boolean matchesAccountRange(String number) {
        String pattern = posSettingsService.customerAccountPattern();
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        return number.matches(pattern.trim());
    }

    /**
     * Looks accounts up by name ({@code LC-07-09-07}), address included so two
     * businesses carrying the same name can be told apart.
     *
     * <p>A search matching EXACTLY ONE account names it straight away
     * ({@code LC-07-09-08}): the operator still confirms, on the same panel, from
     * the number, name and address then displayed.
     *
     * @param state the current POS state
     * @param search the name fragment typed by the operator
     */
    public void searchByName(PosState state, String search) {
        LOGGER.info("Entering method searchByName with state: " + state + ", search: " + search);
        state.payment.creditError = null;
        state.payment.creditPendingAmount = null;
        state.payment.creditSearch = search == null ? "" : search.trim();
        if (state.payment.creditSearch.length() < MIN_SEARCH_LENGTH) {
            state.payment.creditCustomers = new java.util.ArrayList<>();
            state.payment.creditSearched = false;
            state.payment.creditError = "SAISIR AU MOINS " + MIN_SEARCH_LENGTH + " CARACTERES";
            state.touch();
            LOGGER.info("Exiting method searchByName");
            return;
        }
        List<AccountCustomer> matches = AccountCustomer
                .<AccountCustomer>find("lower(companyName) like ?1 order by companyName",
                        "%" + state.payment.creditSearch.toLowerCase() + "%")
                .page(0, SEARCH_LIMIT).list();
        state.payment.creditCustomers = new java.util.ArrayList<>(matches);
        state.payment.creditSearched = true;
        if (matches.size() == 1) {
            confirm(state, matches.get(0));
            LOGGER.info("Exiting method searchByName");
            return;
        }
        state.touch();
        LOGGER.info("Exiting method searchByName");
    }

    /**
     * Names the account the operator picked from the search results.
     *
     * @param state the current POS state
     * @param customerId the database id of the picked account
     */
    public void selectById(PosState state, Long customerId) {
        LOGGER.info("Entering method selectById with state: " + state + ", customerId: " + customerId);
        state.payment.creditError = null;
        state.payment.creditPendingAmount = null;
        AccountCustomer found = customerId == null ? null : AccountCustomer.findById(customerId);
        if (found == null) {
            state.payment.creditError = "COMPTE INTROUVABLE";
            state.touch();
            LOGGER.info("Exiting method selectById");
            return;
        }
        confirm(state, found);
        LOGGER.info("Exiting method selectById");
    }

    /**
     * Holds the account on the panel for the operator to confirm, and says at once
     * when it may not settle on credit at all — knowing before typing an amount is
     * worth more than a refusal after.
     *
     * @param state the current POS state
     * @param customer the account being named
     */
    private void confirm(PosState state, AccountCustomer customer) {
        state.payment.creditCustomer = customer;
        state.payment.creditCustomers = new java.util.ArrayList<>();
        state.payment.creditSearched = false;
        if (!customer.isCreditAllowed()) {
            state.payment.creditError = NO_CREDIT_GRANTED;
        }
        state.touch();
    }

    /**
     * Registers a customer-credit settlement, or refuses it.
     *
     * <p>The order of the guards IS the rule: no account, no credit granted, a
     * referential too old to be trusted, then the ceiling. Only the last one can be
     * passed by a supervisor — the first three are not risks a shop chooses to take,
     * they are things the register does not know.
     *
     * @param state the current POS state
     * @param amount the amount to charge, or null/zero to charge the remaining due
     * @return true when the settlement was registered
     */
    public boolean processCredit(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method processCredit with state: " + state + ", amount: " + amount);
        AccountCustomer customer = state.payment.creditCustomer;
        if (customer == null) {
            state.payment.creditError = NO_ACCOUNT;
            state.touch();
            LOGGER.info("Exiting method processCredit");
            return false;
        }
        if (!customer.isCreditAllowed()) {
            state.payment.creditError = NO_CREDIT_GRANTED;
            state.touch();
            LOGGER.info("Exiting method processCredit");
            return false;
        }
        if (isDegraded()) {
            state.payment.creditError = offlineMessage();
            state.touch();
            LOGGER.info("Exiting method processCredit");
            return false;
        }
        BigDecimal asked = amountToCharge(state, amount);
        if (asked.signum() <= 0) {
            state.touch();
            LOGGER.info("Exiting method processCredit");
            return false;
        }
        // BO-10-04-10: a shop may refuse PARTIAL settlement on credit. Asking for
        // less than what is left is then not a small payment, it is a payment the
        // shop has not authorised — and saying so before the account is charged is
        // the only moment where it costs nothing.
        if (!posSettingsService.partialCreditAllowed() && asked.compareTo(state.getRemaining()) < 0) {
            state.payment.creditError = PARTIAL_REFUSED;
            state.touch();
            LOGGER.info("Exiting method processCredit");
            return false;
        }
        if (exceedsCeiling(customer, asked)) {
            // HELD BACK, not refused: LC-07-09-04 lets a supervisor allow it, and
            // the amount must survive until they answer or the operator gives up.
            state.payment.creditPendingAmount = asked;
            state.payment.creditError = overLimitMessage(customer);
            state.touch();
            LOGGER.info("Exiting method processCredit");
            return false;
        }
        register(state, customer, asked, false);
        LOGGER.info("Exiting method processCredit");
        return true;
    }

    /**
     * Lets a supervisor pass the ceiling for the settlement held back
     * ({@code LC-07-09-04}).
     *
     * <p>What moves is the BALANCE, never the ceiling: the situation this exists for
     * is a customer who has paid their debt without the shop having recorded it, and
     * the answer to that is one authorized sale, not a permanently raised ceiling.
     *
     * @param state the current POS state
     * @param login the supervisor's login, ignored when the logged operator is one
     * @param password the supervisor's password
     * @return true when the settlement was registered
     */
    public boolean authorizeOverLimit(PosState state, String login, String password) {
        LOGGER.info("Entering method authorizeOverLimit with state: " + state + ", login: " + login + ", password: ***");
        BigDecimal held = state.payment.creditPendingAmount;
        AccountCustomer customer = state.payment.creditCustomer;
        if (held == null || customer == null) {
            state.payment.creditError = NO_ACCOUNT;
            state.touch();
            LOGGER.info("Exiting method authorizeOverLimit");
            return false;
        }
        boolean granted = endorsementService.operatorIsSupervisor(state)
                || endorsementService.authorize(login, password, OVER_LIMIT_ACTION);
        if (!granted) {
            state.payment.creditError = "AUTORISATION REFUSEE";
            state.touch();
            LOGGER.info("Exiting method authorizeOverLimit");
            return false;
        }
        state.payment.creditPendingAmount = null;
        register(state, customer, held, true);
        LOGGER.info("Exiting method authorizeOverLimit");
        return true;
    }

    /**
     * Abandons the settlement held back for authorization, keeping the account named
     * so the operator can simply type a smaller amount.
     *
     * @param state the current POS state
     */
    public void cancelOverLimit(PosState state) {
        LOGGER.info("Entering method cancelOverLimit with state: " + state);
        state.payment.creditPendingAmount = null;
        state.payment.creditError = null;
        state.touch();
        LOGGER.info("Exiting method cancelOverLimit");
    }

    /**
     * Registers the settlement, charges the account and closes the panel.
     *
     * @param state the current POS state
     * @param customer the debtor
     * @param amount the amount charged
     * @param overLimit true when a supervisor allowed the ceiling to be passed
     */
    private void register(PosState state, AccountCustomer customer, BigDecimal amount,
            boolean overLimit) {
        paymentService.processCredit(state, customer, amount, overLimit);
        chargeAccount(customer.id, amount);
        LOGGER.infof("Crédit client %s : %s E chargés%s", customer.accountNumber, amount,
                overLimit ? " (autorisation superviseur, plafond dépassé)" : "");
        state.payment.clearCreditPanel();
        state.touch();
    }

    /**
     * Adds the settlement to the account's outstanding balance.
     *
     * <p>The register writes the balance it will lose at the next pull, on purpose:
     * between two pulls, it is the only thing standing between one account and two
     * sales that each slip under the ceiling separately.
     *
     * @param customerId the database id of the account
     * @param amount the amount charged
     */
    @Transactional
    void chargeAccount(Long customerId, BigDecimal amount) {
        AccountCustomer persisted = AccountCustomer.findById(customerId);
        if (persisted == null) {
            return;
        }
        BigDecimal balance = persisted.creditBalance == null
                ? BigDecimal.ZERO : persisted.creditBalance;
        persisted.creditBalance = balance.add(amount);
        persisted.persist();
    }

    /**
     * Resolves what this settlement charges: what was typed, capped at the remaining
     * due, or the whole remaining due when nothing usable was typed.
     *
     * @param state the current POS state
     * @param amount the amount typed, possibly null or non-positive
     * @return the amount to charge, never above the remaining due
     */
    private BigDecimal amountToCharge(PosState state, BigDecimal amount) {
        BigDecimal remaining = state.getRemaining();
        if (amount == null || amount.signum() <= 0) {
            return remaining;
        }
        return amount.min(remaining).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Tells whether this settlement takes the account over its ceiling
     * ({@code LC-07-09-03}): what it already owes PLUS what this sale adds.
     *
     * @param customer the account
     * @param amount the amount about to be charged
     * @return true when the ceiling would be exceeded
     */
    private boolean exceedsCeiling(AccountCustomer customer, BigDecimal amount) {
        BigDecimal balance = customer.creditBalance == null
                ? BigDecimal.ZERO : customer.creditBalance;
        return balance.add(amount).compareTo(customer.creditLimit) > 0;
    }

    /**
     * Tells whether the client referential is too old for its credit figures to be
     * trusted ({@code LC-07-09-05}).
     *
     * <p>A register with NO store node is never degraded: it is not supposed to pull,
     * so its referential is exactly as fresh as whatever was loaded into it, and
     * calling that a network outage would refuse credit on a standalone till for a
     * link it was never meant to have. When a store node IS configured, having never
     * completed a cycle counts as degraded — a register that has just restarted has,
     * in truth, no idea what these accounts owe.
     *
     * @return true when the shop's setting forbids credit on a stale referential
     */
    private boolean isDegraded() {
        if (!syncOutboxService.isEnabled() || posSettingsService.creditAllowedInDegraded()) {
            return false;
        }
        LocalDateTime lastPull = refPullService.getLastSuccessfulPull();
        if (lastPull == null) {
            return true;
        }
        return lastPull.isBefore(
                LocalDateTime.now().minusMinutes(posSettingsService.creditDegradedAfterMinutes()));
    }

    /**
     * Tells whether the discount of the named account is announced to the operator
     * ({@code BO-10-04-07} and {@code BO-10-04-08}).
     *
     * <p>Two administered decisions, not one. The shop first says whether a discount
     * is announced at all; it then names the SEGMENT whose discounts are announced.
     * A shop naming no segment announces every account's discount, which is what a
     * shop that has never segmented its customers expects. A shop that names one is
     * saying that the other segments' discounts are settled in the commercial
     * management and have no business being read out at the till.
     *
     * @param customer the account named on the panel, null when none is
     * @return true when the panel shows the discount
     */
    public boolean discountAnnounced(AccountCustomer customer) {
        LOGGER.info("Entering method discountAnnounced with customer: " + customer);
        boolean announced = announces(customer);
        LOGGER.info("Exiting method discountAnnounced");
        return announced;
    }

    /**
     * Decides the announcement, guard by guard.
     *
     * @param customer the account named on the panel, null when none is
     * @return true when the panel shows the discount
     */
    private boolean announces(AccountCustomer customer) {
        if (!posSettingsService.showCustomerDiscount()) {
            return false;
        }
        if (customer == null || customer.discountPercent == null
                || customer.discountPercent.signum() <= 0) {
            return false;
        }
        String administered = posSettingsService.customerDiscountSegment();
        if (administered == null || administered.isBlank()) {
            return true;
        }
        String segment = customer.segment == null ? "" : customer.segment.trim();
        return administered.trim().equalsIgnoreCase(segment);
    }

    /**
     * Builds the offline alert ({@code BO-10-04-12}).
     *
     * @return the administered message, or the built-in refusal when the
     *         parameter was emptied
     */
    private String offlineMessage() {
        String administered = posSettingsService.customerOfflineMessage();
        return administered == null || administered.isBlank() ? DEGRADED : administered;
    }

    /**
     * Builds the ceiling alert, its two tokens replaced by the figures
     * ({@code BO-10-04-13}).
     *
     * <p>Tokens and not a positional format: a shop rewriting the sentence must
     * be able to put the ceiling before the balance, or to name only one of
     * them, without the message turning into an exception.
     *
     * @param customer the account whose ceiling would be passed
     * @return the message shown to the operator
     */
    private String overLimitMessage(AccountCustomer customer) {
        String administered = posSettingsService.customerOverLimitMessage();
        if (administered == null || administered.isBlank()) {
            administered = "PLAFOND DEPASSE - ENCOURS {encours} / PLAFOND {plafond}"
                    + " - AUTORISATION REQUISE";
        }
        return administered
                .replace("{encours}", plain(customer.creditBalance))
                .replace("{plafond}", plain(customer.creditLimit));
    }

    /**
     * Formats an amount for a cashier-facing message, treating null as zero.
     *
     * @param amount the amount, possibly null
     * @return the amount in plain digits
     */
    private static String plain(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
