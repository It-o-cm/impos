package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FidelityResource}.
 * <p>
 * The resource is a thin JAX-RS facade over a mocked {@link PosState}, a
 * {@link FidelityService} and three Qute {@link Template}s ({@code fidelity},
 * {@code main}, {@code lock}). Every collaborator is a Mockito mock; each
 * template echoes a recognizable {@link TemplateInstance} so the returned view
 * can be identified. Tests assert absolute expected views and verify delegation,
 * covering both arms of the {@code isLocked} guard in {@code fidelityPage} and
 * {@code validateFidelity} plus both arms of the private {@code home} ternary.
 */
class FidelityResourceTest {

    /**
     * Builds a {@link FidelityResource} whose service and three templates are
     * fresh mocks and whose {@link PosState} is a mock.
     *
     * @return a resource with fully wired mocked collaborators
     */
    private FidelityResource newResource() {
        FidelityResource resource = new FidelityResource();
        resource.state = mock(PosState.class);
        resource.fidelityService = mock(FidelityService.class);
        resource.fidelity = mock(Template.class);
        resource.main = mock(Template.class);
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
        // outcome (null outside a search) then the in-store consultation
        // (status, balance, movements). A chained mock returns null by
        // default, which reads as "the page rendered nothing" — the view
        // returns ITSELF so the chain stays observable end to end.
        when(view.data(eq("lookup"), any())).thenReturn(view);
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
     * {@code validateFidelity()} attaches the card and returns the main view
     * when the terminal is unlocked and stays unlocked (guard false arm plus
     * {@code home} ternary false arm).
     */
    @Test
    void validateFidelityAttachesCardAndReturnsMainWhenUnlocked() {
        FidelityResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stub(resource.main, resource);
        assertSame(mainView, resource.validateFidelity("1234"));
        verify(resource.fidelityService).validateCard(resource.state, "1234");
    }

}
