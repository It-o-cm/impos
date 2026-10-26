package com.intermarche.pos.ui.payment;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the routes of the payment screen whose gestures start afresh: the
 * message a PREVIOUS gesture left in the screen's message zone is cleared
 * before the route runs, exactly as the sale screen clears it at the start of
 * every scan or article entry. Binds {@link PaymentGestureFilter} via JAX-RS
 * name binding.
 */
@NameBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PaymentGesture {
}
