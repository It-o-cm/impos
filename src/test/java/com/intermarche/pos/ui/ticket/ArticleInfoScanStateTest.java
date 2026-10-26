package com.intermarche.pos.ui.ticket;

/** Unit contracts for HD_43 referential and document coverage. */
class ArticleInfoScanStateTest {
    /** LC-02-14-12: scans are captured and consumed once only by the visible page, including blank scanner frames. */
    @org.junit.jupiter.api.Test
    void currentPageOwnsItsMailboxWithoutReplayingCodes() {
        var scans = new ArticleInfoScanState();
        scans.clock = () -> 1000;
        org.junit.jupiter.api.Assertions.assertFalse(scans.capture("12345670"));
        org.junit.jupiter.api.Assertions.assertEquals("", scans.poll("absent"));
        String token = scans.open();
        org.junit.jupiter.api.Assertions.assertTrue(scans.capture(" 12345670 "));
        org.junit.jupiter.api.Assertions.assertEquals("", scans.poll("older-page"));
        org.junit.jupiter.api.Assertions.assertEquals("12345670", scans.poll(token));
        org.junit.jupiter.api.Assertions.assertEquals("", scans.poll(token));
        org.junit.jupiter.api.Assertions.assertTrue(scans.capture(null));
        org.junit.jupiter.api.Assertions.assertEquals("", scans.poll(token));
        org.junit.jupiter.api.Assertions.assertTrue(scans.capture(" "));
        org.junit.jupiter.api.Assertions.assertEquals("", scans.poll(token));
    }
    /** LC-02-14-12: expiry cannot be renewed by a late poll; a late release from an older page cannot cancel its successor. */
    @org.junit.jupiter.api.Test
    void expiryAndOldPageCallbacksCannotKeepOrStealTheLease() {
        var scans = new ArticleInfoScanState();
        var time = new java.util.concurrent.atomic.AtomicLong(1000);
        scans.clock = time::get;
        String first = scans.open();
        time.set(31000);
        org.junit.jupiter.api.Assertions.assertFalse(scans.capture("12345670"));
        org.junit.jupiter.api.Assertions.assertEquals("", scans.poll(first));
        String second = scans.open();
        scans.leave(first);
        org.junit.jupiter.api.Assertions.assertTrue(scans.capture("A"));
        time.set(60000);
        org.junit.jupiter.api.Assertions.assertEquals("A", scans.poll(second));
        time.set(70000);
        org.junit.jupiter.api.Assertions.assertTrue(scans.capture("B"));
        scans.leave(second);
        org.junit.jupiter.api.Assertions.assertFalse(scans.capture("C"));
        org.junit.jupiter.api.Assertions.assertEquals("", scans.poll(second));
    }
    /** LC-02-14-12: ordinary HTML navigation immediately restores the sale scanner; machine calls and the current screen do not. */
    @org.junit.jupiter.api.Test
    void onlyNavigationAwayEndsTheConsultation() {
        var scans = new ArticleInfoScanState();
        for (String accept : new String[]{null, "application/json", "text/html"}) {
            for (String path : new String[]{"article-info", "/article-info"}) {
                String token = scans.open();
                var request = org.mockito.Mockito.mock(jakarta.ws.rs.container.ContainerRequestContext.class);
                var uri = org.mockito.Mockito.mock(jakarta.ws.rs.core.UriInfo.class);
                org.mockito.Mockito.when(request.getHeaderString("Accept")).thenReturn(accept);
                org.mockito.Mockito.when(request.getUriInfo()).thenReturn(uri);
                org.mockito.Mockito.when(uri.getPath()).thenReturn(path);
                scans.filter(request);
                org.junit.jupiter.api.Assertions.assertTrue(scans.capture("EAN"));
                org.junit.jupiter.api.Assertions.assertEquals("EAN", scans.poll(token));
            }
        }
        scans.open();
        var request = org.mockito.Mockito.mock(jakarta.ws.rs.container.ContainerRequestContext.class);
        var uri = org.mockito.Mockito.mock(jakarta.ws.rs.core.UriInfo.class);
        org.mockito.Mockito.when(request.getHeaderString("Accept")).thenReturn("text/html,application/xhtml+xml");
        org.mockito.Mockito.when(request.getUriInfo()).thenReturn(uri);
        org.mockito.Mockito.when(uri.getPath()).thenReturn("/");
        scans.filter(request);
        org.junit.jupiter.api.Assertions.assertFalse(scans.capture("SALE"));
    }

}
