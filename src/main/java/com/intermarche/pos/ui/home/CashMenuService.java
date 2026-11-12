package com.intermarche.pos.ui.home;

import com.intermarche.pos.domain.store.CashFunction;
import com.intermarche.pos.domain.store.CashMenu;
import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.domain.store.MenuLayout;
import com.intermarche.pos.service.CheckoutPointService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosMenu;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the CASH MENU this register draws and turns it into what the sale
 * screen and the customer screen apply (BO-03-09-06, BO-03-10-09, BO-03-10-16,
 * BO-03-10-17, BO-03-10-18).
 *
 * <p>THE PRIORITY RULE. The model named on this register's own checkout point
 * wins; otherwise the {@code menu.code} parameter decides, which the store node
 * has already resolved for this register through the island, the PDV, the
 * enseigne and the country. No model, an unknown or an inactive one: every key
 * is drawn, as before the models existed.
 *
 * <p>WHAT A MODEL CHANGES. The keys of the menu bar and their order; the
 * function keys hidden from the grid; the number of key columns, fixed or
 * proportional to the width of the cashier screen the point declares; the size
 * of the keys; the density of the virtual ticket; the model of the customer
 * screen. The functions whose ACTIVATION is off are hidden whatever the model
 * says: a deactivated discount, a drawer without driver, a single queue not
 * administered never show a key that would only answer « refused ».
 */
@ApplicationScoped
public class CashMenuService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashMenuService.class);

    /** The width, in pixels, one key column is given in the proportional layout. */
    static final int PIXELS_PER_COLUMN = 640;

    /** The administered parameters. */
    @Inject
    PosSettingsService posSettingsService;

    /** This register's own checkout point. */
    @Inject
    CheckoutPointService checkoutPointService;

    /** The register's labels, for the language the keys are labelled in. */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * What the screens apply of the resolved model.
     *
     * @param code the code of the model in force, or null when none is
     * @param menus the menu-bar keys to draw, in order
     * @param css the style rules of the sale screen
     * @param customerCss the style rules of the customer screen
     * @param tabs the tabs of the bottom bar, in order, each with its keys
     */
    @io.quarkus.qute.TemplateData
    public record View(String code, List<PosMenu> menus, String css, String customerCss, List<TabView> tabs) {

        /**
         * Builds a view without key layout, every standard tab drawn as before.
         *
         * @param code the code of the model in force, or null when none is
         * @param menus the menu-bar keys to draw, in order
         * @param css the style rules of the sale screen
         * @param customerCss the style rules of the customer screen
         */
        public View(String code, List<PosMenu> menus, String css, String customerCss) {
            this(code, menus, css, customerCss, List.of());
        }

        /**
         * The tab whose keys the grid shows: the one asked for, otherwise the
         * first tab with keys — a tab the layout no longer has never leaves
         * the cashier in front of an empty grid.
         *
         * @param id the tab asked for
         * @return the tab, or null when no tab carries keys
         */
        public TabView shown(String id) {
            TabView first = null;
            for (TabView tab : tabs) {
                if (tab.id().equals(id) && !tab.direct()) {
                    return tab;
                }
                if (first == null && !tab.direct()) {
                    first = tab;
                }
            }
            return first;
        }
    }

    /**
     * One tab of the bottom bar, as the layout gives it.
     *
     * @param id the tab id, a standard menu key or a created tab
     * @param labelKey the bundle key of the standard label, null for a created tab
     * @param label the administered label in the register's language, or null
     * @param color the administered colour, or null for the standard one
     * @param destination the screen the tab opens straight away, or null for a grid
     * @param keys the keys of the grid, the hidden functions left out
     */
    @io.quarkus.qute.TemplateData
    public record TabView(String id, String labelKey, String label, String color, String destination,
                          List<KeyView> keys) {

        /**
         * Tells whether the tab opens a screen instead of showing keys.
         *
         * @return true for the settlement tab
         */
        public boolean direct() {
            return destination != null;
        }

        /**
         * The style class of the tab when another one is shown: the muted face,
         * with its colour as a band when it has one.
         *
         * @return the class
         */
        public String cls() {
            return color == null ? "btn-muted" : "btn-c-" + color + " tab-idle";
        }
    }

    /**
     * One key of a tab's grid, as the layout gives it.
     *
     * @param function the function of the key
     * @param label the administered label in the register's language, or null
     * @param color the administered colour, or null for the standard one
     * @param i18n the register's labels, for the standard label
     */
    @io.quarkus.qute.TemplateData
    public record KeyView(CashFunction function, String label, String color, com.intermarche.pos.ui.Messages i18n) {

        /**
         * The function's name, what the screen tells the keys apart by.
         *
         * @return the name
         */
        public String fn() {
            return function.name();
        }

        /**
         * The text of the key at rest: its administered label, else the
         * standard one.
         *
         * @param bundleKey the bundle key of the standard label
         * @return the text
         */
        public String text(String bundleKey) {
            return label != null ? label : i18n.get(bundleKey);
        }

        /**
         * The text of a key whose standard label is not a bundle entry (an
         * administered name): its administered label, else that name.
         *
         * @param standard the standard text
         * @return the text
         */
        public String plain(String standard) {
            return label != null ? label : standard;
        }

        /**
         * The style class of the key at rest: its colour, else its standard one.
         *
         * @param standard the standard class
         * @return the class
         */
        public String cls(String standard) {
            return color == null ? standard : "btn-c-" + color;
        }

        /**
         * Tells whether the key keeps its standard colour.
         *
         * @return true when no colour is administered
         */
        public boolean standardColor() {
            return color == null;
        }
    }

    /**
     * Resolves the model of this register and what the screens apply of it.
     *
     * @return the view, never null
     */
    public View current() {
        LOGGER.info("Entering method current");
        CheckoutPoint point = checkoutPointService.own();
        CashMenu menu = resolve(point);
        View view = new View(menu == null ? null : menu.code, menus(menu), css(menu, point),
                customerCss(menu), tabs(menu));
        LOGGER.info("Exiting method current");
        return view;
    }

    /**
     * Resolves the model in force on this register: the one its checkout point names,
     * otherwise the {@code menu.code} parameter; an unknown or inactive model is none.
     *
     * @param point this register's checkout point, or null
     * @return the active model, or null when none is in force
     */
    private CashMenu resolve(CheckoutPoint point) {
        String code = point == null || point.menuTemplateCode == null || point.menuTemplateCode.isBlank()
                ? posSettingsService.menuCode() : point.menuTemplateCode.trim();
        CashMenu menu = CashMenu.findByCode(code);
        return menu != null && !menu.active ? null : menu;
    }

    /**
     * Tells which function the model in force HIDES that a request reaches
     * ({@code LS-12-02-03}, {@code BO-03-09-06}).
     *
     * <p>A HIDDEN KEY IS A CLOSED FUNCTION. Removing the key from the grid only removes
     * the way a cashier usually reaches the function; its address stays typeable, and
     * a function the back office switched off — the invoice of a shop that issues
     * none — must not answer it. The test is made once, here, for every function the
     * model can hide, rather than in each screen.
     *
     * <p>The functions hidden because their ACTIVATION is off are not this method's:
     * each refuses itself with its own message (discounts deactivated, no drawer
     * driver, no single queue). The model is resolved only when the request reaches a
     * function at all, so the polls the screens run every second cost nothing.
     *
     * @param path  the request path, with its leading slash
     * @param query the request query parameters
     * @return the hidden function the request reaches, or null when it reaches none
     */
    public CashFunction hiddenFunctionReached(String path, java.util.Map<String, List<String>> query) {
        List<CashFunction> reached = new ArrayList<>();
        for (CashFunction function : CashFunction.values()) {
            for (String functionPath : function.getPaths()) {
                if (reaches(functionPath, path, query)) {
                    reached.add(function);
                }
            }
        }
        if (reached.isEmpty()) {
            return null;
        }
        CashMenu menu = resolve(checkoutPointService.own());
        if (menu == null) {
            return null;
        }
        List<CashFunction> hidden = menu.hiddenFunctionList();
        for (CashFunction function : reached) {
            if (hidden.contains(function)) {
                return function;
            }
        }
        return null;
    }

    /**
     * Tells whether a request reaches a function's address: the address itself or any
     * address under it, and, when the function's address names query parameters, only
     * with those parameters ({@code /manual?cancel=true} is the group cancellation, not
     * the direct entry).
     *
     * @param functionPath the function's address, possibly with {@code key=value} pairs
     * @param path         the request path
     * @param query        the request query parameters
     * @return true when the request reaches the function
     */
    static boolean reaches(String functionPath, String path, java.util.Map<String, List<String>> query) {
        int mark = functionPath.indexOf('?');
        String base = mark < 0 ? functionPath : functionPath.substring(0, mark);
        if (!path.equals(base) && !path.startsWith(base + "/")) {
            return false;
        }
        if (mark < 0) {
            return true;
        }
        for (String pair : functionPath.substring(mark + 1).split("&")) {
            String[] parameter = pair.split("=", 2);
            if (!query.getOrDefault(parameter[0], List.of()).contains(parameter[1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * The menu-bar keys a model shows, every key when it names none.
     *
     * @param menu the model, or null
     * @return the keys, in the model's order
     */
    List<PosMenu> menus(CashMenu menu) {
        List<String> keys = menu == null ? List.of() : menu.menuKeyList();
        if (keys.isEmpty()) {
            return PosMenu.ALL;
        }
        List<PosMenu> menus = new ArrayList<>();
        for (String key : keys) {
            menus.add(PosMenu.of(key));
        }
        return menus;
    }

    /**
     * The functions hidden: those of the model and those whose activation is
     * off.
     *
     * @param menu the model, or null
     * @return the hidden functions
     */
    Set<CashFunction> hidden(CashMenu menu) {
        Set<CashFunction> hidden = new LinkedHashSet<>();
        if (menu != null) {
            hidden.addAll(menu.hiddenFunctionList());
        }
        if (!posSettingsService.discountEnabled()) {
            hidden.add(CashFunction.DISCOUNT);
            hidden.add(CashFunction.TICKET_DISCOUNT);
        }
        if (!posSettingsService.hardwareEnabled("drawer")) {
            hidden.add(CashFunction.DRAWER_OPEN);
        }
        if (!posSettingsService.singleQueueEnabled()) {
            hidden.add(CashFunction.SINGLE_QUEUE);
        }
        return hidden;
    }

    /**
     * Builds the style rules of the sale screen.
     *
     * @param menu the model, or null
     * @param point this register's checkout point, or null
     * @return the rules, possibly empty
     */
    String css(CashMenu menu, CheckoutPoint point) {
        StringBuilder css = new StringBuilder();
        List<String> selectors = new ArrayList<>();
        for (CashFunction function : hidden(menu)) {
            for (String path : function.getPaths()) {
                selectors.add(".grid-buttons a[href=\"" + path + "\"]");
                selectors.add(".grid-buttons form[action=\"" + path + "\"]");
            }
        }
        if (!selectors.isEmpty()) {
            css.append(String.join(",", selectors)).append("{display:none !important;}");
        }
        int columns = columns(menu, point);
        if (columns != 2) {
            css.append(".grid-buttons{grid-template-columns:repeat(").append(columns)
                    .append(",minmax(0,1fr));}");
        }
        String screen = menu == null ? "STANDARD" : CashMenu.normalized(menu.cashierScreen, CashMenu.CASHIER_SCREENS);
        if ("COMPACT".equals(screen)) {
            css.append(".grid-buttons .btn{font-size:var(--fs-row-sub);}");
        } else if ("LARGE".equals(screen)) {
            css.append(".grid-buttons .btn{font-size:var(--fs-amount);}");
        }
        String ticket = menu == null ? "DETAILED" : CashMenu.normalized(menu.ticketModel, CashMenu.TICKET_MODELS);
        if ("COMPACT".equals(ticket)) {
            css.append(".receipt-item{padding-top:4px;padding-bottom:3px;font-size:var(--fs-row-sub);}");
        }
        return css.toString();
    }

    /**
     * The number of key columns: the model's when it fixes one, otherwise
     * proportional to the width of the cashier screen the point declares
     * ({@code 1920x1080} gives three), two when nothing is known.
     *
     * @param menu the model, or null
     * @param point this register's checkout point, or null
     * @return the number of columns, between 1 and {@link CashMenu#MAX_KEY_COLUMNS}
     */
    int columns(CashMenu menu, CheckoutPoint point) {
        if (menu != null && menu.keyColumns > 0) {
            return Math.min(CashMenu.MAX_KEY_COLUMNS, menu.keyColumns);
        }
        int width = width(point == null ? null : point.cashierDisplayResolution);
        if (width <= 0) {
            return 2;
        }
        return Math.max(2, Math.min(CashMenu.MAX_KEY_COLUMNS, width / PIXELS_PER_COLUMN));
    }

    /**
     * Reads the width of a resolution written {@code WIDTHxHEIGHT}.
     *
     * @param resolution the administered resolution, or null
     * @return the width in pixels, zero when unreadable
     */
    static int width(String resolution) {
        if (resolution == null) {
            return 0;
        }
        String[] parts = resolution.trim().toLowerCase().split("x");
        try {
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Builds the style rules of the customer screen.
     *
     * @param menu the model, or null
     * @return the rules, possibly empty
     */
    String customerCss(CashMenu menu) {
        String screen = menu == null ? "STANDARD" : CashMenu.normalized(menu.customerScreen, CashMenu.CUSTOMER_SCREENS);
        if ("TOTAL_ONLY".equals(screen)) {
            return "#cartLines{display:none !important;}";
        }
        if ("LARGE".equals(screen)) {
            return "#cartLines{font-size:1.4em;}";
        }
        return "";
    }

    /**
     * The tabs of the bottom bar: the model's layout — or the standard one —
     * in the register's language, the functions hidden left out of the grids.
     *
     * @param menu the model, or null
     * @return the tabs, in order
     */
    List<TabView> tabs(CashMenu menu) {
        MenuLayout layout = menu == null ? MenuLayout.standard() : menu.effectiveLayout();
        Set<CashFunction> hidden = hidden(menu);
        String language = language();
        List<TabView> tabs = new ArrayList<>();
        for (MenuLayout.Tab tab : layout.tabs) {
            List<KeyView> keys = new ArrayList<>();
            for (MenuLayout.Key key : tab.keys) {
                CashFunction function = key.function();
                if (!hidden.contains(function)) {
                    keys.add(new KeyView(function, key.label(language), key.color, i18n));
                }
            }
            PosMenu standard = tab.custom() ? null : PosMenu.of(tab.id);
            tabs.add(new TabView(tab.id, standard == null ? null : standard.getLabelKey(),
                    tabLabel(tab, language), tab.color, standard == null ? null : standard.getDestination(), keys));
        }
        return tabs;
    }

    /**
     * The administered label of a tab in a language; a created tab without
     * one in that language takes its French one, else any, else a plain name.
     *
     * @param tab the tab
     * @param language the language
     * @return the label, or null for a standard tab keeping its standard label
     */
    static String tabLabel(MenuLayout.Tab tab, String language) {
        String label = tab.label(language);
        if (label != null || !tab.custom()) {
            return label;
        }
        if (tab.label("fr") != null) {
            return tab.label("fr");
        }
        return tab.labels.isEmpty() ? tab.id.toUpperCase(java.util.Locale.ROOT) : tab.labels.values().iterator().next();
    }

    /**
     * The register's language, as the layout's labels are keyed.
     *
     * @return the two-letter language, fr when unknown
     */
    String language() {
        String tag = i18n == null ? null : i18n.getLang();
        return tag == null || tag.length() < 2 ? "fr" : tag.substring(0, 2).toLowerCase(java.util.Locale.ROOT);
    }
}
