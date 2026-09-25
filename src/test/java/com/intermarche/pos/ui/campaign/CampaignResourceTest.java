package com.intermarche.pos.ui.campaign;

import com.intermarche.pos.domain.campaign.Campaign;
import com.intermarche.pos.domain.util.DateTimeProvider;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CampaignResource}.
 * <p>
 * The injected Qute templates are self-chaining Mockito mocks, the service and
 * projector are mocks, and the Panache statics ({@code Campaign.findByCode},
 * {@code listAllOrdered}) plus {@link DateTimeProvider} are intercepted with
 * {@code mockStatic}. Every branch of the resource — the outcome guards, the
 * unknown-campaign guards, the create/update/duplicate try/catch, the offer
 * add/remove/claimed arms and the form parse helpers — is exercised with
 * absolute expected values.
 */
class CampaignResourceTest {

    /** The resource under test. */
    private CampaignResource resource;

    /** The self-chaining instance both templates return. */
    private TemplateInstance instance;

    /**
     * Wires the resource with mock templates, service and projector.
     */
    @BeforeEach
    void setUp() {
        resource = new CampaignResource();
        instance = mock(TemplateInstance.class);
        when(instance.data(anyString(), any())).thenReturn(instance);
        resource.campaignList = mock(Template.class);
        when(resource.campaignList.data(anyString(), any())).thenReturn(instance);
        resource.campaignFiche = mock(Template.class);
        when(resource.campaignFiche.data(anyString(), any())).thenReturn(instance);
        resource.campaignService = mock(CampaignService.class);
        resource.campaignFeedProjector = mock(CampaignFeedProjector.class);
        resource.budgetStatusClient = mock(BudgetStatusClient.class);
    }

    /**
     * Stubs the ordered list read to return the given campaigns.
     *
     * @param panache the open Panache static mock
     * @param campaigns the campaigns to serve
     */
    private void stubList(MockedStatic<PanacheEntityBase> panache, List<Campaign> campaigns) {
        @SuppressWarnings("unchecked")
        PanacheQuery<Campaign> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(campaigns);
        panache.when(() -> Campaign.find("order by code")).thenReturn(query);
    }

    /**
     * Stubs {@code findByCode} to return the given campaign.
     *
     * @param panache the open Panache static mock
     * @param code the code queried
     * @param campaign the campaign to serve, or null
     */
    private void stubFind(MockedStatic<PanacheEntityBase> panache, String code, Campaign campaign) {
        @SuppressWarnings("unchecked")
        PanacheQuery<Campaign> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(campaign);
        panache.when(() -> Campaign.find("code", code)).thenReturn(query);
    }

    /**
     * The list renders with the saved notice when the ok flag is present.
     */
    @Test
    void listRendersWithSavedNotice() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            stubList(panache, List.of(campaign));
            clock.when(DateTimeProvider::now).thenReturn(LocalDateTime.of(2026, 1, 15, 10, 0));
            assertEquals(instance, resource.list(null, "1"));
            verify(instance).data("saved", true);
            verify(resource.budgetStatusClient).groupBudgetStatus("PROMO1");
        }
    }

    /**
     * The list renders without the saved notice when the ok flag is absent.
     */
    @Test
    void listRendersWithoutSavedNotice() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<DateTimeProvider> clock = mockStatic(DateTimeProvider.class)) {
            stubList(panache, List.of());
            clock.when(DateTimeProvider::now).thenReturn(LocalDateTime.of(2026, 1, 15, 10, 0));
            assertEquals(instance, resource.list("period.invalid", null));
            verify(instance).data("saved", false);
        }
    }

    /**
     * The fiche renders the found campaign.
     */
    @Test
    void ficheRendersTheCampaign() {
        Campaign campaign = new Campaign();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            assertEquals(instance, resource.fiche("PROMO1", null));
            verify(resource.campaignFiche).data("campaign", campaign);
        }
    }

    /**
     * A valid create persists, reprojects the feed and redirects with the saved
     * flag, the form fields parsed onto the campaign.
     */
    @Test
    void createPersistsAndReprojects() {
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        Response response = resource.create("PROMO1", "Label", "2026-01-01", "2026-02-01",
                "notes", "10,50", "5", "on");
        assertEquals(303, response.getStatus());
        assertEquals("/admin/campaigns?ok=1", response.getLocation().toString());
        verify(resource.campaignService).create(captor.capture());
        verify(resource.campaignFeedProjector).project();
        Campaign built = captor.getValue();
        assertEquals(LocalDate.of(2026, 1, 1), built.validFrom);
        assertEquals(new BigDecimal("10.50"), built.maxAmount);
        assertEquals(5, built.maxApplications);
        assertTrue(built.perStore);
    }

    /**
     * A create with blank optional cells leaves open bounds, no ceilings and no
     * per-store flag (the blank/false parse arms).
     */
    @Test
    void createLeavesOpenBoundsForBlankCells() {
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        resource.create("PROMO1", "Label", "", "", "", "", "", null);
        verify(resource.campaignService).create(captor.capture());
        Campaign built = captor.getValue();
        assertNull(built.validFrom);
        assertNull(built.maxAmount);
        assertNull(built.maxApplications);
        assertTrue(!built.perStore);
    }

    /**
     * A create with unparseable cells maps them to open bounds and no ceilings
     * (the catch arms of the parse helpers).
     */
    @Test
    void createIgnoresUnparseableCells() {
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        resource.create("PROMO1", "Label", "bad-date", "", "", "abc", "x", "off");
        verify(resource.campaignService).create(captor.capture());
        Campaign built = captor.getValue();
        assertNull(built.validFrom);
        assertNull(built.maxAmount);
        assertNull(built.maxApplications);
    }

    /**
     * A create with null cells maps them to open bounds and no ceilings (the
     * value-null legs of the parse helpers).
     */
    @Test
    void createIgnoresNullCells() {
        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        resource.create("PROMO1", "Label", null, null, "notes", null, null, null);
        verify(resource.campaignService).create(captor.capture());
        Campaign built = captor.getValue();
        assertNull(built.validFrom);
        assertNull(built.maxAmount);
        assertNull(built.maxApplications);
    }

    /**
     * An update of a campaign whose offer list is null initialises it (the
     * offerCodes-null arm of the form fill).
     */
    @Test
    void updateInitialisesANullOfferList() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        campaign.offerCodes = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            resource.update("PROMO1", "New", "", "", "", "", "", null);
            assertTrue(campaign.offerCodes != null);
        }
    }

    /**
     * An invalid create redirects with the error and reprojects nothing.
     */
    @Test
    void createRedirectsWithErrorWhenInvalid() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("period.invalid"))
                .when(resource.campaignService).create(any());
        Response response = resource.create("PROMO1", "Label", "2026-02-01", "2026-01-01",
                "", "", "", null);
        assertEquals("/admin/campaigns?error=period.invalid", response.getLocation().toString());
        verify(resource.campaignFeedProjector, never()).project();
    }

    /**
     * An update of an unknown campaign redirects with the unknown error.
     */
    @Test
    void updateRedirectsWhenUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "NOPE", null);
            Response response = resource.update("NOPE", "L", "", "", "", "", "", null);
            assertEquals("/admin/campaigns?error=unknown", response.getLocation().toString());
        }
    }

    /**
     * A valid update saves, reprojects and redirects with the saved flag.
     */
    @Test
    void updateSavesAndReprojects() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            Response response = resource.update("PROMO1", "New", "", "", "", "", "", null);
            assertEquals("/admin/campaigns?ok=1", response.getLocation().toString());
            verify(resource.campaignService).update(campaign);
            verify(resource.campaignFeedProjector).project();
        }
    }

    /**
     * An invalid update redirects to the fiche with the error.
     */
    @Test
    void updateRedirectsToFicheWhenInvalid() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        org.mockito.Mockito.doThrow(new IllegalArgumentException("amount.invalid"))
                .when(resource.campaignService).update(campaign);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            Response response = resource.update("PROMO1", "New", "", "", "", "0", "", null);
            assertEquals("/admin/campaigns/PROMO1?error=amount.invalid",
                    response.getLocation().toString());
        }
    }

    /**
     * Associating an offer to an unknown campaign redirects with the unknown
     * error.
     */
    @Test
    void associateOfferRedirectsWhenUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "NOPE", null);
            Response response = resource.associateOffer("NOPE", "OFF1", "add");
            assertEquals("/admin/campaigns?error=unknown", response.getLocation().toString());
        }
    }

    /**
     * Associating an offer already claimed elsewhere redirects with the claimed
     * error.
     */
    @Test
    void associateOfferRejectsClaimedOffer() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        when(resource.campaignService.isOfferClaimedElsewhere("OFF1", "PROMO1")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            Response response = resource.associateOffer("PROMO1", "OFF1", "add");
            assertEquals("/admin/campaigns/PROMO1?error=offer.claimed:OFF1",
                    response.getLocation().toString());
        }
    }

    /**
     * Associating a free offer adds it, saves and redirects with the saved flag.
     */
    @Test
    void associateOfferAddsFreeOffer() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        campaign.offerCodes = new ArrayList<>();
        when(resource.campaignService.isOfferClaimedElsewhere("OFF1", "PROMO1")).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            Response response = resource.associateOffer("PROMO1", "OFF1", "add");
            assertTrue(campaign.offerCodes.contains("OFF1"));
            assertEquals("/admin/campaigns/PROMO1?ok=1", response.getLocation().toString());
            verify(resource.campaignFeedProjector).project();
        }
    }

    /**
     * Associating an offer the campaign already holds does not duplicate it (the
     * already-contains arm).
     */
    @Test
    void associateOfferDoesNotDuplicateHeldOffer() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        campaign.offerCodes = new ArrayList<>(List.of("OFF1"));
        when(resource.campaignService.isOfferClaimedElsewhere("OFF1", "PROMO1")).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            resource.associateOffer("PROMO1", "OFF1", "add");
            assertEquals(1, campaign.offerCodes.size());
        }
    }

    /**
     * Dissociating an offer removes it (the remove arm).
     */
    @Test
    void associateOfferRemovesOffer() {
        Campaign campaign = new Campaign();
        campaign.code = "PROMO1";
        campaign.offerCodes = new ArrayList<>(List.of("OFF1"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "PROMO1", campaign);
            resource.associateOffer("PROMO1", "OFF1", "remove");
            assertTrue(campaign.offerCodes.isEmpty());
        }
    }

    /**
     * A successful duplication redirects to the copy's fiche.
     */
    @Test
    void duplicateRedirectsToCopy() {
        Response response = resource.duplicate("SRC", "COPY", "on");
        assertEquals("/admin/campaigns/COPY", response.getLocation().toString());
        verify(resource.campaignService).duplicate("SRC", "COPY", true, "-2");
        verify(resource.campaignFeedProjector).project();
    }

    /**
     * A failed duplication redirects to the source fiche with the error (and the
     * without-offers flag).
     */
    @Test
    void duplicateRedirectsWithErrorOnFailure() {
        when(resource.campaignService.duplicate("SRC", "COPY", false, "-2"))
                .thenThrow(new IllegalArgumentException("source.unknown"));
        Response response = resource.duplicate("SRC", "COPY", null);
        assertEquals("/admin/campaigns/SRC?error=source.unknown",
                response.getLocation().toString());
        verify(resource.campaignFeedProjector, never()).project();
    }
}
