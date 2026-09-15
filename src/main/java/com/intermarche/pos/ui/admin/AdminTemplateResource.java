package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.service.DocumentTemplateService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The back office's DOCUMENT TEMPLATES page ({@code /admin/templates}): the
 * layout of every printed document, edited as a Qute source (BO-03-03).
 *
 * <p>The editor is deliberately a TEXT AREA and not a canvas. A receipt is a
 * flow of lines on a 42-column roll, not a page with things to drag sideways,
 * and the register already runs Qute for every screen it draws — so the layout
 * of a document is a template like any other, stored in a row instead of a file.
 *
 * <p>What makes the screen an editor rather than a form is the PREVIEW: the
 * source is rendered against a demonstration sale on every display, so the
 * paramétreur reads the receipt they have just described instead of imagining
 * it. The demonstration values are the service's own, which are the very shape
 * the printers hand a template at printing time.
 *
 * <p>A source that does not compile is REFUSED, with the engine's own complaint
 * shown: a template saved broken would print nothing at the till, and the till
 * is the worst place to discover a typo.
 *
 * <p>Same admin cycle as the other back-office pages: POST &rarr; 303 &rarr; a
 * one-shot notice, {@code @RolesAllowed("ADMIN")} throughout.
 */
@Path("/admin/templates")
public class AdminTemplateResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AdminTemplateResource.class);

    /** The document templates page. */
    @Inject
    @Location("admin-templates")
    Template adminTemplates;

    /** The renderer, asked both to check a source and to preview it. */
    @Inject
    DocumentTemplateService documentTemplateService;

    /**
     * Shows the templates page: every administered layout, its preview, and the
     * document types a new one may claim.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the page
     */
    @GET
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance templatesPage(@QueryParam("notice") String notice,
            @QueryParam("noticeOk") String noticeOk) {
        LOGGER.info("Entering method templatesPage with notice: " + notice);
        List<DocumentTemplate> templates = DocumentTemplate.listAllOrdered();
        List<Preview> previews = templates.stream().map(this::preview).toList();
        LOGGER.info("Exiting method templatesPage");
        return adminTemplates
                .data("previews", previews)
                .data("documentTypes", DocumentTemplate.DocumentType.values())
                .data("notice", notice)
                .data("noticeOk", !"false".equals(noticeOk));
    }

    /**
     * Upserts a template by its code (BO-03-03).
     *
     * @param form the posted form (code, label, documentType, source, width,
     *             copies, priority, active)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/save")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveTemplate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method saveTemplate with code: " + form.getFirst("code"));
        String code = trimmed(form, "code");
        if (code.isEmpty()) {
            LOGGER.info("Exiting method saveTemplate");
            return redirect("Le code du gabarit est obligatoire.", false);
        }
        DocumentTemplate.DocumentType type = parseType(form.getFirst("documentType"));
        if (type == null) {
            LOGGER.info("Exiting method saveTemplate");
            return redirect("Type de document inconnu.", false);
        }
        String source = form.getFirst("source") == null ? "" : form.getFirst("source");
        String complaint = documentTemplateService.parseError(source);
        if (complaint != null) {
            LOGGER.info("Exiting method saveTemplate");
            return redirect("Gabarit refusé : " + complaint, false);
        }
        int width = intOr(form.getFirst("width"), 42);
        if (width < DocumentTemplate.MIN_WIDTH || width > DocumentTemplate.MAX_WIDTH) {
            LOGGER.info("Exiting method saveTemplate");
            return redirect("La largeur doit être comprise entre "
                    + DocumentTemplate.MIN_WIDTH + " et " + DocumentTemplate.MAX_WIDTH + ".",
                    false);
        }
        DocumentTemplate existing = DocumentTemplate.findByCode(code);
        DocumentTemplate template = existing;
        if (template == null) {
            template = new DocumentTemplate();
            template.code = code;
            template.persist();
        }
        template.label = trimmed(form, "label");
        template.documentType = type;
        template.source = source;
        template.width = width;
        template.copies = intOr(form.getFirst("copies"), 1);
        template.priority = intOr(form.getFirst("priority"), 100);
        template.active = form.getFirst("active") != null;
        documentTemplateService.clearCache();
        LOGGER.info("Exiting method saveTemplate");
        return redirect(existing == null ? "Gabarit créé." : "Gabarit enregistré.", true);
    }

    /**
     * Removes a template from the referential, the register then printing that
     * document its own way again (BO-03-03).
     *
     * @param form the posted form (code)
     * @return a 303 redirect with the notice
     */
    @POST
    @Path("/delete")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteTemplate(MultivaluedMap<String, String> form) {
        LOGGER.info("Entering method deleteTemplate with form: " + form);
        DocumentTemplate template = DocumentTemplate.findByCode(trimmed(form, "code"));
        if (template == null) {
            LOGGER.info("Exiting method deleteTemplate");
            return redirect("Gabarit inconnu.", false);
        }
        template.delete();
        documentTemplateService.clearCache();
        LOGGER.info("Exiting method deleteTemplate");
        return redirect("Gabarit supprimé.", true);
    }

    /**
     * Builds the preview of one template against the demonstration document of
     * its type.
     *
     * @param template the administered template
     * @return the template and what it renders
     */
    Preview preview(DocumentTemplate template) {
        String rendered = documentTemplateService.renderTemplate(
                template, documentTemplateService.sampleData(template.documentType));
        return new Preview(template, rendered);
    }

    /**
     * Parses a document type name, tolerating an unknown or blank value.
     *
     * @param raw the posted name
     * @return the type, or null when unrecognised
     */
    private DocumentTemplate.DocumentType parseType(String raw) {
        for (DocumentTemplate.DocumentType type : DocumentTemplate.DocumentType.values()) {
            if (type.name().equals(raw)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Reads a posted whole number, falling back when it is absent or malformed.
     *
     * @param raw the posted value
     * @param fallback the value to use when nothing readable was posted
     * @return the parsed number, or the fallback
     */
    private int intOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Reads a posted field, trimmed, never null.
     *
     * @param form the posted form
     * @param key the field name
     * @return the trimmed value, or the empty string when absent
     */
    private String trimmed(MultivaluedMap<String, String> form, String key) {
        String raw = form.getFirst(key);
        return raw == null ? "" : raw.trim();
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/templates?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }

    /**
     * One administered template and what it renders on the demonstration
     * document — what the page draws, side by side.
     */
    public static class Preview {

        /** The administered template. */
        public final DocumentTemplate template;

        /** What it renders, or null when it renders nothing. */
        public final String rendered;

        /**
         * Builds a preview.
         *
         * @param template the administered template
         * @param rendered what it renders, or null
         */
        public Preview(DocumentTemplate template, String rendered) {
            this.template = template;
            this.rendered = rendered;
        }

        /**
         * Returns the administered template.
         *
         * @return the template
         */
        public DocumentTemplate getTemplate() {
            return template;
        }

        /**
         * Returns what the template renders on the demonstration document.
         *
         * @return the rendered document, or null when it renders nothing
         */
        public String getRendered() {
            return rendered;
        }

        /**
         * Tells whether the preview has something to show.
         *
         * @return true when the template rendered a document
         */
        public boolean isRendered() {
            return rendered != null;
        }
    }
}
