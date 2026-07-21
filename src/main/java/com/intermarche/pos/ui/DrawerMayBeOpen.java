package com.intermarche.pos.ui;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation utilisée pour indiquer qu'une méthode est autorisée
 * même si le tiroir caisse est ouvert.
 * À utiliser conjointement avec @DrawerMustBeClosed sur la classe.
 */
@NameBinding
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DrawerMayBeOpen {
}