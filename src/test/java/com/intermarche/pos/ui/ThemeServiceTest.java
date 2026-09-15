package com.intermarche.pos.ui;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.endorsement.EndorsementService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * each guard and ternary — the outer service (26 branches) and the
 * {@link ThemeService.Globals} holder (16 branches: {@code posTheme},
 * {@code ageCheckBirthYear}, {@code supervisorLogged} and {@code showEan},
 * each resolved through {@link Arc}), 42 branches in all.
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
        // BO-10-07-08: no training theme administered by default, so the whole
        // pre-existing resolution chain below is unchanged; the dedicated tests
        // re-stub it to prove the setting is READ and not hard-coded.
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.trainingTheme()).thenReturn("");
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

    // --------------------------------------------------
    // Globals.ageCheckBirthYear
    // --------------------------------------------------

    /**
     * A resolvable state with a POSITIVE threshold yields the pivot birth
     * year: this year minus the threshold (handle available true, ternary
     * {@code threshold > 0} true). The value is computed against the current
     * year so the test never expires.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ageCheckBirthYearUsesStateThreshold() {
        PosState state = new PosState();
        state.ageCheck.threshold = 21;
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<PosState> handle = mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(true);
        when(handle.get()).thenReturn(state);
        when(container.instance(PosState.class)).thenReturn(handle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(java.time.LocalDate.now().getYear() - 21,
                    ThemeService.Globals.ageCheckBirthYear());
        }
    }

    /**
     * A resolvable state whose threshold is ZERO (no age check armed) falls
     * back to the legal default of 18 (handle available true, ternary
     * {@code threshold > 0} FALSE) — the keypad must never suggest the
     * current year as a pivot.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ageCheckBirthYearFallsBackWhenThresholdNotArmed() {
        PosState state = new PosState();
        state.ageCheck.threshold = 0;
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<PosState> handle = mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(true);
        when(handle.get()).thenReturn(state);
        when(container.instance(PosState.class)).thenReturn(handle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(java.time.LocalDate.now().getYear() - 18,
                    ThemeService.Globals.ageCheckBirthYear());
        }
    }

    /**
     * A NEGATIVE threshold takes the same fallback leg as zero — the guard is
     * {@code > 0}, not {@code != 0}, and a corrupted state must not push the
     * pivot into the future.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ageCheckBirthYearFallsBackOnNegativeThreshold() {
        PosState state = new PosState();
        state.ageCheck.threshold = -5;
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<PosState> handle = mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(true);
        when(handle.get()).thenReturn(state);
        when(container.instance(PosState.class)).thenReturn(handle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(java.time.LocalDate.now().getYear() - 18,
                    ThemeService.Globals.ageCheckBirthYear());
        }
    }

    /**
     * An UNAVAILABLE bean yields the default of 18 without dereferencing the
     * handle (handle available FALSE arm) — the template still renders.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ageCheckBirthYearDefaultsWhenBeanUnavailable() {
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<PosState> handle = mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(false);
        when(container.instance(PosState.class)).thenReturn(handle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(java.time.LocalDate.now().getYear() - 18,
                    ThemeService.Globals.ageCheckBirthYear());
            verify(handle, never()).get();
        }
    }

    /**
     * Any resolution failure is swallowed by the catch guard: the global
     * still returns the legal default rather than breaking the rendering of
     * the ID-check keypad (catch arm).
     */
    @Test
    void ageCheckBirthYearDefaultsWhenResolutionThrows() {
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new RuntimeException("boom"));
            assertEquals(java.time.LocalDate.now().getYear() - 18,
                    ThemeService.Globals.ageCheckBirthYear());
        }
    }

    // --------------------------------------------------
    // Globals.supervisorLogged
    // --------------------------------------------------

    /**
     * Both beans available AND the operator supervising drives the connected
     * shortcut: the compound {@code &&} takes its all-true arm (service
     * available true, state available true, {@code operatorIsSupervisor} true).
     */
    @Test
    @SuppressWarnings("unchecked")
    void supervisorLoggedReturnsTrueWhenBothAvailableAndSupervising() {
        PosState state = new PosState();
        EndorsementService svc = mock(EndorsementService.class);
        when(svc.operatorIsSupervisor(state)).thenReturn(true);
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<EndorsementService> svcHandle = mock(InstanceHandle.class);
        when(svcHandle.isAvailable()).thenReturn(true);
        when(svcHandle.get()).thenReturn(svc);
        InstanceHandle<PosState> stHandle = mock(InstanceHandle.class);
        when(stHandle.isAvailable()).thenReturn(true);
        when(stHandle.get()).thenReturn(state);
        when(container.instance(EndorsementService.class)).thenReturn(svcHandle);
        when(container.instance(PosState.class)).thenReturn(stHandle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(true, ThemeService.Globals.supervisorLogged());
        }
    }

    /**
     * Both beans available but the operator NOT supervising takes the third
     * condition's false arm ({@code operatorIsSupervisor} false): the modal
     * falls back to asking a credential.
     */
    @Test
    @SuppressWarnings("unchecked")
    void supervisorLoggedReturnsFalseWhenNotSupervising() {
        PosState state = new PosState();
        EndorsementService svc = mock(EndorsementService.class);
        when(svc.operatorIsSupervisor(state)).thenReturn(false);
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<EndorsementService> svcHandle = mock(InstanceHandle.class);
        when(svcHandle.isAvailable()).thenReturn(true);
        when(svcHandle.get()).thenReturn(svc);
        InstanceHandle<PosState> stHandle = mock(InstanceHandle.class);
        when(stHandle.isAvailable()).thenReturn(true);
        when(stHandle.get()).thenReturn(state);
        when(container.instance(EndorsementService.class)).thenReturn(svcHandle);
        when(container.instance(PosState.class)).thenReturn(stHandle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(false, ThemeService.Globals.supervisorLogged());
        }
    }

    /**
     * An available service but an UNAVAILABLE state short-circuits on the
     * second condition's false arm (service available true, state available
     * false): the supervising check is never reached.
     */
    @Test
    @SuppressWarnings("unchecked")
    void supervisorLoggedReturnsFalseWhenStateUnavailable() {
        EndorsementService svc = mock(EndorsementService.class);
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<EndorsementService> svcHandle = mock(InstanceHandle.class);
        when(svcHandle.isAvailable()).thenReturn(true);
        InstanceHandle<PosState> stHandle = mock(InstanceHandle.class);
        when(stHandle.isAvailable()).thenReturn(false);
        when(container.instance(EndorsementService.class)).thenReturn(svcHandle);
        when(container.instance(PosState.class)).thenReturn(stHandle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(false, ThemeService.Globals.supervisorLogged());
            verify(svc, never()).operatorIsSupervisor(stHandle.get());
        }
    }

    /**
     * An UNAVAILABLE service short-circuits on the first condition's false arm
     * (service available false): the state availability is never consulted.
     */
    @Test
    @SuppressWarnings("unchecked")
    void supervisorLoggedReturnsFalseWhenServiceUnavailable() {
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<EndorsementService> svcHandle = mock(InstanceHandle.class);
        when(svcHandle.isAvailable()).thenReturn(false);
        InstanceHandle<PosState> stHandle = mock(InstanceHandle.class);
        when(container.instance(EndorsementService.class)).thenReturn(svcHandle);
        when(container.instance(PosState.class)).thenReturn(stHandle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(false, ThemeService.Globals.supervisorLogged());
            verify(stHandle, never()).isAvailable();
        }
    }

    /**
     * Any resolution failure is swallowed by the catch guard (catch arm): the
     * global answers false so the endorsement modal simply asks a credential.
     */
    @Test
    void supervisorLoggedReturnsFalseWhenResolutionThrows() {
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new RuntimeException("boom"));
            assertEquals(false, ThemeService.Globals.supervisorLogged());
        }
    }

    // --------------------------------------------------
    // Globals.showEan
    // --------------------------------------------------

    /**
     * An available settings bean whose {@code showEan()} is true takes the
     * compound {@code &&} all-true arm (bean available true, setting true): the
     * EAN accompanies the label.
     */
    @Test
    @SuppressWarnings("unchecked")
    void showEanReturnsTrueWhenSettingEnabled() {
        PosSettingsService settings = mock(PosSettingsService.class);
        when(settings.showEan()).thenReturn(true);
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<PosSettingsService> handle = mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(true);
        when(handle.get()).thenReturn(settings);
        when(container.instance(PosSettingsService.class)).thenReturn(handle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(true, ThemeService.Globals.showEan());
        }
    }

    /**
     * An available settings bean whose {@code showEan()} is false takes the
     * second condition's false arm (bean available true, setting false): the
     * EAN is hidden.
     */
    @Test
    @SuppressWarnings("unchecked")
    void showEanReturnsFalseWhenSettingDisabled() {
        PosSettingsService settings = mock(PosSettingsService.class);
        when(settings.showEan()).thenReturn(false);
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<PosSettingsService> handle = mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(true);
        when(handle.get()).thenReturn(settings);
        when(container.instance(PosSettingsService.class)).thenReturn(handle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(false, ThemeService.Globals.showEan());
        }
    }

    /**
     * An UNAVAILABLE settings bean short-circuits on the first condition's
     * false arm (bean available false): the setting is never dereferenced.
     */
    @Test
    @SuppressWarnings("unchecked")
    void showEanReturnsFalseWhenBeanUnavailable() {
        ArcContainer container = mock(ArcContainer.class);
        InstanceHandle<PosSettingsService> handle = mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(false);
        when(container.instance(PosSettingsService.class)).thenReturn(handle);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);
            assertEquals(false, ThemeService.Globals.showEan());
            verify(handle, never()).get();
        }
    }

    /**
     * Any resolution failure is swallowed by the catch guard (catch arm): the
     * global answers false rather than breaking the rendering.
     */
    @Test
    void showEanReturnsFalseWhenResolutionThrows() {
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new RuntimeException("boom"));
            assertEquals(false, ThemeService.Globals.showEan());
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

    // --------------------------------------------------
    // BO-10-07-08 : écran caisse du mode école
    // --------------------------------------------------

    /**
     * In training, the administered training theme wins the whole chain, so the
     * school screen is visibly another screen than the sale one (training arm,
     * non-blank arm). The operator's own preference is NOT consulted.
     */
    @Test
    void trainingTakesTheAdministeredTrainingTheme() {
        ThemeService service = newService();
        service.state.trainingMode = true;
        when(service.posSettingsService.trainingTheme()).thenReturn("clair");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            assertEquals("clair", service.currentTheme());
        }
    }

    /**
     * A SECOND administered value lands as itself, which is what proves the
     * setting is read rather than a literal returned.
     */
    @Test
    void trainingTakesASecondAdministeredTrainingTheme() {
        ThemeService service = newService();
        service.state.trainingMode = true;
        when(service.posSettingsService.trainingTheme()).thenReturn("sombre");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            assertEquals("sombre", service.currentTheme());
        }
    }

    /**
     * An administered value padded with spaces is trimmed before it lands on
     * the {@code data-theme} attribute.
     */
    @Test
    void theAdministeredTrainingThemeIsTrimmed() {
        ThemeService service = newService();
        service.state.trainingMode = true;
        when(service.posSettingsService.trainingTheme()).thenReturn("  clair  ");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            assertEquals("clair", service.currentTheme());
        }
    }

    /**
     * A BLANK administered value leaves training on the ordinary resolution —
     * the store's theme here (blank arm, the leg a null check alone would miss).
     */
    @Test
    void aBlankTrainingThemeKeepsTheOrdinaryChain() {
        ThemeService service = newService();
        service.state.trainingMode = true;
        when(service.posSettingsService.trainingTheme()).thenReturn("   ");
        Store store = new Store();
        store.theme = "clair";
        // The query mock is built BEFORE the static mock: building a mock
        // inside the block confuses Mockito's stubbing state (see above).
        PanacheQuery<Store> query = storeQuery(store);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(Store::findAll).thenReturn(query);
            assertEquals("clair", service.currentTheme());
        }
    }

    /**
     * A NULL administered value behaves like a blank one (null arm).
     */
    @Test
    void aNullTrainingThemeKeepsTheOrdinaryChain() {
        ThemeService service = newService();
        service.state.trainingMode = true;
        when(service.posSettingsService.trainingTheme()).thenReturn(null);
        PanacheQuery<Store> query = storeQuery(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(Store::findAll).thenReturn(query);
            assertEquals(ThemeService.DEFAULT_THEME, service.currentTheme());
        }
    }

    /**
     * OUTSIDE training, the administered training theme is ignored entirely —
     * the setting is never even read (training false arm).
     */
    @Test
    void theSaleScreenIgnoresTheTrainingTheme() {
        ThemeService service = newService();
        service.state.trainingMode = false;
        when(service.posSettingsService.trainingTheme()).thenReturn("clair");
        PanacheQuery<Store> query = storeQuery(null);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(Store::findAll).thenReturn(query);
            assertEquals(ThemeService.DEFAULT_THEME, service.currentTheme());
        }
        verify(service.posSettingsService, never()).trainingTheme();
    }
}
