package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.service.PosSettingsService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** HTTP gestures remain inert until the exact approved continuation is received (LC-01-05-01). */
class FunctionApprovalFilterTest {
    /**
     * Builds a real policy and mocked existing endorsement ceremony.
     * @return filter with a real cart and approval state
     */
    private FunctionApprovalFilter filter() {
        FunctionApprovalFilter filter = new FunctionApprovalFilter();
        filter.state = new PosState();filter.approvals = new FunctionApprovalService();
        filter.approvals.state = filter.state;filter.approvals.settings = mock(PosSettingsService.class);
        when(filter.approvals.settings.functionEndorsements()).thenReturn("GET /manual=REQUIRED\nPOST /sale-tax-id=REQUIRED");
        filter.endorsements = mock(EndorsementService.class);return filter;
    }

    /**
     * Builds a transport request, preserving both raw URI and decoded query values.
     * @param method method
     * @param target local target
     * @param body urlencoded form
     * @return request fixture
     */
    private ContainerRequestContext request(String method, String target, String body) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);UriInfo uri = mock(UriInfo.class);
        URI location = URI.create("http://localhost" + (target.startsWith("/") ? target : "/" + target));
        when(context.getUriInfo()).thenReturn(uri);when(uri.getPath()).thenReturn(target.startsWith("/") ? location.getPath() : location.getPath().substring(1));
        when(uri.getRequestUri()).thenReturn(location);
        var query = new MultivaluedHashMap<String,String>();
        if (location.getRawQuery() != null && !location.getRawQuery().isEmpty()) {
            new FunctionApprovalService().fields(location.getRawQuery()).forEach(query::put);
        }
        when(uri.getQueryParameters()).thenReturn(query);when(context.getMethod()).thenReturn(method);
        when(context.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        when(context.getEntityStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return context;
    }

    /** Every infrastructure-prefix leg stays reachable, with no policy or credential lookup. */
    @Test
    void infrastructureAndUnselectedRoutes() throws Exception {
        FunctionApprovalFilter filter = filter();
        for (String path : new String[]{"/", "/api/pos/scan", "/admin/settings", "/action/endorse-validate",
                "/endorsement-data", "/approved-function", "/ticket-fragment", "/lock", "/action/unlock",
                "/action/hardware-override", "/supervisor/health", "/pos/training-policy.js", "/customer", "/t/1/key"}) {
            var request = request("GET", path, "");filter.filter(request);verify(request, never()).abortWith(any());
        }
        var put = request("PUT", "/manual", "");filter.filter(put);verify(put, never()).abortWith(any());
        var ordinary = request("GET", "/unselected", "");filter.filter(ordinary);verify(ordinary, never()).abortWith(any());
        var head = request("HEAD", "/manual/", "");filter.filter(head);
        verify(head).abortWith(argThat(response -> response.getStatus()==405));
        verifyNoInteractions(filter.endorsements);
        when(filter.approvals.settings.functionEndorsements()).thenReturn("GET /manual=NONE");
        var exempt = request("GET", "/manual", "");filter.filter(exempt);
        assertTrue(filter.functionContext.authorized());assertFalse(filter.functionContext.approved());
        verify(exempt, never()).abortWith(any());
    }

    /** The first GET holds, approval continues to that query, and replay is refused. */
    @Test
    void guardedGetAndReplay() throws Exception {
        for (String target : new String[]{"manual", "/manual?x=1", "/manual?"}) {
            FunctionApprovalFilter filter = filter();var first = request("GET", target, "");filter.filter(first);
            verify(first).abortWith(any());verify(filter.endorsements).requestAuthorization(filter.state, FunctionApprovalService.ACTION);
            String next = filter.approvals.approve().getLocation().toString();
            var approved = request("GET", next, "");filter.filter(approved);verify(approved, never()).abortWith(any());
            var replay = request("GET", next, "");filter.filter(replay);verify(replay).abortWith(any());
        }
    }

    /** An approved POST restores its original body; a connected supervisor does not need a modal. */
    @Test
    void guardedPostAndSupervisor() throws Exception {
        FunctionApprovalFilter filter = filter();var first = request("POST", "/sale-tax-id", "taxId=123");
        filter.filter(first);verify(first).abortWith(any());
        String next = filter.approvals.approve().getLocation().toString();String token=next.substring(next.indexOf("token=")+6);
        var approved = request("POST", "/sale-tax-id", "taxId=123&_posApproval="+token);
        filter.filter(approved);verify(approved, never()).abortWith(any());verify(approved).setEntityStream(any());
        when(filter.endorsements.selfAuthorized(filter.state)).thenReturn(true);
        var supervisor = request("GET", "/manual", "");filter.filter(supervisor);verify(supervisor, never()).abortWith(any());
    }

    /** Unsupported or oversized forms are explicit refusals; they never become a pending gesture. */
    @Test
    void unsupportedFormsAndTokenPlacement() throws Exception {
        FunctionApprovalFilter filter = filter();
        for (MediaType media : new MediaType[]{null,MediaType.APPLICATION_JSON_TYPE}) {
            var request=request("POST","/sale-tax-id","{}");when(request.getMediaType()).thenReturn(media);
            filter.filter(request);verify(request).abortWith(argThat(response -> response.getStatus()==415));
        }
        var huge=request("POST","/sale-tax-id","x".repeat(65537));filter.filter(huge);
        verify(huge).abortWith(argThat(response -> response.getStatus()==413));
        for (String body : new String[]{"_posApproval=bad", "_posApproval=bad&taxId=1"}) {
            var invalid=request("POST","/sale-tax-id",body);filter.filter(invalid);verify(invalid).abortWith(any());
        }
    }
}
