package com.intermarche.pos.ui;

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
 * Unit tests for {@link DrawerMustBeClosed}.
 * <p>
 * {@code DrawerMustBeClosed} is a JAX-RS name-binding marker annotation: it
 * declares no members and holds no logic, so it has zero executable branches.
 * The only observable contract is its meta-annotation set, which drives how the
 * JAX-RS runtime and the drawer guard filter discover it. These tests assert
 * that contract via reflection: {@code RUNTIME} retention (so the filter can
 * read it at request time), a {@code TYPE} and {@code METHOD} target (so it can
 * guard whole resource classes and individual routes alike), and the presence
 * of {@code @NameBinding} (so JAX-RS treats it as a binding). No collaborators
 * are involved and no Quarkus context is booted.
 */
class DrawerMustBeClosedTest {

    /**
     * The annotation must be retained at runtime, otherwise the drawer guard
     * filter could not read it off a resource class or method during request
     * dispatch.
     */
    @Test
    void retentionIsRuntime() {
        Retention retention = DrawerMustBeClosed.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    /**
     * The annotation must target both types and methods, matching its
     * documented use as a class-wide drawer guard that individual routes may
     * still carry directly.
     */
    @Test
    void targetIsTypeAndMethod() {
        Target target = DrawerMustBeClosed.class.getAnnotation(Target.class);
        assertArrayEquals(new ElementType[]{ElementType.TYPE, ElementType.METHOD}, target.value());
    }

    /**
     * The annotation must carry {@code @NameBinding} so the JAX-RS runtime binds
     * it to the drawer guard filter.
     */
    @Test
    void isNameBinding() {
        assertTrue(DrawerMustBeClosed.class.isAnnotationPresent(NameBinding.class));
    }

    /**
     * The type must actually be an annotation interface, guarding against an
     * accidental refactor into a plain interface or class.
     */
    @Test
    void isAnnotationType() {
        assertTrue(DrawerMustBeClosed.class.isAnnotation());
    }

    /**
     * A marker annotation declares no members; assert none creep in, since any
     * added element would change the binding's semantics.
     */
    @Test
    void declaresNoMembers() {
        assertEquals(0, DrawerMustBeClosed.class.getDeclaredMethods().length);
    }
}
