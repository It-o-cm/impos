package com.intermarche.pos.service;

import com.intermarche.pos.domain.barcode.AlertLevel;
import com.intermarche.pos.domain.barcode.CouponControl;
import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.barcode.CouponScan;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.util.DateTimeProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies to a scanned code the controls its range administers
 * (BO-03-06-27/28/29/39/46/47/49/67).
 *
 * <p>This is what makes the administered positions MATTER. A range can declare
 * that characters 3 to 8 hold an expiry date and nothing changes at the till;
 * the date starts being read the day the shop administers
 * {@link CouponControl.Kind#EXPIRED} on that range. Each control here reads one
 * position and turns it into a decision the register acts on.
 *
 * <p>Silence is the default throughout: a control the shop did not administer
 * is {@link AlertLevel#NONE} and is not evaluated at all, so a register whose
 * ranges carry no control behaves exactly as it did before these checks
 * existed.
 *
 * <p>The running ticket total is passed in rather than looked up, so this stays
 * a rule engine and not a screen service. The register's own point of sale
 * number is resolved here and LAZILY: a range administering no shop control
 * never reads the store row at all.
 */
@ApplicationScoped
public class CouponCheckService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CouponCheckService.class);

    /**
     * This register's identifier, which is what says WHICH checkout island it
     * stands on (BO-03-06-07).
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /**
     * Runs every control the range administers against a scanned code.
     *
     * @param type the range that recognised the code, or null
     * @param code the scanned code, or null
     * @param ticketTotal the running ticket total, or null when there is no ticket
     * @return the findings, in control order, empty when the code passes
     */
    public List<Finding> check(CouponType type, String code, BigDecimal ticketTotal) {
        return check(type, code, ticketTotal, null);
    }

    /**
     * Runs every control the range administers against a scanned code, the
     * loyalty card of the sale included (BO-03-06-40).
     *
     * @param type the range that recognised the code, or null
     * @param code the scanned code, or null
     * @param ticketTotal the running ticket total, or null when there is no ticket
     * @param fidelityCard the card attached to the sale, or null when none is
     * @return the findings, in control order, empty when the code passes
     */
    public List<Finding> check(CouponType type, String code, BigDecimal ticketTotal,
                               String fidelityCard) {
        LOGGER.info("Entering method check with type: " + (type == null ? null : type.code)
                + ", code: " + code + ", ticketTotal: " + ticketTotal
                + ", fidelityCard: " + fidelityCard);
        List<Finding> findings = new ArrayList<>();
        if (type == null || code == null) {
            LOGGER.info("Exiting method check");
            return findings;
        }
        LocalDate today = DateTimeProvider.now().toLocalDate();
        addIfBefore(findings, type, CouponControl.Kind.NOT_YET_VALID,
                CouponField.Role.DATE_START, code, today, true);
        addIfBefore(findings, type, CouponControl.Kind.EXPIRED,
                CouponField.Role.DATE_END, code, today, false);
        addOtherStore(findings, type, code);
        addStoreCheck(findings, type, code);
        addMinTicketTotal(findings, type, code, ticketTotal);
        addDuplicate(findings, type, code);
        addFidelity(findings, type, code, fidelityCard);
        addOtherIsland(findings, type);
        LOGGER.info("Exiting method check");
        return findings;
    }

    /**
     * Adds the island control of BO-03-06-07: the range names the checkout
     * islands it is accepted on, and this register belongs to one of them or it
     * does not.
     *
     * <p>Reads no position of the code — the acceptance is a property of the
     * RANGE and of where the register stands, not of the paper. A store
     * administering no island leaves every register outside every island, so a
     * range naming islands is then refused everywhere: naming an island is
     * saying "here and nowhere else".
     *
     * @param findings the findings being collected
     * @param type the range that recognised the code
     */
    private void addOtherIsland(List<Finding> findings, CouponType type) {
        AlertLevel level = type.levelOf(CouponControl.Kind.OTHER_ISLAND);
        if (!level.speaks()) {
            return;
        }
        com.intermarche.pos.domain.store.CheckoutIsland island =
                com.intermarche.pos.domain.store.CheckoutIsland.findByTerminal(terminalId);
        if (!type.acceptedOnIsland(island == null ? null : island.code)) {
            findings.add(new Finding(CouponControl.Kind.OTHER_ISLAND, level,
                    messageOf(type, CouponControl.Kind.OTHER_ISLAND)));
        }
    }

    /**
     * Adds the two loyalty controls of BO-03-06-40: the range may demand that a
     * card be attached to the sale at all, and — when the code carries a part of
     * a card number — that the presented card be that one.
     *
     * <p>The two are administered separately on purpose: a shop may want the
     * card required without the codes carrying any card identity, and a shop
     * whose codes do carry one may still accept a sale without a card. The
     * mismatch control stays silent when no card is presented: what is wrong
     * then is the absence, which the other control already names.
     *
     * @param findings the findings being collected
     * @param type the range that recognised the code
     * @param code the scanned code
     * @param fidelityCard the card attached to the sale, or null when none is
     */
    private void addFidelity(List<Finding> findings, CouponType type, String code,
                             String fidelityCard) {
        boolean cardPresented = fidelityCard != null && !fidelityCard.isBlank();
        AlertLevel required = type.levelOf(CouponControl.Kind.FIDELITY_REQUIRED);
        if (required.speaks() && !cardPresented) {
            findings.add(new Finding(CouponControl.Kind.FIDELITY_REQUIRED, required,
                    messageOf(type, CouponControl.Kind.FIDELITY_REQUIRED)));
        }
        AlertLevel mismatch = type.levelOf(CouponControl.Kind.FIDELITY_MISMATCH);
        if (!mismatch.speaks() || !cardPresented) {
            return;
        }
        String expected = rawOf(type, CouponField.Role.CARD_MATCH, code);
        if (expected == null || expected.isBlank()) {
            return;
        }
        if (!fidelityCard.contains(expected.trim())) {
            findings.add(new Finding(CouponControl.Kind.FIDELITY_MISMATCH, mismatch,
                    messageOf(type, CouponControl.Kind.FIDELITY_MISMATCH)));
        }
    }

    /**
     * Records an accepted code, so the duplicate control can refuse it next time.
     *
     * @param type the range that recognised the code, or null
     * @param code the accepted code, or null
     */
    @Transactional
    public void record(CouponType type, String code) {
        LOGGER.info("Entering method record with type: " + (type == null ? null : type.code)
                + ", code: " + code);
        if (type == null || code == null || code.isBlank()) {
            LOGGER.info("Exiting method record");
            return;
        }
        CouponScan scan = new CouponScan();
        scan.code = code;
        scan.typeCode = type.code;
        scan.identity = identityOf(type, code);
        scan.ticketNumber = rawOf(type, CouponField.Role.TICKET_NUMBER, code);
        scan.tpvNumber = rawOf(type, CouponField.Role.TPV_NUMBER, code);
        scan.scannedAt = DateTimeProvider.now();
        scan.persist();
        LOGGER.info("Exiting method record");
    }

    /**
     * Reduces a set of findings to the single decision the register acts on.
     *
     * @param findings the findings, possibly null or empty
     * @return the sternest level found, {@link AlertLevel#NONE} when there is none
     */
    public AlertLevel worst(List<Finding> findings) {
        LOGGER.info("Entering method worst with findings: " + findings);
        AlertLevel worst = AlertLevel.NONE;
        if (findings != null) {
            for (Finding finding : findings) {
                if (finding != null) {
                    worst = worst.max(finding.level);
                }
            }
        }
        LOGGER.info("Exiting method worst");
        return worst;
    }

    /**
     * Returns the message of the sternest finding, which is the one the cashier
     * must read first.
     *
     * @param findings the findings, possibly null or empty
     * @return the message, or null when nothing speaks
     */
    public String message(List<Finding> findings) {
        LOGGER.info("Entering method message with findings: " + findings);
        Finding loudest = null;
        if (findings != null) {
            for (Finding finding : findings) {
                if (finding == null || !finding.level.speaks()) {
                    continue;
                }
                if (loudest == null || finding.level.ordinal() > loudest.level.ordinal()) {
                    loudest = finding;
                }
            }
        }
        LOGGER.info("Exiting method message");
        return loudest == null ? null : loudest.message;
    }

    /**
     * Adds the finding of a date control, comparing the administered date with
     * the day the register is running.
     *
     * @param findings the list to add to
     * @param type the range
     * @param kind the control to evaluate
     * @param role the position holding the date
     * @param code the scanned code
     * @param today the register's day
     * @param dateMustBePast true when the date is a start date (a future start
     *        is a refusal), false when it is an end date (a past end is one)
     */
    private void addIfBefore(List<Finding> findings, CouponType type, CouponControl.Kind kind,
                             CouponField.Role role, String code, LocalDate today,
                             boolean dateMustBePast) {
        AlertLevel level = type.levelOf(kind);
        if (!level.speaks()) {
            return;
        }
        CouponField field = type.fieldOf(role);
        if (field == null) {
            return;
        }
        LocalDate date = field.dateValue(code, century(today));
        if (date == null) {
            return;
        }
        // A coupon is expired on the day AFTER its end date, and usable from its
        // start date on: the printed day is a valid day, which is how a customer
        // reads the paper.
        boolean wrong = dateMustBePast ? date.isAfter(today) : date.isBefore(today);
        if (!wrong && dateMustBePast && date.isEqual(today)) {
            // BO-03-06-30/31/32: on the OPENING day itself, the administered
            // hour decides. A code opening at 14h is not usable at 13h59, and
            // that is the only bound an hour refines — a code expires at the
            // END of its last day, as every paper voucher does.
            wrong = beforeAdministeredTime(type, code);
        }
        if (wrong) {
            findings.add(new Finding(kind, level, messageOf(type, kind)));
        }
    }

    /**
     * Tells whether the register's clock is still short of the hour the code
     * carries (BO-03-06-30): the range administers WHERE the hour sits and in
     * which layout — HHMM or HHMMSS — and the code says when it opens.
     *
     * <p>Silent when the range administers no hour, and silent when the
     * position cannot be read: an hour nobody administered cannot postpone a
     * code, and an unreadable one must not refuse every customer.
     *
     * @param type the range that recognised the code
     * @param code the scanned code
     * @return true when the current time is before the administered hour
     */
    private boolean beforeAdministeredTime(CouponType type, String code) {
        CouponField field = type.fieldOf(CouponField.Role.TIME);
        if (field == null) {
            return false;
        }
        java.time.LocalTime opensAt = field.timeValue(code);
        if (opensAt == null) {
            return false;
        }
        return DateTimeProvider.now().toLocalTime().isBefore(opensAt);
    }

    /**
     * Adds the finding of the other-shop control.
     *
     * @param findings the list to add to
     * @param type the range
     * @param code the scanned code
     */
    private void addOtherStore(List<Finding> findings, CouponType type, String code) {
        AlertLevel level = type.levelOf(CouponControl.Kind.OTHER_STORE);
        if (!level.speaks()) {
            return;
        }
        CouponField field = type.fieldOf(CouponField.Role.STORE_NUMBER);
        if (field == null) {
            return;
        }
        String raw = field.raw(code);
        if (raw == null) {
            return;
        }
        String storeCode = storeCode();
        if (storeCode == null || storeCode.isBlank()) {
            return;
        }
        // The code carries as many characters as the range declares; the shop
        // number may be shorter and zero-padded, so the comparison is on the
        // numeric value, not on the characters.
        if (!sameNumber(raw, storeCode)) {
            findings.add(new Finding(CouponControl.Kind.OTHER_STORE, level,
                    messageOf(type, CouponControl.Kind.OTHER_STORE)));
        }
    }

    /**
     * Adds the finding of the shop check-character control: the administered
     * check digit must be the modulo-ten sum of the shop number's digits.
     *
     * @param findings the list to add to
     * @param type the range
     * @param code the scanned code
     */
    private void addStoreCheck(List<Finding> findings, CouponType type, String code) {
        AlertLevel level = type.levelOf(CouponControl.Kind.STORE_CHECK);
        if (!level.speaks()) {
            return;
        }
        CouponField number = type.fieldOf(CouponField.Role.STORE_NUMBER);
        CouponField check = type.fieldOf(CouponField.Role.STORE_CHECK_DIGIT);
        if (number == null || check == null) {
            return;
        }
        String rawNumber = number.raw(code);
        String rawCheck = check.raw(code);
        if (rawNumber == null || rawCheck == null) {
            return;
        }
        Integer expected = digitSumModTen(rawNumber);
        Integer actual = parseOrNull(rawCheck);
        if (expected == null || actual == null || !expected.equals(actual)) {
            findings.add(new Finding(CouponControl.Kind.STORE_CHECK, level,
                    messageOf(type, CouponControl.Kind.STORE_CHECK)));
        }
    }

    /**
     * Adds the finding of the minimum ticket total control.
     *
     * @param findings the list to add to
     * @param type the range
     * @param code the scanned code
     * @param ticketTotal the running ticket total, or null
     */
    private void addMinTicketTotal(List<Finding> findings, CouponType type, String code,
                                   BigDecimal ticketTotal) {
        AlertLevel level = type.levelOf(CouponControl.Kind.MIN_TICKET_TOTAL);
        if (!level.speaks()) {
            return;
        }
        CouponField field = type.fieldOf(CouponField.Role.MIN_TICKET_TOTAL);
        if (field == null) {
            return;
        }
        BigDecimal minimum = field.decimalValue(code);
        if (minimum == null) {
            return;
        }
        BigDecimal total = ticketTotal == null ? BigDecimal.ZERO : ticketTotal;
        // The minimum is REACHED, not exceeded: a ticket equal to the printed
        // figure is what the customer was promised.
        if (total.compareTo(minimum) < 0) {
            findings.add(new Finding(CouponControl.Kind.MIN_TICKET_TOTAL, level,
                    messageOf(type, CouponControl.Kind.MIN_TICKET_TOTAL)));
        }
    }

    /**
     * Adds the finding of the duplicate control.
     *
     * @param findings the list to add to
     * @param type the range
     * @param code the scanned code
     */
    private void addDuplicate(List<Finding> findings, CouponType type, String code) {
        AlertLevel level = type.levelOf(CouponControl.Kind.DUPLICATE);
        if (!level.speaks()) {
            return;
        }
        if (CouponScan.alreadySeen(type.code, identityOf(type, code))) {
            findings.add(new Finding(CouponControl.Kind.DUPLICATE, level,
                    messageOf(type, CouponControl.Kind.DUPLICATE)));
        }
    }

    /**
     * Returns the point of sale number of this register, as the shop control
     * compares it.
     *
     * @return the store code, or null when the register holds no store row
     */
    private String storeCode() {
        Store store = Store.findAll().firstResult();
        return store == null ? null : store.code;
    }

    /**
     * Returns what the duplicate control compares: the administered sequence
     * number when the range holds one, the whole code otherwise.
     *
     * @param type the range
     * @param code the scanned code
     * @return the compared identity
     */
    private String identityOf(CouponType type, String code) {
        String sequence = rawOf(type, CouponField.Role.SEQUENCE_NUMBER, code);
        return sequence == null ? code : sequence;
    }

    /**
     * Reads the characters of an administered position, tolerating its absence.
     *
     * @param type the range
     * @param role the position to read
     * @param code the scanned code
     * @return the characters, or null when the range administers no such position
     */
    private String rawOf(CouponType type, CouponField.Role role, String code) {
        CouponField field = type.fieldOf(role);
        return field == null ? null : field.raw(code);
    }

    /**
     * Returns the message the range administers for a control.
     *
     * @param type the range
     * @param kind the control
     * @return the administered wording, or the control's own
     */
    private String messageOf(CouponType type, CouponControl.Kind kind) {
        CouponControl control = type.controlOf(kind);
        return control == null ? kind.getDefaultMessage() : control.effectiveMessage();
    }

    /**
     * Compares two shop numbers by value, so a zero-padded field matches a
     * shorter administered code.
     *
     * @param left the characters read in the code
     * @param right the register's own shop number
     * @return true when both name the same shop
     */
    private boolean sameNumber(String left, String right) {
        Integer a = parseOrNull(left);
        Integer b = parseOrNull(right);
        if (a == null || b == null) {
            return left.trim().equalsIgnoreCase(right.trim());
        }
        return a.equals(b);
    }

    /**
     * Sums the digits of a number and keeps the unit, the check character the
     * requirement describes.
     *
     * @param digits the characters of the shop number
     * @return the expected check digit, or null when the characters are not digits
     */
    private Integer digitSumModTen(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            sum += c - '0';
        }
        return sum % 10;
    }

    /**
     * Reads a whole number, tolerating anything that is not one.
     *
     * @param raw the characters to read
     * @return the number, or null when the characters do not form one
     */
    private Integer parseOrNull(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the century a two-digit year is completed with, taken from the
     * register's own day rather than written in the source.
     *
     * @param today the register's day
     * @return the century, for example 2000
     */
    private int century(LocalDate today) {
        return today.getYear() / 100 * 100;
    }

    /**
     * One thing a control found wrong with a scanned code.
     */
    public static class Finding {

        /** The control that found it. */
        public final CouponControl.Kind kind;

        /** How loudly the shop wants the register to react. */
        public final AlertLevel level;

        /** What the cashier is told. */
        public final String message;

        /**
         * Builds a finding.
         *
         * @param kind the control that found it
         * @param level the administered reaction
         * @param message the wording shown to the cashier
         */
        public Finding(CouponControl.Kind kind, AlertLevel level, String message) {
            this.kind = kind;
            this.level = level;
            this.message = message;
        }

        /**
         * Renders the finding for a log line.
         *
         * @return the control and its level
         */
        @Override
        public String toString() {
            return kind + "/" + level;
        }
    }
}
