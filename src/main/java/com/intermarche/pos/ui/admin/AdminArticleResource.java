package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.attribute.ProductAttributeDef;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The ARTICLE FILE back-office screen ({@code /admin/articles}, BO-02-03-*):
 * lists the referential articles and edits, on an existing fiche, the base data
 * and the declared attributes that drive register behaviour — VAT exemption
 * (BO-02-03-26/27), discount ban (BO-02-03-09), recall (BO-02-03-11),
 * meal-voucher eligibility (BO-02-03-06), bulky flag (BO-02-03-25) — plus the
 * checkout label (BO-02-03-02), the internal code (BO-02-03-04), the
 * forbidden-to-sale flag (BO-02-03-07) and the age restriction (BO-02-03-08).
 * <p>
 * Only EXISTING fiches are edited here: the creation of an article stays on the
 * integration surfaces (CSV import, GraphQL), matching the "intégration en
 * provenance de la Gestion Commerciale" the requirements describe and the same
 * doctrine as {@code AdminStoreResource}. What is edited here is referential
 * data: on a store node it flows to every register at the next draw, which is
 * how the attributes reach the till.
 * <p>
 * Access reserved to ADMIN, like every parameter screen. POST&nbsp;→&nbsp;303&nbsp;→&nbsp;notice.
 */
@Path("/admin/articles")
public class AdminArticleResource {

    /** The article list template. */
    @Inject
    @Location("admin-articles")
    Template adminArticles;

    /** The article edit-form template. */
    @Inject
    @Location("admin-article")
    Template adminArticle;

    /**
     * A rendered attribute row of the fiche form: a well-known catalog entry
     * with its current boolean value. Every declared attribute is a boolean
     * toggle today ({@code ProductAttributeCatalog} holds only BOOL entries),
     * so the form renders one checkbox per row.
     */
    public static class AttributeRow {
        /** The attribute code (posted field name). */
        public String code;
        /** The operator-facing label. */
        public String label;
        /** The current boolean value. */
        public boolean checked;
    }

    /**
     * Shows the article list, ordered by EAN.
     *
     * @param notice the one-shot outcome message, or null
     * @param noticeOk whether the message reports a success
     * @return the list page
     */
    @GET
    @RolesAllowed("ADMIN")
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") @DefaultValue("true") boolean noticeOk) {
        List<Product> products = Product.find("order by ean").list();
        return adminArticles.data("products", products)
                .data("notice", notice)
                .data("noticeOk", noticeOk);
    }

    /**
     * Shows the edit form of one article fiche.
     *
     * @param id the product id
     * @return the form page; {@code product} is null when the id is unknown
     */
    @GET
    @Path("/edit")
    @RolesAllowed("ADMIN")
    public TemplateInstance edit(@QueryParam("id") Long id) {
        Product product = id != null ? Product.<Product>findById(id) : null;
        return adminArticle.data("product", product)
                .data("attributes", product != null ? attributeRows(product) : List.of());
    }

    /**
     * Saves the editable fields and declared attributes of an existing fiche.
     *
     * @param form the posted form
     * @return a 303 redirect to the list with the notice
     */
    @POST
    @Path("/edit")
    @RolesAllowed("ADMIN")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response save(MultivaluedMap<String, String> form) {
        Long id = parseId(form.getFirst("id"));
        Product product = id != null ? Product.<Product>findById(id) : null;
        if (product == null) {
            return redirect("Article introuvable.", false);
        }
        product.checkoutLabel = blankToNull(form.getFirst("checkoutLabel"));
        product.internalCode = blankToNull(form.getFirst("internalCode"));
        product.forbiddenToSale = form.getFirst("forbiddenToSale") != null;
        product.ageRestriction = parseAge(form.getFirst("ageRestriction"));
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG) {
            product.attributes.put(def.code(),
                    form.getFirst(def.code()) != null ? "true" : "false");
        }
        return redirect("Fiche article « " + product.ean + " » enregistrée.", true);
    }

    /**
     * Builds the attribute rows of the form from the catalog and the product's
     * current values.
     *
     * @param product the edited product
     * @return the rendered rows, in catalog order
     */
    List<AttributeRow> attributeRows(Product product) {
        List<AttributeRow> rows = new ArrayList<>();
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG) {
            AttributeRow row = new AttributeRow();
            row.code = def.code();
            row.label = def.label();
            String raw = product.attributes != null ? product.attributes.get(def.code()) : null;
            String effective = raw != null ? raw : def.defaultValue();
            row.checked = Boolean.parseBoolean(effective);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Parses the product id posted with the form.
     *
     * @param raw the raw id, or null
     * @return the parsed id, or null when absent or malformed
     */
    private Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses the age restriction: a positive integer, or null when empty or
     * not a positive number (an unrestricted article).
     *
     * @param raw the raw value, or null
     * @return the age, or null
     */
    private Integer parseAge(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int age = Integer.parseInt(raw.trim());
            return age > 0 ? age : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Normalises a posted text field to null when blank.
     *
     * @param raw the raw value, or null
     * @return the trimmed value, or null when absent or blank
     */
    private String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Builds the 303 redirect carrying the one-shot notice.
     *
     * @param notice the message to display
     * @param ok whether it reports a success
     * @return the redirect response
     */
    private Response redirect(String notice, boolean ok) {
        String target = "/admin/articles?noticeOk=" + ok + "&notice="
                + URLEncoder.encode(notice, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(target)).build();
    }
}
