package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FidelityResource}.
 * <p>
 * The resource is a thin JAX-RS facade over a mocked {@link PosState}, a
 * {@link FidelityService} and the Qute {@code fidelity} {@link Template}. Every
 * collaborator is a Mockito mock; the template echoes a recognizable
 * {@link TemplateInstance} so the returned view can be identified, while the
 * POST actions return a 303 redirect to "/" (PRG pattern) asserted by status
 * and location. Tests assert absolute expected values and verify delegation.
 */
class FidelityResourceTest {

    /**
     * Builds a {@link FidelityResource} whose service and template are fresh
     * mocks and whose {@link PosState} is a mock carrying a real
     * {@link FidelityState} sub-state.
     *
     * @return a resource with fully wired mocked collaborators
     */
    private FidelityResource newResource() {
        FidelityResource resource = new FidelityResource();
        resource.state = mock(PosState.class);
        // The page reads the stored lookup echo from the REAL sub-state (a
        // mock's field would be null and the render would NPE).
        resource.state.fidelity = new FidelityState();
        resource.fidelityService = mock(FidelityService.class);
        resource.fidelity = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the given template to return a recognizable view for the resource's
     * state.
     *
     * @param template the template to stub
     * @param resource the resource whose state is passed to the template
     * @return the view {@code template.data("state", state)} returns
     */
    private TemplateInstance stub(Template template, FidelityResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(template.data("state", resource.state)).thenReturn(view);
        // The fidelity page CHAINS further data() calls: the holder-lookup
        // outcome (null outside a search), the echoed search mode and value,
        // then the in-store consultation (status, balance, movements). A
        // chained mock returns null by default, which reads as "the page
        // rendered nothing" — the view returns ITSELF so the chain stays
        // observable end to end.
        when(view.data(eq("lookup"), any())).thenReturn(view);
        when(view.data(eq("searchMode"), any())).thenReturn(view);
        when(view.data(eq("searchValue"), any())).thenReturn(view);
        when(view.data(eq("consultation"), any())).thenReturn(view);
        return view;
    }

    // --- fidelityPage ---

    /**
     * {@code fidelityPage()} renders the fidelity view when the terminal is
     * unlocked (guard false arm).
     */
    @Test
    void fidelityPageRendersFidelityWhenUnlocked() {
        FidelityResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance fidelityView = stub(resource.fidelity, resource);
        assertSame(fidelityView, resource.fidelityPage());
    }

    // --- validateFidelity ---

    /**
     * {@code validateFidelity()} attaches the card and redirects to the main
     * page (PRG pattern, so a browser reload never replays the POST).
     */
    @Test
    void validateFidelityAttachesCardAndRedirectsHome() {
        FidelityResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.validateFidelity("1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.fidelityService).validateCard(resource.state, "1234");
    }

}
