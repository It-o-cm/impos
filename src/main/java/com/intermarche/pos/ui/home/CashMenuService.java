package com.intermarche.pos.ui.home;

import com.intermarche.pos.domain.store.CashFunction;
import com.intermarche.pos.domain.store.CashMenu;
import com.intermarche.pos.domain.store.CheckoutPoint;
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

    /**
     * What the screens apply of the resolved model.
     *
     * @param code the code of the model in force, or null when none is
     * @param menus the menu-bar keys to draw, in order
     * @param css the style rules of the sale screen
     * @param customerCss the style rules of the customer screen
     */
    public record View(String code, List<PosMenu> menus, String css, String customerCss) {
    }

    /**
     * Resolves the model of this register and what the screens apply of it.
     *
     * @return the view, never null
     */
    public View current() {
        LOGGER.info("Entering method current");
        CheckoutPoint point = checkoutPointService.own();
        String code = point == null || point.menuTemplateCode == null || point.menuTemplateCode.isBlank()
                ? posSettingsService.menuCode() : point.menuTemplateCode.trim();
        CashMenu menu = CashMenu.findByCode(code);
        if (menu != null && !menu.active) {
            menu = null;
        }
        View view = new View(menu == null ? null : menu.code, menus(menu), css(menu, point),
                customerCss(menu));
        LOGGER.info("Exiting method current");
        return view;
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
}
