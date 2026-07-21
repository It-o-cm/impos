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