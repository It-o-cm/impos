package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.PosState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductSearchResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, the
 * {@link TicketService} and two Qute {@link Template}s ({@code search} and
 * {@code lock}). Every collaborator is a Mockito mock; the templates return
 * distinct {@link TemplateInstance} mocks along the fluent {@code data(...)}
 * chain so the exact rendered view can be identified. The catalog lookup goes
 * through the {@link Product} Panache static finder, which under plain
 * {@code mvn test} resolves to {@link PanacheEntityBase} and is intercepted
 * with {@link org.mockito.Mockito#mockStatic}; the {@code page(...).list()}
 * chain is a plain mocked {@link PanacheQuery}. The per-hit current price is
 * resolved through {@link Price#findCurrentPrice(Long)}, intercepted with a
 * second static mock on {@link Price}. All entity mocks have their public
 * fields set directly, no database and no Quarkus context is booted, and every
 * branch of both endpoints is covered with absolute expected values.
 */
class ProductSearchResourceTest {

    /** The exact JPQL fragment issued by the search endpoint. */
    private static final String QUERY =
            "active = true and forbiddenToSale = false"
                    + " and (lower(name) like ?1 or ean like ?2 or plu = ?3) order by name";

    /**
     * Builds a {@link ProductSearchResource} whose collaborators are fresh
     * mocks wired onto its package-private fields.
     *
     * @return a resource with fully mocked state, ticket service and templates
     */
    private ProductSearchResource newResource() {
        ProductSearchResource resource = new ProductSearchResource();
        resource.state = mock(PosState.class);
        resource.ticketService = mock(TicketService.class);
        resource.search = mock(Template.class);
        return resource;
    }

    /**
     * Wires the search template so every {@code data(...)} call chains onto the
     * same instance, and returns it. The endpoint now seeds a dozen keys — the
     * page, the page count, the pager links — so naming each link of the chain
     * would say nothing about the view and everything about the order of the
     * calls.
     *
     * @param resource the resource whose template is wired
     * @return the instance every {@code data(...)} call returns
     */
    private TemplateInstance wireTemplate(ProductSearchResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.search.data(org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(instance);
        when(instance.data(org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(instance);
        return instance;
    }

    /**
     * {@code searchPage(null)} normalizes a null query to the empty string
     * (ternary null arm), skips the search because the length is below two
     * (length guard false arm) and renders the page with an empty result list.
     */
    @Test
    void searchPageWithNullQueryRendersEmpty() {
        ProductSearchResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance instance = wireTemplate(resource);
        assertSame(instance, resource.searchPage(null, null));
        verify(resource.search).data("state", resource.state);
        verify(instance).data("q", "");
        assertTrue(captureHits(instance).getValue().isEmpty());
        verify(instance).data("pageCount", 1);
        verify(instance).data("page", 1);
        verify(instance).data("hasPrev", false);
        verify(instance).data("hasNext", false);
    }

    /**
     * {@code searchPage(query)} trims a non-null query (ternary non-null arm)
     * and, when the trimmed length stays below two (length guard false arm),
     * skips the search and renders the page with an empty result list.
     */
    @Test
    void searchPageWithShortQueryRendersEmpty() {
        ProductSearchResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance instance = wireTemplate(resource);
        assertSame(instance, resource.searchPage("  a  ", null));
        verify(instance).data("q", "a");
        assertTrue(captureHits(instance).getValue().isEmpty());
    }

    /**
     * {@code searchPage("ab")} runs the catalog query (length guard true arm)
     * and folds the results into hits: an EAN-less product is skipped through
     * the null arm of the code guard, an empty-EAN product through the
     * is-empty arm, a priced product yields a formatted amount (price non-null
     * arm) and an unpriced product yields the dash placeholder (price null
     * arm). The surviving hits carry the upper-cased label and the EAN code.
     */
    @Test
    void searchPageWithMatchesBuildsHits() {
        ProductSearchResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Product nullEan = mock(Product.class);
        nullEan.ean = null;
        Product emptyEan = mock(Product.class);
        emptyEan.ean = "";
        Product priced = mock(Product.class);
        priced.ean = "EAN1";
        priced.plu = "1234";
        priced.name = "Milk";
        priced.id = 3L;
        Product unpriced = mock(Product.class);
        unpriced.ean = "EAN2";
        unpriced.plu = null;
        unpriced.name = "Bread";
        unpriced.id = 4L;
        List<Product> found = List.of(nullEan, emptyEan, priced, unpriced);
        Price price = mock(Price.class);
        price.priceIncludingTax = new BigDecimal("2.5");
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> paged = mock(PanacheQuery.class);
        when(query.page(0, 24)).thenReturn(paged);
        when(paged.list()).thenReturn(found);
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            panache.when(() -> Product.find(QUERY, "%ab%", "ab%", "ab")).thenReturn(query);
            prices.when(() -> Price.findCurrentPrice(3L)).thenReturn(price);
            prices.when(() -> Price.findCurrentPrice(4L)).thenReturn(null);
            assertSame(instance, resource.searchPage("ab", null));
        }
        List<ProductSearchResource.SearchHit> hits = captureHits(instance).getValue();
        assertEquals(2, hits.size());
        assertEquals("MILK", hits.get(0).label);
        assertEquals("EAN1", hits.get(0).code);
        assertEquals("2,50 €", hits.get(0).priceFormatted);
        assertEquals("BREAD", hits.get(1).label);
        assertEquals("EAN2", hits.get(1).code);
        assertEquals("—", hits.get(1).priceFormatted);
    }

    /**
     * {@code addFromSearch(ean)} adds one unit of the chosen product to the
     * ticket and redirects home when the terminal is unlocked (lock guard
     * false arm).
     */
    @Test
    void addFromSearchAddsAndRedirectsHome() {
        ProductSearchResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addFromSearch("EAN1");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN1", BigDecimal.ONE);
    }

    /**
     * {@code searchPage(query, page)} with a non-null page number (page ternary
     * non-null arm) over a result set that spans three pages of eight clamps the request
     * to a middle page: {@code current} is neither the first nor the last, so
     * {@code hasPrev} takes the {@code current > 1} true arm and {@code hasNext}
     * takes the {@code current < pageCount} true arm, and the pager exposes both
     * neighbours.
     */
    @Test
    void searchPageWithMiddlePageEnablesBothPagerArms() {
        ProductSearchResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        List<Product> found = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            Product product = mock(Product.class);
            product.ean = "E" + i;
            product.name = "P" + i;
            product.id = (long) i;
            found.add(product);
        }
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> paged = mock(PanacheQuery.class);
        when(query.page(0, 24)).thenReturn(paged);
        when(paged.list()).thenReturn(found);
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            panache.when(() -> Product.find(QUERY, "%ab%", "ab%", "ab")).thenReturn(query);
            assertSame(instance, resource.searchPage("ab", 2));
        }
        assertEquals(17, captureHits(instance).getValue().size());
        verify(instance).data("pageCount", 3);
        verify(instance).data("page", 2);
        verify(instance).data("hasPrev", true);
        verify(instance).data("hasNext", true);
        verify(instance).data("prevPage", 1);
        verify(instance).data("nextPage", 3);
    }

    /**
     * {@code scanTypedCode(null)} normalizes a null code to the empty string
     * (code ternary null arm), finds it empty (is-not-empty guard false arm),
     * hands nothing to the scan chain and redirects home.
     */
    @Test
    void scanTypedCodeWithNullCodeRedirectsWithoutScanning() {
        ProductSearchResource resource = newResource();
        Response response = resource.scanTypedCode(null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * {@code scanTypedCode(code)} trims a non-null code (code ternary non-null
     * arm) and, when the trimmed code is not empty (is-not-empty guard true
     * arm), walks the trimmed code through the scan chain and redirects home.
     */
    @Test
    void scanTypedCodeWithTypedCodeRoutesThroughScan() {
        ProductSearchResource resource = newResource();
        Response response = resource.scanTypedCode("  3245  ");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).processScan("3245");
    }

    /**
     * Captures the {@code hits} list handed to the {@code search} template on
     * the given query-seeded template instance.
     *
     * @param instance the template instance on which the {@code hits} data is set
     * @return the captor holding the captured {@code hits} list
     */
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<ProductSearchResource.SearchHit>> captureHits(TemplateInstance instance) {
        ArgumentCaptor<List<ProductSearchResource.SearchHit>> hitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(instance).data(eq("hits"), hitsCaptor.capture());
        return hitsCaptor;
    }
}
