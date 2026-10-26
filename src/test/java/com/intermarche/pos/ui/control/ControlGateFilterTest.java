package com.intermarche.pos.ui.control;

import com.intermarche.pos.ui.PosState;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link ControlGateFilter} (BO-04-03-08/09/10/12).
 */
class ControlGateFilterTest {

    /**
     * Builds a filter over a mocked gate.
     *
     * @return the filter
     */
    private ControlGateFilter filter() {
        ControlGateFilter filter = new ControlGateFilter();
        filter.state = new PosState();
        filter.gate = mock(ControlGateService.class);
        return filter;
    }

    /**
     * A request on a path, with its query and, for a POST, its form.
     *
     * @param method the method
     * @param path the path, with no leading slash as JAX-RS gives it
     * @param query the raw query, or null
     * @param type the media type, or null
     * @param body the form
     * @return the mocked request
     */
    private ContainerRequestContext request(String method, String path, String query, MediaType type, String body) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uri);
        when(context.getMethod()).thenReturn(method);
        when(uri.getPath()).thenReturn(path);
        String raw = "/" + path.replaceFirst("^/", "");
        when(uri.getRequestUri()).thenReturn(URI.create("http://h" + raw + (query == null ? "" : "?" + query)));
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                params.add(parts[0], parts.length == 2 ? parts[1] : "");
            }
        }
        when(uri.getQueryParameters()).thenReturn(params);
        when(context.getMediaType()).thenReturn(type);
        InputStream stream = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        when(context.getEntityStream()).thenReturn(stream);
        return context;
    }

    /**
     * The gate does not look at its own screens, the ceremony, the polls nor
     * any other method than GET and POST.
     */
    @Test
    void ungatedRequestsPass() throws Exception {
        ControlGateFilter filter = filter();
        filter.filter(request("GET", "lock", null, null, ""));
        filter.filter(request("POST", "/action/control-gate/answer", null, null, ""));
        filter.filter(request("PUT", "action/x", null, null, ""));
        verify(filter.gate, never()).onFunction(anyString(), anyString(), anyString(), anyString());
        assertFalse(ControlGateFilter.gated("/action/endorse-validate"));
        for (String open : new String[]{"/", "/api/pos/scan", "/admin/x", "/control-gate", "/endorsement-data",
                "/approved-function", "/ticket-fragment", "/lock", "/supervisor/x", "/pos/x", "/customer-data", "/t/1"}) {
            assertFalse(ControlGateFilter.gated(open), open);
        }
        assertTrue(ControlGateFilter.gated("/abandon"));
        assertFalse(ControlGateFilter.gated("/action/unlock"));
        assertFalse(ControlGateFilter.gated("/action/lock"));
        assertFalse(ControlGateFilter.gated("/action/hardware-override"));
        assertTrue(ControlGateFilter.gated("/action/cancelTicket"));
    }

    /**
     * A replay passes untouched; its gate token leaves the form.
     */
    @Test
    void replayPasses() throws Exception {
        ControlGateFilter filter = filter();
        ContainerRequestContext context = request("POST", "action/x", null, MediaType.APPLICATION_FORM_URLENCODED_TYPE,
                "a=1&_posGate=tok");
        when(filter.gate.replay("POST", "/action/x", "tok", false)).thenReturn(true);
        filter.filter(context);
        ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
        verify(context).setEntityStream(body.capture());
        assertEquals("a=1", new String(body.getValue().readAllBytes(), StandardCharsets.UTF_8));
        verify(context, never()).abortWith(any());
    }

    /**
     * An endorsement continuation is recognised in the query and in the form.
     */
    @Test
    void approvalContinuationsAreRecognised() throws Exception {
        ControlGateFilter filter = filter();
        when(filter.gate.replay("GET", "/action/x", null, true)).thenReturn(true);
        filter.filter(request("GET", "action/x", "_posApproval=1", null, ""));
        verify(filter.gate).replay("GET", "/action/x", null, true);
        when(filter.gate.replay("POST", "/action/y", null, true)).thenReturn(true);
        filter.filter(request("POST", "action/y", null, MediaType.APPLICATION_FORM_URLENCODED_TYPE, "_posApproval=2"));
        verify(filter.gate).replay("POST", "/action/y", null, true);
        when(filter.gate.replay("POST", "/action/v", null, true)).thenReturn(true);
        filter.filter(request("POST", "action/v", "_posApproval=3", MediaType.APPLICATION_FORM_URLENCODED_TYPE, "a=1"));
        verify(filter.gate).replay("POST", "/action/v", null, true);
        filter.filter(request("GET", "action/u", "", null, ""));
        verify(filter.gate).onFunction("GET", "/action/u", "/action/u", "");
    }

    /**
     * A stopped function is redirected where the gate says; a POST not a form
     * keeps its body unread.
     */
    @Test
    void stoppedFunctionsAreRedirected() throws Exception {
        ControlGateFilter filter = filter();
        ContainerRequestContext context = request("GET", "action/x", "b=2&_posGate=old", null, "");
        when(filter.gate.onFunction("GET", "/action/x", "/action/x?b=2", "")).thenReturn("/control-gate");
        filter.filter(context);
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(context).abortWith(response.capture());
        assertEquals(303, response.getValue().getStatus());
        assertEquals("/control-gate", response.getValue().getLocation().getPath());
        ContainerRequestContext json = request("POST", "action/z", null, MediaType.APPLICATION_JSON_TYPE, "{}");
        filter.filter(json);
        verify(json, never()).setEntityStream(any());
        verify(filter.gate).onFunction("POST", "/action/z", "/action/z", "");
        ContainerRequestContext untyped = request("POST", "action/w", null, null, "");
        filter.filter(untyped);
        verify(untyped, never()).abortWith(any());
    }

    /**
     * The form helpers read and remove one field.
     */
    @Test
    void formHelpers() {
        assertEquals("v w", ControlGateFilter.field("a=1&b=v+w", "b"));
        assertEquals("", ControlGateFilter.field("b", "b"));
        assertNull(ControlGateFilter.field("a=1", "b"));
        assertEquals("a=1&c=3", ControlGateFilter.removeField("a=1&b=2&&c=3", "b"));
        assertEquals("", ControlGateFilter.removeField("", "b"));
    }
}
