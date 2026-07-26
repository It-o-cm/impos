package com.intermarche.pos.ui;

import com.intermarche.pos.ui.hardware.HardwareService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;

/**
 * JAX-RS filter enforcing the drawer guard on every
 * {@code @DrawerMustBeClosed} route without a method-level opt-out: when
 * the physical drawer answers OPEN, the request is aborted toward the
 * drawer-error screen.
 * <p>
 * The RETURN URL is the subtle part: for a GET the blocked path itself is
 * remembered, for a mutation (POST) the REFERER page is remembered instead
 * — replaying a blocked mutation after closing the drawer would be wrong
 * (the form is stale), sending the cashier back to the page they came from
 * is right. Falls back to the home page when no referer exists. Note the
 * dependency on {@code HardwareService.isDrawerOpen()} answering false on
 * sensor failure: a dead sensor disables this guard rather than bricking
 * the register — the documented degraded-mode trade-off.
 */
@Provider
@DrawerMustBeClosed
public class DrawerCheckFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(DrawerCheckFilter.class);

    @Inject
    HardwareService hardwareService;
    @Inject
    ResourceInfo resourceInfo;
    @Inject
    PosState state;

    /**
     * Aborts the request toward the drawer-error screen when the drawer is
     * open, remembering where to send the cashier back.
     *
     * @param requestContext the JAX-RS request context
     * @throws IOException never in practice (filter contract)
     */
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (resourceInfo.getResourceMethod().isAnnotationPresent(DrawerMayBeOpen.class)) {
            return;
        }
        if (hardwareService.isDrawerOpen()) {
            UriInfo uriInfo = requestContext.getUriInfo();
            String method = requestContext.getMethod();
            if ("GET".equalsIgnoreCase(method)) {
                state.returnUrl = uriInfo.getPath();
            } else {
                String referer = requestContext.getHeaderString("Referer");
                if (referer != null) {
                    try {
                        state.returnUrl = URI.create(referer).getPath();
                    } catch (Exception e) {
                        state.returnUrl = "/";
                    }
                } else {
                    state.returnUrl = "/";
                }
            }
            state.touch();
            LOG.warn("Accès bloqué (Tiroir ouvert). Return URL enregistrée: " + state.returnUrl);
            requestContext.abortWith(
                    Response.seeOther(URI.create("/drawer-error")).build()
            );
        }
    }
}