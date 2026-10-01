package com.intermarche.pos.domain.store;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CheckoutPoint}, targeting 100% branch coverage of its
 * finders and of its two derived predicates.
 *
 * <p>Branch enumeration, leg by leg. {@code findByTerminal}: the null leg, the
 * blank leg, and the queried leg — which also proves the identifier is trimmed.
 * {@code listByIsland}: the same three legs, the empty list being returned
 * WITHOUT touching the database. {@code isSelfService}: {@code tpvType} null,
 * then non-null on both sides of {@code isSelfService}. {@code hasCustomerDisplay}:
 * NONE, a declared type, and null.
 */
class CheckoutPointTest {

    /** The null identifier never reaches the database. */
    @Test
    void findByTerminalIsNullForANullIdentifier() {
        Assertions.assertNull(CheckoutPoint.findByTerminal(null));
    }

    /** A blank identifier never reaches the database either. */
    @Test
    void findByTerminalIsNullForABlankIdentifier() {
        Assertions.assertNull(CheckoutPoint.findByTerminal("   "));
    }

    /** A real identifier is trimmed and queried. */
    @Test
    void findByTerminalTrimsAndQueries() {
        CheckoutPoint expected = new CheckoutPoint();
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<CheckoutPoint> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            statics.when(() -> PanacheEntityBase.find("terminalId", "POS03")).thenReturn(query);
            Assertions.assertSame(expected, CheckoutPoint.findByTerminal("  POS03  "));
        }
    }

    /** A null island code answers an empty list without querying. */
    @Test
    void listByIslandIsEmptyForANullCode() {
        Assertions.assertTrue(CheckoutPoint.listByIsland(null).isEmpty());
    }

    /** A blank island code answers an empty list without querying. */
    @Test
    void listByIslandIsEmptyForABlankCode() {
        Assertions.assertTrue(CheckoutPoint.listByIsland("  ").isEmpty());
    }

    /** A real island code is trimmed and queried for its active points. */
    @Test
    void listByIslandTrimsAndQueries() {
        CheckoutPoint point = new CheckoutPoint();
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            statics.when(() -> PanacheEntityBase.list(
                    "islandCode = ?1 and active = true order by terminalId", "SERVICE"))
                    .thenReturn(List.of(point));
            List<CheckoutPoint> found = CheckoutPoint.listByIsland(" SERVICE ");
            Assertions.assertEquals(1, found.size());
            Assertions.assertSame(point, found.get(0));
        }
    }

    /** A point whose type the node never described is not self-service. */
    @Test
    void isSelfServiceIsFalseWhenNoTypeIsDescribed() {
        CheckoutPoint point = new CheckoutPoint();
        point.tpvType = null;
        Assertions.assertFalse(point.isSelfService());
    }

    /** A manned till is not self-service. */
    @Test
    void isSelfServiceIsFalseForAMannedTill() {
        CheckoutPoint point = new CheckoutPoint();
        point.tpvType = TpvType.CASH_REGISTER;
        Assertions.assertFalse(point.isSelfService());
    }

    /** Every self-service family is self-service. */
    @Test
    void isSelfServiceIsTrueForASelfServiceFamily() {
        CheckoutPoint point = new CheckoutPoint();
        point.tpvType = TpvType.SELF_CHECKOUT_DN;
        Assertions.assertTrue(point.isSelfService());
    }

    /** NONE means no customer display. */
    @Test
    void hasNoCustomerDisplayWhenTheTypeIsNone() {
        CheckoutPoint point = new CheckoutPoint();
        point.customerDisplayType = DisplayType.NONE;
        Assertions.assertFalse(point.hasCustomerDisplay());
    }

    /** A declared type means a customer display. */
    @Test
    void hasCustomerDisplayWhenATypeIsDeclared() {
        CheckoutPoint point = new CheckoutPoint();
        point.customerDisplayType = DisplayType.TOUCH;
        Assertions.assertTrue(point.hasCustomerDisplay());
    }

    /** A null type is treated as carrying a display, not as none. */
    @Test
    void hasCustomerDisplayWhenNoTypeIsDescribed() {
        CheckoutPoint point = new CheckoutPoint();
        point.customerDisplayType = null;
        Assertions.assertTrue(point.hasCustomerDisplay());
    }
}
