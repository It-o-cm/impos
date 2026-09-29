package com.intermarche.pos.ui.balance;

import com.intermarche.pos.ui.PosState;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link TareResource}, the container-tare gestures of the
 * weighing screen (LC-14-01-04/05).
 * <p>
 * The resource is a thin JAX-RS facade over a real {@link PosState} and a
 * mocked {@link TareService}. Branch enumeration (100%): each of the three
 * endpoints (select, scan, clear) delegates and redirects to {@code /fruits}.
 */
class TareResourceTest {

    /**
     * Builds a resource with a real POS state and a mocked tare service.
     *
     * @return the wired resource
     */
    private TareResource newResource() {
        TareResource resource = new TareResource();
        resource.state = new PosState();
        resource.tareService = mock(TareService.class);
        return resource;
    }

    /**
     * Asserts the response is a 303 redirect to the weighing grid.
     *
     * @param response the response under test
     */
    private void assertRedirectsToFruits(Response response) {
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/fruits", response.getLocation().toString());
    }

    /**
     * {@code selectContainer} arms the tapped container through the service and
     * redirects to the weighing grid.
     */
    @Test
    void selectContainerDelegatesAndRedirects() {
        TareResource resource = newResource();
        assertRedirectsToFruits(resource.selectContainer("BQS"));
        verify(resource.tareService).selectContainer(resource.state, "BQS");
    }

    /**
     * {@code scanContainer} arms the scanned container through the service and
     * redirects to the weighing grid.
     */
    @Test
    void scanContainerDelegatesAndRedirects() {
        TareResource resource = newResource();
        assertRedirectsToFruits(resource.scanContainer("3800000000010"));
        verify(resource.tareService).scanContainer(resource.state, "3800000000010");
    }

    /**
     * {@code clearContainer} disarms any armed container tare and redirects to
     * the weighing grid.
     */
    @Test
    void clearContainerDisarmsAndRedirects() {
        TareResource resource = newResource();
        resource.state.tare.containerTare = new BigDecimal("0.035");
        resource.state.tare.containerLabel = "Barquette S";
        assertRedirectsToFruits(resource.clearContainer());
        assertNull(resource.state.tare.containerTare);
        assertNull(resource.state.tare.containerLabel);
    }
}
