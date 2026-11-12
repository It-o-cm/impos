package com.intermarche.pos.ui.home;

import com.intermarche.pos.domain.store.CashFunction;
import com.intermarche.pos.domain.store.CashMenu;
import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.service.CheckoutPointService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosMenu;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashMenuService} (BO-03-09-06, BO-03-10-09, -16, -17,
 * -18): the priority of the point over the parameter, the inactive and unknown
 * models, the menu bar, the hidden keys — by the model and by activation —, the
 * columns fixed or proportional, the key size, the virtual ticket and the
 * customer screen.
 */
class CashMenuServiceTest {

    /**
     * Builds a service whose activations are all on.
     *
     * @param point the register's checkout point, or null
     * @param menuCode the resolved menu parameter
     * @return the service
     */
    private CashMenuService service(CheckoutPoint point, String menuCode) {
        CashMenuService service = new CashMenuService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.checkoutPointService = mock(CheckoutPointService.class);
        when(service.checkoutPointService.own()).thenReturn(point);
        when(service.posSettingsService.menuCode()).thenReturn(menuCode);
        when(service.posSettingsService.discountEnabled()).thenReturn(true);
        when(service.posSettingsService.hardwareEnabled("drawer")).thenReturn(true);
        when(service.posSettingsService.singleQueueEnabled()).thenReturn(true);
        return service;
    }

    /**
     * Stubs one model lookup.
     *
     * @param panache the static mock
     * @param code the code
     * @param menu the model answered, or null
     */
    @SuppressWarnings("unchecked")
    private void stubMenu(MockedStatic<PanacheEntityBase> panache, String code, CashMenu menu) {
        PanacheQuery<CashMenu> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(menu);
        panache.when(() -> CashMenu.find("code", code)).thenReturn(query);
    }

    /** The model named on the point wins over the parameter, and shapes both screens. */
    @Test
    void thePointsModelWinsAndShapesTheScreens() {
        CheckoutPoint point = new CheckoutPoint();
        point.menuTemplateCode = " SLS ";
        CashMenu menu = new CashMenu();
        menu.code = "SLS";
        menu.menuKeys = "articles;reglement";
        menu.hiddenFunctions = "ABANDON";
        menu.keyColumns = 3;
        menu.cashierScreen = "LARGE";
        menu.customerScreen = "TOTAL_ONLY";
        menu.ticketModel = "COMPACT";
        CashMenuService service = service(point, "ISLAND");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMenu(panache, "SLS", menu);
            CashMenuService.View view = service.current();
            assertEquals("SLS", view.code());
            assertEquals(List.of(PosMenu.ARTICLES, PosMenu.REGLEMENT), view.menus());
            assertTrue(view.css().startsWith(".grid-buttons a[href=\"/abandon\"],.grid-buttons form[action=\"/abandon\"]{display:none !important;}"));
            assertTrue(view.css().contains("repeat(3,minmax(0,1fr))"));
            assertTrue(view.css().contains("var(--fs-amount)"));
            assertTrue(view.css().contains(".receipt-item{"));
            assertEquals("#cartLines{display:none !important;}", view.customerCss());
        }
    }

    /** Without a point the parameter decides; an inactive model is no model. */
    @Test
    void theParameterDecidesAndAnInactiveModelIsIgnored() {
        CashMenu inactive = new CashMenu();
        inactive.code = "OFF";
        inactive.active = false;
        CashMenuService service = service(null, "OFF");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMenu(panache, "OFF", inactive);
            CashMenuService.View view = service.current();
            assertNull(view.code());
            assertEquals(PosMenu.ALL, view.menus());
            assertEquals("", view.css());
            assertEquals("", view.customerCss());
        }
    }

    /** A point without a model, or with a blank one, leaves the parameter decide; no model draws everything. */
    @Test
    void aPointWithoutAModelDefersToTheParameter() {
        CheckoutPoint point = new CheckoutPoint();
        point.menuTemplateCode = " ";
        CashMenuService service = service(point, "");
        CashMenuService.View view = service.current();
        assertNull(view.code());
        CheckoutPoint none = new CheckoutPoint();
        assertNull(service(none, "").current().code());
    }

    /** A deactivated discount, drawer or single queue hides its keys whatever the model. */
    @Test
    void theDeactivatedFunctionsAreHidden() {
        CashMenuService service = service(null, "");
        when(service.posSettingsService.discountEnabled()).thenReturn(false);
        when(service.posSettingsService.hardwareEnabled("drawer")).thenReturn(false);
        when(service.posSettingsService.singleQueueEnabled()).thenReturn(false);
        String css = service.css(null, null);
        assertTrue(css.contains("/action/price-mod/remise"));
        assertTrue(css.contains("/action/price-mod/global_remise"));
        assertTrue(css.contains("/drawer-open"));
        assertTrue(css.contains("/action/single-queue"));
    }

    /** A model naming no key shows them all; the compact cashier screen shrinks the keys. */
    @Test
    void anEmptyBarShowsEveryKeyAndCompactShrinks() {
        CashMenuService service = service(null, "");
        CashMenu menu = new CashMenu();
        menu.cashierScreen = "COMPACT";
        menu.customerScreen = "LARGE";
        assertEquals(PosMenu.ALL, service.menus(menu));
        assertTrue(service.css(menu, null).contains("var(--fs-row-sub)"));
        assertEquals("#cartLines{font-size:1.4em;}", service.customerCss(menu));
    }

    /**
     * The columns are fixed by the model (bounded), else proportional to the
     * administered resolution, two when nothing is readable.
     */
    @Test
    void theColumnsFollowTheModelOrTheScreen() {
        CashMenuService service = service(null, "");
        CashMenu fixed = new CashMenu();
        fixed.keyColumns = 9;
        assertEquals(4, service.columns(fixed, null));
        CheckoutPoint wide = new CheckoutPoint();
        wide.cashierDisplayResolution = "1920x1080";
        assertEquals(3, service.columns(null, wide));
        assertEquals(3, service.columns(new CashMenu(), wide));
        CheckoutPoint small = new CheckoutPoint();
        small.cashierDisplayResolution = "800x600";
        assertEquals(2, service.columns(null, small));
        CheckoutPoint huge = new CheckoutPoint();
        huge.cashierDisplayResolution = "3840x2160";
        assertEquals(4, service.columns(null, huge));
        CheckoutPoint garbage = new CheckoutPoint();
        garbage.cashierDisplayResolution = "HD";
        assertEquals(2, service.columns(null, garbage));
        assertEquals(2, service.columns(null, new CheckoutPoint()));
        assertEquals(2, service.columns(null, null));
        assertTrue(service.css(null, wide).contains("repeat(3"));
    }

    /**
     * A model hiding the invoice closes its address and every address under it, and the
     * invoice of the Portuguese fiscal screen with it; a function the model does not hide
     * stays open (LS-12-02-03).
     */
    @Test
    void aHiddenFunctionIsReachedByItsAddresses() {
        CashMenu menu = new CashMenu();
        menu.code = "NOFAC";
        menu.hiddenFunctions = "INVOICE";
        CashMenuService service = service(null, "NOFAC");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMenu(panache, "NOFAC", menu);
            assertEquals(CashFunction.INVOICE, service.hiddenFunctionReached("/invoice", java.util.Map.of()));
            assertEquals(CashFunction.INVOICE, service.hiddenFunctionReached("/invoice/print", java.util.Map.of()));
            assertEquals(CashFunction.INVOICE, service.hiddenFunctionReached("/fiscal-pt", java.util.Map.of()));
            assertNull(service.hiddenFunctionReached("/reprint", java.util.Map.of()));
        }
    }

    /**
     * An address no function owns never resolves the model at all: the polls the screens
     * run every second cost no lookup.
     */
    @Test
    void anAddressOfNoFunctionResolvesNothing() {
        CashMenuService service = service(null, "NOFAC");
        assertNull(service.hiddenFunctionReached("/ticket-fragment", java.util.Map.of()));
        assertNull(service.hiddenFunctionReached("/invoices", java.util.Map.of()));
        org.mockito.Mockito.verifyNoInteractions(service.checkoutPointService);
    }

    /**
     * Without a model in force — none administered, or an inactive one — nothing is
     * refused.
     */
    @Test
    void withoutAModelNothingIsRefused() {
        CashMenu inactive = new CashMenu();
        inactive.code = "OFF";
        inactive.active = false;
        inactive.hiddenFunctions = "INVOICE";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMenu(panache, "OFF", inactive);
            assertNull(service(null, "").hiddenFunctionReached("/invoice", java.util.Map.of()));
            assertNull(service(null, "OFF").hiddenFunctionReached("/invoice", java.util.Map.of()));
        }
    }

    /**
     * A function whose address names a query parameter is reached only with it: the group
     * cancellation hidden leaves the direct entry open, the direct entry hidden closes
     * both.
     */
    @Test
    void aQueryAddressIsReachedOnlyWithItsParameter() {
        CashMenu group = new CashMenu();
        group.code = "NOGROUP";
        group.hiddenFunctions = "CANCEL_GROUP";
        CashMenu manual = new CashMenu();
        manual.code = "NOMANUAL";
        manual.hiddenFunctions = "MANUAL";
        java.util.Map<String, List<String>> cancel = java.util.Map.of("cancel", List.of("true"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMenu(panache, "NOGROUP", group);
            stubMenu(panache, "NOMANUAL", manual);
            assertEquals(CashFunction.CANCEL_GROUP, service(null, "NOGROUP").hiddenFunctionReached("/manual", cancel));
            assertNull(service(null, "NOGROUP").hiddenFunctionReached("/manual", java.util.Map.of()));
            assertNull(service(null, "NOGROUP").hiddenFunctionReached("/manual",
                    java.util.Map.of("cancel", List.of("false"))));
            assertEquals(CashFunction.MANUAL, service(null, "NOMANUAL").hiddenFunctionReached("/manual", cancel));
            assertEquals(CashFunction.MANUAL, service(null, "NOMANUAL").hiddenFunctionReached("/manual", java.util.Map.of()));
        }
    }

    /**
     * The address test: the address itself, an address under it, never a mere prefix of
     * another word, and every named parameter required.
     */
    @Test
    void theAddressTestKeepsToWholeSegments() {
        assertTrue(CashMenuService.reaches("/return", "/return", java.util.Map.of()));
        assertTrue(CashMenuService.reaches("/return", "/return/confirm", java.util.Map.of()));
        assertFalse(CashMenuService.reaches("/return", "/return-mode", java.util.Map.of()));
        assertFalse(CashMenuService.reaches("/a?x=1&y=2", "/a", java.util.Map.of("x", List.of("1"))));
        assertTrue(CashMenuService.reaches("/a?x=1&y=2", "/a",
                java.util.Map.of("x", List.of("1"), "y", List.of("2"))));
    }

    /**
     * The tabs follow the model's layout in the register's language: a created
     * tab with its own label and colour, a standard tab with its bundle key,
     * the settlement tab opening its screen, the hidden functions left out.
     */
    @Test
    void theTabsFollowTheLayoutInTheRegistersLanguage() {
        CashMenu menu = new CashMenu();
        menu.code = "SLS";
        menu.hiddenFunctions = "ABANDON";
        menu.layout = "{\"tabs\":[{\"id\":\"x1\",\"labels\":{\"fr\":\"PROMO\",\"nl\":\"ACTIE\"},\"color\":\"orange\","
                + "\"keys\":[{\"fn\":\"SEARCH\",\"labels\":{\"nl\":\"ZOEK\"},\"color\":\"teal\"},{\"fn\":\"ABANDON\"}]},"
                + "{\"id\":\"ticket\",\"keys\":[{\"fn\":\"PARK\"}]},{\"id\":\"reglement\"}]}";
        CashMenuService service = service(null, "SLS");
        service.i18n = mock(com.intermarche.pos.ui.Messages.class);
        when(service.i18n.getLang()).thenReturn("nl-BE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubMenu(panache, "SLS", menu);
            List<CashMenuService.TabView> tabs = service.current().tabs();
            assertEquals(3, tabs.size());
            CashMenuService.TabView created = tabs.get(0);
            assertEquals("x1", created.id());
            assertNull(created.labelKey());
            assertEquals("ACTIE", created.label());
            assertEquals("orange", created.color());
            assertNull(created.destination());
            assertEquals(1, created.keys().size());
            assertEquals(CashFunction.SEARCH, created.keys().get(0).function());
            assertEquals("ZOEK", created.keys().get(0).label());
            assertEquals("teal", created.keys().get(0).color());
            assertEquals("menu.ticket", tabs.get(1).labelKey());
            assertNull(tabs.get(1).label());
            assertEquals("/pay", tabs.get(2).destination());
        }
    }

    /** Without a model the tabs are the standard ones, every key in place. */
    @Test
    void withoutAModelTheTabsAreTheStandardOnes() {
        CashMenuService service = service(null, "");
        List<CashMenuService.TabView> tabs = service.tabs(null);
        assertEquals(7, tabs.size());
        assertEquals("articles", tabs.get(0).id());
        assertEquals(13, tabs.get(0).keys().size());
    }

    /**
     * A standard tab keeps its standard label when it has none in the language;
     * a created tab falls back on its French label, then on any, then on its id.
     */
    @Test
    void theLabelOfATab() {
        com.intermarche.pos.domain.store.MenuLayout.Tab standard = new com.intermarche.pos.domain.store.MenuLayout.Tab();
        standard.id = "caisse";
        assertNull(CashMenuService.tabLabel(standard, "nl"));
        com.intermarche.pos.domain.store.MenuLayout.Tab created = new com.intermarche.pos.domain.store.MenuLayout.Tab();
        created.id = "x3";
        assertEquals("X3", CashMenuService.tabLabel(created, "nl"));
        created.labels.put("pt", "PROMOÇÃO");
        assertEquals("PROMOÇÃO", CashMenuService.tabLabel(created, "nl"));
        created.labels.put("fr", "PROMO");
        assertEquals("PROMO", CashMenuService.tabLabel(created, "nl"));
        created.labels.put("nl", "ACTIE");
        assertEquals("ACTIE", CashMenuService.tabLabel(created, "nl"));
    }

    /** The register's language is the two first letters of its tag, French when unknown. */
    @Test
    void theRegistersLanguage() {
        CashMenuService service = service(null, "");
        assertEquals("fr", service.language());
        service.i18n = mock(com.intermarche.pos.ui.Messages.class);
        assertEquals("fr", service.language());
        when(service.i18n.getLang()).thenReturn("p");
        assertEquals("fr", service.language());
        when(service.i18n.getLang()).thenReturn("PT-pt");
        assertEquals("pt", service.language());
    }

    /**
     * The grid shows the tab asked for; the settlement tab, an unknown tab or a
     * view without tabs fall back on the first tab with keys, or on none.
     */
    @Test
    void theTabShown() {
        CashMenuService.TabView pay = new CashMenuService.TabView("reglement", "menu.reglement", null, null, "/pay", List.of());
        CashMenuService.TabView sale = new CashMenuService.TabView("articles", "menu.articles", null, null, null, List.of());
        CashMenuService.TabView promo = new CashMenuService.TabView("x1", null, "PROMO", null, null, List.of());
        CashMenuService.View view = new CashMenuService.View(null, List.of(), "", "", List.of(pay, sale, promo));
        assertEquals(promo, view.shown("x1"));
        assertEquals(sale, view.shown("reglement"));
        assertEquals(sale, view.shown("x9"));
        assertNull(new CashMenuService.View(null, List.of(), "", "", List.of(pay)).shown("reglement"));
        assertNull(new CashMenuService.View(null, List.of(), "", "").shown("articles"));
    }

    /** A tab tells whether it opens a screen, and how it is drawn when another is shown. */
    @Test
    void aTabsKindAndFace() {
        assertTrue(new CashMenuService.TabView("reglement", null, null, null, "/pay", List.of()).direct());
        CashMenuService.TabView plain = new CashMenuService.TabView("caisse", null, null, null, null, List.of());
        assertFalse(plain.direct());
        assertEquals("btn-muted", plain.cls());
        assertEquals("btn-c-red tab-idle", new CashMenuService.TabView("x1", null, null, "red", null, List.of()).cls());
    }

    /** A key gives its function name, its text and its colour, administered or standard. */
    @Test
    void aKeysTextAndColour() {
        com.intermarche.pos.ui.Messages i18n = mock(com.intermarche.pos.ui.Messages.class);
        when(i18n.get("main.recherche")).thenReturn("RECHERCHE");
        CashMenuService.KeyView standard = new CashMenuService.KeyView(CashFunction.SEARCH, null, null, i18n);
        assertEquals("SEARCH", standard.fn());
        assertEquals("RECHERCHE", standard.text("main.recherche"));
        assertEquals("MODE RETOUR", standard.plain("MODE RETOUR"));
        assertEquals("btn-muted", standard.cls("btn-muted"));
        assertTrue(standard.standardColor());
        CashMenuService.KeyView administered = new CashMenuService.KeyView(CashFunction.SEARCH, "CHERCHER", "blue", i18n);
        assertEquals("CHERCHER", administered.text("main.recherche"));
        assertEquals("CHERCHER", administered.plain("MODE RETOUR"));
        assertEquals("btn-c-blue", administered.cls("btn-muted"));
        assertFalse(administered.standardColor());
    }
}
