package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.attribute.ProductAttributeCatalog;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminArticleResource}, targeting 100% branch coverage.
 * <p>
 * The resource is a Qute-backed back-office page over the {@link Product}
 * static finders, which resolve to {@link PanacheEntityBase} under plain
 * {@code mvn test} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}. Templates are Mockito mocks whose
 * chained {@code data(...)} returns a self-returning instance; no database and
 * no Quarkus context is booted.
 * <p>
 * Branch enumeration (every arm exercised): {@code edit} covers the id
 * null/non-null arms and the product null/non-null arm feeding
 * {@code attributeRows}; {@code attributeRows} covers the map null/non-null arm
 * and the raw value present/absent arm; {@code save} covers the product
 * null-redirect arm and the full write path; {@code parseId} covers the
 * null/blank, malformed and valid arms; {@code parseAge} covers null/blank,
 * malformed, non-positive and positive arms; {@code blankToNull} covers the
 * null, blank and non-blank arms; the attribute loop covers checkbox
 * present/absent.
 */
class AdminArticleResourceTest {

    /**
     * Builds a resource over mocked list and form templates.
     *
     * @return the wired resource
     */
    private AdminArticleResource newResource() {
        AdminArticleResource resource = new AdminArticleResource();
        resource.adminArticles = mock(Template.class);
        resource.adminArticle = mock(Template.class);
        return resource;
    }

    /**
     * Wires a template's chained {@code data(...)} to one self-returning instance.
     *
     * @param template the template mock to wire
     * @return the instance the chain returns
     */
    private TemplateInstance wire(Template template) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(template.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * {@code list} renders the EAN-ordered products with the notice fields.
     */
    @Test
    @SuppressWarnings("unchecked")
    void listRendersProducts() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticles);
        Product product = new Product();
        product.ean = "3000000000001";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(product));
            panache.when(() -> Product.find("order by ean")).thenReturn(query);
            assertEquals(instance, resource.list("saved", true));
        }
        verify(resource.adminArticles).data("products", List.of(product));
        verify(instance).data("notice", "saved");
        verify(instance).data("noticeOk", true);
    }

    /**
     * {@code edit} with a null id passes a null product and an empty attribute
     * list (id-null arm, product-null arm).
     */
    @Test
    void editWithNullIdPassesNullProduct() {
        AdminArticleResource resource = newResource();
        TemplateInstance instance = wire(resource.adminArticle);
        assertEquals(instance, resource.edit(null));
        verify(resource.adminArticle).data("product", null);
        verify(instance).data("attributes", List.of());
    }

    /**
     * {@code edit} with an unknown id passes a null product (id-non-null arm,
     * product-null arm of {@code attributeRows}'s guard).
     */
    @Test
    void editWithUnknownIdPassesNullProduct() {
        AdminArticleResource resource = newResource();
        wire(resource.adminArticle);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(7L)).thenReturn(null);
            resource.edit(7L);
        }
        verify(resource.adminArticle).data("product", null);
    }

    /**
     * {@code edit} builds one attribute row per catalog entry, checked from the
     * product's own value (map non-null, raw present arm) and from the default
     * when the product omits the value (raw absent arm).
     */
    @Test
    @SuppressWarnings("unchecked")
    void editBuildsAttributeRowsFromProduct() {
        AdminArticleResource resource = newResource();
        wire(resource.adminArticle);
        Product product = new Product();
        product.ean = "3000000000001";
        product.attributes.put(ProductAttributeCatalog.VAT_EXEMPT, "true");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            resource.edit(1L);
        }
        verify(resource.adminArticle).data("product", product);
        List<AdminArticleResource.AttributeRow> rows = resource.attributeRows(product);
        assertEquals(ProductAttributeCatalog.CATALOG.size(), rows.size());
        AdminArticleResource.AttributeRow vat = rows.stream()
                .filter(r -> r.code.equals(ProductAttributeCatalog.VAT_EXEMPT)).findFirst().orElseThrow();
        assertTrue(vat.checked);
        AdminArticleResource.AttributeRow bulky = rows.stream()
                .filter(r -> r.code.equals(ProductAttributeCatalog.BULKY)).findFirst().orElseThrow();
        assertFalse(bulky.checked);
    }

    /**
     * {@code attributeRows} tolerates a null attribute map (map-null arm),
     * falling back to catalog defaults.
     */
    @Test
    void attributeRowsToleratesNullMap() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.attributes = null;
        List<AdminArticleResource.AttributeRow> rows = resource.attributeRows(product);
        assertEquals(ProductAttributeCatalog.CATALOG.size(), rows.size());
        for (AdminArticleResource.AttributeRow row : rows) {
            assertFalse(row.checked);
        }
    }

    /**
     * {@code save} redirects red without touching data when the posted id is
     * null (parseId null-arm, product-null arm).
     */
    @Test
    void saveRejectsNullId() {
        AdminArticleResource resource = newResource();
        Response response = resource.save(new MultivaluedHashMap<>());
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} redirects red when the posted id is a blank string
     * (parseId blank-arm).
     */
    @Test
    void saveRejectsBlankId() {
        AdminArticleResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "  ");
        Response response = resource.save(form);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} redirects red when the posted id is not a number
     * (parseId malformed-arm).
     */
    @Test
    void saveRejectsMalformedId() {
        AdminArticleResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "abc");
        Response response = resource.save(form);
        assertEquals(303, response.getStatus());
        assertTrue(response.getLocation().toString().contains("noticeOk=false"));
    }

    /**
     * {@code save} redirects red when the id is valid but the product is
     * unknown (product-null arm after a successful parse).
     */
    @Test
    void saveRejectsUnknownProduct() {
        AdminArticleResource resource = newResource();
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "9");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(9L)).thenReturn(null);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=false"));
        }
    }

    /**
     * {@code save} on an existing fiche writes the trimmed checkout label
     * (blankToNull non-blank arm), the forbidden flag (present), the positive
     * age (parseAge positive arm) and each ticked attribute (checkbox-present
     * arm), leaving unticked attributes false.
     */
    @Test
    void saveWritesFieldsAndAttributes() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.ean = "3000000000001";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("checkoutLabel", "  MELON JAUNE  ");
        form.putSingle("internalCode", "INT-42");
        form.putSingle("forbiddenToSale", "on");
        form.putSingle("ageRestriction", "18");
        form.putSingle(ProductAttributeCatalog.VAT_EXEMPT, "on");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            Response response = resource.save(form);
            assertEquals(303, response.getStatus());
            assertTrue(response.getLocation().toString().contains("noticeOk=true"));
        }
        assertEquals("MELON JAUNE", product.checkoutLabel);
        assertEquals("INT-42", product.internalCode);
        assertTrue(product.forbiddenToSale);
        assertEquals(18, product.ageRestriction);
        assertEquals("true", product.attributes.get(ProductAttributeCatalog.VAT_EXEMPT));
        assertEquals("false", product.attributes.get(ProductAttributeCatalog.BULKY));
    }

    /**
     * {@code save} clears fields the form omits: a blank checkout label becomes
     * null (blankToNull blank-arm), an absent internal code becomes null
     * (blankToNull null-arm), an absent forbidden flag becomes false, and a
     * blank age becomes null (parseAge null/blank-arm).
     */
    @Test
    void saveClearsOmittedFields() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.ean = "3000000000001";
        product.checkoutLabel = "old";
        product.internalCode = "old";
        product.forbiddenToSale = true;
        product.ageRestriction = 18;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("checkoutLabel", "   ");
        form.putSingle("ageRestriction", "   ");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            resource.save(form);
        }
        assertNull(product.checkoutLabel);
        assertNull(product.internalCode);
        assertFalse(product.forbiddenToSale);
        assertNull(product.ageRestriction);
    }

    /**
     * {@code save} treats an omitted age field as unrestricted (parseAge
     * null-arm, when the form carries no {@code ageRestriction} at all).
     */
    @Test
    void saveTreatsAbsentAgeAsNull() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.ean = "3000000000001";
        product.ageRestriction = 18;
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            resource.save(form);
        }
        assertNull(product.ageRestriction);
    }

    /**
     * {@code save} treats a non-numeric age as unrestricted (parseAge
     * malformed-arm).
     */
    @Test
    void saveTreatsMalformedAgeAsNull() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.ean = "3000000000001";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("ageRestriction", "grand");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            resource.save(form);
        }
        assertNull(product.ageRestriction);
    }

    /**
     * {@code save} treats a zero or negative age as unrestricted (parseAge
     * non-positive arm).
     */
    @Test
    void saveTreatsNonPositiveAgeAsNull() {
        AdminArticleResource resource = newResource();
        Product product = new Product();
        product.ean = "3000000000001";
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.putSingle("id", "1");
        form.putSingle("ageRestriction", "0");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(1L)).thenReturn(product);
            resource.save(form);
        }
        assertNull(product.ageRestriction);
    }
}
