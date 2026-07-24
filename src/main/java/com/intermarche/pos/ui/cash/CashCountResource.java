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
import java.net.URI;
import java.util.List;

@Path("/")
public class CashCountResource {

    @Inject @Location("cash-count") Template cashCount;
    @Inject HardwareService hardwareService;
    @Inject CashCountService cashCountService;
    @Inject
    PosState state;

    @GET
    @Path("/action/start-cash-count")
    public Object startCashCount() {
        hardwareService.openDrawer();
        return Response.seeOther(URI.create("/cash-count")).build();
    }

    @GET
    @Path("/cash-count")
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public TemplateInstance cashCountPage() {
        // Affichage complet de la page (Billets par défaut)
        return cashCount
            .data("state", state)
            .data("items", cashCountService.getBills())
            .data("fragment", false);
    }

    @GET
    @Path("/cash-count/fragment/{type}")
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public TemplateInstance getFragment(@PathParam("type") String type) {
        // Retourne juste le fragment HTML de la liste pour l'AJAX
        List<CashItem> items = switch (type) {
            case "coins" -> cashCountService.getCoins();
            case "rolls" -> cashCountService.getRolls();
            default -> cashCountService.getBills();
        };
        return cashCount.data("items", items).data("fragment", true);
    }

}