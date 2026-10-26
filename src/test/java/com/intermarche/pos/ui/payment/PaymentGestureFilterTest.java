package com.intermarche.pos.ui.payment;

import com.intermarche.pos.ui.PosState;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link PaymentGestureFilter}: the stale message of the
 * payment screen is cleared before every gesture, and on the screen itself
 * only on entry into the payment phase.
 */
class PaymentGestureFilterTest {

    /**
     * Builds the filter over a real state holding a stale refusal, matched on
     * the given resource method.
     *
     * @param method the matched resource method
     * @return the filter
     */
    private PaymentGestureFilter filterOn(Method method) {
        PaymentGestureFilter filter = new PaymentGestureFilter();
        filter.state = new PosState();
        filter.state.ticket.setError("CARTE REFUSEE");
        filter.resourceInfo = mock(ResourceInfo.class);
        doReturn(method).when(filter.resourceInfo).getResourceMethod();
        return filter;
    }

    /**
     * Resolves a method of the payment resource by name.
     *
     * @param name the method name
     * @param types the parameter types
     * @return the method
     * @throws NoSuchMethodException never for the names used here
     */
    private Method route(String name, Class<?>... types) throws NoSuchMethodException {
        return PaymentResource.class.getMethod(name, types);
    }

    /**
     * A settlement gesture clears the message a previous gesture left.
     *
     * @throws Exception never
     */
    @Test
    void aGestureClearsTheStaleMessage() throws Exception {
        PaymentGestureFilter filter = filterOn(route("doCashPayment", String.class));
        filter.state.payment.paymentInProgress = true;
        filter.filter(mock(ContainerRequestContext.class));
        assertNull(filter.state.ticket.transientError);
    }

    /**
     * A route carrying no {@code @Path} of its own is a gesture as well.
     *
     * @throws Exception never
     */
    @Test
    void aRouteWithoutPathClearsTheStaleMessage() throws Exception {
        PaymentGestureFilter filter = filterOn(Object.class.getMethod("toString"));
        filter.filter(mock(ContainerRequestContext.class));
        assertNull(filter.state.ticket.transientError);
    }

    /**
     * The screen redisplayed while a payment is under way keeps the message
     * the gesture just set: it is the target of that gesture's redirect.
     *
     * @throws Exception never
     */
    @Test
    void theScreenKeepsTheMessageWhileAPaymentIsUnderWay() throws Exception {
        PaymentGestureFilter filter = filterOn(route("showPaymentPage"));
        filter.state.payment.paymentInProgress = true;
        filter.filter(mock(ContainerRequestContext.class));
        assertEquals("CARTE REFUSEE", filter.state.ticket.transientError);
    }

    /**
     * The completion modal redisplayed keeps the message too.
     *
     * @throws Exception never
     */
    @Test
    void theScreenKeepsTheMessageOnACompletedTransaction() throws Exception {
        PaymentGestureFilter filter = filterOn(route("showPaymentPage"));
        filter.state.payment.transactionComplete = true;
        filter.filter(mock(ContainerRequestContext.class));
        assertEquals("CARTE REFUSEE", filter.state.ticket.transientError);
    }

    /**
     * Entering the payment phase clears what the sale screen left.
     *
     * @throws Exception never
     */
    @Test
    void enteringThePaymentPhaseClearsTheSaleScreenMessage() throws Exception {
        PaymentGestureFilter filter = filterOn(route("showPaymentPage"));
        filter.filter(mock(ContainerRequestContext.class));
        assertNull(filter.state.ticket.transientError);
    }
}
