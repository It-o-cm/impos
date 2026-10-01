package com.intermarche.pos.service;

import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.domain.store.DisplayType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CheckoutPointService}, targeting 100% branch coverage.
 *
 * <p>The checkout point is reached through a Panache static finder, which plain
 * {@code mvn test} resolves to {@link PanacheEntityBase} because the entities are
 * not bytecode-enhanced; it is intercepted with
 * {@link org.mockito.Mockito#mockStatic}. No database, no Quarkus boot.
 *
 * <p>Branch enumeration, leg by leg. {@code own}: absent row and present row.
 * {@code isDeactivated}: the {@code point != null} leg false, then true with
 * {@code active} true and false — the three legs of the compound guard.
 * {@code ownIslandCode}: absent row, row with a null island, row with an island.
 * {@code expectsCustomerDisplay}: the {@code point == null} leg true, then false
 * with {@code customerDisplayType} NONE, non-NONE, and null — the null being the
 * half-described point the node can legitimately send.
 */
class CheckoutPointServiceTest {

    /** The identifier this register answers to in every test below. */
    private static final String TERMINAL = "POS07";

    /**
     * Builds the service under test, already carrying this register's identifier.
     *
     * @return the service, ready to call
     */
    private CheckoutPointService service() {
        CheckoutPointService service = new CheckoutPointService();
        service.terminalId = TERMINAL;
        return service;
    }

    /**
     * Arms the static finder so that {@code findByTerminal} answers the given
     * point — or nothing at all when it is null.
     *
     * @param statics the open static mock of the Panache base
     * @param point the point the finder must return, or null for no row
     */
    private void arm(MockedStatic<PanacheEntityBase> statics, CheckoutPoint point) {
        @SuppressWarnings("unchecked")
        PanacheQuery<CheckoutPoint> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(point);
        statics.when(() -> PanacheEntityBase.find("terminalId", TERMINAL)).thenReturn(query);
    }

    /**
     * Builds a point with the two attributes the tests vary.
     *
     * @param active whether the point is in service
     * @param islandCode the island code, possibly null
     * @param displayType the customer display type, possibly null
     * @return the point
     */
    private CheckoutPoint point(boolean active, String islandCode, DisplayType displayType) {
        CheckoutPoint point = new CheckoutPoint();
        point.terminalId = TERMINAL;
        point.active = active;
        point.islandCode = islandCode;
        point.customerDisplayType = displayType;
        return point;
    }

    /** No row at all: {@code own} answers null rather than inventing a point. */
    @Test
    void ownIsNullWhenTheNodeHasSentNoPoint() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, null);
            Assertions.assertNull(service().own());
        }
    }

    /** A row exists: {@code own} answers it. */
    @Test
    void ownIsThePointOfThisRegister() {
        CheckoutPoint expected = point(true, "CAISSES_AVANT", DisplayType.LCD_TEXT);
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, expected);
            Assertions.assertSame(expected, service().own());
        }
    }

    /** First leg false: no administered point never closes a lane. */
    @Test
    void isDeactivatedIsFalseWithoutAnAdministeredPoint() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, null);
            Assertions.assertFalse(service().isDeactivated());
        }
    }

    /** Second leg false: a point in service is not deactivated. */
    @Test
    void isDeactivatedIsFalseForAPointInService() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, point(true, null, DisplayType.NONE));
            Assertions.assertFalse(service().isDeactivated());
        }
    }

    /** Both legs true: BO-03-09-01, the lane is out of service. */
    @Test
    void isDeactivatedIsTrueForAPointTakenOutOfService() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, point(false, null, DisplayType.NONE));
            Assertions.assertTrue(service().isDeactivated());
        }
    }

    /** No point: no island either, and the caller falls back. */
    @Test
    void ownIslandCodeIsNullWithoutAnAdministeredPoint() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, null);
            Assertions.assertNull(service().ownIslandCode());
        }
    }

    /** A point attached to no island answers null, not an empty string. */
    @Test
    void ownIslandCodeIsNullForAPointInNoIsland() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, point(true, null, DisplayType.NONE));
            Assertions.assertNull(service().ownIslandCode());
        }
    }

    /** A point in an island answers that island's code. */
    @Test
    void ownIslandCodeIsTheAdministeredIsland() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, point(true, "SERVICE", DisplayType.NONE));
            Assertions.assertEquals("SERVICE", service().ownIslandCode());
        }
    }

    /** First leg true: with no point, a display is still expected. */
    @Test
    void expectsCustomerDisplayWithoutAnAdministeredPoint() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, null);
            Assertions.assertTrue(service().expectsCustomerDisplay());
        }
    }

    /** Second leg false: the point says NONE, so nothing is expected. */
    @Test
    void doesNotExpectCustomerDisplayWhenThePointDeclaresNone() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, point(true, null, DisplayType.NONE));
            Assertions.assertFalse(service().expectsCustomerDisplay());
        }
    }

    /** Second leg true: a declared display is expected. */
    @Test
    void expectsCustomerDisplayWhenThePointDeclaresOne() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, point(true, null, DisplayType.GRAPHIC));
            Assertions.assertTrue(service().expectsCustomerDisplay());
        }
    }

    /** A half-described point (null type) is treated as carrying a display. */
    @Test
    void expectsCustomerDisplayWhenThePointDescribesNoType() {
        try (MockedStatic<PanacheEntityBase> statics = mockStatic(PanacheEntityBase.class)) {
            arm(statics, point(true, null, null));
            Assertions.assertTrue(service().expectsCustomerDisplay());
        }
    }
}
