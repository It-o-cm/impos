package com.intermarche.pos.ui;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Store;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ThemeService}.
 * <p>
 * The service reads two Panache entities through static finders
 * ({@code Employee.findById} and {@code Store.findAll}); under plain
 * {@code mvn test} these resolve to {@link PanacheEntityBase}, so they are
 * intercepted with {@link org.mockito.Mockito#mockStatic}. The injected
 * {@link PosState} is a real instance because the resolution chain only reads
 * its {@code auth} sub-state, and {@code touch()} is observed through the
 * plain {@code version} counter. The Qute global {@link ThemeService.Globals}
 * reaches the bean through {@link Arc}, itself mocked statically. Every test is
 * fully isolated and asserts absolute expected values, covering both arms of
 * each guard and ternary (28 branches).
 */
class ThemeServiceTest {

    /**
     * Builds a {@link ThemeService} wired onto a fresh, real {@link PosState}
     * so tests can drive its {@code auth} sub-state and observe {@code touch()}.
     *
     * @return a service with a real in-memory state
     */
    private ThemeService newService() {
        ThemeService service = new ThemeService();
        service.state = new PosState();
        return service;
    }

    /**
     * Creates a mocked {@link PanacheQuery} whose {@code firstResult()} yields
     * the given store, mirroring {@code Store.findAll().firstResult()}.
     *
     * @param store the store to return, or null for no store
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Store> storeQuery(Store store) {
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(store);
        return query;
    }

    // --------------------------------------------------
    // currentTheme
    // --------------------------------------------------

    /**
     * The operator's personal theme wins the whole resolution chain when a
     * cashier is logged in with a non-blank preference (L53 both true, L55 all
     * true): the store is never consulted.
     */
    @Test
    void currentThemeReturnsOperatorPreference() {
        ThemeService service = newService();
        service.state.auth.operatorId = 5L;
        Employee operator = new Employee();
        operator.theme = "clair";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(5L)).thenReturn(operator);
            assertEquals("clair", service.currentTheme());
        }
    }

    /**
     * A null {@code auth} short-circuits the operator branch (L53 first false),
     * so the store's default theme is used (L60 all true).
     */
    @Test
    void currentThemeFallsBackToStoreWhenAuthNull() {
        ThemeService service = newService();
        service.state.auth = null;
        Store store = new Store();
        store.theme = "clair";
        PanacheQuery<Store> query = storeQuery(store);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(Store::findAll).thenReturn(query);
            assertEquals("clair", service.currentTheme());
        }
    }

    /**
     * A logged-in {@code auth} with a null operator id skips the operator
     * branch (L53 first true, second false); with no store present (L60 first
     * false) the built-in default is returned.
     */
    @Test
    void currentThemeReturnsDefaultWhenOperatorIdNullAndNoStore() {
        ThemeService service = newService();
        service.state.auth.operatorId = null;
        PanacheQuery<Store> query = storeQuery(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(Store::findAll).thenReturn(query);
            assertEquals(ThemeService.DEFAULT_THEME, service.currentTheme());
        }
    }

    /**
     * A resolvable operator id that finds no employee (L55 first false) falls
     * through to the store; a store whose theme is null (L60 second false)
     * yields the built-in default.
     */
    @Test
    void currentThemeReturnsDefaultWhenOperatorMissingAndStoreThemeNull() {
        ThemeService service = newService();
        service.state.auth.operatorId = 9L;
        Store store = new Store();
        store.theme = null;
        PanacheQuery<Store> query = storeQuery(store);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(9L)).thenReturn(null);
            mocked.when(Store::findAll).thenReturn(query);
            assertEquals(ThemeService.DEFAULT_THEME, service.currentTheme());
        }
    }

    /**
     * An operator with a null theme (L55 second false) falls through to the
     * store; a store with a blank theme (L60 third false) yields the built-in
     * default.
     */
    @Test
    void currentThemeReturnsDefaultWhenOperatorThemeNullAndStoreThemeBlank() {
        ThemeService service = newService();
        service.state.auth.operatorId = 3L;
        Employee operator = new Employee();
        operator.theme = null;
        Store store = new Store();
        store.theme = "   ";
        PanacheQuery<Store> query = storeQuery(store);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(3L)).thenReturn(operator);
            mocked.when(Store::findAll).thenReturn(query);
            assertEquals(ThemeService.DEFAULT_THEME, service.currentTheme());
        }
    }

    /**
     * An operator with a blank theme (L55 third false) is treated as no
     * preference, so a valid store theme (L60 all true) wins.
     */
    @Test
    void currentThemeFallsBackToStoreWhenOperatorThemeBlank() {
        ThemeService service = newService();
        service.state.auth.operatorId = 7L;
        Employee operator = new Employee();
        operator.theme = "   ";
        Store store = new Store();
        store.theme = "clair";
        PanacheQuery<Store> query = storeQuery(store);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(7L)).thenReturn(operator);
            mocked.when(Store::findAll).thenReturn(query);
            assertEquals("clair", service.currentTheme());
        }
    }

    // --------------------------------------------------
    // setThemeForOperator
    // --------------------------------------------------

    /**
     * A null {@code auth} makes the setter a no-op (L75 first true): no finder
     * is touched and the state is never bumped.
     */
    @Test
    void setThemeForOperatorReturnsWhenAuthNull() {
        ThemeService service = newService();
        service.state.auth = null;
        service.setThemeForOperator("clair");
        assertEquals(0L, service.state.version);
    }

    /**
     * A logged-in {@code auth} with a null operator id makes the setter a no-op
     * (L75 first false, second true): the state is never bumped.
     */
    @Test
    void setThemeForOperatorReturnsWhenOperatorIdNull() {
        ThemeService service = newService();
        service.state.auth.operatorId = null;
        service.setThemeForOperator("clair");
        assertEquals(0L, service.state.version);
    }

    /**
     * A resolvable operator id that finds no employee makes the setter a no-op
     * (L79 true): the state is never bumped.
     */
    @Test
    void setThemeForOperatorReturnsWhenOperatorMissing() {
        ThemeService service = newService();
        service.state.auth.operatorId = 4L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(4L)).thenReturn(null);
            service.setThemeForOperator("clair");
            assertEquals(0L, service.state.version);
        }
    }

    /**
     * A known theme name on a found operator (L79 false, L82 both true) is
     * persisted onto the employee and the state is bumped once.
     */
    @Test
    void setThemeForOperatorStoresKnownTheme() {
        ThemeService service = newService();
        service.state.auth.operatorId = 6L;
        Employee operator = new Employee();
        operator.theme = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(6L)).thenReturn(operator);
            service.setThemeForOperator("clair");
            assertEquals("clair", operator.theme);
            assertEquals(1L, service.state.version);
        }
    }

    /**
     * A null theme name clears the operator's preference (L82 first false):
     * the field is reset to null and the state is bumped once.
     */
    @Test
    void setThemeForOperatorClearsPreferenceWhenNull() {
        ThemeService service = newService();
        service.state.auth.operatorId = 8L;
        Employee operator = new Employee();
        operator.theme = "clair";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(8L)).thenReturn(operator);
            service.setThemeForOperator(null);
            assertNull(operator.theme);
            assertEquals(1L, service.state.version);
        }
    }

    /**
     * An unknown theme name clears the operator's preference (L82 first true,
     * second false): the field is reset to null and the state is bumped once.
     */
    @Test
    void setThemeForOperatorClearsPreferenceWhenUnknown() {
        ThemeService service = newService();
        service.state.auth.operatorId = 2L;
        Employee operator = new Employee();
        operator.theme = "clair";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.findById(2L)).thenReturn(operator);
            service.setThemeForOperator("neon");
            assertNull(operator.theme);
            assertEquals(1L, service.state.version);
        }
    }

    // --------------------------------------------------
    // Globals.posTheme
    // --------------------------------------------------

    /**
     * When the bean is available (L103 true), the global delegates to
     * {@code currentTheme()} and returns its value.
     */
    @Test
    @SuppressWarnings("unchecked")
    void posThemeReturnsResolvedThemeWhenBeanAvailable() {
        ThemeService svc = mock(ThemeService.class);
        when(svc.currentTheme()).thenReturn("clair");
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<ThemeService> handle = mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(true);
        when(handle.get()).thenReturn(svc);
        when(container.instance(ThemeService.class)).thenReturn(handle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals("clair", ThemeService.Globals.posTheme());
        }
    }

    /**
     * When the bean is unavailable (L103 false), the global returns the
     * built-in default without dereferencing the handle.
     */
    @Test
    @SuppressWarnings("unchecked")
    void posThemeReturnsDefaultWhenBeanUnavailable() {
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<ThemeService> handle = mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(false);
        when(container.instance(ThemeService.class)).thenReturn(handle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(ThemeService.DEFAULT_THEME, ThemeService.Globals.posTheme());
        }
    }

    /**
     * Any resolution failure is swallowed by the catch guard, so the global
     * still returns the built-in default rather than breaking a rendering.
     */
    @Test
    void posThemeReturnsDefaultWhenResolutionThrows() {
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new RuntimeException("boom"));
            assertEquals(ThemeService.DEFAULT_THEME, ThemeService.Globals.posTheme());
        }
    }

    /**
     * Exercises the implicit constructor of the static globals holder so the
     * class body is fully covered; the instance carries no behaviour of its own.
     */
    @Test
    void globalsHolderIsInstantiable() {
        assertNotNull(new ThemeService.Globals());
    }
}
