package com.intermarche.pos.ui.ticket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/** LC-02-14-12: a leased scanner mailbox, scoped to the visible read-only price consultation. */
@Provider
@ApplicationScoped
@jakarta.annotation.Priority(jakarta.ws.rs.Priorities.AUTHENTICATION - 1)
public class ArticleInfoScanState implements ContainerRequestFilter {
    /** Clock seam keeps expiry tests deterministic. */
    java.util.function.LongSupplier clock = System::currentTimeMillis;
    /** Only this page instance may refresh or release its lease. */
    private String session;
    /** The consultation is abandoned after a browser disappears without navigation. */
    private long expiresAt;
    /** The latest scan is consumed once by the matching page. */
    private String pending = "";

    /** Opens a new page lease, invalidating callbacks from an older page.
     * @return the opaque page token
     */
    public synchronized String open() {
        session = java.util.UUID.randomUUID().toString();
        pending = "";
        expiresAt = clock.getAsLong() + 30000;
        return session;
    }

    /** Captures a scan before any ticket admission or mutation.
     * @param code the scanner payload
     * @return true when this consultation consumed it
     */
    public synchronized boolean capture(String code) {
        if (session == null || clock.getAsLong() >= expiresAt) {
            return false;
        }
        pending = code == null ? "" : code.trim();
        return true;
    }

    /** Renews the current page and consumes its latest scan exactly once.
     * @param token the requesting page instance
     * @return the scanned code, or an empty mailbox
     */
    public synchronized String poll(String token) {
        if (session == null || !session.equals(token) || clock.getAsLong() >= expiresAt) {
            return "";
        }
        expiresAt = clock.getAsLong() + 30000;
        String code = pending;
        pending = "";
        return code;
    }

    /** Releases only the page that is leaving, ignoring a delayed older page.
     * @param token the leaving page instance
     */
    public synchronized void leave(String token) {
        if (java.util.Objects.equals(session, token)) {
            clear();
        }
    }

    /** Clears consultation routing on ordinary navigation, while machine calls and polls leave it alone.
     * @param request the incoming browser or machine request
     */
    @Override
    public void filter(ContainerRequestContext request) {
        String accept = request.getHeaderString("Accept");
        String path = request.getUriInfo().getPath();
        if (accept != null && accept.contains("text/html")
                && !path.replaceFirst("^/", "").equals("article-info")) {
            clear();
        }
    }

    /** Ends the lease and drops codes that belong to an abandoned consultation. */
    public synchronized void clear() {
        session = null;
        pending = "";
        expiresAt = 0;
    }
}
