package com.intermarche.pos.ui.journal;

import com.intermarche.pos.domain.ticket.TechnicalEvent;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;

/**
 * The electronic journal back office (BO-04-01, lot 4): a multi-criteria
 * search over the consolidated documents, split into a transactional tab
 * (tickets) and a functional tab (technical events) as BO-04-01-52 requires,
 * a ticket detail view reachable from the list, and a CSV export that
 * reproduces the transactional list to the character (BO-04-01-50).
 * <p>
 * Reserved to MANAGER and ADMIN — the journal is a supervision surface, like
 * the dashboard, not a public LAN page. It reads this node's own database, so
 * it is the STORE journal when opened on the consolidated node. It produces
 * nothing: every endpoint is a GET, there is no state to mutate.
 * <p>
 * Rendered inside the back-office gabarit ({@code admin-layout}), the same
 * chrome as the parameters and supervision screens — one surface, one
 * navigation.
 */
@Path("/admin/journal")
public class JournalResource {

    /** The journal search page template (both tabs). */
    @Inject
    @Location("journal")
    Template journal;

    /** The ticket detail page template. */
    @Inject
    @Location("journal-detail")
    Template journalDetail;

    @Inject
    JournalService journalService;

    /**
     * Shows the transactional journal: the ticket search form and its results.
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the transactional tab page
     */
    @GET
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance transactional(@Context UriInfo uriInfo) {
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        List<JournalRow> rows = journalService.search(criteria);
        return page("transactional", criteria, rows, List.of());
    }

    /**
     * Shows the functional journal: the technical-event search form and its
     * results.
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the functional tab page
     */
    @GET
    @Path("/functional")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance functional(@Context UriInfo uriInfo) {
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        List<JournalEventRow> events = journalService.searchEvents(criteria);
        return page("functional", criteria, List.of(), events);
    }

    /**
     * Shows the detail of one consolidated ticket.
     *
     * @param id the ticket database id
     * @return the detail page; {@code ticket} is null when the id is unknown
     */
    @GET
    @Path("/ticket/{id}")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(@PathParam("id") Long id) {
        JournalTicketDetail ticket = journalService.buildDetail(id);
        return journalDetail.data("ticket", ticket);
    }

    /**
     * Exports the transactional list as a CSV attachment, running the same
     * search as the screen (BO-04-01-50, export à l'identique).
     *
     * @param uriInfo the request URI carrying the search criteria
     * @return the CSV attachment response
     */
    @GET
    @Path("/export")
    @RolesAllowed({"ADMIN", "MANAGER"})
    @Produces("text/csv")
    public Response export(@Context UriInfo uriInfo) {
        JournalCriteria criteria = JournalCriteria.fromParams(uriInfo.getQueryParameters());
        String csv = journalService.exportCsv(criteria);
        return Response.ok(csv)
                .header("Content-Disposition", "attachment; filename=\"journal.csv\"")
                .build();
    }

    /**
     * Assembles the search page with the active tab, the criteria echoed back
     * to keep the form filled, the results and the option catalogs.
     *
     * @param tab the active tab ("transactional" or "functional")
     * @param criteria the parsed criteria to echo
     * @param rows the transactional rows (empty on the functional tab)
     * @param events the functional rows (empty on the transactional tab)
     * @return the wired page instance
     */
    private TemplateInstance page(String tab, JournalCriteria criteria,
                                  List<JournalRow> rows, List<JournalEventRow> events) {
        return journal.data("tab", tab)
                .data("criteria", criteria)
                .data("rows", rows)
                .data("events", events)
                .data("paymentKeys", PaymentTypes.keys())
                .data("ticketFlags", JournalCriteria.Flag.values())
                .data("sorts", JournalSort.values())
                .data("eventTypes", TechnicalEvent.EventType.values());
    }
}
