package com.intermarche.pos.ui;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource class (or method) as unreachable while the cash drawer
 * is physically open — the register-wide discipline forcing the cashier to
 * close the drawer between operations. Binds {@code DrawerCheckFilter} via
 * JAX-RS name binding; individual routes opt back out with
 * {@code @DrawerMayBeOpen} (lock screen, cash count, drawer-error screen).
 */
// Lier cette annotation à un filtre JAX-RS
@NameBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DrawerMustBeClosed {
}