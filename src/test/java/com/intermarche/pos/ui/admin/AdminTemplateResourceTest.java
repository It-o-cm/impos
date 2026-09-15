package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.service.DocumentTemplateService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminTemplateResource}.
 * <p>
 * A Qute-backed back-office page over the Panache statics of
 * {@link DocumentTemplate} (resolved to {@link PanacheEntityBase} outside a
 * Quarkus context, intercepted with {@link org.mockito.Mockito#mockStatic}) plus
 * a mocked {@link DocumentTemplateService}. The row inserted by the create arm
 * is neutralised with {@link org.mockito.Mockito#mockConstruction}. No database,
 * no Quarkus boot.
 * <p>
 * Branch enumeration (every arm exercised): {@code saveTemplate} covers the
 * blank-code arm, the unknown-type arm, the refused-source arm, both width
 * bounds at their exact values and one outside each, the insert arm, the update
 * arm, both arms of the active checkbox and both arms of the three number
 * fallbacks; {@code deleteTemplate} covers the unknown arm and the removed arm;
 * {@code templatesPage} covers the successful and failed notice arms; the
 * preview covers the rendered arm and the renders-nothing arm.
 */
class AdminTemplateResourceTest {

    /**
     * Builds a resource over a mocked template and renderer.
     *
     * @return the wired resource
     */
    private AdminTemplateResource newResource() {
        AdminTemplateResource resource = new AdminTemplateResource();
        resource.adminTemplates = mock(Template.class);
        resource.documentTemplateService = mock(DocumentTemplateService.class);
        return resource;
    }

    /**
     * Wires the chained {@code data(...)} of the page template to a single
     * self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(AdminTemplateResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.adminTemplates.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds an empty posted form.
     *
     * @return a mutable empty form
     */
    private MultivaluedMap<String, String> form() {
        return new MultivaluedHashMap<>();
    }

    /**
     * Builds a sound posted description of a template.
     *
     * @param code the template code
     * @return the posted form
     */
    private MultivaluedMap<String, String> soundForm(String code) {
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", code);
        posted.putSingle("label", "Ticket de vente");
        posted.putSingle("documentType", "SALE_RECEIPT");
        posted.putSingle("source", "TOTAL {totals.includingTax}");
        posted.putSingle("width", "42");
        posted.putSingle("copies", "2");
        posted.putSingle("priority", "50");
        return posted;
    }

    /**
     * Builds an administered template.
     *
     * @return the administered row
     */
    private DocumentTemplate stored() {
        DocumentTemplate template = new DocumentTemplate();
        template.code = "TICKET_VENTE";
        template.label = "Ticket de vente";
        template.documentType = DocumentTemplate.DocumentType.SALE_RECEIPT;
        template.active = true;
        template.width = 42;
        template.source = "TOTAL {totals.includingTax}";
        return template;
    }

    /**
     * Builds a Panache query answering the given single result.
     *
     * @param result the row the query answers, or null
     * @return the stubbed query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<DocumentTemplate> queryOf(DocumentTemplate result) {
        PanacheQuery<DocumentTemplate> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Reads the notice a redirect carries.
     *
     * @param response the redirect response
     * @return the decoded notice
     */
    private String notice(Response response) {
        String query = response.getLocation().getQuery();
        int at = query.indexOf("notice=");
        return java.net.URLDecoder.decode(query.substring(at + "notice=".length()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The page hands the template one preview per administered layout, each
     * rendered on the demonstration document of its own type.
     */
    @Test
    void thePageShowsOnePreviewPerTemplate() {
        AdminTemplateResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        DocumentTemplate template = stored();
        Map<String, Object> sample = Map.of("totals", Map.of("includingTax", "4,60"));
        when(resource.documentTemplateService.sampleData(
                DocumentTemplate.DocumentType.SALE_RECEIPT)).thenReturn(sample);
        when(resource.documentTemplateService.renderTemplate(template, sample))
                .thenReturn("TOTAL 4,60");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list("order by documentType, priority, code"))
                    .thenReturn(List.of(template));
            assertSame(instance, resource.templatesPage(null, null));
        }
        verify(instance).data("documentTypes", DocumentTemplate.DocumentType.values());
        verify(instance).data("noticeOk", true);
    }

    /**
     * A notice explicitly reported as a failure reaches the page as one, which
     * is the other leg of the {@code noticeOk} guard.
     */
    @Test
    void thePageCarriesAFailedNotice() {
        AdminTemplateResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list("order by documentType, priority, code"))
                    .thenReturn(List.of());
            resource.templatesPage("Refusé.", "false");
        }
        verify(instance).data("noticeOk", false);
    }

    /**
     * The preview carries what the template renders, and says plainly when it
     * renders nothing — both arms of what the page draws.
     */
    @Test
    void thePreviewSaysWhatTheTemplateRenders() {
        AdminTemplateResource resource = newResource();
        DocumentTemplate template = stored();
        // The DEMONSTRATION document of the template's OWN type, stubbed
        // exactly: a preview rendered against anything else would show the
        // paramétreur a receipt the printers will never produce.
        Map<String, Object> sample = Map.of("totals", Map.of("includingTax", "4,60"));
        when(resource.documentTemplateService.sampleData(
                DocumentTemplate.DocumentType.SALE_RECEIPT)).thenReturn(sample);
        when(resource.documentTemplateService.renderTemplate(template, sample))
                .thenReturn("TOTAL 4,60");
        AdminTemplateResource.Preview rendered = resource.preview(template);
        assertTrue(rendered.isRendered());
        assertEquals("TOTAL 4,60", rendered.getRendered());
        assertSame(template, rendered.getTemplate());

        when(resource.documentTemplateService.renderTemplate(template, sample))
                .thenReturn(null);
        AdminTemplateResource.Preview silent = resource.preview(template);
        assertFalse(silent.isRendered());
        assertNull(silent.getRendered());
    }

    /**
     * A template without a code is refused, and nothing is written.
     */
    @Test
    void aTemplateWithoutACodeIsRefused() {
        AdminTemplateResource resource = newResource();
        MultivaluedMap<String, String> posted = soundForm("   ");
        try (MockedConstruction<DocumentTemplate> created =
                mockConstruction(DocumentTemplate.class)) {
            Response response = resource.saveTemplate(posted);
            assertEquals(303, response.getStatus());
            assertEquals("Le code du gabarit est obligatoire.", notice(response));
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * A template attached to a document the register does not print is refused.
     */
    @Test
    void anUnknownDocumentTypeIsRefused() {
        AdminTemplateResource resource = newResource();
        MultivaluedMap<String, String> posted = soundForm("TICKET_VENTE");
        posted.putSingle("documentType", "BON_DE_LIVRAISON");
        Response response = resource.saveTemplate(posted);
        assertEquals("Type de document inconnu.", notice(response));
    }

    /**
     * A source the engine refuses is refused HERE, with the engine's own
     * complaint: a broken layout saved is a till that prints nothing, and the
     * till is the worst place to discover a typo.
     */
    @Test
    void aSourceTheEngineRefusesIsRefused() {
        AdminTemplateResource resource = newResource();
        when(resource.documentTemplateService.parseError("TOTAL {totals.includingTax}"))
                .thenReturn("section for non fermée");
        Response response = resource.saveTemplate(soundForm("TICKET_VENTE"));
        assertEquals("Gabarit refusé : section for non fermée", notice(response));
    }

    /**
     * The width is accepted at both bounds and refused just outside each of
     * them — the two boundaries of the guard.
     */
    @Test
    void theWidthIsCheckedAtItsExactBounds() {
        AdminTemplateResource resource = newResource();
        PanacheQuery<DocumentTemplate> absent = queryOf(null);

        MultivaluedMap<String, String> tooNarrow = soundForm("T1");
        tooNarrow.putSingle("width", String.valueOf(DocumentTemplate.MIN_WIDTH - 1));
        assertTrue(notice(resource.saveTemplate(tooNarrow)).contains("largeur"));

        MultivaluedMap<String, String> tooWide = soundForm("T2");
        tooWide.putSingle("width", String.valueOf(DocumentTemplate.MAX_WIDTH + 1));
        assertTrue(notice(resource.saveTemplate(tooWide)).contains("largeur"));

        MultivaluedMap<String, String> narrow = soundForm("T3");
        narrow.putSingle("width", String.valueOf(DocumentTemplate.MIN_WIDTH));
        MultivaluedMap<String, String> wide = soundForm("T4");
        wide.putSingle("width", String.valueOf(DocumentTemplate.MAX_WIDTH));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<DocumentTemplate> created =
                        mockConstruction(DocumentTemplate.class)) {
            panache.when(() -> DocumentTemplate.find("code", "T3")).thenReturn(absent);
            panache.when(() -> DocumentTemplate.find("code", "T4")).thenReturn(absent);
            assertEquals("Gabarit créé.", notice(resource.saveTemplate(narrow)));
            assertEquals("Gabarit créé.", notice(resource.saveTemplate(wide)));
            assertEquals(DocumentTemplate.MIN_WIDTH, created.constructed().get(0).width);
            assertEquals(DocumentTemplate.MAX_WIDTH, created.constructed().get(1).width);
        }
    }

    /**
     * A code no row carries is created with everything the form stated, and the
     * renderer is told to forget what it had parsed.
     */
    @Test
    void anUnadministeredCodeIsCreated() {
        AdminTemplateResource resource = newResource();
        MultivaluedMap<String, String> posted = soundForm("TICKET_VENTE");
        posted.putSingle("active", "on");
        PanacheQuery<DocumentTemplate> absent = queryOf(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<DocumentTemplate> created =
                        mockConstruction(DocumentTemplate.class)) {
            panache.when(() -> DocumentTemplate.find("code", "TICKET_VENTE")).thenReturn(absent);
            assertEquals("Gabarit créé.", notice(resource.saveTemplate(posted)));
            DocumentTemplate inserted = created.constructed().get(0);
            assertEquals("TICKET_VENTE", inserted.code);
            assertEquals("Ticket de vente", inserted.label);
            assertEquals(DocumentTemplate.DocumentType.SALE_RECEIPT, inserted.documentType);
            assertEquals("TOTAL {totals.includingTax}", inserted.source);
            assertEquals(42, inserted.width);
            assertEquals(2, inserted.copies);
            assertEquals(50, inserted.priority);
            assertTrue(inserted.active);
            verify(inserted, times(1)).persist();
        }
        verify(resource.documentTemplateService, times(1)).clearCache();
    }

    /**
     * An administered code is updated in place, the unchecked box takes it out
     * of service, and the three number fallbacks answer for what the form did
     * not state.
     */
    @Test
    void anAdministeredCodeIsUpdatedInPlace() {
        AdminTemplateResource resource = newResource();
        DocumentTemplate existing = stored();
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", "TICKET_VENTE");
        posted.putSingle("label", "Ticket refait");
        posted.putSingle("documentType", "SALE_RECEIPT");
        posted.putSingle("source", "TICKET");
        PanacheQuery<DocumentTemplate> found = queryOf(existing);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedConstruction<DocumentTemplate> created =
                        mockConstruction(DocumentTemplate.class)) {
            panache.when(() -> DocumentTemplate.find("code", "TICKET_VENTE")).thenReturn(found);
            assertEquals("Gabarit enregistré.", notice(resource.saveTemplate(posted)));
            assertTrue(created.constructed().isEmpty());
        }
        assertEquals("Ticket refait", existing.label);
        assertEquals("TICKET", existing.source);
        assertEquals(42, existing.width);
        assertEquals(1, existing.copies);
        assertEquals(100, existing.priority);
        assertFalse(existing.active);
    }

    /**
     * Deleting a code no row carries is refused, and nothing is touched.
     */
    @Test
    void deletingAnUnknownTemplateIsRefused() {
        AdminTemplateResource resource = newResource();
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", "INCONNU");
        PanacheQuery<DocumentTemplate> absent = queryOf(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.find("code", "INCONNU")).thenReturn(absent);
            assertEquals("Gabarit inconnu.", notice(resource.deleteTemplate(posted)));
        }
        verify(resource.documentTemplateService, never()).clearCache();
    }

    /**
     * Deleting an administered template removes its row and clears the parsed
     * form the renderer was holding.
     */
    @Test
    void deletingAnAdministeredTemplateRemovesIt() {
        AdminTemplateResource resource = newResource();
        MultivaluedMap<String, String> posted = form();
        posted.putSingle("code", "TICKET_VENTE");
        DocumentTemplate template = mock(DocumentTemplate.class);
        template.code = "TICKET_VENTE";
        PanacheQuery<DocumentTemplate> found = queryOf(template);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.find("code", "TICKET_VENTE")).thenReturn(found);
            assertEquals("Gabarit supprimé.", notice(resource.deleteTemplate(posted)));
        }
        verify(template, times(1)).delete();
        verify(resource.documentTemplateService, times(1)).clearCache();
    }
}
