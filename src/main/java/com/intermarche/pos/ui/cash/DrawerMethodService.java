package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.payment.TenderDefinition;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The tenders the drawer holds, what the register believes it holds of each, and
 * which of them a cashier may take out or move ({@code LC-12-03} and
 * {@code LC-12-10}).
 *
 * <p>THE DRAWER IS NOT ONLY CASH. It holds cheques, meal vouchers and paper coupons,
 * each with a theoretical the register can state — how many transactions took it in,
 * and for how much. Everything the two drawer gestures need starts from that: a
 * withdrawal takes one tender out, a transfer moves an amount from one to another, and
 * both are checked against what the register believes is there.
 *
 * <p>WHICH TENDERS ARE OFFERED IS THE SHOP'S BUSINESS ({@code LC-12-03-03}). A tender
 * collected automatically — a card, settled by the monetics — is never withdrawn by
 * hand, and showing it would offer a gesture that means nothing.
 */
@ApplicationScoped
public class DrawerMethodService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DrawerMethodService.class);

    /** How a tender is described to the operator. */
    @Inject
    PosSettingsService posSettingsService;

    /** The session report, which carries the per-tender theoretical. */
    @Inject
    CashSessionService cashSessionService;

    /**
     * One tender of the drawer, as the screen shows it.
     *
     * @param key      the tender key the register records
     * @param label    the wording the cashier reads
     * @param count    how many transactions took this tender in
     * @param amount   the theoretical amount of this tender in the drawer
     */
    public record DrawerMethod(String key, String label, int count, BigDecimal amount) {

        /**
         * Returns the theoretical amount, French format.
         *
         * @return the amount, two decimals, comma
         */
        public String getAmountFormatted() {
            return String.format("%.2f", amount.setScale(2, java.math.RoundingMode.HALF_UP))
                    .replace('.', ',');
        }
    }

    /**
     * Reads an administered tender list.
     *
     * @param administered the raw list of {@code CLÉ:LIBELLÉ} pairs
     * @return the pairs, in administered order, empty when none is usable
     */
    private List<String[]> parse(String administered) {
        List<String[]> pairs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (administered == null) {
            return pairs;
        }
        for (String entry : administered.split(";")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) {
                continue;
            }
            String key = parts[0].trim().toUpperCase(java.util.Locale.ROOT);
            String label = parts[1].trim();
            if (key.isEmpty() || label.isEmpty() || !seen.add(key)) {
                continue;
            }
            pairs.add(new String[] {key, label});
        }
        return pairs;
    }

    /**
     * The tenders a cashier may withdraw by hand ({@code LC-12-03-02/03}), each with
     * what the register believes the drawer holds of it.
     *
     * @return the tenders, in administered order, empty when the shop offers none
     */
    public List<DrawerMethod> withdrawable() {
        LOGGER.info("Entering method withdrawable");
        LOGGER.info("Exiting method withdrawable");
        return describe(parse(posSettingsService.drawerWithdrawalMethods()));
    }

    /**
     * The tenders a settlement may be transferred between ({@code LC-12-10-02}).
     *
     * <p>An unset list falls back to the withdrawal one: a shop that named the tenders
     * its drawer holds once should not have to name them twice.
     *
     * @return the tenders, in administered order
     */
    public List<DrawerMethod> transferable() {
        LOGGER.info("Entering method transferable");
        List<String[]> pairs = parse(posSettingsService.drawerTransferMethods());
        LOGGER.info("Exiting method transferable");
        return describe(pairs.isEmpty()
                ? parse(posSettingsService.drawerWithdrawalMethods()) : pairs);
    }

    /**
     * Attaches the theoretical of the open session to each named tender.
     *
     * @param pairs the administered key-and-label pairs
     * @return the described tenders
     */
    private List<DrawerMethod> describe(List<String[]> pairs) {
        Map<String, BigDecimal> totals = theoreticalByMethod();
        Map<String, Integer> counts = countByMethod();
        List<DrawerMethod> methods = new ArrayList<>();
        for (String[] pair : pairs) {
            methods.add(new DrawerMethod(pair[0], pair[1],
                    counts.getOrDefault(pair[0], 0),
                    totals.getOrDefault(pair[0], BigDecimal.ZERO)));
        }
        return methods;
    }

    /**
     * The theoretical amount of each tender in the drawer of the open session.
     *
     * @return the amounts by tender key, empty when no session is open
     */
    public Map<String, BigDecimal> theoreticalByMethod() {
        LOGGER.info("Entering method theoreticalByMethod");
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method theoreticalByMethod");
            return Map.of();
        }
        LOGGER.info("Exiting method theoreticalByMethod");
        return cashSessionService.buildReport(session).totalsByMethod;
    }

    /**
     * How many transactions took each tender in, on the open session
     * ({@code LC-12-03-06}).
     *
     * @return the counts by tender key, empty when no session is open
     */
    public Map<String, Integer> countByMethod() {
        LOGGER.info("Entering method countByMethod");
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method countByMethod");
            return Map.of();
        }
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (com.intermarche.pos.domain.sale.Ticket ticket : closedTickets(session)) {
            for (com.intermarche.pos.domain.payment.TicketPayment payment : ticket.payments) {
                counts.merge(payment.getMethodKey(), 1, Integer::sum);
            }
        }
        LOGGER.info("Exiting method countByMethod");
        return counts;
    }

    /**
     * The settled tickets of a session, which carry the tenders the drawer took in.
     *
     * @param session the open session
     * @return the closed tickets of that session
     */
    private List<com.intermarche.pos.domain.sale.Ticket> closedTickets(CashSession session) {
        return com.intermarche.pos.domain.sale.Ticket.list("session = ?1 and status = ?2",
                session, com.intermarche.pos.domain.sale.Ticket.TicketStatus.CLOSED);
    }

    /**
     * The settlements taken in with one tender, transaction by transaction
     * ({@code LC-12-03-07}).
     *
     * <p>THE WITHDRAWAL TICKET OF A NON-CASH TENDER IS A CHECKLIST. A cashier handing
     * over a bundle of cheques counts them against this list; a total alone would let
     * a missing cheque through, since the bundle would still be one line on the paper.
     *
     * @param key the tender key
     * @return one {@code {transaction number, amount}} pair per settlement, in ticket
     *         order, empty when no session is open
     */
    public List<String[]> transactionsOf(String key) {
        LOGGER.info("Entering method transactionsOf with key: " + key);
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method transactionsOf");
            return List.of();
        }
        List<String[]> lines = new ArrayList<>();
        for (com.intermarche.pos.domain.sale.Ticket ticket : closedTickets(session)) {
            for (com.intermarche.pos.domain.payment.TicketPayment payment : ticket.payments) {
                if (payment.getMethodKey().equals(key)) {
                    lines.add(new String[] {ticket.ticketNumber,
                            String.format("%.2f", payment.amount).replace('.', ',') + " E"});
                }
            }
        }
        LOGGER.info("Exiting method transactionsOf");
        return lines;
    }

    /**
     * The theoretical amount of one tender.
     *
     * @param key the tender key
     * @return the amount, zero when the drawer holds none of it
     */
    public BigDecimal theoreticalOf(String key) {
        LOGGER.info("Entering method theoreticalOf with key: " + key);
        String wanted = key == null || key.isBlank() ? CashMovement.CASH : key;
        LOGGER.info("Exiting method theoreticalOf");
        return theoreticalByMethod().getOrDefault(wanted, BigDecimal.ZERO);
    }

    /**
     * Returns the wording of a tender, as administered.
     *
     * @param key the tender key
     * @return the label, the key itself when the shop named no label for it
     */
    public String labelOf(String key) {
        LOGGER.info("Entering method labelOf with key: " + key);
        for (DrawerMethod method : transferable()) {
            if (method.key().equals(key)) {
                LOGGER.info("Exiting method labelOf");
                return method.label();
            }
        }
        for (DrawerMethod method : withdrawable()) {
            if (method.key().equals(key)) {
                LOGGER.info("Exiting method labelOf");
                return method.label();
            }
        }
        LOGGER.info("Exiting method labelOf");
        return key;
    }

    /**
     * Tells whether a tender is offered for a manual withdrawal.
     *
     * @param key the tender key posted by the screen
     * @return true when the shop administered it as manually withdrawable
     */
    public boolean isWithdrawable(String key) {
        LOGGER.info("Entering method isWithdrawable with key: " + key);
        for (DrawerMethod method : withdrawable()) {
            if (method.key().equals(key)) {
                LOGGER.info("Exiting method isWithdrawable");
                return true;
            }
        }
        LOGGER.info("Exiting method isWithdrawable");
        return false;
    }

    /**
     * Tells whether a tender is offered as an end of a transfer.
     *
     * @param key the tender key posted by the screen
     * @return true when the shop administered it as transferable
     */
    public boolean isTransferable(String key) {
        LOGGER.info("Entering method isTransferable with key: " + key);
        for (DrawerMethod method : transferable()) {
            if (method.key().equals(key)) {
                LOGGER.info("Exiting method isTransferable");
                return true;
            }
        }
        LOGGER.info("Exiting method isTransferable");
        return false;
    }

    /**
     * The tenders an amount may be taken FROM ({@code BO-05-02-17}).
     *
     * @return the administered transfer list, narrowed to the tenders the
     *         referential allows as a source
     */
    public List<DrawerMethod> transferSources() {
        LOGGER.info("Entering method transferSources");
        List<DrawerMethod> allowed = new ArrayList<>();
        for (DrawerMethod method : transferable()) {
            if (allowedAsSource(method.key())) {
                allowed.add(method);
            }
        }
        LOGGER.info("Exiting method transferSources");
        return allowed;
    }

    /**
     * The tenders an amount may be moved INTO ({@code BO-05-02-17}).
     *
     * @return the administered transfer list, narrowed to the tenders the
     *         referential allows as a destination
     */
    public List<DrawerMethod> transferDestinations() {
        LOGGER.info("Entering method transferDestinations");
        List<DrawerMethod> allowed = new ArrayList<>();
        for (DrawerMethod method : transferable()) {
            if (allowedAsDestination(method.key())) {
                allowed.add(method);
            }
        }
        LOGGER.info("Exiting method transferDestinations");
        return allowed;
    }

    /**
     * Tells whether an amount may be taken from a tender ({@code BO-05-02-17}).
     *
     * @param key the tender key posted by the screen
     * @return true when the tender is transferable AND allowed as a source
     */
    public boolean isTransferSource(String key) {
        LOGGER.info("Entering method isTransferSource with key: " + key);
        boolean allowed = isTransferable(key) && allowedAsSource(key);
        LOGGER.info("Exiting method isTransferSource");
        return allowed;
    }

    /**
     * Tells whether an amount may be moved into a tender ({@code BO-05-02-17}).
     *
     * @param key the tender key posted by the screen
     * @return true when the tender is transferable AND allowed as a destination
     */
    public boolean isTransferDestination(String key) {
        LOGGER.info("Entering method isTransferDestination with key: " + key);
        boolean allowed = isTransferable(key) && allowedAsDestination(key);
        LOGGER.info("Exiting method isTransferDestination");
        return allowed;
    }

    /**
     * Reads the SOURCE flag of the tender referential.
     *
     * <p>A tender the referential does not know is allowed: the transfer list of
     * {@code drawer.transfer-methods} is what a shop administered on purpose, and
     * a referential row that was never opened must not quietly cancel it.
     *
     * @param key the tender key
     * @return true when nothing forbids taking an amount from it
     */
    private boolean allowedAsSource(String key) {
        TenderDefinition definition = TenderDefinition.findByCode(key);
        return definition == null || definition.transferSource;
    }

    /**
     * Reads the DESTINATION flag of the tender referential.
     *
     * @param key the tender key
     * @return true when nothing forbids moving an amount into it
     */
    private boolean allowedAsDestination(String key) {
        TenderDefinition definition = TenderDefinition.findByCode(key);
        return definition == null || definition.transferDestination;
    }
}
