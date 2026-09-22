package com.intermarche.pos.ui.admin;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;

/**
 * Sign-in and sign-out screens of the back office.
 * <p>
 * Authentication itself is performed by Quarkus form authentication: the
 * form posts to {@code /j_security_check}, which hands the credentials to
 * {@code EmployeeIdentityProvider} and issues the session cookie. This
 * resource only renders the surrounding pages — it never reads a password.
 * <p>
 * The login page is deliberately open to anonymous requests, and so are the
 * two stylesheets it loads (they sit under {@code /admin/} as static
 * resources, outside any permission policy). Every other back-office screen
 * carries {@code @RolesAllowed}.
 * <p>
 * Placement: with {@link AdminSettingsResource} in {@code ui.admin}, because
 * these are back-office SCREENS; the identity plumbing they lean on lives in
 * {@code pos.security}, the mirror of the imvaluation split.
 */
@Path("/")
public class AdminAuthResource {

    /**
     * Name of the cookie holding the form authentication session.
     * <p>
     * Clearing it is what actually signs the operator out.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    /** The sign-in page template. */
    @Inject
    @Location("admin-login")
    Template adminLogin;

    /**
     * Shows the sign-in page.
     *
     * @param error present when form authentication rejected the credentials
     * @param notice the one-shot message carried back from a sign-out
     * @return the sign-in page
     */
    @GET
    @Path("/admin/login")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance loginPage(@QueryParam("error") String error,
                                      @QueryParam("notice") String notice) {
        String message = error == null
                ? null
                : "Identifiant ou code incorrect, ou compte désactivé ou verrouillé.";
        return adminLogin.data("error", message).data("notice", notice);
    }

    /**
     * Signs the operator out by clearing the session cookie.
     *
     * @return a redirect to the sign-in page, carrying the cleared cookie
     */
    @POST
    @Path("/admin/logout")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response logout() {
        NewCookie cleared = new NewCookie.Builder(SESSION_COOKIE)
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build();
        URI target = UriBuilder.fromPath("/admin/login")
                .queryParam("notice", "Vous êtes déconnecté.")
                .build();
        return Response.seeOther(target).cookie(cleared).build();
    }
}
