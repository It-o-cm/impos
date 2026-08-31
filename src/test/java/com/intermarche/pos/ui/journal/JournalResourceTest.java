package com.intermarche.pos.ui.journal;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JournalResource}.
 * <p>
 * The resource is a thin GET adapter: it parses the URI into a
 * {@link JournalCriteria}, delegates to a mocked {@link JournalService}, and
 * wires a mocked Qute template. Each endpoint has a single path (no
 * conditional branches), so one test per endpoint pins the delegation and the
 * response shape. No database, no Quarkus context.
 */
class JournalResourceTest {

    /**
     * Builds a resource over mocked templates and service.
     *
     * @return the wired resource
     */
    private JournalResource newResource() {
        JournalResource resource = new JournalResource();
        resource.journal = mock(Template.class);
        resource.journalDetail = mock(Template.class);
        resource.journalService = mock(JournalService.class);
        return resource;
    }

    /**
     * Wires a self-returning template instance on the page template.
     *
     * @param template the template to wire
     * @return the mocked instance the chain returns
     */
    private TemplateInstance wire(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a UriInfo whose query parameters are empty.
     *
     * @return the mocked UriInfo
     */
    private UriInfo emptyUri() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        return uriInfo;
    }

    /**
     * The transactional endpoint runs the ticket search and renders the page
     * with the transactional tab.
     */
    @Test
    void transactionalRendersTicketSearch() {
        JournalResource resource = newResource();
        TemplateInstance instance = wire(resource.journal);
        when(resource.journalService.search(any())).thenReturn(List.of());
        assertSame(instance, resource.transactional(emptyUri()));
        verify(resource.journal).data("tab", "transactional");
        verify(resource.journalService).search(any());
    }

    /**
     * The functional endpoint runs the event search and renders the page with
     * the functional tab.
     */
    @Test
    void functionalRendersEventSearch() {
        JournalResource resource = newResource();
        TemplateInstance instance = wire(resource.journal);
        when(resource.journalService.searchEvents(any())).thenReturn(List.of());
        assertSame(instance, resource.functional(emptyUri()));
        verify(resource.journal).data("tab", "functional");
        verify(resource.journalService).searchEvents(any());
    }

    /**
     * The detail endpoint materializes the ticket and renders the detail
     * template.
     */
    @Test
    void detailRendersTicket() {
        JournalResource resource = newResource();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.journalDetail.data(anyString(), any())).thenReturn(instance);
        JournalTicketDetail detail = new JournalTicketDetail("C04-00000001", "C04", "CLOSED",
                "31/08/2026", "10:00", "Jean Dupont", "12341234", "IM Lyon", "",
                "3,00", "2,85", "0,15", List.of(), List.of());
        when(resource.journalService.buildDetail(1L)).thenReturn(detail);
        assertSame(instance, resource.detail(1L));
        verify(resource.journalDetail).data("ticket", detail);
    }

    /**
     * The export endpoint returns the CSV as a downloadable attachment.
     */
    @Test
    void exportReturnsCsvAttachment() {
        JournalResource resource = newResource();
        when(resource.journalService.exportCsv(any())).thenReturn("H\nrow\n");
        Response response = resource.export(emptyUri());
        assertEquals(200, response.getStatus());
        assertEquals("H\nrow\n", response.getEntity());
        assertTrue(response.getHeaderString("Content-Disposition").contains("journal.csv"));
    }
}
