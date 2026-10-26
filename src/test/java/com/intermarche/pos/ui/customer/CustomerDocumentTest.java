package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.CustomerDocument;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** LC-08-02-06: persisted selection, history, destination and actual delivery outcomes. */
class CustomerDocumentTest {
    /** Creates a plain issued copy with fixed dates and no live business dependency.
     * @return a persisted-looking customer copy
     */
    private CustomerDocument copy() {
        CustomerDocument d = spy(new CustomerDocument());
        d.id = 12L; d.kind = CustomerDocument.Kind.RETURN; d.reference = "RET-0042";
        d.issuedAt = LocalDateTime.of(2026, 10, 22, 10, 30); d.content = "CUSTOMER ONLY <script>";
        doNothing().when(d).persist();
        return d;
    }

    /** Wires a fresh resource without database or SMTP access.
     * @return resource whose template records the escaped rendering model
     */
    private CustomerDocumentResource resource() {
        CustomerDocumentResource r = new CustomerDocumentResource();
        r.state = new PosState(); r.documents = mock(CustomerDocumentService.class);
        r.mail = mock(TicketMailService.class); r.i18n = TestMessages.french();
        r.template = mock(Template.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(r.template.data(eq("documents"), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        return r;
    }

    /** Negative pages clamp to zero and an empty history still offers destination entry. */
    @Test void emptyHistoryAndNegativePage() {
        var r = resource(); var view = r.show(-1);
        verify(r.documents).list(0); verify(view).data("hasPrevious", false);
        verify(view).data("hasNext", false); verify(view).data("previous", 0);
    }

    /** Full pages expose navigation and every allowed family resolves to a real bundle key. */
    @Test void customerFamiliesAndPagination() {
        var r = resource(); var d = copy();
        for (var kind : CustomerDocument.Kind.values()) {
            d.kind = kind;
            when(r.documents.list(2)).thenReturn(java.util.Collections.nCopies(20, d));
            var view = r.show(2);
            verify(view, atLeastOnce()).data("hasPrevious", true);
            verify(view, atLeastOnce()).data("hasNext", true);
            assertNotEquals("customerdocs.kind" + kind.name(), r.i18n.get("customerdocs.kind" + kind.name()));
        }
    }

    /** Training prevents both archive reads and delivery, even with a forged form. */
    @Test void trainingBlocksGetAndPost() {
        var r = resource(); r.state.trainingMode = true;
        var view = r.show(0); r.send(12L, "client@example.test", 0);
        verify(view, times(2)).data("error", r.i18n.get(PosState.TRAINING_FORBIDDEN_KEY));
        verifyNoInteractions(r.mail, r.documents);
    }

    /** Missing/invalid destinations and missing/unknown identifiers never send arbitrary content. */
    @Test void invalidAddressAndDocument() {
        var r = resource(); var view = r.send(12L, null, 0);
        r.send(12L, "wrong", 0);
        verify(view, times(2)).data("error", r.i18n.get("op.adresseInvalide"));
        r.send(null, "client@example.test", 0);
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CustomerDocument.findById(99L)).thenReturn(null);
            r.send(99L, "client@example.test", 0);
        }
        verify(view, times(2)).data("error", r.i18n.get("customerdocs.notFound"));
        verifyNoInteractions(r.mail);
    }

    /** SMTP acceptance, no relay and relay failure are distinct operator outcomes. */
    @Test void confirmedCopyAndActualDeliveryResult() {
        var r = resource(); var d = copy();
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> CustomerDocument.findById(12L)).thenReturn(d);
            when(r.mail.sendDocument(d, "client@example.test")).thenReturn(true, false, false);
            var view = r.send(12L, " client@example.test ", 0);
            verify(view).data("outcome", r.i18n.get("customerdocs.sent"));
            assertEquals("client@example.test", d.customerEmail);
            r.send(12L, "client@example.test", 0);
            verify(view).data("outcome", r.i18n.get("customerdocs.recorded"));
            when(r.mail.isConfigured()).thenReturn(true);
            r.send(12L, "client@example.test", 0);
            verify(view).data("error", r.i18n.get("customerdocs.failed"));
            verify(d, times(3)).persist();
            verify(r.mail, times(3)).sendDocument(d, "client@example.test");
        }
    }

    /** Archive storage freezes family, reference, content and timestamp, without any printer call. */
    @Test void archivePersistsExactCopy() {
        try (var construction = mockConstruction(CustomerDocument.class)) {
            new CustomerDocumentService().archive(CustomerDocument.Kind.CREDIT, "AV-008", "EXACT\r\nCOPY");
            var d = construction.constructed().get(0);
            assertEquals(CustomerDocument.Kind.CREDIT, d.kind); assertEquals("AV-008", d.reference);
            assertEquals("EXACT\r\nCOPY", d.content); assertNotNull(d.issuedAt); verify(d).persist();
        }
    }

    /** History orders by issue identifier and pages twenty rows with negative-page normalization. */
    @Test void archivePagination() {
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<CustomerDocument> query = mock(PanacheQuery.class);
            panache.when(() -> CustomerDocument.find("order by id desc")).thenReturn(query);
            when(query.page(0, 20)).thenReturn(query); when(query.page(3, 20)).thenReturn(query);
            var list = List.of(copy()); when(query.list()).thenReturn(list);
            assertSame(list, new CustomerDocumentService().list(-1));
            assertSame(list, new CustomerDocumentService().list(3));
        }
    }
}
