package com.intermarche.pos.imports;

import com.intermarche.pos.service.sync.EngineFeedService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FeedImportResource}.
 * <p>
 * The resource is the CSV door of a register running without a store node. It
 * owns four decisions and every leg of each is exercised here: the null arm and
 * the non-null arm of the code normalisation; the delegated arm and the
 * non-delegated arm of the dispatch, with one case per entry of the delegation
 * table so no code silently loses its importer; the known and unknown arms of
 * the catalog lookup for what the dispatch did not take; and, on the stored
 * route, the blank-body refusal, the read failure and the nominal store. The
 * catalog lookup is the real static one, because the point of the test is that
 * the resource speaks the catalog's own vocabulary.
 */
class FeedImportResourceTest {

    /** The feed store, kept as a field so each test can verify what was stored. */
    private final EngineFeedService feeds = mock(EngineFeedService.class);

    /** The store importer behind the STORES entry. */
    private final StoreCsvResource stores = mock(StoreCsvResource.class);

    /** The product importer behind the PRODUCTS entry. */
    private final ProductCsvResource products = mock(ProductCsvResource.class);

    /** The family importer behind the FAMILIES entry. */
    private final ProductFamilyCsvResource families = mock(ProductFamilyCsvResource.class);

    /** The VAT importer behind the VAT_RATES entry. */
    private final VatRateCsvResource vatRates = mock(VatRateCsvResource.class);

    /** The price importer behind the PRICES entry. */
    private final PriceCsvResource prices = mock(PriceCsvResource.class);

    /** The employee importer behind the EMPLOYEES entry. */
    private final EmployeeCsvResource employees = mock(EmployeeCsvResource.class);

    /** The customer importer behind the CUSTOMERS entry. */
    private final AccountCustomerCsvResource customers = mock(AccountCustomerCsvResource.class);

    /** The postal-code importer behind the POSTAL_CODES entry. */
    private final PostalCodeCsvResource postalCodes = mock(PostalCodeCsvResource.class);

    /** The nomenclature importer behind the NOMENCLATURE entry. */
    private final NomenclatureCsvResource nomenclature = mock(NomenclatureCsvResource.class);

    /** The EGAlim importer behind the EGALIM_REGIMES entry. */
    private final EgalimRegimeCsvResource egalimRegimes = mock(EgalimRegimeCsvResource.class);

    /**
     * Builds the resource over the mocked collaborators.
     *
     * @return the wired resource
     */
    private FeedImportResource newResource() {
        FeedImportResource resource = new FeedImportResource();
        resource.engineFeedService = feeds;
        resource.storeCsvResource = stores;
        resource.productCsvResource = products;
        resource.productFamilyCsvResource = families;
        resource.vatRateCsvResource = vatRates;
        resource.egalimRegimeCsvResource = egalimRegimes;
        resource.priceCsvResource = prices;
        resource.employeeCsvResource = employees;
        resource.accountCustomerCsvResource = customers;
        resource.postalCodeCsvResource = postalCodes;
        resource.nomenclatureCsvResource = nomenclature;
        return resource;
    }

    /**
     * The EGAlim regimes are a DELEGATED code: the door hands the stream to
     * their importer and stores nothing, the classification being a POS
     * referential the engine does not receive as a feed.
     */
    @Test
    void theEgalimRegimesAreDelegatedAndNotStored() {
        FeedImportResource resource = newResource();
        Response answer = Response.ok("{}").build();
        when(egalimRegimes.importEgalimRegimes(any())).thenReturn(answer);
        Response response = resource.importFeed("EGALIM_REGIMES", null, null, null, null,
                stream("CODE|LABEL|CAP_RATE\nFOOD|Alimentaire|0.3400\n"));
        assertSame(answer, response);
        verify(egalimRegimes).importEgalimRegimes(any());
        verifyNoInteractions(feeds);
    }

    /**
     * Wraps a body in the stream the door reads.
     *
     * @param body the CSV body
     * @return the stream carrying it
     */
    private InputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A null code takes the empty-string arm of the normalisation, finds no
     * delegate, is refused by the catalog as unknown, and stores nothing.
     */
    @Test
    void nullCodeIsRefused() {
        Response response = newResource()
                .importFeed(null, null, null, null, null, stream("EAN|NAME"));
        assertEquals(400, response.getStatus());
        verify(feeds, never()).store(any(), any());
    }

    /**
     * A code absent from both the delegation table and the engine catalog is
     * refused, and the answer names it as well as every code that would work.
     */
    @Test
    void unknownCodeIsRefusedAndListsTheKnownOnes() {
        Response response = newResource()
                .importFeed("NOPE", null, null, null, null, stream("EAN|NAME"));
        assertEquals(400, response.getStatus());
        String body = String.valueOf(response.getEntity());
        assertTrue(body.contains("NOPE"));
        assertTrue(body.contains("EMPLOYEES"));
        assertTrue(body.contains("NOMENCLATURE"));
        assertTrue(body.contains("OFFERS"));
        verify(feeds, never()).store(any(), any());
    }

    /**
     * The refusal of a code carrying a quote and a backslash is still valid
     * JSON: a real parser reads the code back in the error message.
     *
     * @throws Exception never: the answer parses
     */
    @Test
    void unknownCodeAnswerIsValidJson() throws Exception {
        Response response = newResource()
                .importFeed("NO\"PE\\", null, null, null, null, stream("EAN|NAME"));
        String error = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(String.valueOf(response.getEntity())).get("error").asText();
        assertTrue(error.startsWith("Code de flux inconnu: NO\"PE\\ - codes connus: "));
    }

    /**
     * The code is normalised before the table and the catalog see it:
     * surrounding blanks and a lower-case spelling still reach the right
     * importer, so a caller's typography never invents a second vocabulary.
     */
    @Test
    void theCodeIsTrimmedAndUpperCased() {
        Response counts = Response.ok("{\"createdCount\":3}").build();
        when(products.importProducts(any())).thenReturn(counts);
        Response response = newResource()
                .importFeed("  products  ", null, null, null, null, stream("EAN|NAME"));
        assertSame(counts, response);
    }

    /**
     * STORES reaches the store importer and its answer is what comes back.
     */
    @Test
    void storesAreDelegated() {
        Response counts = Response.ok("{\"createdCount\":1}").build();
        when(stores.importStores(any())).thenReturn(counts);
        assertSame(counts, newResource()
                .importFeed("STORES", null, null, null, null, stream("CODE|NAME")));
        verify(feeds, never()).store(any(), any());
    }

    /**
     * PRODUCTS reaches the product importer.
     */
    @Test
    void productsAreDelegated() {
        Response counts = Response.ok("{\"createdCount\":2}").build();
        when(products.importProducts(any())).thenReturn(counts);
        assertSame(counts, newResource()
                .importFeed("PRODUCTS", null, null, null, null, stream("EAN|NAME")));
    }

    /**
     * FAMILIES reaches the family importer.
     */
    @Test
    void familiesAreDelegated() {
        Response counts = Response.ok("{\"createdCount\":3}").build();
        when(families.importProductFamilies(any())).thenReturn(counts);
        assertSame(counts, newResource()
                .importFeed("FAMILIES", null, null, null, null, stream("CODE|DESCRIPTION")));
    }

    /**
     * VAT_RATES reaches the VAT importer — the feed a standalone till needs
     * before its prices can name a regime.
     */
    @Test
    void vatRatesAreDelegated() {
        Response counts = Response.ok("{\"createdCount\":4}").build();
        when(vatRates.importVatRates(any())).thenReturn(counts);
        assertSame(counts, newResource()
                .importFeed("VAT_RATES", null, null, null, null, stream("VAT_NUMBER|RATE")));
    }

    /**
     * PRICES reaches the price importer.
     */
    @Test
    void pricesAreDelegated() {
        Response counts = Response.ok("{\"createdCount\":5}").build();
        when(prices.importPrices(any())).thenReturn(counts);
        assertSame(counts, newResource()
                .importFeed("PRICES", null, null, null, null, stream("EAN|PRICE")));
    }

    /**
     * EMPLOYEES reaches the employee importer — the feed without which nobody
     * can open a lane on a till that has no node.
     */
    @Test
    void employeesAreDelegated() {
        Response counts = Response.ok("{\"createdCount\":6}").build();
        when(employees.importEmployees(any())).thenReturn(counts);
        assertSame(counts, newResource()
                .importFeed("EMPLOYEES", null, null, null, null, stream("BADGE_ID|LOGIN")));
        verify(feeds, never()).store(any(), any());
    }

    /**
     * CUSTOMERS reaches the customer importer AND carries the mode along: the
     * population is full or delta, and the code alone does not say which.
     */
    @Test
    void customersAreDelegatedWithTheirMode() {
        Response counts = Response.ok("{\"createdCount\":7}").build();
        when(customers.importCustomers(any(), eq("delta"))).thenReturn(counts);
        assertSame(counts, newResource()
                .importFeed("CUSTOMERS", "delta", null, null, null, stream("CODE|NAME")));
        verify(customers).importCustomers(any(), eq("delta"));
    }

    /**
     * A customer push without a mode forwards a null one rather than inventing
     * a default the importer did not ask for.
     */
    @Test
    void customersWithoutModeForwardNull() {
        Response counts = Response.ok("{\"createdCount\":8}").build();
        when(customers.importCustomers(any(), eq(null))).thenReturn(counts);
        assertSame(counts, newResource()
                .importFeed("CUSTOMERS", null, null, null, null, stream("CODE|NAME")));
        verify(customers).importCustomers(any(), eq(null));
    }

    /**
     * POSTAL_CODES reaches the postal-code importer.
     */
    @Test
    void postalCodesAreDelegated() {
        Response counts = Response.ok("{\"createdCount\":9}").build();
        when(postalCodes.importPostalCodes(any())).thenReturn(counts);
        assertSame(counts, newResource()
                .importFeed("POSTAL_CODES", null, null, null, null, stream("CODE|PLACE")));
    }

    /**
     * NOMENCLATURE reaches its importer with the three parameters the file
     * cannot carry: the scheme it lands in, its label and its enseigne.
     */
    @Test
    void nomenclatureIsDelegatedWithItsScheme() {
        Response counts = Response.ok("{\"createdCount\":10}").build();
        when(nomenclature.importNomenclature(eq("ITM_FR"), eq("Structure ITM"), eq("ITM"), any()))
                .thenReturn(counts);
        assertSame(counts, newResource()
                .importFeed("NOMENCLATURE", null, "ITM_FR", "Structure ITM", "ITM",
                        stream("SOURCE|NIVEAU|NUMERO|LIBELLE")));
        verify(nomenclature).importNomenclature(eq("ITM_FR"), eq("Structure ITM"), eq("ITM"), any());
    }

    /**
     * A catalog code with no importer — an engine-only feed — is stored
     * verbatim, and the answer says so rather than pretending the register
     * applied it: the till's engine is served even when its own referential is
     * not.
     */
    @Test
    void anEngineOnlyCodeIsStoredButNotApplied() {
        when(feeds.store("OFFERS", "CODE|TYPE")).thenReturn("abc123");
        Response response = newResource()
                .importFeed("OFFERS", null, null, null, null, stream("CODE|TYPE"));
        assertEquals(200, response.getStatus());
        String body = String.valueOf(response.getEntity());
        assertTrue(body.contains("\"applied\":false"));
        assertTrue(body.contains("abc123"));
        verify(feeds).store("OFFERS", "CODE|TYPE");
    }

    /**
     * A blank body on the stored route is refused before anything is written:
     * an empty parcel would overwrite the engine's copy with nothing.
     */
    @Test
    void aBlankBodyOnTheStoredRouteIsRefused() {
        Response response = newResource()
                .importFeed("OFFERS", null, null, null, null, stream("   \n  "));
        assertEquals(400, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("OFFERS"));
        verify(feeds, never()).store(any(), any());
    }

    /**
     * A stream that fails while being read answers 500 and stores nothing —
     * the arm no caller sees until a connection drops mid-push.
     */
    @Test
    void aStreamThatFailsAnswersServerError() throws IOException {
        InputStream broken = mock(InputStream.class);
        when(broken.readAllBytes()).thenThrow(new IOException("cable"));
        Response response = newResource()
                .importFeed("OFFERS", null, null, null, null, broken);
        assertEquals(500, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("cable"));
        verify(feeds, never()).store(any(), any());
    }

    /**
     * The delegation is decided before the catalog: a delegated code is never
     * stored by the door itself, because its importer captures the verbatim
     * copy when the feed is an engine one — storing here would do it twice.
     */
    @Test
    void aDelegatedCatalogCodeIsNotStoredByTheDoor() {
        when(prices.importPrices(any())).thenReturn(Response.ok("{}").build());
        Response response = newResource()
                .importFeed("PRICES", null, null, null, null, stream("EAN|PRICE"));
        assertEquals(200, response.getStatus());
        assertFalse(String.valueOf(response.getEntity()).contains("applied"));
        verify(feeds, never()).store(any(), any());
    }
}
