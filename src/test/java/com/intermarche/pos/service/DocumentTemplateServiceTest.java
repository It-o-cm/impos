package com.intermarche.pos.service;

import com.intermarche.pos.domain.setting.DocumentTemplate;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentTemplateService}.
 * <p>
 * The engine is a REAL Qute engine built with its defaults, not a stand-in:
 * what these cases are about is that an administered template renders the maps,
 * lists and strings the register hands it, and a mocked engine would have
 * asserted nothing of the sort. The referential is reached through the Panache
 * statics of {@link DocumentTemplate}, resolved to {@link PanacheEntityBase}
 * outside a Quarkus context and intercepted with
 * {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code render} covers the
 * unadministered-type arm and the rendered arm; {@code renderTemplate} covers
 * the null-template leg, the deactivated leg, the null-source leg, the
 * blank-source leg, the unparsable arm, the failing-render arm and the rendered
 * arm; {@code parse} covers the cold-cache arm, the warm-cache arm, the
 * source-changed arm and the unparsable arm; {@code parseError} covers the null
 * leg, the blank leg, the sound arm and the broken arm; {@code findFor} covers
 * the null-type arm, the empty-list arm, the null-entry arm, the
 * unrenderable-entry arm and the found arm.
 */
class DocumentTemplateServiceTest {

    /**
     * Builds the service over a real Qute engine carrying its default section
     * helpers and value resolvers — the engine the register itself runs.
     *
     * @return the wired service
     */
    private DocumentTemplateService newService() {
        DocumentTemplateService service = new DocumentTemplateService();
        service.engine = Engine.builder().addDefaults().build();
        return service;
    }

    /**
     * Builds an administered template of the sale receipt.
     *
     * @param source the Qute source
     * @return the administered template
     */
    private DocumentTemplate template(String source) {
        DocumentTemplate template = new DocumentTemplate();
        template.code = "TICKET_VENTE";
        template.label = "Ticket de vente";
        template.documentType = DocumentTemplate.DocumentType.SALE_RECEIPT;
        template.active = true;
        template.width = 42;
        template.source = source;
        return template;
    }

    /**
     * The sale a template lays out, already formatted — maps, lists and strings
     * and nothing else.
     *
     * @return the document values
     */
    private Map<String, Object> saleData() {
        return Map.of(
                "store", Map.of("name", "INTERMARCHE VAUCRESSON"),
                "ticket", Map.of("number", "C04-000123", "total", "24,90"),
                "lines", List.of(
                        Map.of("label", "LAIT DEMI-ECREME", "total", "1,15"),
                        Map.of("label", "PAIN COMPLET", "total", "2,30")));
    }

    /**
     * An administered template lays out the values the register hands it — the
     * whole point of the design, asserted against a real engine.
     */
    @Test
    void anAdministeredTemplateLaysOutTheDocument() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template(
                "{store.name}\n{#for line in lines}{line.label} {line.total}\n{/for}"
                        + "TOTAL {ticket.total}");
        String rendered = service.renderTemplate(template, saleData());
        assertEquals("INTERMARCHE VAUCRESSON\n"
                + "LAIT DEMI-ECREME 1,15\n"
                + "PAIN COMPLET 2,30\n"
                + "TOTAL 24,90", rendered);
    }

    /**
     * A SECOND administered layout states the very same sale differently, which
     * is what proves the layout is read rather than compiled in.
     */
    @Test
    void aSecondAdministeredLayoutStatesTheSameSaleDifferently() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template("TOTAL {ticket.total} - {ticket.number}");
        assertEquals("TOTAL 24,90 - C04-000123",
                service.renderTemplate(template, saleData()));
    }

    /**
     * {@code render} goes through the referential: an administered type is laid
     * out by its template.
     */
    @Test
    void renderFindsTheAdministeredTemplate() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template("TOTAL {ticket.total}");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(List.of(template));
            assertEquals("TOTAL 24,90",
                    service.render(DocumentTemplate.DocumentType.SALE_RECEIPT, saleData()));
        }
    }

    /**
     * A document type NO row administers renders nothing, which is how the
     * caller knows to print it its own way.
     */
    @Test
    void anUnadministeredTypeRendersNothing() {
        DocumentTemplateService service = newService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.Z_REPORT))
                    .thenReturn(List.of());
            assertNull(service.render(DocumentTemplate.DocumentType.Z_REPORT, saleData()));
        }
    }

    /**
     * A null document type is answered without touching the referential, which
     * is what the absence of any stubbing here asserts.
     */
    @Test
    void aNullTypeRendersNothing() {
        assertNull(newService().render(null, saleData()));
    }

    /**
     * {@code findFor} skips a null row and a row that cannot render, and answers
     * the first that can.
     */
    @Test
    void findForSkipsWhatCannotRender() {
        DocumentTemplate deactivated = template("TOTAL {ticket.total}");
        deactivated.active = false;
        DocumentTemplate empty = template("   ");
        DocumentTemplate wanted = template("TOTAL {ticket.total}");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.SALE_RECEIPT))
                    .thenReturn(java.util.Arrays.asList(null, deactivated, empty, wanted));
            assertEquals(wanted, DocumentTemplate.findFor(DocumentTemplate.DocumentType.SALE_RECEIPT));
        }
    }

    /**
     * A null template, a deactivated one and one whose source was never written
     * all render nothing — the legs of the renderable guard.
     */
    @Test
    void anUnwrittenTemplateRendersNothing() {
        DocumentTemplateService service = newService();
        assertNull(service.renderTemplate(null, saleData()));

        DocumentTemplate deactivated = template("TOTAL {ticket.total}");
        deactivated.active = false;
        assertNull(service.renderTemplate(deactivated, saleData()));

        assertNull(service.renderTemplate(template(null), saleData()));
        assertNull(service.renderTemplate(template("   "), saleData()));
    }

    /**
     * A source the engine cannot parse renders nothing rather than throwing: a
     * paramétreur's typo costs the built-in layout, never a sale that cannot be
     * printed.
     */
    @Test
    void anUnparsableSourceRendersNothing() {
        DocumentTemplateService service = newService();
        assertNull(service.renderTemplate(template("{#for line in lines}oubli du for"),
                saleData()));
    }

    /**
     * A template that PARSES but fails while rendering is caught the same way —
     * the other arm of the same promise.
     */
    @Test
    void aFailingRenderFallsBackToNothing() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template("{#for line in lines}{line.label}{/for}");
        // The values contradict the layout: "lines" is not iterable, so the
        // engine parses happily and blows up at render time.
        assertNull(service.renderTemplate(template, Map.of("lines", "pas une liste")));
    }

    /**
     * Rendering with no values at all is legitimate — a template that reads
     * nothing lays out its constant text.
     */
    @Test
    void aTemplateReadingNothingStillLaysOutItsText() {
        DocumentTemplateService service = newService();
        assertEquals("TICKET", service.renderTemplate(template("TICKET"), null));
    }

    /**
     * The parsed form is REUSED while the source is unchanged, and thrown away
     * the moment the back office rewrites it — the two arms of the cache guard.
     */
    @Test
    void theParsedFormIsReusedUntilTheSourceChanges() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template("A {ticket.total}");
        assertEquals("A 24,90", service.renderTemplate(template, saleData()));
        assertEquals("A 24,90", service.renderTemplate(template, saleData()));

        template.source = "B {ticket.total}";
        assertEquals("B 24,90", service.renderTemplate(template, saleData()));
    }

    /**
     * A source is parsed ONCE however often the shop prints it, parsed again
     * when the back office rewrites it, and parsed again after the cache is
     * cleared — the three states of the cache, counted on the engine itself
     * because a parse that happens twice is invisible in the output.
     */
    @Test
    void aSourceIsParsedOncePerVersion() {
        DocumentTemplateService service = new DocumentTemplateService();
        Engine engine = org.mockito.Mockito.spy(Engine.builder().addDefaults().build());
        service.engine = engine;
        DocumentTemplate template = template("A {ticket.total}");

        assertEquals("A 24,90", service.renderTemplate(template, saleData()));
        assertEquals("A 24,90", service.renderTemplate(template, saleData()));
        assertEquals("A 24,90", service.renderTemplate(template, saleData()));
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).parse("A {ticket.total}");

        template.source = "B {ticket.total}";
        assertEquals("B 24,90", service.renderTemplate(template, saleData()));
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).parse("B {ticket.total}");

        service.clearCache();
        assertEquals("B 24,90", service.renderTemplate(template, saleData()));
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(2)).parse("B {ticket.total}");
    }

    /**
     * An unparsable source leaves NOTHING in the cache, so correcting it takes
     * effect at once.
     */
    @Test
    void anUnparsableSourceLeavesNothingBehind() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template("A {ticket.total}");
        assertEquals("A 24,90", service.renderTemplate(template, saleData()));

        template.source = "{#for x in lines}cassé";
        assertNull(service.renderTemplate(template, saleData()));

        template.source = "C {ticket.total}";
        assertEquals("C 24,90", service.renderTemplate(template, saleData()));
    }

    /**
     * {@code parseError} says nothing about a sound source and about an empty
     * one — null and blank alike — and complains about a broken one.
     */
    @Test
    void parseErrorComplainsOnlyAboutABrokenSource() {
        DocumentTemplateService service = newService();
        assertNull(service.parseError(null));
        assertNull(service.parseError("   "));
        assertNull(service.parseError("TOTAL {ticket.total}"));
        assertNotNull(service.parseError("{#for line in lines}oubli du for"));
    }

    /**
     * The demonstration document carries what EVERY document carries — the
     * store, the register, the operator and the moment — whatever its type, and
     * even when no type is named.
     */
    @Test
    void everyDemonstrationDocumentCarriesTheCommonValues() {
        DocumentTemplateService service = newService();
        for (DocumentTemplate.DocumentType type : DocumentTemplate.DocumentType.values()) {
            Map<String, Object> data = service.sampleData(type);
            assertNotNull(data.get("store"), type.name());
            assertNotNull(data.get("terminal"), type.name());
            assertNotNull(data.get("operator"), type.name());
            assertNotNull(data.get("date"), type.name());
            assertNotNull(data.get("time"), type.name());
        }
        Map<String, Object> untyped = service.sampleData(null);
        assertNotNull(untyped.get("store"));
        assertNull(untyped.get("lines"));
    }

    /**
     * Each family of document carries what its own templates read — the four
     * arms of the switch, each asserted on the key that only it provides.
     */
    @Test
    void eachFamilyOfDocumentCarriesItsOwnValues() {
        DocumentTemplateService service = newService();
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.SALE_RECEIPT).get("lines"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.REFUND_RECEIPT).get("vatRows"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.INVOICE).get("payments"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.X_REPORT).get("tenders"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.Z_REPORT).get("session"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.WITHDRAWAL_TICKET).get("movement"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.TRANSFER_TICKET).get("movement"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.GIFT_CARD_VOUCHER).get("instrument"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.CREDIT_NOTE_VOUCHER).get("instrument"));
        assertNotNull(service.sampleData(
                DocumentTemplate.DocumentType.CARD_RECEIPT).get("card"));
    }

    /**
     * A document type carries ONLY its own values: a report has no sale lines
     * and a sale has no session — which is what makes the demonstration a
     * contract rather than a grab bag.
     */
    @Test
    void aDocumentTypeCarriesOnlyItsOwnValues() {
        DocumentTemplateService service = newService();
        assertNull(service.sampleData(DocumentTemplate.DocumentType.Z_REPORT).get("lines"));
        assertNull(service.sampleData(DocumentTemplate.DocumentType.SALE_RECEIPT).get("session"));
        assertNull(service.sampleData(
                DocumentTemplate.DocumentType.CARD_RECEIPT).get("instrument"));
    }

    /**
     * The demonstration document RENDERS: a layout written against the keys the
     * editor advertises lays out without a fault, which is what makes the
     * preview pane trustworthy.
     */
    @Test
    void theDemonstrationDocumentRenders() {
        DocumentTemplateService service = newService();
        DocumentTemplate template = template(
                "{store.name}\n{#for l in lines}{l.label} {l.total}\n{/for}"
                        + "TOTAL {totals.includingTax}");
        String rendered = service.renderTemplate(template,
                service.sampleData(DocumentTemplate.DocumentType.SALE_RECEIPT));
        assertNotNull(rendered);
        assertTrue(rendered.contains("INTERMARCHE VAUCRESSON"));
        assertTrue(rendered.contains("TOTAL 4,60"));
    }

    /**
     * The engine is asked to parse only what the back office typed: a mocked
     * engine that answers nothing leaves the render on the fallback path.
     */
    @Test
    void anEngineAnsweringNothingLeavesTheFallbackPath() {
        DocumentTemplateService service = new DocumentTemplateService();
        Engine engine = mock(Engine.class);
        when(engine.parse("TOTAL {ticket.total}")).thenThrow(new IllegalStateException("boum"));
        service.engine = engine;
        assertNull(service.renderTemplate(template("TOTAL {ticket.total}"), saleData()));
        assertTrue(service.parseError("TOTAL {ticket.total}").contains("boum"));
    }
}
