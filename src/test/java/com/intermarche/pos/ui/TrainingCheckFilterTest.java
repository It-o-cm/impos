package com.intermarche.pos.ui;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Refused GET and POST routes never reach their resource in training (LC-13-05-04). */
class TrainingCheckFilterTest {

    /**
     * Wires a real state and a deterministic path policy.
     * @param training current mode
     * @return filter under test
     */
    private TrainingCheckFilter filter(boolean training) {
        TrainingCheckFilter filter = new TrainingCheckFilter();
        filter.state = new PosState();
        filter.state.trainingMode = training;
        filter.policy = mock(TrainingPolicyService.class);
        filter.i18n = mock(Messages.class);
        when(filter.i18n.get("op.fonctionIndisponibleFormation")).thenReturn("BLOCKED IN TRAINING");
        return filter;
    }

    /**
     * Builds a path-only request; submitted amounts are never read by the guard.
     * @param path request path
     * @return request fixture
     */
    private ContainerRequestContext request(String path) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(path);
        return context;
    }

    /** Normal selling never consults the training policy. */
    @Test
    void normalModeUnaffected() {
        TrainingCheckFilter filter = filter(false);
        ContainerRequestContext context = request("pay");
        filter.filter(context);
        verifyNoInteractions(filter.policy);
        verify(context, never()).abortWith(any());
    }

    /** Training actions remain usable when their path is allowed. */
    @Test
    void allowedTrainingPath() {
        TrainingCheckFilter filter = filter(true);
        ContainerRequestContext context = request("pay");
        filter.filter(context);
        verify(filter.policy).blocksPath("/pay");
        verify(context, never()).abortWith(any());
    }

    /** Both URI path representations are refused before GET or POST can execute. */
    @Test
    void blockedPathsReturnTranslatedRefusal() {
        for (String path : new String[]{"pay", "/pay"}) {
            TrainingCheckFilter filter = filter(true);
            ContainerRequestContext context = request(path);
            when(filter.policy.blocksPath("/pay")).thenReturn(true);
            filter.filter(context);
            ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
            verify(context).abortWith(response.capture());
            assertEquals(303, response.getValue().getStatus());
            assertEquals("/", response.getValue().getLocation().toString());
            assertEquals("BLOCKED IN TRAINING", filter.state.ticket.transientError);
        }
    }
}
