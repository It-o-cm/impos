package com.intermarche.pos.ui;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** LC-02-03-11: direct sale actions and hardware scans cannot bypass acknowledgement. */
class ArticleMessageFilterTest {
    /** @param path request path @return a path-only request mock */
    private ContainerRequestContext request(String path) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(path);
        return context;
    }

    /** No pending message leaves all actions available, without consulting their path. */
    @Test
    void normalSellingIsUnaffected() {
        ArticleMessageFilter filter = new ArticleMessageFilter();
        filter.state = new PosState();
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        filter.filter(context);
        verifyNoInteractions(context);
    }

    /** Polling, exact allowed pages and nested machine paths remain reachable. */
    @Test
    void readOnlyAndAcknowledgementPathsRemainAvailable() {
        ArticleMessageFilter filter = new ArticleMessageFilter();
        filter.state = new PosState();
        filter.state.blockingArticleMessage = "Stop";
        for (String path : new String[]{"article-message", "/action/article-message/ack", "/ticket-fragment", "/customer", "/customer-data", "/api/hardware/drawer/close", "/lock", "/action/unlock", "/admin/articles"}) {
            ContainerRequestContext context = request(path);
            filter.filter(context);
            verify(context, never()).abortWith(any());
        }
    }

    /** Both machine input routes return conflict; operator actions redirect before mutation. */
    @Test
    void scansPaymentAndManualActionsAreStopped() {
        ArticleMessageFilter filter = new ArticleMessageFilter();
        filter.state = new PosState();
        filter.state.blockingArticleMessage = "Stop";
        for (String path : new String[]{"/api/pos/scan", "weight", "/action/pay-cash", "/pay", "/manual", "/", "/action/article-message/acknowledge-other"}) {
            ContainerRequestContext context = request(path);
            filter.filter(context);
            ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
            verify(context).abortWith(response.capture());
            if (path.equals("/api/pos/scan") || path.equals("weight")) {
                assertEquals(409, response.getValue().getStatus());
            } else {
                assertEquals(303, response.getValue().getStatus());
                assertEquals("/article-message", response.getValue().getLocation().toString());
            }
        }
    }
}
