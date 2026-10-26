package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.CustomerDocument;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** LC-08-02-06: sends an explicitly selected issued customer copy from the till. */
@Path("/customer-documents")
@DrawerMustBeClosed
public class CustomerDocumentResource {
    /** Current operator and training gate. */
    @Inject PosState state;
    /** Issued customer-only archive. */
    @Inject CustomerDocumentService documents;
    /** Existing administered MIME delivery. */
    @Inject TicketMailService mail;
    /** Operator messages in the configured language. */
    @Inject com.intermarche.pos.ui.Messages i18n;
    /** Existing tactile keyboard and customer-copy selection page. */
    @Inject @Location("customer-documents") Template template;

    /** Opens a page of issued copies, without requiring a previous sale.
     * @param page zero-based history page
     * @return customer-only history and destination entry
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @com.intermarche.pos.ui.DrawerMayBeOpen
    public TemplateInstance show(@QueryParam("page") @DefaultValue("0") int page) {
        return page(page, "", state.trainingMode ? i18n.get(PosState.TRAINING_FORBIDDEN_KEY) : "", "");
    }

    /** Sends the selected persisted customer copy; no arbitrary content is accepted.
     * @param documentId selected archive identifier
     * @param email confirmed destination
     * @param page history page to retain after submission
     * @return the page with the actual delivery outcome
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance send(@FormParam("documentId") Long documentId,
                                 @FormParam("email") String email,
                                 @FormParam("page") @DefaultValue("0") int page) {
        String address = email == null ? "" : email.trim();
        if (state.trainingMode) {
            return page(page, address, i18n.get(PosState.TRAINING_FORBIDDEN_KEY), "");
        }
        if (!address.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            return page(page, address, i18n.get("op.adresseInvalide"), "");
        }
        CustomerDocument document = documentId == null ? null : CustomerDocument.findById(documentId);
        if (document == null) {
            return page(page, address, i18n.get("customerdocs.notFound"), "");
        }
        document.customerEmail = address;
        document.persist();
        boolean sent = mail.sendDocument(document, address);
        if (sent) {
            return page(page, address, "", i18n.get("customerdocs.sent"));
        }
        if (mail.isConfigured()) {
            return page(page, address, i18n.get("customerdocs.failed"), "");
        }
        return page(page, address, "", i18n.get("customerdocs.recorded"));
    }

    /** Renders escaped customer content and bounded history navigation.
     * @param requestedPage requested history page
     * @param address entered destination
     * @param error refusal, empty on success
     * @param outcome confirmed transport or journal result
     * @return the tactile page
     */
    private TemplateInstance page(int requestedPage, String address, String error, String outcome) {
        int page = Math.max(0, requestedPage);
        List<CustomerDocument> issued = state.trainingMode ? List.of() : documents.list(page);
        List<Map<String, Object>> rows = issued.stream().map(document -> Map.<String, Object>of(
                "id", document.id, "reference", document.reference,
                "label", i18n.get(document.kind.labelKey),
                "date", document.issuedAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                "content", new io.quarkus.qute.RawString(CustomerReceiptRendering.render(document.content).html()))).toList();
        return template.data("documents", rows).data("address", address).data("error", error)
                .data("outcome", outcome).data("page", page).data("previous", Math.max(0, page - 1))
                .data("next", page + 1).data("hasPrevious", page > 0).data("hasNext", rows.size() == 20);
    }
}
