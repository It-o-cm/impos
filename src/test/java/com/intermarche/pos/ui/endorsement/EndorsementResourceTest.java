package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.home.HomeService;
import com.intermarche.pos.ui.returnprocess.RefundService;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EndorsementResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, its
 * {@link EndorsementState} and {@link TicketState} sub-states, an
 * {@link EndorsementService}, a {@link TicketService}, a {@link RefundService},
 * a {@link HomeService} and two Qute {@link Template}s ({@code main},
 * {@code lock}). Every service and template is a Mockito mock while the two
 * state holders are real objects so their public fields can be driven and read
 * directly. Templates echo a recognizable {@link TemplateInstance} so the
 * returned view can be identified; the POST actions instead return a 303
 * redirect to "/" (PRG pattern) that the tests assert by status and location.
 * Tests assert absolute expected values and verify delegation, covering both
 * arms of every guard, ternary and dispatch branch of the resource, including
 * the whole endorsement dispatch registry.
 */
class EndorsementResourceTest {

    /**
     * Builds an {@link EndorsementResource} whose services and templates are
     * fresh mocks and whose {@link PosState} is a mock carrying real
     * {@link EndorsementState} and {@link TicketState} sub-states.
     *
     * @return a resource with fully wired collaborators
     */
    private EndorsementResource newResource() {
        EndorsementResource resource = new EndorsementResource();
        resource.state = mock(PosState.class);
        resource.state.endorsement = new EndorsementState();
        resource.state.ticket = new TicketState();
        // LC-04-04-07/09: approving a ticket abandon undoes the settlements already
        // taken, so the approval path reads them. An empty settlement state is the
        // ordinary case — nothing was paid yet — and keeps that read from hitting a
        // null.
        resource.state.payment = new com.intermarche.pos.ui.payment.PaymentState();
        // The same rule needs the settlement service itself: with entries present the
        // approval undoes them before the ticket goes.
        resource.paymentService = mock(com.intermarche.pos.ui.payment.PaymentService.class);
        resource.endorsementService = mock(EndorsementService.class);
        resource.ticketService = mock(TicketService.class);
        resource.refundService = mock(RefundService.class);
        resource.homeService = mock(HomeService.class);
        resource.main = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the {@code main} template to return a recognizable view for the
     * given resource's state.
     *
     * @param resource the resource whose {@code main} template is stubbed
     * @return the view {@code main.data("state", state)} returns
     */
    private TemplateInstance stubMain(EndorsementResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.main.data("state", resource.state)).thenReturn(view);
        return view;
    }

    /**
     * Adds a ticket line with the given uid to the resource's real ticket state.
     *
     * @param resource the resource whose ticket receives the line
     * @param uid the uid to assign to the created line
     * @return the created ticket item
     */
    private TicketState.TicketItem addItem(EndorsementResource resource, String uid) {
        TicketState.TicketItem item = new TicketState.TicketItem(
                "3000", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, new BigDecimal("0.2000"));
        item.uid = uid;
        resource.state.ticket.items.add(item);
        return item;
    }

    // --- getEndorsementData ---

    /**
     * {@code getEndorsementData()} clears the scanned badge and echoes every
     * present field when badge, requested action and error are all non-null
     * (every guard/ternary true arm).
     */
    @Test
    void getEndorsementDataEchoesAllFieldsWhenPresent() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.active = true;
        resource.state.endorsement.requestedAction = "CANCEL_TICKET";
        resource.state.endorsement.scannedBadge = "MGR1";
        resource.state.endorsement.error = "BOOM";
        Map<String, Object> data = resource.getEndorsementData();
        assertEquals(true, data.get("active"));
        assertEquals("CANCEL_TICKET", data.get("action"));
        assertEquals("MGR1", data.get("scannedBadge"));
        assertEquals("BOOM", data.get("error"));
        assertNull(resource.state.endorsement.scannedBadge);
    }

    /**
     * {@code getEndorsementData()} substitutes empty strings and skips the
     * badge clearing when badge, requested action and error are all null
     * (every guard/ternary false arm).
     */
    @Test
    void getEndorsementDataSubstitutesEmptyWhenAbsent() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.active = false;
        resource.state.endorsement.requestedAction = null;
        resource.state.endorsement.scannedBadge = null;
        resource.state.endorsement.error = null;
        Map<String, Object> data = resource.getEndorsementData();
        assertEquals(false, data.get("active"));
        assertEquals("", data.get("action"));
        assertEquals("", data.get("scannedBadge"));
        assertEquals("", data.get("error"));
    }

    // --- validateEndorsement: no pending action ---

    /**
     * {@code validateEndorsement()} clears the request and redirects to the main
     * page without authorizing when no action is pending (null guard true arm).
     */
    @Test
    void validateEndorsementWithNoPendingActionClearsAndReturnsMain() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = null;
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.endorsementService).clearRequest(resource.state);
        verifyNoInteractions(resource.ticketService);
        verifyNoInteractions(resource.refundService);
        verifyNoInteractions(resource.homeService);
    }

    // --- validateEndorsement: authorization refused ---

    /**
     * {@code validateEndorsement()} sets the refusal error and redirects to the
     * main page when the credentials are refused (authorize false arm).
     */
    @Test
    void validateEndorsementRefusedSetsErrorAndReturnsMain() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "CANCEL_TICKET";
        when(resource.endorsementService.authorize("m", "0000", "CANCEL_TICKET")).thenReturn(false);
        Response response = resource.validateEndorsement("m", "0000");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        assertEquals("AUTORISATION REFUSÉE", resource.state.endorsement.error);
        verify(resource.state).touch();
        verify(resource.endorsementService, never()).clearRequest(resource.state);
        verifyNoInteractions(resource.ticketService);
    }

    // --- validateEndorsement: granted dispatch registry ---

    /**
     * {@code validateEndorsement()} cancels the whole ticket when the granted
     * action is {@code CANCEL_TICKET}.
     */
    @Test
    void validateEndorsementGrantedCancelTicket() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "CANCEL_TICKET";
        when(resource.endorsementService.authorize("m", "1234", "CANCEL_TICKET")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).cancelTicket(resource.state);
        verify(resource.endorsementService).clearRequest(resource.state);
        verify(resource.state).touch();
    }

    /**
     * {@code validateEndorsement()} cancels the targeted line when the granted
     * action is {@code CANCEL_LINE_<uid>}, passing the parsed uid.
     */
    @Test
    void validateEndorsementGrantedCancelLine() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "CANCEL_LINE_ABC-42";
        when(resource.endorsementService.authorize("m", "1234", "CANCEL_LINE_ABC-42")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).cancelItemById(resource.state, "ABC-42");
        verify(resource.endorsementService).clearRequest(resource.state);
    }

    /**
     * {@code validateEndorsement()} applies a REMISE and recalculates the total
     * when the granted price modification targets an existing line.
     */
    @Test
    void validateEndorsementGrantedPriceModificationRemise() {
        EndorsementResource resource = newResource();
        TicketState.TicketItem item = addItem(resource, "L1");
        resource.state.endorsement.requestedAction = "PRICE_MODIFICATION";
        resource.state.endorsement.pendingPriceType = "REMISE";
        resource.state.endorsement.pendingTargetUid = "L1";
        resource.state.endorsement.pendingValue = new BigDecimal("0.50");
        when(resource.endorsementService.authorize("m", "1234", "PRICE_MODIFICATION")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).applyRemise(item, new BigDecimal("0.50"));
        verify(resource.ticketService).recalculateTotal(resource.state);
    }

    /**
     * A granted GLOBAL_ gesture goes to the whole-ticket path: the service is
     * handed the TYPE and the VALUE, and NO target line is looked up — a
     * sale-level discount has none, and demanding one would make the gesture
     * impossible on a ticket whose selection was lost meanwhile.
     */
    @Test
    void validateEndorsementGrantedGlobalRemiseTargetsTheWholeTicket() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "PRICE_MODIFICATION";
        resource.state.endorsement.pendingPriceType = "GLOBAL_REMISE";
        resource.state.endorsement.pendingTargetUid = null;
        resource.state.endorsement.pendingValue = new BigDecimal("1.00");
        when(resource.endorsementService.authorize("m", "1234", "PRICE_MODIFICATION"))
                .thenReturn(true);

        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());

        verify(resource.ticketService).applyGlobalDiscount(
                resource.state, "GLOBAL_REMISE", new BigDecimal("1.00"));
        verify(resource.ticketService, never()).applyRemise(any(), any());
        verify(resource.ticketService, never()).recalculateTotal(resource.state);
    }

    /**
     * The percentage flavour takes the same path — the PREFIX routes, and the
     * full type is forwarded so the service can tell euros from percent.
     */
    @Test
    void validateEndorsementGrantedGlobalDiscountTargetsTheWholeTicket() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "PRICE_MODIFICATION";
        resource.state.endorsement.pendingPriceType = "GLOBAL_DISCOUNT";
        resource.state.endorsement.pendingValue = new BigDecimal("10");
        when(resource.endorsementService.authorize("m", "1234", "PRICE_MODIFICATION"))
                .thenReturn(true);

        resource.validateEndorsement("m", "1234");

        verify(resource.ticketService).applyGlobalDiscount(
                resource.state, "GLOBAL_DISCOUNT", new BigDecimal("10"));
    }

    /**
     * A GLOBAL_ gesture applies even when the ticket carries lines and the
     * pending uid points at one of them: the whole-ticket branch must not be
     * diverted onto a line by a stale selection.
     */
    @Test
    void validateEndorsementGlobalIgnoresAnyPendingTargetLine() {
        EndorsementResource resource = newResource();
        TicketState.TicketItem item = addItem(resource, "L1");
        resource.state.endorsement.requestedAction = "PRICE_MODIFICATION";
        resource.state.endorsement.pendingPriceType = "GLOBAL_REMISE";
        resource.state.endorsement.pendingTargetUid = "L1";
        resource.state.endorsement.pendingValue = new BigDecimal("1.00");
        when(resource.endorsementService.authorize("m", "1234", "PRICE_MODIFICATION"))
                .thenReturn(true);

        resource.validateEndorsement("m", "1234");

        verify(resource.ticketService).applyGlobalDiscount(
                resource.state, "GLOBAL_REMISE", new BigDecimal("1.00"));
        verify(resource.ticketService, never()).applyRemise(item, new BigDecimal("1.00"));
    }

    /**
     * A NULL type takes the per-line path (first leg of the guard) and, with
     * no line matching, applies nothing — the endorsement is simply consumed.
     */
    @Test
    void validateEndorsementNullTypeFallsToThePerLinePath() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "PRICE_MODIFICATION";
        resource.state.endorsement.pendingPriceType = null;
        resource.state.endorsement.pendingValue = new BigDecimal("1.00");
        when(resource.endorsementService.authorize("m", "1234", "PRICE_MODIFICATION"))
                .thenReturn(true);

        resource.validateEndorsement("m", "1234");

        verify(resource.ticketService, never()).applyGlobalDiscount(any(), any(), any());
        verify(resource.ticketService, never()).recalculateTotal(resource.state);
    }

    /**
     * A type that merely CONTAINS "GLOBAL" without starting with the prefix
     * keeps the per-line path: the router is a prefix test, so a future
     * per-line gesture cannot silently become a whole-ticket one.
     */
    @Test
    void validateEndorsementOnlyThePrefixRoutesToTheWholeTicket() {
        EndorsementResource resource = newResource();
        TicketState.TicketItem item = addItem(resource, "L1");
        resource.state.endorsement.requestedAction = "PRICE_MODIFICATION";
        resource.state.endorsement.pendingPriceType = "REMISE_GLOBAL";
        resource.state.endorsement.pendingTargetUid = "L1";
        resource.state.endorsement.pendingValue = new BigDecimal("1.00");
        when(resource.endorsementService.authorize("m", "1234", "PRICE_MODIFICATION"))
                .thenReturn(true);

        resource.validateEndorsement("m", "1234");

        verify(resource.ticketService, never()).applyGlobalDiscount(any(), any(), any());
        verify(resource.ticketService).recalculateTotal(resource.state);
        assertNotNull(item);
    }

    /**
     * {@code validateEndorsement()} applies a DISCOUNT and recalculates the total
     * when the granted price modification uses the DISCOUNT type.
     */
    @Test
    void validateEndorsementGrantedPriceModificationDiscount() {
        EndorsementResource resource = newResource();
        TicketState.TicketItem item = addItem(resource, "L1");
        resource.state.endorsement.requestedAction = "PRICE_MODIFICATION";
        resource.state.endorsement.pendingPriceType = "DISCOUNT";
        resource.state.endorsement.pendingTargetUid = "L1";
        resource.state.endorsement.pendingValue = new BigDecimal("10");
        when(resource.endorsementService.authorize("m", "1234", "PRICE_MODIFICATION")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).applyDiscount(item, new BigDecimal("10"));
        verify(resource.ticketService).recalculateTotal(resource.state);
    }

    /**
     * {@code validateEndorsement()} forces the price and recalculates the total
     * when the granted price modification uses the FORCE_PRICE type.
     */
    @Test
    void validateEndorsementGrantedPriceModificationForcePrice() {
        EndorsementResource resource = newResource();
        TicketState.TicketItem item = addItem(resource, "L1");
        resource.state.endorsement.requestedAction = "PRICE_MODIFICATION";
        resource.state.endorsement.pendingPriceType = "FORCE_PRICE";
        resource.state.endorsement.pendingTargetUid = "L1";
        resource.state.endorsement.pendingValue = new BigDecimal("2.00");
        when(resource.endorsementService.authorize("m", "1234", "PRICE_MODIFICATION")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).forcePrice(item, new BigDecimal("2.00"));
        verify(resource.ticketService).recalculateTotal(resource.state);
    }

    /**
     * {@code validateEndorsement()} still recalculates the total but applies no
     * gesture when the granted price modification carries an unknown type (all
     * three type ternaries false, item present).
     */
    @Test
    void validateEndorsementGrantedPriceModificationUnknownType() {
        EndorsementResource resource = newResource();
        addItem(resource, "L1");
        resource.state.endorsement.requestedAction = "PRICE_MODIFICATION";
        resource.state.endorsement.pendingPriceType = "UNKNOWN";
        resource.state.endorsement.pendingTargetUid = "L1";
        resource.state.endorsement.pendingValue = new BigDecimal("1.00");
        when(resource.endorsementService.authorize("m", "1234", "PRICE_MODIFICATION")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).recalculateTotal(resource.state);
        verify(resource.ticketService, never()).applyRemise(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(resource.ticketService, never()).applyDiscount(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(resource.ticketService, never()).forcePrice(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * {@code validateEndorsement()} applies no gesture and does not recalculate
     * when the granted price modification targets a missing line (item null arm).
     */
    @Test
    void validateEndorsementGrantedPriceModificationMissingItem() {
        EndorsementResource resource = newResource();
        addItem(resource, "L1");
        resource.state.endorsement.requestedAction = "PRICE_MODIFICATION";
        resource.state.endorsement.pendingPriceType = "REMISE";
        resource.state.endorsement.pendingTargetUid = "NOPE";
        resource.state.endorsement.pendingValue = new BigDecimal("0.50");
        when(resource.endorsementService.authorize("m", "1234", "PRICE_MODIFICATION")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService, never()).recalculateTotal(resource.state);
        verify(resource.endorsementService).clearRequest(resource.state);
    }

    /**
     * {@code validateEndorsement()} toggles training mode when the granted action
     * is {@code TRAINING_TOGGLE}.
     */
    @Test
    void validateEndorsementGrantedTrainingToggle() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "TRAINING_TOGGLE";
        when(resource.endorsementService.authorize("m", "1234", "TRAINING_TOGGLE")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).performTrainingToggle();
        verify(resource.endorsementService).clearRequest(resource.state);
    }

    /**
     * {@code validateEndorsement()} performs the parsed refund method when the
     * granted action is {@code REFUND_<METHOD>_<ticketId>}.
     */
    @Test
    void validateEndorsementGrantedRefund() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "REFUND_CARD_77";
        when(resource.endorsementService.authorize("m", "1234", "REFUND_CARD_77")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.refundService).performRefund(resource.state, Refund.RefundMethod.CARD);
        verify(resource.endorsementService).clearRequest(resource.state);
    }

    /**
     * {@code validateEndorsement()} sets the unknown-refund error when the method
     * segment is not a valid {@link Refund.RefundMethod} (IllegalArgumentException
     * arm of the multi-catch).
     */
    @Test
    void validateEndorsementGrantedRefundUnknownMethod() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "REFUND_BITCOIN_77";
        when(resource.endorsementService.authorize("m", "1234", "REFUND_BITCOIN_77")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        assertEquals("ACTION DE REMBOURSEMENT INCONNUE", resource.state.endorsement.error);
        verifyNoInteractions(resource.refundService);
    }

    /**
     * {@code validateEndorsement()} sets the unknown-refund error when no method
     * segment is present (ArrayIndexOutOfBoundsException arm of the multi-catch).
     */
    @Test
    void validateEndorsementGrantedRefundMissingMethodSegment() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "REFUND_";
        when(resource.endorsementService.authorize("m", "1234", "REFUND_")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        assertEquals("ACTION DE REMBOURSEMENT INCONNUE", resource.state.endorsement.error);
        verifyNoInteractions(resource.refundService);
    }

    /**
     * {@code validateEndorsement()} swallows a guard refusal from the refund
     * service, leaving the message already on screen ({@code IllegalStateException}
     * catch arm).
     */
    @Test
    void validateEndorsementGrantedRefundGuardRefusal() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "REFUND_CASH_5";
        when(resource.endorsementService.authorize("m", "1234", "REFUND_CASH_5")).thenReturn(true);
        doThrow(new IllegalStateException("blocked"))
                .when(resource.refundService).performRefund(resource.state, Refund.RefundMethod.CASH);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        assertNull(resource.state.endorsement.error);
        verify(resource.endorsementService).clearRequest(resource.state);
    }

    /**
     * {@code validateEndorsement()} matches no dispatch branch for an
     * unrecognized granted action yet still clears the request and redirects to
     * the main page (final else-none arm of the registry).
     */
    @Test
    void validateEndorsementGrantedUnknownActionClearsOnly() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "SOMETHING_ELSE";
        when(resource.endorsementService.authorize("m", "1234", "SOMETHING_ELSE")).thenReturn(true);
        Response response = resource.validateEndorsement("m", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.endorsementService).clearRequest(resource.state);
        verifyNoInteractions(resource.ticketService);
        verifyNoInteractions(resource.refundService);
        verifyNoInteractions(resource.homeService);
    }

    // --- cancelEndorsement ---

    /**
     * {@code cancelEndorsement()} clears the pending request, touches the state
     * and renders the main view.
     */
    @Test
    void cancelEndorsementClearsAndReturnsMain() {
        EndorsementResource resource = newResource();
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.cancelEndorsement());
        verify(resource.endorsementService).clearRequest(resource.state);
        verify(resource.state).touch();
    }

    // --- selfEndorse (connected-supervisor shortcut) ---

    /**
     * {@code selfEndorse} with no pending action clears the request and
     * redirects to the main page (null-action arm), never consulting the
     * supervisor role.
     */
    @Test
    void selfEndorseWithoutActionClears() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = null;
        Response response = resource.selfEndorse();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.endorsementService).clearRequest(resource.state);
        verify(resource.endorsementService, never()).operatorIsSupervisor(any());
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * {@code selfEndorse} refuses when the logged operator is not a supervisor
     * (refused arm): it flags the error, touches and redirects to the main page
     * without executing the action.
     */
    @Test
    void selfEndorseRefusesNonSupervisor() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "CANCEL_TICKET";
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        Response response = resource.selfEndorse();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        assertEquals("AUTORISATION REFUSÉE", resource.state.endorsement.error);
        verify(resource.state).touch();
        verify(resource.ticketService, never()).cancelTicket(any());
        verify(resource.endorsementService, never()).clearRequest(any());
    }

    /**
     * {@code selfEndorse} executes the pending action for a supervisor
     * (success arm): it runs the approved action, clears the request and
     * redirects to the main page.
     */
    @Test
    void selfEndorseExecutesForSupervisor() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "CANCEL_TICKET";
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(true);
        Response response = resource.selfEndorse();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).cancelTicket(resource.state);
        verify(resource.endorsementService).clearRequest(resource.state);
        verify(resource.state).touch();
    }

    /**
     * The approved-action dispatch routes a {@code PRINT_BADGE_<n>} action to
     * {@code homeService.printOperatorBadge} with the trailing selector
     * (PRINT_BADGE branch), reached through the supervisor shortcut.
     */
    @Test
    void selfEndorseDispatchesPrintBadge() {
        EndorsementResource resource = newResource();
        resource.state.endorsement.requestedAction = "PRINT_BADGE_5";
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(true);
        resource.selfEndorse();
        verify(resource.homeService).printOperatorBadge("5");
        verify(resource.endorsementService).clearRequest(resource.state);
    }
}
