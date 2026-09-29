package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.service.sync.ConsumptionDeliveryService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.jboss.logging.Logger;

/**
 * Data endpoints of the "Santé des échanges" panel on the supervisor screen
 * (spec-sante-caisse): the three auto-refresh table-body fragments and the
 * consumption "Rejouer" action. The panel itself is rendered by the supervisor
 * page ({@code HomeResource.supervisorPage}); this resource serves only what the
 * panel polls and posts.
 * <p>
 * ACCESS — supervisor screen only: every method is reserved to a LOGGED
 * SUPERVISOR (MANAGER/ADMIN), the same role the endorsement shortcut uses. A
 * cashier — who reaches {@code /supervisor} only to call for help — never sees
 * the panel ({@code {#if supervisorLogged}} hides it) and is refused here too, so
 * a hand-made request cannot read the health or replay a delivery.
 * <p>
 * It is NOT drawer-guarded (no {@code @DrawerMustBeClosed}): the panel is polled
 * from a screen reachable drawer-open, exactly like the supervisor call it sits
 * beside.
 */
@Path("/supervisor/health")
public class ExchangeHealthResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ExchangeHealthResource.class);

    /** The supervisor page template, whose fragments this resource renders. */
    @Inject
    Template supervisor;

    /** The read-only exchange-health assembly. */
    @Inject
    ExchangeHealthService exchangeHealthService;

    /** The consumption delivery loop (the "Rejouer" re-drive). */
    @Inject
    ConsumptionDeliveryService consumptionDeliveryService;

    /** The referential pull loop (the "Relancer le tirage" re-drive). */
    @Inject
    com.intermarche.pos.service.sync.RefPullService refPullService;

    /** The supervisor-role decision (LC-01-05-07). */
    @Inject
    EndorsementService endorsementService;

    /** The current register state (who is logged in). */
    @Inject
    PosState state;

    /**
     * Refuses the request unless a supervisor is logged, enforcing the
     * "supervisor screen only" access rule server side.
     */
    private void requireSupervisor() {
        LOGGER.info("Entering method requireSupervisor");
        if (!endorsementService.operatorIsSupervisor(state)) {
            LOGGER.info("Exiting method requireSupervisor");
            throw new ForbiddenException();
        }
        LOGGER.info("Exiting method requireSupervisor");
    }

    /**
     * Renders the ticket-uplink table body alone, for the 2-second auto-refresh.
     *
     * @return the {@code uplink} fragment of the supervisor page
     */
    @GET
    @Path("/uplink")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance uplinkFragment() {
        LOGGER.info("Entering method uplinkFragment");
        requireSupervisor();
        LOGGER.info("Exiting method uplinkFragment");
        return supervisor.getFragment("uplink").data("health", exchangeHealthService.build());
    }

    /**
     * Renders the consumption table body alone, for the auto-refresh.
     *
     * @return the {@code consumption} fragment of the supervisor page
     */
    @GET
    @Path("/consumption")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance consumptionFragment() {
        LOGGER.info("Entering method consumptionFragment");
        requireSupervisor();
        LOGGER.info("Exiting method consumptionFragment");
        return supervisor.getFragment("consumption").data("health", exchangeHealthService.build());
    }

    /**
     * Renders the engine-feed table body alone, for the auto-refresh.
     *
     * @return the {@code feeds} fragment of the supervisor page
     */
    @GET
    @Path("/feeds")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance feedsFragment() {
        LOGGER.info("Entering method feedsFragment");
        requireSupervisor();
        LOGGER.info("Exiting method feedsFragment");
        return supervisor.getFragment("feeds").data("health", exchangeHealthService.build());
    }

    /**
     * Relaunches the referential pull NOW, after an integration left a domain
     * behind (BO-08-04-09/10/12/13 at the point-of-contact echelon).
     *
     * <p>Why the register needed this. The store node has offered a manual
     * relaunch on its own supervision screen from the start; the register had
     * none, so a domain that failed to apply here waited for the next
     * scheduled cycle with nobody able to force it. The service side was
     * already written for exactly this — {@link
     * com.intermarche.pos.service.sync.RefPullService#triggerPull()} carries
     * the BO reference in its own Javadoc — and only the button was missing.
     *
     * <p>Re-driving costs nothing when there is nothing to do: a cycle
     * compares each domain's upstream fingerprint with the one applied here
     * and downloads only what differs. The outcome is logged by the service,
     * which reports a failure at ERROR level.
     *
     * @return a 303 redirect to the supervisor screen, carrying the outcome as
     *         a one-shot {@code notice}
     */
    @POST
    @Path("/feeds/replay")
    public Response replayPull() {
        LOGGER.info("Entering method replayPull");
        requireSupervisor();
        String error = refPullService.triggerPull();
        LOGGER.info("Exiting method replayPull");
        if (error == null) {
            return redirect("Tirage des référentiels relancé.", true);
        }
        return redirect("Tirage des référentiels en échec : " + error, false);
    }

    /**
     * Builds the 303 redirect back to the supervisor screen, carrying the
     * one-shot notice the screen shows once after a relaunch.
     *
     * @param notice the message to display
     * @param ok whether the message reports a success
     * @return the 303 redirect
     */
    private Response redirect(String notice, boolean ok) {
        LOGGER.info("Entering method redirect with notice: " + notice + ", ok: " + ok);
        String target = "/supervisor?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        LOGGER.info("Exiting method redirect");
        return Response.seeOther(URI.create(target)).build();
    }

    /**
     * Re-drives the consumption delivery NOW (the "Rejouer" button), then returns
     * to the supervisor screen.
     * <p>
     * The replay is safe by construction: the valuation engine is idempotent on
     * {@code (storeCode, ticketId, direction)} and the enqueue dedups on the same
     * key, so re-driving a queue — including declarations that already went
     * through — never double-counts a consumption
     * ({@link ConsumptionDeliveryService#triggerDelivery()}). A cleared queue
     * therefore stays cleared and a failed row is simply completed.
     *
     * @return a 303 redirect to the supervisor screen
     */
    @POST
    @Path("/consumption/replay")
    public Response replayConsumption() {
        LOGGER.info("Entering method replayConsumption");
        requireSupervisor();
        consumptionDeliveryService.triggerDelivery();
        LOGGER.info("Exiting method replayConsumption");
        return Response.seeOther(URI.create("/supervisor")).build();
    }
}
