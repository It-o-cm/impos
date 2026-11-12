package com.intermarche.pos.ui.home;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.store.CashFunction;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HiddenFunctionFilter} (LS-12-02-03): a function the menu model
 * hides is refused server-side with the till's usual refusal, whatever form the path
 * takes; a function it does not hide passes; the session screen stays reachable while no
 * session is open.
 */
class HiddenFunctionFilterTest {

    /** The query parameters every fixture request carries. */
    private final MultivaluedMap<String, String> query = new MultivaluedHashMap<>();

    /**
     * Builds the filter on a real state, the French bundle and mocked collaborators.
     *
     * @return the filter under test
     */
    private HiddenFunctionFilter filter() {
        HiddenFunctionFilter filter = new HiddenFunctionFilter();
        filter.state = new PosState();
        filter.cashMenuService = mock(CashMenuService.class);
        filter.cashSessionService = mock(CashSessionService.class);
        filter.i18n = TestMessages.french();
        return filter;
    }

    /**
     * Builds a request on a path.
     *
     * @param path the path as the container hands it over
     * @return the request fixture
     */
    private ContainerRequestContext request(String path) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(path);
        when(uri.getQueryParameters()).thenReturn(query);
        return context;
    }

    /**
     * Asserts the request was refused the till's way: back to the sale screen, the
     * refusal on the message band.
     *
     * @param filter  the filter that ran
     * @param context the request it refused
     */
    private void assertRefused(HiddenFunctionFilter filter, ContainerRequestContext context) {
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(context).abortWith(response.capture());
        assertEquals(303, response.getValue().getStatus());
        assertEquals("/", response.getValue().getLocation().toString());
        assertEquals("FONCTION NON DISPONIBLE SUR CETTE CAISSE", filter.state.ticket.transientError);
    }

    /**
     * A hidden function is refused whether the container hands the path over with or
     * without its leading slash.
     */
    @Test
    void aHiddenFunctionIsRefused() {
        for (String path : new String[]{"invoice", "/invoice"}) {
            HiddenFunctionFilter filter = filter();
            when(filter.cashMenuService.hiddenFunctionReached("/invoice", query)).thenReturn(CashFunction.INVOICE);
            ContainerRequestContext context = request(path);
            filter.filter(context);
            assertRefused(filter, context);
            verifyNoInteractions(filter.cashSessionService);
        }
    }

    /**
     * A request reaching no hidden function passes untouched.
     */
    @Test
    void aVisibleFunctionPasses() {
        HiddenFunctionFilter filter = filter();
        ContainerRequestContext context = request("/reprint");
        filter.filter(context);
        verify(context, never()).abortWith(any());
        assertNull(filter.state.ticket.transientError);
    }

    /**
     * A hidden session screen stays reachable while no session is open — the register
     * sends the operator there — and is refused once one is.
     */
    @Test
    void aHiddenSessionScreenIsRefusedOnlyWithAnOpenSession() {
        HiddenFunctionFilter filter = filter();
        when(filter.cashMenuService.hiddenFunctionReached("/session", query)).thenReturn(CashFunction.SESSION);
        ContainerRequestContext closed = request("/session");
        filter.filter(closed);
        verify(closed, never()).abortWith(any());
        when(filter.cashSessionService.getOpenSession()).thenReturn(new CashSession());
        ContainerRequestContext open = request("/session");
        filter.filter(open);
        assertRefused(filter, open);
    }
}
