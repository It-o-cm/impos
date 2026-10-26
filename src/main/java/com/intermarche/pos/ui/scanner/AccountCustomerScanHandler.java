package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.payment.CreditClientService;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.PatternSyntaxException;

/**
 * Names the account customer on the sale screen and grants the discount of
 * their record automatically (LC-03-02-09).
 *
 * <p>The account card is scanned, or its number keyed on the sale pad, whose
 * OK rings through this same chain. A code is an account number only where the
 * store administers the range of account numbers ({@code customer.account-pattern},
 * BO-03-06-52) and an account carries that number: without a range nothing is
 * recognised, and a code in the range that names no account goes on down the
 * chain as before.
 *
 * <p>Whether the record's discount applies is the rule the credit panel already
 * states (a positive rate, the administered segment — BO-10-04-07); the
 * discount itself is the ticket-level one, applied without endorsement because
 * the back office set it. Once the payment has started the ticket is no longer
 * changed. Inert while the register is locked, like every sale gesture.
 */
@ApplicationScoped
@Priority(1)
public class AccountCustomerScanHandler implements ScanContext.ScanHandler {

    /** The localised operator messages (LC-13-06). */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The administered range of account numbers. */
    @Inject
    PosSettingsService posSettingsService;

    /** The rule deciding whether an account's discount applies. */
    @Inject
    CreditClientService creditClientService;

    /** The ticket-level discount and the total it moves. */
    @Inject
    TicketService ticketService;

    /**
     * Grants the discount of the account a scanned or keyed number names.
     *
     * @param ctx the token walking the chain
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled || ctx.state.isLocked()) {
            return;
        }
        AccountCustomer customer = recognise(ctx.code);
        if (customer == null) {
            return;
        }
        ctx.handled = true;
        synchronized (ctx.state) {
            if (ctx.state.payment.paymentInProgress || ctx.state.payment.transactionComplete) {
                ctx.state.ticket.setError(i18n.get("op.terminezAnnulezTicketAbord"));
            } else if (!creditClientService.discountApplies(customer)) {
                ctx.state.ticket.setNotice(i18n.format("op.clientCompteSansRemise", customer.getDisplayName()));
            } else {
                ticketService.applyAccountCustomerDiscount(ctx.state, customer);
            }
            ctx.state.touch();
        }
    }

    /**
     * Finds the account a code names, within the administered range.
     *
     * @param code the scanned or keyed code
     * @return the account, or null when the code is no account number
     */
    private AccountCustomer recognise(String code) {
        String pattern = posSettingsService.customerAccountPattern();
        if (pattern == null || pattern.isBlank() || code == null) {
            return null;
        }
        String number = code.trim();
        try {
            if (!number.matches(pattern.trim())) {
                return null;
            }
        } catch (PatternSyntaxException e) {
            // A range the shop mistyped is an administration defect, not a lane
            // error: the code goes on to the other handlers.
            return null;
        }
        return AccountCustomer.find("accountNumber", number).firstResult();
    }
}
