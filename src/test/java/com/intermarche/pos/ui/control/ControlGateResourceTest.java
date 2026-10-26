package com.intermarche.pos.ui.control;

import com.intermarche.pos.service.PosListService;
import com.intermarche.pos.ui.endorsement.FunctionApprovalService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link ControlGateResource} (BO-04-03-09/10/12).
 */
class ControlGateResourceTest {

    /**
     * Builds the resource over a mocked gate and templates.
     *
     * @return the resource
     */
    private ControlGateResource resource() {
        ControlGateResource resource = new ControlGateResource();
        resource.gate = mock(ControlGateService.class);
        resource.functionApprovals = mock(FunctionApprovalService.class);
        resource.page = mock(Template.class);
        resource.continuePage = mock(Template.class);
        return resource;
    }

    /**
     * A held gesture.
     *
     * @param function the justified function, or null for a question
     * @return the gesture
     */
    private ControlGateService.Held held(String function) {
        return new ControlGateService.Held(ControlGateService.Kind.HTTP, "POST", "/action/x", "a=1", null,
                BigDecimal.ONE, null, function, "Sûr ?", "tok");
    }

    /**
     * With nothing held the screen goes back to the sale; otherwise it shows
     * the question, or the justifications.
     */
    @Test
    void showRendersWhatIsHeld() {
        ControlGateResource resource = resource();
        assertEquals(303, ((Response) resource.show()).getStatus());
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.page.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        when(resource.gate.pending()).thenReturn(held("F"));
        List<PosListService.ListChoice> reasons = List.of(new PosListService.ListChoice("1", "Erreur"));
        when(resource.gate.justifications()).thenReturn(reasons);
        assertSame(instance, resource.show());
        verify(resource.page).data("question", "Sûr ?");
        verify(instance).data("justification", true);
        verify(instance).data("reasons", reasons);
        when(resource.gate.pending()).thenReturn(held(null));
        resource.show();
        verify(instance).data("justification", false);
    }

    /**
     * The answer goes where the gate says.
     */
    @Test
    void answerRedirects() {
        ControlGateResource resource = resource();
        when(resource.gate.answer("YES")).thenReturn("/control-gate/continue?token=tok");
        Response response = resource.answer("YES");
        assertEquals(303, response.getStatus());
        assertEquals("/control-gate/continue", response.getLocation().getPath());
    }

    /**
     * A valid continuation renders the auto-submitted form; a stale one goes
     * back to the sale.
     */
    @Test
    void resumeRendersTheForm() {
        ControlGateResource resource = resource();
        assertEquals(303, ((Response) resource.resume("old")).getStatus());
        when(resource.gate.continuation("tok")).thenReturn(held(null));
        Map<String, List<String>> fields = Map.of("a", List.of("1"));
        when(resource.functionApprovals.fields("a=1")).thenReturn(fields);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.continuePage.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        assertSame(instance, resource.resume("tok"));
        verify(resource.continuePage).data("target", "/action/x");
        verify(instance).data("fields", fields);
        verify(instance).data("token", "tok");
    }
}
