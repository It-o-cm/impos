package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.domain.deploy.FunctionUsage;
import com.intermarche.pos.service.deploy.FunctionUsageService;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RegisterFunctionUsageFilter}. */
class RegisterFunctionUsageFilterTest {

    /** A resource rooted at the server's root, for the template. */
    @Path("/")
    static class Screen {

        /** A register function. */
        @Path("/action/add/{code}")
        void add() {
            // Only its path is read.
        }

        /** A page that is not a function. */
        @Path("/sale")
        void page() {
            // Only its path is read.
        }

        /** A handler with no path of its own. */
        void bare() {
            // Only its absence of path is read.
        }
    }

    /** A resource without path. */
    static class Unrooted {

        /** A handler with a path. */
        @Path("action/pay")
        void pay() {
            // Only its path is read.
        }
    }

    /**
     * Builds the filter on a handler.
     *
     * @param type the resource class
     * @param handler the handler's name, or null for no handler
     * @return the filter
     * @throws Exception never
     */
    private RegisterFunctionUsageFilter filter(Class<?> type, String handler) throws Exception {
        RegisterFunctionUsageFilter filter = new RegisterFunctionUsageFilter();
        filter.resourceInfo = mock(ResourceInfo.class);
        Method method = handler == null ? null : type.getDeclaredMethod(handler);
        when(filter.resourceInfo.getResourceMethod()).thenReturn(method);
        doReturn(type).when(filter.resourceInfo).getResourceClass();
        filter.functionUsageService = mock(FunctionUsageService.class);
        filter.terminalId = "C04";
        filter.pdvNumber = Optional.of("07039");
        return filter;
    }

    /**
     * Builds a request.
     *
     * @param method the HTTP method
     * @return the request
     */
    private ContainerRequestContext request(String method) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn(method);
        return request;
    }

    /** BO-08-02-02: a register function is counted under its path template. */
    @Test
    void aFunctionIsCounted() throws Exception {
        RegisterFunctionUsageFilter filter = filter(Screen.class, "add");
        filter.filter(request("POST"));
        verify(filter.functionUsageService).record("C04", "07039", FunctionUsage.REGISTER, "/action/add/{code}");
        RegisterFunctionUsageFilter unrooted = filter(Unrooted.class, "pay");
        unrooted.pdvNumber = Optional.empty();
        unrooted.filter(request("POST"));
        verify(unrooted.functionUsageService).record("C04", null, FunctionUsage.REGISTER, "/action/pay");
    }

    /** A page, a GET, a missing handler or a missing resource info is not counted; a failure never blocks. */
    @Test
    void nothingElseIsCounted() throws Exception {
        RegisterFunctionUsageFilter page = filter(Screen.class, "page");
        page.filter(request("POST"));
        RegisterFunctionUsageFilter get = filter(Screen.class, "add");
        get.filter(request("GET"));
        RegisterFunctionUsageFilter none = filter(Screen.class, null);
        none.filter(request("POST"));
        RegisterFunctionUsageFilter absent = filter(Screen.class, "add");
        absent.resourceInfo = null;
        absent.filter(request("POST"));
        verify(page.functionUsageService, never()).record(any(), any(), any(), any());
        verify(get.functionUsageService, never()).record(any(), any(), any(), any());
        verify(none.functionUsageService, never()).record(any(), any(), any(), any());
        RegisterFunctionUsageFilter failing = filter(Screen.class, "add");
        doThrow(new IllegalStateException("db")).when(failing.functionUsageService)
                .record(anyString(), any(), anyString(), anyString());
        failing.filter(request("POST"));
        assertEquals("/", RegisterFunctionUsageFilter.template(Screen.class, Screen.class.getDeclaredMethod("bare")));
    }
}
