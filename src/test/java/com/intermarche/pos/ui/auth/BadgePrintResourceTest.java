package com.intermarche.pos.ui.auth;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BadgePrintResource}.
 * <p>
 * The resource is a thin JAX-RS facade over a {@link PosState}, an
 * {@link EndorsementService} and the {@code badge-print} Qute {@link Template}.
 * Every collaborator is a Mockito mock: the template echoes a recognizable
 * {@link TemplateInstance} so the returned view can be identified, and the
 * endorsement service records the parked action. Tests assert absolute expected
 * values (view identity, redirect status and location, parked action string) and
 * cover both legs of the compound {@code operator != null && !operator.isBlank()}
 * guard: null operator (first leg false, short-circuits), blank non-null operator
 * (first leg true, second leg false) and non-blank operator (both legs true).
 */
class BadgePrintResourceTest {

    /**
     * Builds a {@link BadgePrintResource} whose collaborators are fresh mocks wired
     * onto its package-private fields.
     *
     * @return a resource with a mocked template, endorsement service and state
     */
    private BadgePrintResource newResource() {
        BadgePrintResource resource = new BadgePrintResource();
        resource.badgePrint = mock(Template.class);
        resource.endorsementService = mock(EndorsementService.class);
        resource.state = mock(PosState.class);
        return resource;
    }

    /**
     * {@code badgePrintPage()} renders the badge-print entry template bound to the
     * register state and returns its recognizable view.
     */
    @Test
    void badgePrintPageRendersEntryTemplate() {
        BadgePrintResource resource = newResource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.badgePrint.data("state", resource.state)).thenReturn(view);
        assertSame(view, resource.badgePrintPage());
    }

    /**
     * {@code requestBadgePrint()} parks the trimmed operator id as the endorsed
     * {@code PRINT_BADGE_<n>} action and redirects to the sale screen (both guard
     * legs true, non-blank operator; the id is trimmed before use).
     */
    @Test
    void requestBadgePrintParksTrimmedOperatorAndRedirects() {
        BadgePrintResource resource = newResource();
        Response response = resource.requestBadgePrint("  7  ");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.endorsementService).requestAuthorization(resource.state, "PRINT_BADGE_7");
    }

    /**
     * {@code requestBadgePrint()} skips parking and only redirects when the operator
     * is null (first guard leg false, short-circuits the second).
     */
    @Test
    void requestBadgePrintNullOperatorParksNothing() {
        BadgePrintResource resource = newResource();
        Response response = resource.requestBadgePrint(null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verifyNoInteractions(resource.endorsementService);
    }

    /**
     * {@code requestBadgePrint()} skips parking and only redirects when the operator
     * is non-null but blank (first guard leg true, second guard leg false).
     */
    @Test
    void requestBadgePrintBlankOperatorParksNothing() {
        BadgePrintResource resource = newResource();
        Response response = resource.requestBadgePrint("   ");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verifyNoInteractions(resource.endorsementService);
    }
}
