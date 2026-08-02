package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.ui.PosState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState} and its
 * {@link PaymentState} sub-state, a {@link PaymentService}, a
 * {@link VoucherService}, a {@link TicketPrinterService} and two Qute
 * {@link Template}s ({@code pay}, {@code main}). Every collaborator is a
 * Mockito mock: templates echo a recognizable {@link TemplateInstance} chain
 * so the returned view can be identified, {@code PosState} carries a mocked
 * {@code payment} holder whose delegating calls are verified and whose public
 * fields the tests drive and assert directly. The Panache static finders
 * {@code CouponType.find(...)}, {@code CouponType.listActivePaymentTypes()}
 * and {@code Ticket.findById(...)} resolve to {@link PanacheEntityBase} /
 * {@link CouponType} under plain {@code mvn test} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}. Tests assert absolute expected
 * values and verify delegation, covering both arms of every guard: the two
 * {@code digitalPath} guards (id null, ticket/key null compound), the four
 * {@code parseAmount} cases (null, blank, valid, invalid), the voucher-type
 * and voucher-number guards, the print training / draft / exception /
 * completion-modal branches and the reprint-last guard.
 */
class PaymentResourceTest {

    /**
     * Builds a {@link PaymentResource} whose collaborators are fresh mocks
     * wired onto its package-private fields, including the
     * {@link PosState#payment} sub-state holder so no direct field access hits
     * a null.
     *
     * @return a resource with fully mocked state, services and templates
     */
    private PaymentResource newResource() {
        PaymentResource resource = new PaymentResource();
        resource.state = mock(PosState.class);
        resource.state.payment = mock(PaymentState.class);
        resource.paymentService = mock(PaymentService.class);
        resource.voucherService = mock(VoucherService.class);
        resource.ticketPrinterService = mock(TicketPrinterService.class);
        resource.pay = mock(Template.class);
        resource.main = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the three-link {@code pay} template chain
     * ({@code pay.data("state").data("couponTypes").data("digitalPath")}) with
     * permissive matchers on the coupon and digital-path values.
     *
     * @param resource the resource whose {@code pay} template is stubbed
     * @return the three chained {@link TemplateInstance} mocks, the last of
     *         which is the final rendered view
     */
    private TemplateInstance[] stubPayChain(PaymentResource resource) {
        TemplateInstance ti1 = mock(TemplateInstance.class);
        TemplateInstance ti2 = mock(TemplateInstance.class);
        TemplateInstance ti3 = mock(TemplateInstance.class);
        when(resource.pay.data("state", resource.state)).thenReturn(ti1);
        when(ti1.data(eq("couponTypes"), any())).thenReturn(ti2);
        when(ti2.data(eq("digitalPath"), any())).thenReturn(ti3);
        return new TemplateInstance[]{ti1, ti2, ti3};
    }

    /**
     * Stubs the {@code main} template to return a recognizable view for the
     * given resource's state.
     *
     * @param resource the resource whose {@code main} template is stubbed
     * @return the view {@code main.data("state", state)} returns
     */
    private TemplateInstance stubMain(PaymentResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.main.data("state", resource.state)).thenReturn(view);
        return view;
    }

    /**
     * Stubs {@code CouponType.find("code = ?1 and active = true", code)} on the
     * given Panache static mock to return a query yielding the given type.
     *
     * @param panache the Panache static mock
     * @param code the technical code searched for
     * @param type the type the query's {@code firstResult()} returns, or null
     */
    private void stubFind(MockedStatic<PanacheEntityBase> panache, String code, CouponType type) {
        @SuppressWarnings("unchecked")
        PanacheQuery<CouponType> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(type);
        panache.when(() -> CouponType.find("code = ?1 and active = true", code)).thenReturn(query);
    }

    /**
     * Asserts the given response is a 303 redirect to {@code /pay}.
     *
     * @param response the response under test
     */
    private void assertRedirectPay(Response response) {
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/pay", response.getLocation().toString());
    }

    // --- showPaymentPage / digitalPath ---

    /**
     * {@code showPaymentPage()} resets the numpad, initializes the payment and
     * renders the page with a null digital path when no draft id exists
     * ({@code ticketDbId == null}, first digitalPath guard true).
     */
    @Test
    void showPaymentPageRendersWithoutDraft() {
        PaymentResource resource = newResource();
        resource.state.payment.ticketDbId = null;
        TemplateInstance[] chain = stubPayChain(resource);
        try (MockedStatic<CouponType> coupon = mockStatic(CouponType.class)) {
            coupon.when(CouponType::listActivePaymentTypes).thenReturn(List.of());
            assertSame(chain[2], resource.showPaymentPage());
        }
        verify(resource.paymentService).initPayment(resource.state);
        assertNull(resource.state.payment.inputMode);
        assertEquals("0,00", resource.state.payment.temporaryInput);
        verify(chain[1]).data("digitalPath", null);
    }

    /**
     * {@code showPaymentPage()} yields a null digital path when the draft id
     * matches no ticket (first guard false, {@code ticket == null} true).
     */
    @Test
    void showPaymentPageNullDigitalPathWhenTicketMissing() {
        PaymentResource resource = newResource();
        resource.state.payment.ticketDbId = 5L;
        TemplateInstance[] chain = stubPayChain(resource);
        try (MockedStatic<CouponType> coupon = mockStatic(CouponType.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            coupon.when(CouponType::listActivePaymentTypes).thenReturn(List.of());
            panache.when(() -> Ticket.findById(5L)).thenReturn(null);
            assertSame(chain[2], resource.showPaymentPage());
        }
        verify(chain[1]).data("digitalPath", null);
    }

    /**
     * {@code showPaymentPage()} yields a null digital path when the ticket has
     * no digital key (first guard false, {@code ticket == null} false,
     * {@code digitalKey == null} true).
     */
    @Test
    void showPaymentPageNullDigitalPathWhenNoKey() {
        PaymentResource resource = newResource();
        resource.state.payment.ticketDbId = 5L;
        TemplateInstance[] chain = stubPayChain(resource);
        Ticket ticket = mock(Ticket.class);
        ticket.digitalKey = null;
        try (MockedStatic<CouponType> coupon = mockStatic(CouponType.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            coupon.when(CouponType::listActivePaymentTypes).thenReturn(List.of());
            panache.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            assertSame(chain[2], resource.showPaymentPage());
        }
        verify(chain[1]).data("digitalPath", null);
    }

    /**
     * {@code showPaymentPage()} builds the online receipt path when the ticket
     * carries a digital key (both digitalPath guards false).
     */
    @Test
    void showPaymentPageBuildsDigitalPath() {
        PaymentResource resource = newResource();
        resource.state.payment.ticketDbId = 5L;
        TemplateInstance[] chain = stubPayChain(resource);
        Ticket ticket = mock(Ticket.class);
        ticket.id = 42L;
        ticket.digitalKey = "abcdef";
        try (MockedStatic<CouponType> coupon = mockStatic(CouponType.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            coupon.when(CouponType::listActivePaymentTypes).thenReturn(List.of());
            panache.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            assertSame(chain[2], resource.showPaymentPage());
        }
        verify(chain[1]).data("digitalPath", "/t/42/abcdef");
    }

    // --- cancelPendingCard / toggleDonation ---

    /**
     * {@code cancelPendingCard()} delegates to the service and redirects to the
     * payment page.
     */
    @Test
    void cancelPendingCardDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.cancelPendingCard());
        verify(resource.paymentService).cancelPendingCard(resource.state);
    }

    /**
     * {@code toggleDonation()} delegates to the service and redirects to the
     * payment page.
     */
    @Test
    void toggleDonationDelegatesAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.toggleDonation());
        verify(resource.paymentService).toggleDonationRoundup(resource.state);
    }

    // --- payment methods / parseAmount ---

    /**
     * {@code doCardPayment()} parses a valid French-comma amount and registers
     * the card payment (parseAmount: non-null, non-blank, valid number).
     */
    @Test
    void doCardPaymentParsesValidAmount() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doCardPayment("10,50"));
        verify(resource.paymentService).processCard(resource.state, new BigDecimal("10.50"));
    }

    /**
     * {@code doCashPayment()} treats a null amount as zero (parseAmount:
     * {@code value == null} true arm).
     */
    @Test
    void doCashPaymentTreatsNullAsZero() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doCashPayment(null));
        verify(resource.paymentService).processCash(resource.state, BigDecimal.ZERO);
    }

    /**
     * {@code doTrPayment()} treats a blank amount as zero (parseAmount:
     * non-null, {@code isBlank()} true arm).
     */
    @Test
    void doTrPaymentTreatsBlankAsZero() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doTrPayment("   "));
        verify(resource.paymentService).processTicketResto(resource.state, BigDecimal.ZERO);
    }

    /**
     * {@code doFidelityPayment()} treats an unparsable amount as zero
     * (parseAmount: non-null, non-blank, {@code NumberFormatException} catch
     * arm).
     */
    @Test
    void doFidelityPaymentTreatsInvalidAsZero() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doFidelityPayment("abc"));
        verify(resource.paymentService).processFidelity(resource.state, BigDecimal.ZERO);
    }

    /**
     * {@code doChequePayment()} parses a plain integer amount and registers the
     * cheque payment.
     */
    @Test
    void doChequePaymentParsesAmount() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.doChequePayment("5"));
        verify(resource.paymentService).processCheque(resource.state, new BigDecimal("5"));
    }

    // --- openVoucherPanel ---

    /**
     * {@code openVoucherPanel()} clears any pending voucher, opens the panel,
     * touches the state and redirects.
     */
    @Test
    void openVoucherPanelClearsAndOpens() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.openVoucherPanel());
        verify(resource.state.payment).clearPendingVoucher();
        assertTrue(resource.state.payment.voucherPanelOpen);
        verify(resource.state).touch();
    }

    // --- selectVoucherType ---

    /**
     * {@code selectVoucherType()} opens the panel but sets no pending type when
     * the code matches no active type ({@code type == null} arm).
     */
    @Test
    void selectVoucherTypeUnknownCodeSetsNothing() {
        PaymentResource resource = newResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "X", null);
            assertRedirectPay(resource.selectVoucherType("X"));
        }
        verify(resource.state.payment).clearPendingVoucher();
        assertTrue(resource.state.payment.voucherPanelOpen);
        assertNull(resource.state.payment.pendingVoucherTypeCode);
        verify(resource.state).touch();
    }

    /**
     * {@code selectVoucherType()} records the type and does NOT request an
     * amount for a numbered type ({@code type != null}, {@code hasNumber()}
     * true so {@code !hasNumber()} false).
     */
    @Test
    void selectVoucherTypeNumberedSetsType() {
        PaymentResource resource = newResource();
        CouponType type = mock(CouponType.class);
        type.code = "GIFT";
        type.label = "Chèque cadeau";
        when(type.hasNumber()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.selectVoucherType("GIFT"));
        }
        assertEquals("GIFT", resource.state.payment.pendingVoucherTypeCode);
        assertEquals("Chèque cadeau", resource.state.payment.pendingVoucherLabel);
        assertEquals(false, resource.state.payment.pendingVoucherNeedsAmount);
        verify(resource.state).touch();
    }

    /**
     * {@code selectVoucherType()} requests the amount directly for a numberless
     * type ({@code type != null}, {@code hasNumber()} false so
     * {@code !hasNumber()} true).
     */
    @Test
    void selectVoucherTypeNumberlessRequestsAmount() {
        PaymentResource resource = newResource();
        CouponType type = mock(CouponType.class);
        type.code = "CATALINA";
        type.label = "Coupon";
        when(type.hasNumber()).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "CATALINA", type);
            assertRedirectPay(resource.selectVoucherType("CATALINA"));
        }
        assertEquals("CATALINA", resource.state.payment.pendingVoucherTypeCode);
        assertTrue(resource.state.payment.pendingVoucherNeedsAmount);
        verify(resource.state).touch();
    }

    // --- validateVoucherNumber ---

    /**
     * {@code validateVoucherNumber()} rejects entry with a type error when no
     * type is pending (ternary {@code code != null} false, {@code type == null}
     * true).
     */
    @Test
    void validateVoucherNumberNoPendingCode() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = null;
        assertRedirectPay(resource.validateVoucherNumber("123"));
        assertEquals("Type de bon inconnu", resource.state.payment.voucherError);
        verify(resource.state).touch();
        verifyNoInteractions(resource.voucherService);
    }

    /**
     * {@code validateVoucherNumber()} rejects entry with a type error when the
     * pending code no longer resolves (ternary {@code code != null} true,
     * {@code type == null} true).
     */
    @Test
    void validateVoucherNumberTypeGone() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", null);
            assertRedirectPay(resource.validateVoucherNumber("123"));
        }
        assertEquals("Type de bon inconnu", resource.state.payment.voucherError);
        verify(resource.state).touch();
    }

    /**
     * {@code validateVoucherNumber()} rejects a null number with a
     * not-recognized error ({@code type != null}, {@code number == null} true
     * arm short-circuits the compound guard).
     */
    @Test
    void validateVoucherNumberNullNumber() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        CouponType type = mock(CouponType.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.validateVoucherNumber(null));
        }
        assertEquals("Numéro non reconnu — vérifiez la saisie", resource.state.payment.voucherError);
        verify(resource.state).touch();
        verify(type, never()).matches(any());
    }

    /**
     * {@code validateVoucherNumber()} rejects a non-matching number with a
     * not-recognized error ({@code number != null}, {@code !matches} true).
     */
    @Test
    void validateVoucherNumberNotMatching() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        CouponType type = mock(CouponType.class);
        when(type.matches("bad")).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.validateVoucherNumber("bad"));
        }
        assertEquals("Numéro non reconnu — vérifiez la saisie", resource.state.payment.voucherError);
        verify(resource.state).touch();
    }

    /**
     * {@code validateVoucherNumber()} accepts a matching number and requests
     * the amount when the type needs a manual amount ({@code !matches} false,
     * {@code requiresManualAmount()} true).
     */
    @Test
    void validateVoucherNumberMatchingNeedsAmount() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        CouponType type = mock(CouponType.class);
        when(type.matches("good")).thenReturn(true);
        when(type.requiresManualAmount()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.validateVoucherNumber("good"));
        }
        assertNull(resource.state.payment.voucherError);
        assertEquals("good", resource.state.payment.pendingVoucherNumber);
        assertTrue(resource.state.payment.pendingVoucherNeedsAmount);
        verify(resource.state).touch();
        verifyNoInteractions(resource.voucherService);
    }

    /**
     * {@code validateVoucherNumber()} applies an encoded voucher immediately
     * and clears the entry when no manual amount is needed ({@code !matches}
     * false, {@code requiresManualAmount()} false).
     */
    @Test
    void validateVoucherNumberMatchingEncoded() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        CouponType type = mock(CouponType.class);
        when(type.matches("good")).thenReturn(true);
        when(type.requiresManualAmount()).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.validateVoucherNumber("good"));
        }
        assertEquals("good", resource.state.payment.pendingVoucherNumber);
        verify(resource.voucherService).applyEncodedVoucher(resource.state, type, "good");
        verify(resource.state.payment).clearPendingVoucher();
        verify(resource.state).touch();
    }

    // --- validateVoucherAmount ---

    /**
     * {@code validateVoucherAmount()} resolves the pending type and applies the
     * manual voucher with the parsed amount (ternary {@code code != null}
     * true).
     */
    @Test
    void validateVoucherAmountWithType() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = "GIFT";
        resource.state.payment.pendingVoucherNumber = "N1";
        CouponType type = mock(CouponType.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "GIFT", type);
            assertRedirectPay(resource.validateVoucherAmount("12,00"));
        }
        verify(resource.voucherService).applyManualVoucher(resource.state, type, "N1", new BigDecimal("12.00"));
        verify(resource.state.payment).clearPendingVoucher();
        verify(resource.state).touch();
    }

    /**
     * {@code validateVoucherAmount()} passes a null type when no code is
     * pending (ternary {@code code != null} false).
     */
    @Test
    void validateVoucherAmountNoType() {
        PaymentResource resource = newResource();
        resource.state.payment.pendingVoucherTypeCode = null;
        resource.state.payment.pendingVoucherNumber = "N1";
        assertRedirectPay(resource.validateVoucherAmount("12,00"));
        verify(resource.voucherService).applyManualVoucher(resource.state, null, "N1", new BigDecimal("12.00"));
        verify(resource.state.payment).clearPendingVoucher();
        verify(resource.state).touch();
    }

    // --- cancelVoucher / pagination ---

    /**
     * {@code cancelVoucher()} clears the pending voucher, touches the state and
     * redirects.
     */
    @Test
    void cancelVoucherClearsAndRedirects() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.cancelVoucher());
        verify(resource.state.payment).clearPendingVoucher();
        verify(resource.state).touch();
    }

    /**
     * {@code paymentsPrev()} pages the history back, touches the state and
     * redirects.
     */
    @Test
    void paymentsPrevPagesBack() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.paymentsPrev());
        verify(resource.state.payment).prevPage();
        verify(resource.state).touch();
    }

    /**
     * {@code paymentsNext()} pages the history forward, touches the state and
     * redirects.
     */
    @Test
    void paymentsNextPagesForward() {
        PaymentResource resource = newResource();
        assertRedirectPay(resource.paymentsNext());
        verify(resource.state.payment).nextPage();
        verify(resource.state).touch();
    }

    // --- validatePayment / cancelPayment ---

    /**
     * {@code validatePayment()} finalizes the transaction and returns the main
     * page.
     */
    @Test
    void validatePaymentFinalizesAndReturnsMain() {
        PaymentResource resource = newResource();
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.validatePayment());
        verify(resource.paymentService).finalizeTransaction(resource.state);
    }

    /**
     * {@code cancelPayment()} cancels the registered payments and returns the
     * main page.
     */
    @Test
    void cancelPaymentCancelsAndReturnsMain() {
        PaymentResource resource = newResource();
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.cancelPayment());
        verify(resource.paymentService).cancelPayments(resource.state);
    }

    // --- printTicket ---

    /**
     * {@code printTicket()} prints the in-memory training receipt in training
     * mode and returns the main page when the modal is not shown
     * ({@code trainingMode} true, {@code transactionComplete} false).
     */
    @Test
    void printTicketTrainingReturnsMain() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = true;
        resource.state.payment.ticketDbId = 9L;
        resource.state.payment.transactionComplete = false;
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.printTicket());
        verify(resource.ticketPrinterService).printTrainingReceipt(resource.state);
        verify(resource.ticketPrinterService, never()).printTicket(any());
    }

    /**
     * {@code printTicket()} prints the draft by id and, when the completion
     * modal is shown, returns the payment page with its digital path
     * ({@code trainingMode} false, {@code ticketId != null}, no exception,
     * {@code transactionComplete} true).
     */
    @Test
    void printTicketDraftReturnsPayWhenComplete() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.payment.ticketDbId = 9L;
        resource.state.payment.transactionComplete = true;
        TemplateInstance[] chain = stubPayChain(resource);
        try (MockedStatic<CouponType> coupon = mockStatic(CouponType.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            coupon.when(CouponType::listActivePaymentTypes).thenReturn(List.of());
            panache.when(() -> Ticket.findById(9L)).thenReturn(null);
            assertSame(chain[2], resource.printTicket());
        }
        verify(resource.ticketPrinterService).printTicket(9L);
        verify(resource.ticketPrinterService, never()).printTrainingReceipt(any());
    }

    /**
     * {@code printTicket()} swallows a printing failure and returns the main
     * page ({@code trainingMode} false, {@code ticketId != null}, printer
     * throws, {@code transactionComplete} false).
     */
    @Test
    void printTicketSwallowsPrintFailure() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.payment.ticketDbId = 9L;
        resource.state.payment.transactionComplete = false;
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(resource.ticketPrinterService).printTicket(9L);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.printTicket());
        verify(resource.ticketPrinterService).printTicket(9L);
    }

    /**
     * {@code printTicket()} prints nothing when not training and no draft
     * exists, returning the main page ({@code trainingMode} false,
     * {@code ticketId != null} false, {@code transactionComplete} false).
     */
    @Test
    void printTicketNoDraftReturnsMain() {
        PaymentResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.payment.ticketDbId = null;
        resource.state.payment.transactionComplete = false;
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.printTicket());
        verify(resource.ticketPrinterService, never()).printTicket(any());
        verify(resource.ticketPrinterService, never()).printTrainingReceipt(any());
    }

    // --- reprintLastTicket ---

    /**
     * {@code reprintLastTicket()} reprints the last closed ticket and redirects
     * home ({@code lastClosedTicketId != null}, no exception).
     */
    @Test
    void reprintLastPrintsAndRedirectsHome() {
        PaymentResource resource = newResource();
        resource.state.lastClosedTicketId = 7L;
        Response response = resource.reprintLastTicket();
        verify(resource.ticketPrinterService).printTicket(7L);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code reprintLastTicket()} swallows a reprint failure and still
     * redirects home ({@code lastClosedTicketId != null}, printer throws).
     */
    @Test
    void reprintLastSwallowsFailure() {
        PaymentResource resource = newResource();
        resource.state.lastClosedTicketId = 7L;
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(resource.ticketPrinterService).printTicket(7L);
        Response response = resource.reprintLastTicket();
        verify(resource.ticketPrinterService).printTicket(7L);
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code reprintLastTicket()} prints nothing and redirects home when there
     * is no last closed ticket ({@code lastClosedTicketId != null} false).
     */
    @Test
    void reprintLastNoTicketRedirectsHome() {
        PaymentResource resource = newResource();
        resource.state.lastClosedTicketId = null;
        Response response = resource.reprintLastTicket();
        verify(resource.ticketPrinterService, never()).printTicket(any());
        assertEquals("/", response.getLocation().toString());
    }
}
