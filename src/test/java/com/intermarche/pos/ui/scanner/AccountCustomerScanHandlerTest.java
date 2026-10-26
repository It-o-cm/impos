package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.payment.CreditClientService;
import com.intermarche.pos.ui.ticket.TicketService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountCustomerScanHandler}, the account customer named
 * on the sale screen and granted the discount of their record (LC-03-02-09).
 * Covered: a token already handled, a locked register, no range (null and
 * blank), no code, a code out of the range, a mistyped range, a number naming
 * no account, the payment guard on both legs, an account without discount and
 * an account whose discount applies.
 */
class AccountCustomerScanHandlerTest {

    /** The administered range of account numbers. */
    private static final String RANGE = "^CC\\d{8}$";

    /**
     * Builds a handler over the administered range, its collaborators mocked.
     *
     * @return the handler
     */
    private AccountCustomerScanHandler handler() {
        AccountCustomerScanHandler handler = new AccountCustomerScanHandler();
        handler.i18n = TestMessages.french();
        handler.posSettingsService = mock(PosSettingsService.class);
        when(handler.posSettingsService.customerAccountPattern()).thenReturn(RANGE);
        handler.creditClientService = mock(CreditClientService.class);
        handler.ticketService = mock(TicketService.class);
        return handler;
    }

    /**
     * Builds the token of one scan on an unlocked register.
     *
     * @param code the scanned or keyed code
     * @return the token
     */
    private ScanContext scan(String code) {
        PosState state = new PosState();
        state.auth.isLocked = false;
        return new ScanContext(code, state);
    }

    /**
     * Builds the account the tests name.
     *
     * @return the account
     */
    private AccountCustomer account() {
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = "CC00000001";
        customer.companyName = "DUPONT BTP";
        customer.discountPercent = new BigDecimal("5");
        return customer;
    }

    /**
     * Runs one scan with the account lookup answering the given account.
     *
     * @param handler the handler
     * @param ctx the token
     * @param found the account the number names, or null
     */
    @SuppressWarnings("unchecked")
    private void run(AccountCustomerScanHandler handler, ScanContext ctx, AccountCustomer found) {
        PanacheQuery<AccountCustomer> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.find("accountNumber", "CC00000001")).thenReturn(query);
            handler.handle(ctx);
        }
    }

    /** An account whose discount applies gets it on the ticket, the scan consumed. */
    @Test
    void anAccountWhoseDiscountAppliesGetsIt() {
        AccountCustomerScanHandler handler = handler();
        AccountCustomer customer = account();
        when(handler.creditClientService.discountApplies(customer)).thenReturn(true);
        ScanContext ctx = scan(" CC00000001 ");
        long version = ctx.state.version;
        run(handler, ctx, customer);
        assertTrue(ctx.handled);
        verify(handler.ticketService).applyAccountCustomerDiscount(ctx.state, customer);
        assertTrue(ctx.state.version > version);
    }

    /** An account without a discount that applies is named, and nothing is granted. */
    @Test
    void anAccountWithoutDiscountIsNamedOnly() {
        AccountCustomerScanHandler handler = handler();
        ScanContext ctx = scan("CC00000001");
        run(handler, ctx, account());
        assertTrue(ctx.handled);
        assertEquals("CLIENT EN COMPTE DUPONT BTP : AUCUNE REMISE", ctx.state.ticket.transientError);
        verify(handler.ticketService, never()).applyAccountCustomerDiscount(any(), any());
    }

    /** Once the payment started, or the sale is closed, the ticket is no longer changed. */
    @Test
    void theTicketIsLeftAloneOnceThePaymentStarted() {
        for (boolean inProgress : new boolean[]{true, false}) {
            AccountCustomerScanHandler handler = handler();
            ScanContext ctx = scan("CC00000001");
            ctx.state.payment.paymentInProgress = inProgress;
            ctx.state.payment.transactionComplete = !inProgress;
            run(handler, ctx, account());
            assertTrue(ctx.handled);
            assertEquals("TERMINEZ OU ANNULEZ LE TICKET D'ABORD", ctx.state.ticket.transientError);
            verify(handler.ticketService, never()).applyAccountCustomerDiscount(any(), any());
        }
    }

    /** A number in the range naming no account goes on down the chain. */
    @Test
    void aNumberNamingNoAccountGoesOn() {
        AccountCustomerScanHandler handler = handler();
        ScanContext ctx = scan("CC00000001");
        run(handler, ctx, null);
        assertFalse(ctx.handled);
    }

    /** A handled token, a locked register, no range, no code, a foreign code or a mistyped range: nothing. */
    @Test
    void unrecognisedCodesGoOnDownTheChain() {
        AccountCustomerScanHandler handler = handler();
        ScanContext handled = scan("CC00000001");
        handled.handled = true;
        run(handler, handled, account());
        verify(handler.creditClientService, never()).discountApplies(any());
        ScanContext locked = scan("CC00000001");
        locked.state.auth.isLocked = true;
        run(handler, locked, account());
        assertFalse(locked.handled);
        ScanContext none = scan(null);
        run(handler, none, account());
        assertFalse(none.handled);
        ScanContext article = scan("3300000000002");
        run(handler, article, account());
        assertFalse(article.handled);
        for (String range : new String[]{null, "  ", "^CC("}) {
            when(handler.posSettingsService.customerAccountPattern()).thenReturn(range);
            ScanContext ctx = scan("CC00000001");
            run(handler, ctx, account());
            assertFalse(ctx.handled);
        }
        verify(handler.ticketService, never()).applyAccountCustomerDiscount(any(), any());
    }
}
