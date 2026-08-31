package com.intermarche.pos.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link Menu}.
 * <p>
 * A pure label-carrying enum with no branches: the coverage target is met by
 * instantiating every constant (class load) and exercising the single getter.
 */
class MenuTest {

    /**
     * Every constant exposes its French label.
     */
    @Test
    void labelsAreExposed() {
        assertEquals("Paramétrage", Menu.PARAMETRAGE.getLabel());
        assertEquals("Administration", Menu.ADMINISTRATION.getLabel());
        assertEquals("Supervision", Menu.SUPERVISION.getLabel());
    }

    /**
     * The declared constants are the three expected menus.
     */
    @Test
    void declaresThreeMenus() {
        assertEquals(3, Menu.values().length);
        assertNotNull(Menu.valueOf("ADMINISTRATION"));
    }
}
