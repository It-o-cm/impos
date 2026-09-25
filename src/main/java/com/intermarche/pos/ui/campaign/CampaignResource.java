package com.intermarche.pos.ui.campaign;

import com.intermarche.pos.domain.campaign.Campaign;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The back-office administration of campaigns (spec §2): the list, the fiche
 * (edition + offer association), and duplication (GB-01-06-15).
 *
 * <p>Reads are open to any back-office reader (MANAGER, which ADMIN implies),
 * writes to the role that writes offers (ADMIN), the same gate the CSV import
 * door carries. Every write follows the POST → 303 → notice cycle: the outcome
 * travels back as an {@code ok}/{@code error} query parameter, so a refresh
 * never replays the write. Every save reprojects the {@code BUDGET_GROUPS} feed
 * so the engine's budget view follows the back office.
 */
@Path("/admin/campaigns")
public class CampaignResource {

    /** Logger for this resource. */
    private static final Logger LOGGER = Logger.getLogger(CampaignResource.class);

    /** The campaign list template. */
    @Inject
    @io.quarkus.qute.Location("campaign-list")
    Template campaignList;

    /** The campaign fiche template. */
    @Inject
    @io.quarkus.qute.Location("campaign-fiche")
    Template campaignFiche;

    /** The validation, creation, edition and duplication rules. */
    @Inject
    CampaignService campaignService;

    /** The BUDGET_GROUPS feed projector, run after every save. */
    @Inject
    CampaignFeedProjector campaignFeedProjector;

    /** The live consumption reader (engine GraphQL), for the list and fiche. */
    @Inject
    BudgetStatusClient budgetStatusClient;

    /**
     * Shows the campaign list.
     *
     * @param error an outcome error code from a redirect, or null
     * @param ok a non-null flag when the previous write succeeded
     * @return the list page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @RolesAllowed("MANAGER")
    public TemplateInstance list(@QueryParam("error") String error, @QueryParam("ok") String ok) {
        LOGGER.info("Entering method list");
        List<Campaign> campaigns = Campaign.listAllOrdered();
        java.util.Map<String, BudgetStatusClient.BudgetStatus> statuses =
                new java.util.LinkedHashMap<>();
        for (Campaign campaign : campaigns) {
            statuses.put(campaign.code, budgetStatusClient.groupBudgetStatus(campaign.code));
        }
        LOGGER.info("Exiting method list");
        return campaignList
                .data("campaigns", campaigns)
                .data("statuses", statuses)
                .data("today", com.intermarche.pos.domain.util.DateTimeProvider.now().toLocalDate())
                .data("saved", ok != null)
                .data("error", error);
    }

    /**
     * Shows the fiche of one campaign for edition.
     *
     * @param code the campaign code
     * @param error an outcome error code from a redirect, or null
     * @return the fiche page
     */
    @GET
    @Path("/{code}")
    @Produces(MediaType.TEXT_HTML)
    @RolesAllowed("MANAGER")
    public TemplateInstance fiche(@PathParam("code") String code, @QueryParam("error") String error) {
        LOGGER.info("Entering method fiche with code: " + code);
        Campaign campaign = Campaign.findByCode(code);
        LOGGER.info("Exiting method fiche");
        return campaignFiche
                .data("campaign", campaign)
                .data("status", budgetStatusClient.groupBudgetStatus(code))
                .data("error", error);
    }

    /**
     * Creates a campaign from the fiche form.
     *
     * @param code the campaign code
     * @param label the label
     * @param validFrom the inclusive start (ISO date), or blank for open
     * @param validTo the exclusive end (ISO date), or blank for open
     * @param notes the free notes
     * @param maxAmount the amount ceiling in euros, or blank for none
     * @param maxApplications the applications ceiling, or blank for none
     * @param perStore whether the ceilings apply per point of sale
     * @return a redirect to the list on success, or back to a fresh fiche on error
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @RolesAllowed("ADMIN")
    public Response create(@FormParam("code") String code, @FormParam("label") String label,
            @FormParam("validFrom") String validFrom, @FormParam("validTo") String validTo,
            @FormParam("notes") String notes, @FormParam("maxAmount") String maxAmount,
            @FormParam("maxApplications") String maxApplications,
            @FormParam("perStore") String perStore) {
        LOGGER.info("Entering method create with code: " + code);
        Campaign campaign = new Campaign();
        applyForm(campaign, code, label, validFrom, validTo, notes, maxAmount, maxApplications,
                perStore);
        try {
            campaignService.create(campaign);
        } catch (IllegalArgumentException e) {
            LOGGER.info("Exiting method create: invalid");
            return redirect("/admin/campaigns?error=" + e.getMessage());
        }
        campaignFeedProjector.project();
        LOGGER.info("Exiting method create");
        return redirect("/admin/campaigns?ok=1");
    }

    /**
     * Saves edits to an existing campaign.
     *
     * @param code the campaign code (from the path)
     * @param label the label
     * @param validFrom the inclusive start (ISO date), or blank for open
     * @param validTo the exclusive end (ISO date), or blank for open
     * @param notes the free notes
     * @param maxAmount the amount ceiling in euros, or blank for none
     * @param maxApplications the applications ceiling, or blank for none
     * @param perStore whether the ceilings apply per point of sale
     * @return a redirect to the list on success, or back to the fiche on error
     */
    @POST
    @Path("/{code}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @RolesAllowed("ADMIN")
    public Response update(@PathParam("code") String code, @FormParam("label") String label,
            @FormParam("validFrom") String validFrom, @FormParam("validTo") String validTo,
            @FormParam("notes") String notes, @FormParam("maxAmount") String maxAmount,
            @FormParam("maxApplications") String maxApplications,
            @FormParam("perStore") String perStore) {
        LOGGER.info("Entering method update with code: " + code);
        Campaign campaign = Campaign.findByCode(code);
        if (campaign == null) {
            LOGGER.info("Exiting method update: unknown");
            return redirect("/admin/campaigns?error=unknown");
        }
        applyForm(campaign, code, label, validFrom, validTo, notes, maxAmount, maxApplications,
                perStore);
        try {
            campaignService.update(campaign);
        } catch (IllegalArgumentException e) {
            LOGGER.info("Exiting method update: invalid");
            return redirect("/admin/campaigns/" + code + "?error=" + e.getMessage());
        }
        campaignFeedProjector.project();
        LOGGER.info("Exiting method update");
        return redirect("/admin/campaigns?ok=1");
    }

    /**
     * Associates or dissociates an offer code on a campaign.
     *
     * @param code the campaign code
     * @param offerCode the offer code to move
     * @param action {@code add} to associate, anything else to dissociate
     * @return a redirect back to the fiche
     */
    @POST
    @Path("/{code}/offers")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @RolesAllowed("ADMIN")
    public Response associateOffer(@PathParam("code") String code,
            @FormParam("offerCode") String offerCode, @FormParam("action") String action) {
        LOGGER.info("Entering method associateOffer with code: " + code);
        Campaign campaign = Campaign.findByCode(code);
        if (campaign == null) {
            LOGGER.info("Exiting method associateOffer: unknown");
            return redirect("/admin/campaigns?error=unknown");
        }
        if ("add".equals(action)) {
            if (campaignService.isOfferClaimedElsewhere(offerCode, code)) {
                LOGGER.info("Exiting method associateOffer: claimed");
                return redirect("/admin/campaigns/" + code + "?error=offer.claimed:" + offerCode);
            }
            if (!campaign.offerCodes.contains(offerCode)) {
                campaign.offerCodes.add(offerCode);
            }
        } else {
            campaign.offerCodes.remove(offerCode);
        }
        campaignService.update(campaign);
        campaignFeedProjector.project();
        LOGGER.info("Exiting method associateOffer");
        return redirect("/admin/campaigns/" + code + "?ok=1");
    }

    /**
     * Duplicates a campaign (GB-01-06-15).
     *
     * @param code the source campaign code
     * @param newCode the mandatory code of the copy
     * @param withOffers whether the offer associations are copied too
     * @return a redirect to the copy's fiche on success, or back on error
     */
    @POST
    @Path("/{code}/duplicate")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @RolesAllowed("ADMIN")
    public Response duplicate(@PathParam("code") String code, @FormParam("newCode") String newCode,
            @FormParam("withOffers") String withOffers) {
        LOGGER.info("Entering method duplicate with code: " + code + ", newCode: " + newCode);
        try {
            campaignService.duplicate(code, newCode, "on".equals(withOffers), "-2");
        } catch (IllegalArgumentException e) {
            LOGGER.info("Exiting method duplicate: error");
            return redirect("/admin/campaigns/" + code + "?error=" + e.getMessage());
        }
        campaignFeedProjector.project();
        LOGGER.info("Exiting method duplicate");
        return redirect("/admin/campaigns/" + newCode);
    }

    /**
     * Fills a campaign from the fiche form fields, mapping blank cells to open
     * bounds and unset ceilings.
     *
     * @param campaign the campaign to fill
     * @param code the code
     * @param label the label
     * @param validFrom the inclusive start, or blank
     * @param validTo the exclusive end, or blank
     * @param notes the notes
     * @param maxAmount the amount ceiling, or blank
     * @param maxApplications the applications ceiling, or blank
     * @param perStore the per-store flag
     */
    private void applyForm(Campaign campaign, String code, String label, String validFrom,
            String validTo, String notes, String maxAmount, String maxApplications,
            String perStore) {
        campaign.code = code;
        campaign.label = label;
        campaign.validFrom = parseDate(validFrom);
        campaign.validTo = parseDate(validTo);
        campaign.notes = notes;
        campaign.maxAmount = parseAmount(maxAmount);
        campaign.maxApplications = parseInteger(maxApplications);
        campaign.perStore = "on".equals(perStore);
        if (campaign.offerCodes == null) {
            campaign.offerCodes = new ArrayList<>();
        }
    }

    /**
     * Parses an ISO date, treating a blank cell as an open bound.
     *
     * @param value the cell, possibly blank
     * @return the date, or null when blank or unparseable
     */
    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Parses a euro amount, accepting a French comma, a blank cell meaning none.
     *
     * @param value the cell, possibly blank
     * @return the amount at scale 2, or null when blank or unparseable
     */
    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(',', '.'))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses an integer ceiling, a blank cell meaning none.
     *
     * @param value the cell, possibly blank
     * @return the integer, or null when blank or unparseable
     */
    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Builds a 303 redirect to a back-office path (the POST → 303 → notice
     * cycle).
     *
     * @param path the target path
     * @return the redirect response
     */
    private Response redirect(String path) {
        return Response.seeOther(URI.create(path)).build();
    }
}
