package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.service.sync.ConsumptionDeliveryService;
import com.intermarche.pos.service.sync.RefPullService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExchangeHealthResource}: the three auto-refresh fragment
 * endpoints and the consumption "Rejouer" action, plus the "supervisor screen
 * only" access rule (normative case 3). Every collaborator is a Mockito mock; the
 * supervisor guard is driven through {@link EndorsementService#operatorIsSupervisor}.
 */
class ExchangeHealthResourceTest {

    /**
     * Builds a resource over mocked collaborators.
     *
     * @return the wired resource
     */
    private ExchangeHealthResource newResource() {
        ExchangeHealthResource resource = new ExchangeHealthResource();
        resource.supervisor = mock(Template.class);
        resource.exchangeHealthService = mock(ExchangeHealthService.class);
        resource.consumptionDeliveryService = mock(ConsumptionDeliveryService.class);
        resource.refPullService = mock(RefPullService.class);
        resource.endorsementService = mock(EndorsementService.class);
        resource.state = mock(PosState.class);
        return resource;
    }

    /**
     * Makes the guard admit a supervisor on the given resource.
     *
     * @param resource the resource whose guard to open
     */
    private void asSupervisor(ExchangeHealthResource resource) {
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(true);
    }

    /**
     * Wires a template fragment lookup to a self-identifying instance.
     *
     * @param resource the resource whose template to wire
     * @param id the fragment id expected
     * @return the instance the fragment's {@code data(...)} returns
     */
    private TemplateInstance wireFragment(ExchangeHealthResource resource, String id) {
        Template.Fragment fragment = mock(Template.Fragment.class);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.supervisor.getFragment(id)).thenReturn(fragment);
        when(fragment.data(eq("health"), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Case 1/2 — {@code uplinkFragment} renders the uplink table body off a fresh
     * build for a logged supervisor.
     */
    @Test
    void uplinkFragmentRendersForSupervisor() {
        ExchangeHealthResource resource = newResource();
        asSupervisor(resource);
        TemplateInstance instance = wireFragment(resource, "uplink");
        assertSame(instance, resource.uplinkFragment());
        verify(resource.exchangeHealthService).build();
    }

    /**
     * {@code consumptionFragment} renders the consumption table body for a
     * supervisor.
     */
    @Test
    void consumptionFragmentRendersForSupervisor() {
        ExchangeHealthResource resource = newResource();
        asSupervisor(resource);
        TemplateInstance instance = wireFragment(resource, "consumption");
        assertSame(instance, resource.consumptionFragment());
    }

    /**
     * {@code feedsFragment} renders the engine-feed table body for a supervisor.
     */
    @Test
    void feedsFragmentRendersForSupervisor() {
        ExchangeHealthResource resource = newResource();
        asSupervisor(resource);
        TemplateInstance instance = wireFragment(resource, "feeds");
        assertSame(instance, resource.feedsFragment());
    }

    /**
     * Case 3 — a non-supervisor is refused a fragment (ForbiddenException) and the
     * view is never even built (guard runs first).
     */
    @Test
    void fragmentRefusedForNonSupervisor() {
        ExchangeHealthResource resource = newResource();
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        assertThrows(ForbiddenException.class, resource::uplinkFragment);
        verifyNoInteractions(resource.exchangeHealthService);
    }

    /**
     * Case 1 — {@code replayConsumption} re-drives the consumption delivery and
     * redirects to the supervisor screen for a logged supervisor.
     */
    @Test
    void replayConsumptionRedrivesForSupervisor() {
        ExchangeHealthResource resource = newResource();
        asSupervisor(resource);
        Response response = resource.replayConsumption();
        verify(resource.consumptionDeliveryService).triggerDelivery();
        assertEquals(303, response.getStatus());
        assertEquals("/supervisor", response.getLocation().toString());
    }

    /**
     * Case 3 — a non-supervisor cannot replay: the guard refuses and the delivery
     * loop is never touched.
     */
    @Test
    void replayConsumptionRefusedForNonSupervisor() {
        ExchangeHealthResource resource = newResource();
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        assertThrows(ForbiddenException.class, resource::replayConsumption);
        verify(resource.consumptionDeliveryService, never()).triggerDelivery();
    }

    /**
     * Covers the success arm of {@code replayPull}: a clean cycle (triggerPull
     * returns null) redirects to the supervisor screen carrying a green
     * {@code noticeOk=true} and the success message.
     */
    @Test
    void replayPullShowsSuccessNoticeForSupervisor() {
        ExchangeHealthResource resource = newResource();
        asSupervisor(resource);
        when(resource.refPullService.triggerPull()).thenReturn(null);
        Response response = resource.replayPull();
        verify(resource.refPullService).triggerPull();
        assertEquals(303, response.getStatus());
        URI location = response.getLocation();
        assertEquals("/supervisor", location.getPath());
        assertEquals("noticeOk=true&notice=Tirage des référentiels relancé.",
                URLDecoder.decode(location.getRawQuery(), StandardCharsets.UTF_8));
    }

    /**
     * Covers the failure arm of {@code replayPull}: a cycle that fails
     * (triggerPull returns a message) redirects carrying a red
     * {@code noticeOk=false} and the failure message appended.
     */
    @Test
    void replayPullShowsErrorNoticeWhenPullFails() {
        ExchangeHealthResource resource = newResource();
        asSupervisor(resource);
        when(resource.refPullService.triggerPull()).thenReturn("boom");
        Response response = resource.replayPull();
        assertEquals(303, response.getStatus());
        URI location = response.getLocation();
        assertEquals("/supervisor", location.getPath());
        assertEquals("noticeOk=false&notice=Tirage des référentiels en échec : boom",
                URLDecoder.decode(location.getRawQuery(), StandardCharsets.UTF_8));
    }

    /**
     * Case 3 — a non-supervisor cannot relaunch the pull: the guard refuses and
     * the pull loop is never triggered.
     */
    @Test
    void replayPullRefusedForNonSupervisor() {
        ExchangeHealthResource resource = newResource();
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(false);
        assertThrows(ForbiddenException.class, resource::replayPull);
        verify(resource.refPullService, never()).triggerPull();
    }
}
