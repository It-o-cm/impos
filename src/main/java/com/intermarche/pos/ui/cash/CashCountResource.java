package com.intermarche.pos.ui.cash;

import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

/**
 * JAX-RS resource of the drawer-count page, the middle step of the Z
 * closing: {@code /action/session/close-start} routes here after its
 * checks, {@code /action/start-cash-count} opens the drawer, the page's
 * client-side calculator sums the typed denomination counts, and the
 * validated total (with its per-denomination JSON) is posted to
 * {@code /action/session/close}. Every display route is
 * {@code @DrawerMayBeOpen} — counting happens with the drawer open by
 * definition, while the rest of the register stays behind the drawer
 * guard.
 */
@Path("/")
public class CashCountResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashCountResource.class);

    @Inject @Location("cash-count") Template cashCount;
    @Inject HardwareService hardwareService;
    @Inject CashCountService cashCountService;
    /** The session report, read for the paper tenders to declare (EXP-83). */
    @Inject com.intermarche.pos.service.CashSessionService cashSessionService;
    @Inject
    PosState state;

    /**
     * Opens the drawer and routes to the counting page.
     *
     * @return a redirect to the counting page
     */
    @GET
    @Path("/action/start-cash-count")
    public Object startCashCount() {
        LOGGER.info("Entering method startCashCount");
        hardwareService.openDrawer();
        LOGGER.info("Exiting method startCashCount");
        return Response.seeOther(URI.create("/cash-count")).build();
    }

    /**
     * Shows the counting page, banknotes tab first.
     *
     * @return the counting page
     */
    @GET
    @Path("/cash-count")
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public TemplateInstance cashCountPage() {
        LOGGER.info("Entering method cashCountPage");
        // Affichage complet de la page (Billets par défaut)
        // EXP-83: the paper tenders the cashier declares by count, with their
        // theoretical quantities — empty when no session is open.
        com.intermarche.pos.domain.session.CashSession open = cashSessionService.getOpenSession();
        java.util.List<com.intermarche.pos.service.CashSessionService.SessionReport.PaperTenderRow> paperTenders =
                (open != null) ? cashSessionService.buildReport(open).getPaperTenders() : java.util.List.of();
        LOGGER.info("Exiting method cashCountPage");
        return cashCount
            .data("state", state)
            .data("items", cashCountService.getBills())
            .data("paperTenders", paperTenders)
            .data("fragment", false);
    }

    /**
     * Returns the denomination-list fragment of one tab (AJAX swap).
     *
     * @param type the tab: "coins", "rolls", anything else = banknotes
     * @return the list fragment
     */
    @GET
    @Path("/cash-count/fragment/{type}")
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public TemplateInstance getFragment(@PathParam("type") String type) {
        LOGGER.info("Entering method getFragment with type: " + type);
        // Retourne juste le fragment HTML de la liste pour l'AJAX
        List<CashItem> items = switch (type) {
            case "coins" -> cashCountService.getCoins();
            case "rolls" -> cashCountService.getRolls();
            default -> cashCountService.getBills();
        };
        LOGGER.info("Exiting method getFragment");
        return cashCount.data("items", items).data("fragment", true);
    }

}