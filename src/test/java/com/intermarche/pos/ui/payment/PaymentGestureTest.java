package com.intermarche.pos.ui.payment;

import jakarta.ws.rs.NameBinding;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PaymentGesture}: a name-binding marker, asserted by its
 * meta-annotations and by where it is carried.
 */
class PaymentGestureTest {

    /**
     * The marker is a runtime name binding usable on classes and methods.
     */
    @Test
    void isARuntimeNameBindingOnTypesAndMethods() {
        assertTrue(PaymentGesture.class.isAnnotationPresent(NameBinding.class));
        assertEquals(RetentionPolicy.RUNTIME, PaymentGesture.class.getAnnotation(Retention.class).value());
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD},
                PaymentGesture.class.getAnnotation(Target.class).value());
    }

    /**
     * The payment screen's resource and the filter both carry the binding, so
     * every route of the screen goes through the filter.
     */
    @Test
    void bindsThePaymentResourceToItsFilter() {
        assertTrue(PaymentResource.class.isAnnotationPresent(PaymentGesture.class));
        assertTrue(PaymentGestureFilter.class.isAnnotationPresent(PaymentGesture.class));
    }
}
