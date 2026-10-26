package com.intermarche.pos.ui.payment;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory state of the payment in progress for the current ticket.
 * <p>
 * All monetary amounts are {@link BigDecimal} (phase 0).
 * <p>
 * {@code paymentInProgress} is THE payment-context discriminator of the
 * whole register — the voucher scan handler routes on it, parking refuses
 * on it, the training toggle refuses on it. It exists precisely because
 * {@code ticketDbId} stopped meaning "payment started" the day the draft
 * became early (first article): the id now lives for the whole sale, the
 * flag marks the payment phase alone. Payment entries keep their 1-based
 * registration order; restart recovery and the completion modal rebuild
 * from them, and {@code transactionComplete} + {@code lastChangeAmount}
 * are what the completion modal and the customer thank-you screen read —
 * both survive a restart through the recovery path.
 */
public class PaymentState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Number of payment lines displayable without a scrollbar (full list). */
    private static final int PAGE_SIZE = 5;

    /** The payments registered so far. */
    public List<PaymentEntry> payments = new ArrayList<>();

    /** The sum of registered payment amounts. */
    public BigDecimal paidAmount = BigDecimal.ZERO;

    /** True once the remaining due reaches zero. */
    public boolean transactionComplete = false;

    /** LC-07-16-01: prevents duplicate drawer orders for a completed sale. */
    public boolean settlementDrawerOpened = false;

    /** The database id of the draft ticket, or null before creation. */
    public Long ticketDbId = null;

    /**
     * Amount awaiting the virtual payment terminal's decision, or null when
     * no card request is pending (phase 6: the simulator accepts or refuses).
     */
    public BigDecimal pendingCardAmount = null;

    /**
     * The terminal callback of the card transaction pending, or null: the
     * only callback whose decision may settle it, consumed by the decision —
     * a decision arriving for a transaction already settled, cancelled or
     * replaced is ignored. Never serialized: it lives with the exchange.
     */
    public transient com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback pendingCardCallback = null;

    /**
     * The gift card whose balance the payment hub just stated, waiting for the
     * operator to choose the amount it pays (LC-07-11-01), or null.
     */
    public PendingGiftCard pendingGiftCard = null;

    /**
     * A gift card read on the payment hub, its balance shown to the operator
     * before any payment is asked for (LC-07-11-01).
     */
    public static class PendingGiftCard implements Serializable {

        /** The card's unique code. */
        public final String cardNumber;

        /** What the card carries, as the hub stated it. */
        public final BigDecimal balance;

        /** The provider of the card, as the hub named it, or null. */
        public final String provider;

        /** The amount proposed: the balance, or what is left to pay when less. */
        public final BigDecimal suggested;

        /**
         * Holds a card read on the hub.
         *
         * @param cardNumber the card's unique code
         * @param balance what the card carries
         * @param provider the provider, or null
         * @param suggested the amount proposed
         */
        public PendingGiftCard(String cardNumber, BigDecimal balance, String provider,
                               BigDecimal suggested) {
            this.cardNumber = cardNumber;
            this.balance = balance;
            this.provider = provider;
            this.suggested = suggested;
        }

        /**
         * The card's code as the screen shows it, last four characters only.
         *
         * @return the masked code
         */
        public String getMasked() {
            return cardNumber.length() <= 4 ? cardNumber : "****" + cardNumber.substring(cardNumber.length() - 4);
        }

        /**
         * The balance as the screen writes it.
         *
         * @return the balance, two decimals
         */
        public String getBalanceFormatted() {
            return String.format("%.2f", balance);
        }

        /**
         * The proposed amount as the keypad takes it.
         *
         * @return the amount, two decimals
         */
        public String getSuggestedNumpad() {
            return String.format("%.2f", suggested);
        }
    }

    /**
     * Authorization number returned by the terminal for the card payment being
     * registered (BO-04-01-08), or null when none (degraded acceptance reaches
     * no monetique). Carried from the accept callback to the persisted
     * {@code CardPayment}; ephemeral, cleared once the entry is built.
     */
    public String pendingCardAuthNumber = null;

    /**
     * True when the card payment being registered was accepted in degraded mode
     * (BO-04-01-47/49). Carried from the accept callback to the persisted
     * {@code CardPayment}; ephemeral, cleared once the entry is built.
     */
    public boolean pendingCardDegraded = false;

    /**
     * The real card entry mode the terminal reported for the card payment being
     * registered (EXP-54): {@code "SANS_CONTACT"} or {@code "CODE"}, or null.
     * Carried from the accept callback to the persisted {@code CardPayment};
     * ephemeral, cleared once the entry is built.
     */
    public String pendingCardEntryMode = null;

    /** LC-07-08-01: live vendor display, never a generated offline approval. */
    public volatile String terminalLine1 = "";
    /** LC-07-08-01: second live terminal display line. */
    public volatile String terminalLine2 = "";

    /** LC-07-05-06: front printing owns the cheque path until it completes. */
    public boolean chequeFrontPrinting;

    /** LC-07-06-02: remembers which terminal flow a supervisor is authorizing before it starts. */
    public boolean tenderHeldCombined;

    /** LC-07-07-03: selected server waiting for the payment persistence boundary. */
    public String pendingMoneticServer;

    /**
     * True when a card payment of this transaction asked for the customer's
     * handwritten signature (LC-08-03-11): the conditional-printing rule adds
     * the card receipt back whatever the cashier chose. Held in memory for the
     * transaction only — the decision is taken before the state is cleared, so
     * no column carries it.
     */
    public boolean cardSignatureRequired = false;

    /**
     * The cashier's end-of-transaction printing choice (LC-08-03-01), or null
     * while none has been made. Only meaningful when conditional printing is
     * activated on the back office.
     */
    public com.intermarche.pos.ui.hardware.PrintChoice printChoice = null;

    /**
     * True once the printing choice has been applied and its documents sent to
     * the printer: the finalization then adds nothing, so a cashier who picked
     * "aucun ticket" is not handed a ticket by the closing step.
     */
    public boolean printApplied = false;

    /**
     * Amount awaiting the cheque reader, or null when no cheque is pending.
     * <p>
     * The cheque is read BEFORE the payment is registered: a cheque the reader
     * refuses must not leave a settled line on the ticket.
     */
    public BigDecimal pendingChequeAmount = null;

    /** LC-07-10-05: reserved loyalty amount awaiting a handwritten signature. */
    public BigDecimal pendingFidelitySignatureAmount = null;

    /**
     * The amount due on the cash changer while a deposit runs, or null when none
     * does (LC-07-15-01). Like the card, the deposit is registered only once the
     * machine has closed it: coins in the machine are not yet a settlement.
     */
    public BigDecimal cashChangerDue = null;

    /** What the customer has fed into the cash changer so far (LC-07-15-02). */
    public BigDecimal cashChangerInserted = null;

    /**
     * The outcome of a deposit the machine closed, deposited by the following
     * thread for the next request to register, or null.
     */
    public com.intermarche.pos.ui.hardware.CashChangerService.Deposit cashChangerOutcome = null;

    /** The failure of a deposit, deposited by the following thread, or null. */
    public String cashChangerFailure = null;

    /**
     * The amount due on the cash changer, French comma (LC-07-15-02).
     *
     * @return the formatted amount, empty when no deposit runs
     */
    public String getCashChangerDueFormatted() {
        return formatAmount(cashChangerDue);
    }

    /**
     * What the customer has fed into the cash changer, French comma
     * (LC-07-15-02).
     *
     * @return the formatted amount, empty when no deposit runs
     */
    public String getCashChangerInsertedFormatted() {
        return formatAmount(cashChangerInserted);
    }

    /**
     * Formats an amount with two decimals and a French comma.
     *
     * @param amount the amount, possibly null
     * @return the formatted amount, empty when null
     */
    private static String formatAmount(BigDecimal amount) {
        return amount == null ? ""
                : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /**
     * The magnetic line of the cheque being registered, or null when none was read.
     * <p>
     * Kept in memory only for now: it is shown to the cashier and journalled, but
     * the persisted {@code ChequePayment} carries no column for it yet.
     */
    public String pendingChequeLine = null;

    /**
     * The administered tender the next settlement of its nature is taken as
     * (BO-03-02-01), or null: set by the key of a tender the store added, and
     * consumed by the settlement it names.
     */
    public String selectedTenderCode = null;

    /**
     * True while the held settlement is a cheque waiting for its AUTHORIZATION
     * before it is read (BO-03-02-24): the supervisor's answer then starts the
     * reading instead of registering a cheque nobody read.
     */
    public boolean chequeHeldBeforeReading = false;

    /** The account number keyed for the cheque being taken (BO-03-02-24), or null. */
    public String pendingChequeAccount = null;

    /** The cheque-guarantee authorization of the cheque being taken (BO-03-02-24), or null. */
    public String pendingChequeGuarantee = null;

    /** The cheque-guarantee provider that authorized the cheque being taken (BO-03-02-24), or null. */
    public String pendingChequeGuaranteeProvider = null;

    /**
     * The change of the last foreign-currency settlement, given back IN that
     * currency (BO-03-02-39), formatted with its unit, or null when the change
     * was given in euros.
     */
    public String lastChangeForeign = null;

    /**
     * Outcome of the remote valuation at payment entry (phase 7): LOCAL
     * (engine not configured), ENGINE (valued), DEGRADED (engine failed,
     * catalog prices apply). Null before payment entry.
     */
    public String valuationStatus = null;

    /**
     * Raw JSON of the engine's valuation when {@code valuationStatus} is
     * ENGINE — held for the lot 2 reconciliation, null otherwise.
     */
    public String valuationJson = null;

    /**
     * The engine's own total including tax, kept for log comparison against
     * the register's authoritative total. Null unless valued.
     */
    public BigDecimal valuationEngineTotal = null;

    /**
     * Total adjustment applied by the reconciliation (valued minus local
     * over the covered lines, advantages allocated), tax included. Negative
     * when the engine grants advantages; null before payment entry, zero
     * when valued without effect.
     */
    public BigDecimal valuationAdjustment = null;

    /**
     * Meal-voucher eligible base of the BASKET (tax included) from the
     * engine's MEAL_VOUCHER advantage, rewritten verbatim at each valuation.
     * Never decremented here: the allowance left for a new meal ticket is
     * derived at payment time as this base (capped by the threshold) minus
     * the meal tickets already registered. Null when the engine emitted none
     * (no cap applies).
     */
    public BigDecimal valuationMealEligible = null;

    /**
     * Meal-voucher threshold (legal cap per payment context) from the
     * engine, or null.
     */
    public BigDecimal valuationMealThreshold = null;

    /**
     * Upsell suggestions of the engine, ready to display (product labels
     * resolved). Never null, empty when none.
     */
    public java.util.ArrayList<String> valuationUpsells = new java.util.ArrayList<>();

    /**
     * Offer messages of the engine to show the seller during the scan and print
     * on the ticket (GB-01-07-05/06), rewritten at each valuation. Never null,
     * empty when the engine emitted none — a message carries no money, so the
     * list changes nothing about the totals.
     */
    public java.util.List<com.intermarche.pos.ui.valuation.ValuationService.EngineMessage>
            valuationMessages = new java.util.ArrayList<>();

    /**
     * True while the payment screen drives the transaction. Needed since the
     * early draft (phase 0 lot 2): {@link #ticketDbId} is set from the first
     * article, so it no longer indicates an active payment.
     */
    public boolean paymentInProgress = false;

    /**
     * True while the RESTO FLASH zone of the payment screen is open (EXP-67):
     * the next code the scanner reads is the customer's mobile token, delivered
     * to the Resto Flash confirmation instead of the sale's scan chain. Armed by
     * the screen when the zone opens, consumed by one reading, and dropped at
     * every page navigation ({@link RestoFlashScanFilter}): a page drawn afresh
     * has every input zone closed.
     */
    public boolean restoFlashScanArmed = false;

    /** LC-07-01-02/-05: the operator requested a return with settlements present. */
    public boolean returnCancellationOffered = false;

    /** Current page of the payment history (previous / next). */
    public int currentPage = 0;

    /** Change given back on the last cash payment, or null. Kept for the end-of-transaction modal. */
    public BigDecimal lastChangeAmount = null;

    /** Current input mode of the payment numpad, or null. */
    public String inputMode = null;

    /** Current content of the payment numpad display. */
    public String temporaryInput = "0,00";

    /** True while the voucher panel is open (manual entry, or Catalina recognized at scan). */
    public boolean voucherPanelOpen = false;

    /** The technical code of the selected coupon type, or null. */
    public String pendingVoucherTypeCode = null;

    /** The display label of the selected coupon type, or null. */
    public String pendingVoucherLabel = null;

    /** The voucher number being entered, or null. */
    public String pendingVoucherNumber = null;

    /** True when the cashier must type the voucher amount. */
    public boolean pendingVoucherNeedsAmount = false;

    /**
     * True while the voucher being applied was read as a GS1 payload, so its
     * reading is journalled as such (BO-11-01-18).
     */
    public boolean pendingVoucherGs1 = false;

    /** The current voucher entry error, or null. */
    public String voucherError = null;

    // --------------------------------------------------
    // Administered tender rules (BO-03-02-10/12/13/14/16)
    // --------------------------------------------------

    /**
     * The settlement key held back for a supervisor's authorization, or null
     * while none is (BO-03-02-10/12/13/14, control level "bloquant superviseur").
     *
     * <p>Held rather than refused, and held HERE rather than replayed from the
     * screen: the supervisor authorizes the settlement the cashier actually
     * asked for, not whatever the pad happens to show when they arrive.
     */
    public String tenderHeldMethod = null;

    /** The label the held settlement shows on the customer display, or null. */
    public String tenderHeldLabel = null;

    /** The amount handed over for the held settlement, or null when none is held. */
    public BigDecimal tenderHeldAmount = null;

    /** The rule the held settlement broke, shown in the authorization panel. */
    public String tenderHeldMessage = null;

    /**
     * The settlement key a supervisor has just authorized, or null.
     *
     * <p>Consumed by the very next registration of that tender and cleared at
     * once: an authorization that survived its settlement would apply to
     * whatever the cashier typed next.
     */
    public String tenderOverride = null;

    /**
     * The change this sale owes as a CREDIT NOTE rather than in cash
     * (BO-03-02-16), accumulated over the sale.
     *
     * <p>Accumulated and issued at the fiscal moment only, exactly like the gift
     * cards sold on the ticket: an abandoned sale never creates value.
     */
    public BigDecimal changeAsCreditNote = BigDecimal.ZERO;

    /**
     * Tells whether a settlement is waiting for a supervisor to allow an
     * administered bound to be passed.
     *
     * @return true while a settlement is held back
     */
    public boolean isTenderAuthorizationPending() {
        return tenderHeldMethod != null;
    }

    /**
     * Abandons the settlement held back for a supervisor's authorization.
     *
     * <p>The granted override goes with it, deliberately: an authorization kept
     * past the settlement it was given for is an authorization for the next one.
     */
    public void clearTenderHold() {
        tenderHeldMethod = null;
        tenderHeldLabel = null;
        tenderHeldAmount = null;
        tenderHeldMessage = null;
        tenderOverride = null;
        tenderHeldCombined = false;
        chequeHeldBeforeReading = false;
    }

    /**
     * Registers a plain payment (card, cheque, meal ticket, fidelity...).
     *
     * @param method the payment method key
     * @param amount the paid amount
     */
    public void addPayment(String method, BigDecimal amount) {
        payments.add(new PaymentEntry(method, amount));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers the automatic recovery of a deposit already collected on an
     * order (LC-06-03-07), carrying the deposit's per-rate VAT detail and the
     * order code so both reach the persisted {@code DepositPayment} row and
     * the printed receipt (LC-06-03-08).
     *
     * @param amount the deposit recovered on the ticket
     * @param depositVatDetail the compact per-rate VAT detail of the deposit,
     *        or null when the recalled document carried none
     * @param depositOrderCode the code of the order the deposit was taken on,
     *        or null
     */
    public void addDepositPayment(BigDecimal amount, String depositVatDetail,
                                  String depositOrderCode) {
        payments.add(new PaymentEntry("ACOMPTE", amount, depositVatDetail, depositOrderCode));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a card payment carrying the monetique traces the terminal
     * returned (authorization number, degraded-mode indicator), so they reach
     * the persisted {@code CardPayment} (BO-04-01-08/47/49).
     *
     * @param amount the paid amount
     * @param authorizationNumber the terminal authorization number, or null
     * @param degradedMode true when accepted in degraded mode
     */
    public void addCardPayment(BigDecimal amount, String authorizationNumber, boolean degradedMode) {
        addCardPayment(amount, authorizationNumber, degradedMode, null);
    }

    /**
     * Registers a card payment carrying the terminal traces AND the real entry
     * mode (EXP-54): contactless vs chip-and-PIN, traced onto the entry so it
     * reaches the persisted {@code CardPayment}.
     *
     * @param amount the paid amount
     * @param authorizationNumber the terminal authorization number, or null
     * @param degradedMode true when accepted in degraded mode
     * @param cardEntryMode the real card entry mode, or null when undiscriminated
     */
    public void addCardPayment(BigDecimal amount, String authorizationNumber, boolean degradedMode,
            String cardEntryMode) {
        PaymentEntry entry = new PaymentEntry("CARD", amount, degradedMode, authorizationNumber);
        entry.cardEntryMode = cardEntryMode;
        payments.add(entry);
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a cheque payment carrying the magnetic line the reader read, so it
     * reaches the persisted {@code ChequePayment}.
     *
     * @param amount the paid amount
     * @param magneticLine the CMC7 line as read, or null when the cheque was not read
     */
    public void addChequePayment(BigDecimal amount, String magneticLine) {
        PaymentEntry entry = new PaymentEntry("CHEQUE", amount);
        entry.magneticLine = magneticLine;
        payments.add(entry);
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a cash payment with the tendered amount.
     * <p>
     * Important: {@link #lastChangeAmount} is NOT cleared here, it is needed
     * by the end-of-transaction modal.
     *
     * @param amount the amount applied to the ticket
     * @param tenderedAmount the amount handed over by the customer
     */
    public void addCashPayment(BigDecimal amount, BigDecimal tenderedAmount) {
        payments.add(new PaymentEntry("CASH", amount, tenderedAmount));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a customer-credit settlement, carrying the debtor.
     *
     * <p>The account rides ON THE ENTRY and not on the screen state: the entry is
     * what the persistence turns into a payment row, and a debtor kept beside it
     * would be lost the moment a second credit line is registered for another
     * account on the same sale.
     *
     * @param amount the amount charged to the account
     * @param accountNumber the account number the debt is charged to
     * @param accountName the account name as it stands at sale time
     * @param overLimit true when a supervisor authorized it over the ceiling
     * @param tenderKey the administered tender key the settlement is registered
     *        under (BO-10-04-09)
     */
    public void addCreditPayment(BigDecimal amount, String accountNumber, String accountName,
            boolean overLimit, String tenderKey) {
        PaymentEntry entry = new PaymentEntry(tenderKey, amount);
        entry.creditAccountNumber = accountNumber;
        entry.creditAccountName = accountName;
        entry.creditOverLimit = overLimit;
        payments.add(entry);
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Adds a voucher payment carrying its type label and optional number.
     *
     * @param label the voucher type label shown to the cashier
     * @param number the voucher number, or null when there is none
     * @param amount the paid amount
     */
    public void addVoucherPayment(String label, String number, BigDecimal amount) {
        payments.add(new PaymentEntry(label, amount, number, true));
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Clears the registered payments and resets the history pagination.
     */
    // --------------------------------------------------
    // Backup monetics (LC-07-07-06/09)
    // --------------------------------------------------

    /** True while the backup-monetics panel is open over the payment screen. */
    public boolean backupPanelOpen = false;

    /**
     * The request this till emitted, or null while none is pending. It is what an
     * answer is matched against, so it must outlive the screen refresh — the payment
     * page reloads itself on every version bump.
     */
    public transient BackupPaymentTicket.Request backupRequest = null;

    /** The request rendered as an SVG QR code, or null while none is pending. */
    public String backupRequestSvg = null;

    /** The refusal shown inside the backup panel, or null. */
    public String backupError = null;

    /** True once the operator asked to key the outcome in instead of scanning it. */
    public boolean backupManualEntry = false;

    /**
     * Closes the backup-monetics panel and forgets the pending request.
     *
     * <p>The request goes with it, deliberately: an answer arriving after the panel
     * was closed answers a question this till is no longer asking.
     */
    public void clearBackupPanel() {
        backupPanelOpen = false;
        backupRequest = null;
        backupRequestSvg = null;
        backupError = null;
        backupManualEntry = false;
    }

    // --------------------------------------------------
    // Foreign currency (LC-07-14)
    // --------------------------------------------------

    /** True while the foreign-currency panel is open over the payment screen. */
    public boolean currencyPanelOpen = false;

    /** The currency the operator selected, or null while none is. */
    public com.intermarche.pos.domain.payment.Currency selectedCurrency = null;

    /** The refusal shown inside the currency panel, or null. */
    public String currencyError = null;

    /**
     * Registers a settlement taken on a backup-monetics terminal.
     *
     * @param amount the amount that terminal accepted
     * @param methodLabel the scheme it reported
     * @param transactionNumber the sale the two codes were matched on
     * @param manual true when the outcome was keyed in rather than scanned
     */
    public void addBackupPayment(BigDecimal amount, String methodLabel, String transactionNumber,
            boolean manual) {
        PaymentEntry entry = new PaymentEntry("SECOURS", amount);
        entry.backupMethodLabel = methodLabel;
        entry.backupTransaction = transactionNumber;
        entry.backupManual = manual;
        payments.add(entry);
        paidAmount = paidAmount.add(amount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Registers a settlement handed over in a foreign currency, carrying what the
     * euro figure does not say.
     *
     * @param euroAmount the euro value credited to the sale
     * @param code the ISO code of the currency handed over
     * @param foreignAmount the amount handed over, in that currency
     * @param rate the euros-for-one-unit rate applied
     */
    public void addCurrencyPayment(BigDecimal euroAmount, String code, BigDecimal foreignAmount,
            BigDecimal rate) {
        PaymentEntry entry = new PaymentEntry("DEVISE", euroAmount);
        entry.currencyCode = code;
        entry.currencyAmount = foreignAmount;
        entry.currencyRate = rate;
        payments.add(entry);
        paidAmount = paidAmount.add(euroAmount);
        goToLastPage();
        clearTemporaryInputs();
    }

    /**
     * Closes the foreign-currency panel and forgets what it held.
     */
    public void clearCurrencyPanel() {
        currencyPanelOpen = false;
        selectedCurrency = null;
        currencyError = null;
    }

    // --------------------------------------------------
    // Customer credit (LC-07-09)
    // --------------------------------------------------

    /** How many matching accounts the credit panel shows at once. */
    public static final int CREDIT_ROWS = 5;

    /** True while the customer-credit panel is open over the payment screen. */
    public boolean creditPanelOpen = false;

    /** What the operator typed in the account search box. */
    public String creditSearch = "";

    /** The accounts matching the last search, empty before any. */
    public List<com.intermarche.pos.domain.payment.AccountCustomer> creditCustomers = new ArrayList<>();

    /** True once a search ran, so an empty list can be told from "not searched yet". */
    public boolean creditSearched = false;

    /** The account the operator confirmed, or null while none is named. */
    public com.intermarche.pos.domain.payment.AccountCustomer creditCustomer = null;

    /** The refusal or warning shown inside the credit panel, or null. */
    public String creditError = null;

    /**
     * The amount held back because it takes the account over its ceiling, awaiting
     * a supervisor ({@code LC-07-09-04}), or null when nothing is pending.
     */
    public BigDecimal creditPendingAmount = null;

    /**
     * Returns the accounts offered by the credit panel, capped to what it shows.
     *
     * @return at most {@link #CREDIT_ROWS} accounts
     */
    public List<com.intermarche.pos.domain.payment.AccountCustomer> getVisibleCreditCustomers() {
        return creditCustomers.size() <= CREDIT_ROWS
                ? creditCustomers
                : creditCustomers.subList(0, CREDIT_ROWS);
    }

    /**
     * Tells whether the credit search ran and matched nothing.
     *
     * @return true when the operator searched and no account matched
     */
    public boolean isCreditSearchWithoutMatch() {
        return creditSearched && creditCustomers.isEmpty();
    }

    /**
     * Tells whether a settlement is waiting for a supervisor to allow the ceiling
     * to be exceeded.
     *
     * @return true while an over-ceiling amount is held back
     */
    public boolean isCreditOverLimitPending() {
        return creditPendingAmount != null;
    }

    /**
     * Closes the customer-credit panel and forgets everything it held.
     *
     * <p>The pending over-ceiling amount is cleared with the rest, deliberately: an
     * authorization that survived the panel being closed would apply to whatever
     * the next operator typed.
     */
    public void clearCreditPanel() {
        creditPanelOpen = false;
        creditSearch = "";
        creditCustomers = new ArrayList<>();
        creditSearched = false;
        creditCustomer = null;
        creditError = null;
        creditPendingAmount = null;
    }

    /** The active fidelity lease id (imfid burn reservation), or null. */
    public Long fidReservationId = null;

    /** The lease expiry instant, for the half-life renewal. */
    public java.time.LocalDateTime fidLeaseExpiresAt = null;

    /** The lease duration in seconds, captured at grant (renewal timing). */
    public long fidLeaseSeconds = 0L;

    /**
     * The magnetic line of a FINISHED cheque reading, waiting to be registered,
     * or null.
     * <p>
     * The reading ends on the reader's own follower thread, which must not write
     * to the database: the register is a single-writer machine and the request
     * thread rewrites the same draft row on every revaluation, so two writers
     * race and the loser's settlement is silently lost. The follower therefore
     * DEPOSITS its outcome here and the next request registers it. Volatile
     * because the two threads are the only readers and writers of this field.
     */
    public volatile String chequeReadLine = null;

    /**
     * The operator-facing message of a FAILED cheque reading, waiting to be
     * shown, or null. Deposited by the follower thread for the same reason as
     * {@link #chequeReadLine}.
     */
    public volatile String chequeReadFailure = null;

    public void clearPayments() {
        payments.clear();
        settlementDrawerOpened = false;
        pendingFidelitySignatureAmount = null;
        returnCancellationOffered = false;
        paidAmount = BigDecimal.ZERO;
        currentPage = 0;
        clearTemporaryInputs();
    }

    /**
     * Removes a SINGLE registered payment without touching the others (EXP-63):
     * the entry at the index leaves the list and its amount is taken back off the
     * paid total. The other payments and the payment-in-progress state stand.
     *
     * @param index the 0-based index of the payment to remove
     * @return true when the index was valid and the payment was removed
     */
    public boolean removePayment(int index) {
        if (index < 0 || index >= payments.size()) {
            return false;
        }
        PaymentEntry removed = payments.remove(index);
        paidAmount = paidAmount.subtract(removed.amount);
        goToLastPage();
        return true;
    }

    /**
     * Resets the whole payment state for a new transaction.
     */
    public void reset() {
        clearPayments();
        fidReservationId = null;
        fidLeaseExpiresAt = null;
        fidLeaseSeconds = 0L;
        transactionComplete = false;
        ticketDbId = null;
        paymentInProgress = false;
        restoFlashScanArmed = false;
        pendingCardAmount = null;
        pendingCardCallback = null;
        pendingGiftCard = null;
        pendingChequeAmount = null;
        cashChangerDue = null;
        cashChangerInserted = null;
        cashChangerOutcome = null;
        cashChangerFailure = null;
        pendingChequeLine = null;
        selectedTenderCode = null;
        pendingChequeAccount = null;
        pendingChequeGuarantee = null;
        pendingChequeGuaranteeProvider = null;
        lastChangeForeign = null;
        chequeReadLine = null;
        chequeReadFailure = null;
        pendingCardAuthNumber = null;
        pendingCardDegraded = false;
        pendingCardEntryMode = null;
        pendingMoneticServer = null;
        cardSignatureRequired = false;
        printChoice = null;
        printApplied = false;
        valuationStatus = null;
        valuationJson = null;
        valuationEngineTotal = null;
        valuationAdjustment = null;
        valuationMealEligible = null;
        valuationMealThreshold = null;
        valuationUpsells = new java.util.ArrayList<>();
        lastChangeAmount = null; // Cleared only here (full new transaction)
        changeAsCreditNote = BigDecimal.ZERO;
        clearTenderHold();
        clearPendingVoucher();
    }

    /**
     * Clears any in-progress voucher entry and its error message.
     */
    public void clearPendingVoucher() {
        voucherPanelOpen = false;
        pendingVoucherTypeCode = null;
        pendingVoucherLabel = null;
        pendingVoucherNumber = null;
        pendingVoucherNeedsAmount = false;
        pendingVoucherGs1 = false;
        voucherError = null;
    }

    /**
     * Resets the payment numpad input mode and display.
     */
    private void clearTemporaryInputs() {
        this.inputMode = null;
        this.temporaryInput = "0,00";
    }

    // --------------------------------------------------
    // Payment history pagination
    // --------------------------------------------------

    /**
     * Returns the total number of pages of payments (at least one).
     *
     * @return the page count
     */
    public int getTotalPages() {
        if (payments.isEmpty()) return 1;
        return (int) Math.ceil((double) payments.size() / PAGE_SIZE);
    }

    /**
     * Moves the view to the last page so the most recent payments are shown.
     */
    public void goToLastPage() {
        currentPage = getTotalPages() - 1;
    }


    /**
     * Indicates whether a previous page of payments exists.
     *
     * @return true if not on the first page
     */
    public boolean isHasPreviousPage() {
        return currentPage > 0;
    }

    /**
     * Indicates whether a next page of payments exists.
     *
     * @return true if more payments follow the current page
     */
    public boolean isHasNextPage() {
        return (currentPage + 1) * PAGE_SIZE < payments.size();
    }


    /**
     * Returns the 1-based current page number for display.
     *
     * @return the current page number
     */
    public int getCurrentPageDisplay() {
        return currentPage + 1;
    }

    /**
     * Indicates whether the payment at the given index belongs to the current page.
     *
     * @param index the index of the payment in the full list
     * @return true if the payment is on the current page
     */
    public boolean isOnCurrentPage(int index) {
        int fromIndex = currentPage * PAGE_SIZE;
        int toIndex = fromIndex + PAGE_SIZE;
        return index >= fromIndex && index < toIndex;
    }

    /**
     * Indicates whether the payment at the given index is among the two most recent.
     *
     * @param index the index of the payment in the full list
     * @return true if the payment is one of the last two
     */
    public boolean isAmongLastTwo(int index) {
        return index >= payments.size() - 2;
    }

    /**
     * Moves to the next page of payments if one exists.
     */
    public void nextPage() {
        if (isHasNextPage()) currentPage++;
    }

    /**
     * Moves to the previous page of payments if one exists.
     */
    public void prevPage() {
        if (isHasPreviousPage()) currentPage--;
    }

    /**
     * A single registered payment.
     */
    public static class PaymentEntry implements Serializable {
        private static final long serialVersionUID = 1L;

        /** The payment method key, or the voucher type label for voucher entries. */
        public String method;

        /** The amount applied to the ticket. */
        public BigDecimal amount;

        /** The tendered amount for cash payments, or null. */
        public BigDecimal tenderedAmount;

        /** The voucher number, or null. */
        public String voucherNumber;

        /** True when this entry is a voucher payment. */
        public boolean voucher;

        /** The card authorization number (BO-04-01-08), or null. */
        public String authorizationNumber;

        /**
         * The payment hub's identifier of a gift-card payment (LC-07-11-01), or
         * null on any other settlement: the reference its reversal gives back.
         */
        public String hubReference;

        /** The gift card a hub settlement was paid with (LC-07-11-01), or null. */
        public String hubCardNumber;

        /** The provider of that card, as the hub named it (LC-07-11-03), or null. */
        public String hubProvider;

        /** The provider's own code for the payment (LC-07-11-03), or null. */
        public String hubApproval;

        /** What the card carried before the payment (LC-07-11-03), or null. */
        public BigDecimal hubBalanceBefore;

        /** What the card carries after the payment (LC-07-11-03), or null. */
        public BigDecimal hubBalanceAfter;

        /** Set once the hub gave the payment back (LC-07-11-01); never persisted. */
        public transient boolean hubReversed;

        /** True when the card payment was accepted in degraded mode (BO-04-01-47/49). */
        public boolean degradedMode;

        /** The real card entry mode (EXP-54): SANS_CONTACT, CODE, or null. */
        public String cardEntryMode;

        /** LC-07-07-03: server returned for this precise settlement. */
        public String moneticServer;

        /** LC-07-04-10/-11: ephemeral WCL customer frame, never sent to store synchronization. */
        public transient String customerReceiptFrame;

        /** LC-07-04-11: merchant frame is printed only and must never be archived. */
        public transient String merchantReceiptFrame;

        /** LC-07-17-05: additional terminal receipts live only until this sale clears. */
        public transient java.util.List<String> additionalReceiptFrames;

        /** The CMC7 magnetic line read off the cheque, or null. */
        public String magneticLine;

        /** The account number keyed for the cheque (BO-03-02-24), or null. */
        public String chequeAccountNumber;

        /** The cheque-guarantee authorization number (BO-03-02-24), or null. */
        public String chequeGuaranteeAuthorization;

        /** The cheque-guarantee provider that authorized the cheque (BO-03-02-24), or null. */
        public String chequeGuaranteeProvider;

        /**
         * The administered tender this settlement was taken as (BO-03-02-01/-44),
         * or null when it was taken as its nature itself.
         */
        public String tenderCode;

        /** The scheme a backup-monetics settlement reported (LC-07-07-08), or null. */
        public String backupMethodLabel;

        /** The transaction number the two backup QR codes were matched on, or null. */
        public String backupTransaction;

        /** True when a backup-monetics outcome was keyed in rather than scanned. */
        public boolean backupManual;

        /**
         * True on the cash given back by a card CASHBACK (LC-07-04-07/08): a
         * negative cash settlement, so the drawer accounts for the notes that
         * left it, named as a cashback wherever it is shown.
         */
        public boolean cashback;

        /**
         * True on cash taken by the cash changer (LC-07-15-03): registered as
         * cash like any other, but the notes are in the machine, not in the
         * drawer — the drawer stays shut and the amount is transferred to the
         * safe when the sale closes (LC-07-15-04).
         */
        public boolean cashChanger;

        /** The ISO code of the currency handed over (LC-07-14), or null. */
        public String currencyCode;

        /** The amount handed over in that currency, or null. */
        public BigDecimal currencyAmount;

        /** The euros-for-one-unit rate applied, or null. */
        public BigDecimal currencyRate;

        /** The account number charged by a customer-credit settlement, or null. */
        public String creditAccountNumber;

        /** The account name as it stood at sale time, or null. */
        public String creditAccountName;

        /** True when a supervisor authorized this settlement over the ceiling. */
        public boolean creditOverLimit;

        /**
         * The per-rate VAT detail of the deposit this entry recovers, in the
         * compact {@code DepositVat} form, or null on every other entry
         * (LC-06-03-05/-08): it rides from the recall of the order to the
         * persisted {@code DepositPayment} row, so the final receipt restates
         * the ventilation computed at collection time.
         */
        public String depositVatDetail;

        /**
         * The code of the order whose deposit this entry recovers, or null on
         * every other entry (LC-06-03-07).
         */
        public String depositOrderCode;

        /**
         * Creates a plain payment entry.
         *
         * @param method the payment method key
         * @param amount the paid amount
         */
        public PaymentEntry(String method, BigDecimal amount) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
        }

        /**
         * Creates a cash payment entry with the tendered amount.
         *
         * @param method the payment method key (CASH)
         * @param amount the amount applied to the ticket
         * @param tenderedAmount the amount handed over by the customer
         */
        public PaymentEntry(String method, BigDecimal amount, BigDecimal tenderedAmount) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = tenderedAmount;
        }

        /**
         * Creates a voucher payment entry holding its display label and number.
         *
         * @param method the display label of the voucher type
         * @param amount the paid amount
         * @param voucherNumber the voucher number, or null when there is none
         * @param voucher always true; marks the entry as a voucher payment
         */
        public PaymentEntry(String method, BigDecimal amount, String voucherNumber, boolean voucher) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
            this.voucherNumber = voucherNumber;
            this.voucher = voucher;
        }

        /**
         * Creates a card payment entry carrying the monetique traces the
         * terminal returned (BO-04-01-08/47/49).
         *
         * @param method the payment method key (CARD)
         * @param amount the paid amount
         * @param degradedMode true when accepted in degraded mode
         * @param authorizationNumber the terminal authorization number, or null
         */
        public PaymentEntry(String method, BigDecimal amount, boolean degradedMode, String authorizationNumber) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
            this.degradedMode = degradedMode;
            this.authorizationNumber = authorizationNumber;
        }

        /**
         * Creates a deposit-recovery entry carrying the deposit's per-rate
         * VAT detail and the order it comes from (LC-06-03-07).
         *
         * @param method the payment method key (ACOMPTE)
         * @param amount the deposit recovered on the ticket
         * @param depositVatDetail the compact per-rate VAT detail of the
         *        deposit, or null when the recalled document carried none
         * @param depositOrderCode the code of the order the deposit was
         *        taken on, or null
         */
        public PaymentEntry(String method, BigDecimal amount, String depositVatDetail,
                            String depositOrderCode) {
            this.method = method;
            this.amount = amount;
            this.tenderedAmount = null;
            this.depositVatDetail = depositVatDetail;
            this.depositOrderCode = depositOrderCode;
        }

        /**
         * Indicates whether this entry recovers a deposit already collected
         * on an order (LC-06-03-07).
         *
         * @return true when the entry is a deposit recovery
         */
        public boolean isDepositRecovery() {
            return "ACOMPTE".equals(method);
        }

        /**
         * Returns the paid amount formatted for display (2 decimals, French comma).
         *
         * @return the formatted amount
         */
        public String getFormattedAmount() {
            return String.format("%.2f", amount.setScale(2, RoundingMode.HALF_UP)).replace(".", ",");
        }


        /**
         * Indicates whether this entry is a voucher payment.
         *
         * @return true if the entry was created as a voucher payment
         */
        public boolean isVoucher() {
            return voucher;
        }

        /**
         * Returns the voucher number for display, or an empty string when there is none.
         *
         * @return the voucher number, or an empty string
         */
        public String getVoucherNumber() {
            return voucherNumber != null ? voucherNumber : "";
        }
    }
}
