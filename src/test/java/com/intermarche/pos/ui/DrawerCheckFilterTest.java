package com.intermarche.pos.ui;

import com.intermarche.pos.ui.hardware.HardwareService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DrawerCheckFilter}.
 * <p>
 * Every collaborator is a Mockito mock; no Quarkus context is booted. The
 * filter has five decision points — the {@code @DrawerMayBeOpen} opt-out, the
 * {@code isDrawerOpen()} guard, the GET-vs-mutation split, the referer-null
 * fallback and the URI-parse try/catch — so ten branches are exercised. The
 * two annotation arms are driven with real {@link Method} handles reflected
 * off the {@link #mayBeOpenRoute()} and {@link #guardedRoute()} helpers, since
 * {@code isAnnotationPresent} needs a genuine method. {@code PosState} is
 * mocked too: its {@code returnUrl} public field is read back off the mock
 * instance and its {@code touch()} call is verified.
 */
class DrawerCheckFilterTest {

    /** Filter under test with mocks assigned to its package-private fields. */
    private DrawerCheckFilter filter;
    /** Mocked drawer sensor. */
    private HardwareService hardwareService;
    /** Mocked resource-method holder. */
    private ResourceInfo resourceInfo;
    /** Mocked terminal state. */
    private PosState state;
    /** Mocked JAX-RS request context. */
    private ContainerRequestContext requestContext;
    /** Mocked URI info returned by the request context. */
    private UriInfo uriInfo;

    /**
     * Wires a fresh filter and fresh mocks before each test to guarantee full
     * isolation, and stubs the URI info the context hands back.
     */
    @BeforeEach
    void setUp() {
        filter = new DrawerCheckFilter();
        hardwareService = mock(HardwareService.class);
        resourceInfo = mock(ResourceInfo.class);
        state = mock(PosState.class);
        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        filter.hardwareService = hardwareService;
        filter.resourceInfo = resourceInfo;
        filter.state = state;
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
    }

    /**
     * Reflection helper carrying the {@code @DrawerMayBeOpen} opt-out, used to
     * drive the annotation-present arm.
     */
    @DrawerMayBeOpen
    private void mayBeOpenRoute() {
    }

    /**
     * Reflection helper without the opt-out, used to drive the
     * annotation-absent arm.
     */
    private void guardedRoute() {
    }

    /**
     * Stubs {@link ResourceInfo#getResourceMethod()} to return the named helper
     * method of this test class.
     *
     * @param name the declared helper method name
     * @throws Exception if reflection fails
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void useMethod(String name) throws Exception {
        Method method = DrawerCheckFilterTest.class.getDeclaredMethod(name);
        doReturn(method).when(resourceInfo).getResourceMethod();
    }

    /**
     * Annotation-present arm: a {@code @DrawerMayBeOpen} route returns
     * immediately, never touching the sensor, the state or the context.
     *
     * @throws Exception if reflection or the filter fails
     */
    @Test
    void returnsEarlyWhenRouteMayBeOpen() throws Exception {
        useMethod("mayBeOpenRoute");
        filter.filter(requestContext);
        verifyNoInteractions(hardwareService);
        verify(state, never()).touch();
        verify(requestContext, never()).abortWith(org.mockito.ArgumentMatchers.any());
        assertNull(state.returnUrl);
    }

    /**
     * Guarded route with a closed drawer: the guard passes through, no return
     * URL is stored, no touch and no abort occur.
     *
     * @throws Exception if reflection or the filter fails
     */
    @Test
    void doesNothingWhenDrawerClosed() throws Exception {
        useMethod("guardedRoute");
        when(hardwareService.isDrawerOpen()).thenReturn(false);
        filter.filter(requestContext);
        verify(state, never()).touch();
        verify(requestContext, never()).abortWith(org.mockito.ArgumentMatchers.any());
        assertNull(state.returnUrl);
    }

    /**
     * Guarded GET with an open drawer: the blocked path itself is remembered,
     * the state is touched and the request is aborted toward
     * {@code /drawer-error}.
     *
     * @throws Exception if reflection or the filter fails
     */
    @Test
    void remembersBlockedPathOnGet() throws Exception {
        useMethod("guardedRoute");
        when(hardwareService.isDrawerOpen()).thenReturn(true);
        when(requestContext.getMethod()).thenReturn("get");
        when(uriInfo.getPath()).thenReturn("session");
        filter.filter(requestContext);
        assertEquals("session", state.returnUrl);
        verify(state).touch();
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        assertEquals("/drawer-error", captor.getValue().getLocation().getPath());
        assertEquals(303, captor.getValue().getStatus());
    }

    /**
     * Guarded POST with an open drawer and a valid Referer: the referer's path
     * is remembered rather than the blocked mutation path.
     *
     * @throws Exception if reflection or the filter fails
     */
    @Test
    void remembersRefererPathOnPost() throws Exception {
        useMethod("guardedRoute");
        when(hardwareService.isDrawerOpen()).thenReturn(true);
        when(requestContext.getMethod()).thenReturn("POST");
        when(requestContext.getHeaderString("Referer"))
                .thenReturn("http://localhost:8080/pay");
        filter.filter(requestContext);
        assertEquals("/pay", state.returnUrl);
        verify(state).touch();
        verify(requestContext).abortWith(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Guarded POST with an open drawer and a malformed Referer: the URI parse
     * throws and the catch arm falls back to the home page.
     *
     * @throws Exception if reflection or the filter fails
     */
    @Test
    void fallsBackToHomeWhenRefererMalformed() throws Exception {
        useMethod("guardedRoute");
        when(hardwareService.isDrawerOpen()).thenReturn(true);
        when(requestContext.getMethod()).thenReturn("POST");
        when(requestContext.getHeaderString("Referer"))
                .thenReturn("http://bad host/x");
        filter.filter(requestContext);
        assertEquals("/", state.returnUrl);
        verify(state).touch();
        verify(requestContext).abortWith(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Guarded POST with an open drawer and no Referer: the null-referer arm
     * falls back to the home page.
     *
     * @throws Exception if reflection or the filter fails
     */
    @Test
    void fallsBackToHomeWhenNoReferer() throws Exception {
        useMethod("guardedRoute");
        when(hardwareService.isDrawerOpen()).thenReturn(true);
        when(requestContext.getMethod()).thenReturn("POST");
        when(requestContext.getHeaderString("Referer")).thenReturn(null);
        filter.filter(requestContext);
        assertEquals("/", state.returnUrl);
        verify(state).touch();
        verify(requestContext).abortWith(org.mockito.ArgumentMatchers.any());
    }
}
