package com.intermarche.pos.ui.cash;

import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VignetteResource} (BO-11-01-20): the page hands the
 * template the switch, the summary and the outcome; the declaration reads its
 * count, refuses an unreadable one, refuses while the vignettes are off, and
 * redirects with what was recorded.
 */
class VignetteResourceTest {

    /**
     * Builds the resource over mocked collaborators.
     *
     * @return the resource
     */
    private VignetteResource resource() {
        VignetteResource resource = new VignetteResource();
        resource.vignettes = mock(Template.class);
        resource.vignetteService = mock(VignetteService.class);
        resource.state = new PosState();
        resource.state.auth.operatorBadgeId = "12341234";
        return resource;
    }

    /**
     * Reads the target of a redirect.
     *
     * @param response the response
     * @return the location
     */
    private String target(Response response) {
        return response.getLocation().toString();
    }

    /** The page carries the switch, the summary and the outcome flags. */
    @Test
    void thePageCarriesTheShift() {
        VignetteResource resource = resource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.vignettes.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        VignetteService.Summary summary = new VignetteService.Summary("S1", 2, 3, 1);
        when(resource.vignetteService.enabled()).thenReturn(true);
        when(resource.vignetteService.summary()).thenReturn(summary);
        Assertions.assertSame(instance, resource.page("1", null));
        verify(instance).data("summary", summary);
        verify(instance).data("saved", true);
        Assertions.assertSame(instance, resource.page(null, "count"));
        verify(instance).data("saved", false);
        verify(instance).data("error", "count");
    }

    /** An unreadable count is refused; with the vignettes off nothing is declared; otherwise the outcome is shown. */
    @Test
    void theDeclarationIsRecorded() {
        VignetteResource resource = resource();
        Assertions.assertEquals("/vignettes?error=count", target(resource.declare("abc")));
        Assertions.assertEquals("/vignettes?error=count", target(resource.declare(null)));
        Assertions.assertEquals("/vignettes?error=disabled", target(resource.declare("3")));
        verify(resource.vignetteService, never()).declare(anyLong(), any());
        when(resource.vignetteService.enabled()).thenReturn(true);
        when(resource.vignetteService.declare(3L, "12341234")).thenReturn(true);
        Assertions.assertEquals("/vignettes?ok=1", target(resource.declare(" 3 ")));
        Assertions.assertEquals("/vignettes?error=count", target(resource.declare("-1")));
    }
}
