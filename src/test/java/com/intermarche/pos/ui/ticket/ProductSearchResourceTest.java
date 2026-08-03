package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
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
        resource.lock = mock(Template.class);
        return resource;
    }

    /**
     * {@code searchPage(query)} renders the lock page seeded with the state and
     * a null error, and never runs the catalog query, when the terminal is
     * locked (lock guard true arm).
     */
    @Test
    void searchPageRendersLockWhenLocked() {
        ProductSearchResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withError = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(withState);
        when(withState.data("error", null)).thenReturn(withError);
        assertSame(withError, resource.searchPage("milk"));
        verifyNoInteractions(resource.search);
        verifyNoInteractions(resource.ticketService);
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
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withQ = mock(TemplateInstance.class);
        TemplateInstance withHits = mock(TemplateInstance.class);
        when(resource.search.data("state", resource.state)).thenReturn(withState);
        when(withState.data("q", "")).thenReturn(withQ);
        when(withQ.data(eq("hits"), any())).thenReturn(withHits);
        assertSame(withHits, resource.searchPage(null));
        assertTrue(captureHits(withQ).getValue().isEmpty());
        verifyNoInteractions(resource.lock);
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
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withQ = mock(TemplateInstance.class);
        TemplateInstance withHits = mock(TemplateInstance.class);
        when(resource.search.data("state", resource.state)).thenReturn(withState);
        when(withState.data("q", "a")).thenReturn(withQ);
        when(withQ.data(eq("hits"), any())).thenReturn(withHits);
        assertSame(withHits, resource.searchPage("  a  "));
        assertTrue(captureHits(withQ).getValue().isEmpty());
        verifyNoInteractions(resource.lock);
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
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withQ = mock(TemplateInstance.class);
        TemplateInstance withHits = mock(TemplateInstance.class);
        when(resource.search.data("state", resource.state)).thenReturn(withState);
        when(withState.data("q", "ab")).thenReturn(withQ);
        when(withQ.data(eq("hits"), any())).thenReturn(withHits);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            panache.when(() -> Product.find(QUERY, "%ab%", "ab%", "ab")).thenReturn(query);
            prices.when(() -> Price.findCurrentPrice(3L)).thenReturn(price);
            prices.when(() -> Price.findCurrentPrice(4L)).thenReturn(null);
            assertSame(withHits, resource.searchPage("ab"));
        }
        List<ProductSearchResource.SearchHit> hits = captureHits(withQ).getValue();
        assertEquals(2, hits.size());
        assertEquals("MILK", hits.get(0).label);
        assertEquals("EAN1", hits.get(0).code);
        assertEquals("2,50 €", hits.get(0).priceFormatted);
        assertEquals("BREAD", hits.get(1).label);
        assertEquals("EAN2", hits.get(1).code);
        assertEquals("—", hits.get(1).priceFormatted);
        verifyNoInteractions(resource.lock);
    }

    /**
     * {@code addFromSearch(ean)} redirects to the lock page and never touches
     * the ticket when the terminal is locked (lock guard true arm).
     */
    @Test
    void addFromSearchRedirectsToLockWhenLocked() {
        ProductSearchResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        Response response = resource.addFromSearch("EAN1");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/lock", response.getLocation().toString());
        verifyNoInteractions(resource.ticketService);
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
     * Captures the {@code hits} list handed to the {@code search} template on
     * the given query-seeded template instance.
     *
     * @param withQ the template instance on which the {@code hits} data is set
     * @return the captor holding the captured {@code hits} list
     */
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<ProductSearchResource.SearchHit>> captureHits(TemplateInstance withQ) {
        ArgumentCaptor<List<ProductSearchResource.SearchHit>> hitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(withQ).data(eq("hits"), hitsCaptor.capture());
        return hitsCaptor;
    }
}
