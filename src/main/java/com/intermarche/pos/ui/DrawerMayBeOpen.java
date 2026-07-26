package com.intermarche.pos.ui;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level opt-out of the drawer guard: the route stays reachable while
 * the drawer is open. Used together with {@code @DrawerMustBeClosed} on the
 * class, and reserved for the screens whose JOB involves an open drawer —
 * the lock screen (a cashier who locked drawer-open must be able to come
 * back), the cash count (counting happens drawer open by definition) and
 * the drawer-error screen itself.
 */
@NameBinding
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DrawerMayBeOpen {
}