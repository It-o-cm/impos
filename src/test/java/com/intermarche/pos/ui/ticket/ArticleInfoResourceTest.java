package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.VatRate;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ArticleInfoResource} (EXP-93).
 * <p>
 * The resource is a READ-ONLY article lookup: it renders a form, and when a code
 * is given, the found article or a not-found message — never a path that adds a
 * line. The Qute {@code articleInfo} template is a Mockito mock whose fluent
 * {@code data(...)} chain echoes itself so the returned view is identifiable and
 * the {@code info}/{@code notFound} values can be captured; the two Panache
 * finders and the current-price lookup are neutralized with
 * {@link org.mockito.Mockito#mockStatic}. Each test asserts absolute expected
 * values. {@link ArticleInfoResource.ArticleInfo} is exercised directly for its
 * formatting arms, being a package-visible view.
 */
class ArticleInfoResourceTest {

    /** A scanned/keyed code used across the lookup tests. */
    private static final String CODE = "3560070000000";

    /** The echoing view the fluent {@code data(...)} chain resolves to. */
    private TemplateInstance view;

    /**
     * Builds a resource whose {@code articleInfo} template echoes its fluent
     * {@code data(...)} chain: the first call returns {@link #view} and every
     * further call on it returns itself, so the {@code info}/{@code notFound}
     * links (called on the view) can be verified there.
     *
     * @return the wired resource
     */
    private ArticleInfoResource newResource() {
        ArticleInfoResource resource = new ArticleInfoResource();
        resource.articleInfo = mock(Template.class);
        view = mock(TemplateInstance.class);
        when(resource.articleInfo.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        return resource;
    }

    /**
     * Builds an active product with the given identifiers.
     *
     * @param name the sale name
     * @param ean the EAN, or null
     * @param plu the PLU, or null
     * @return the wired product
     */
    private Product product(String name, String ean, String plu) {
        Product p = new Product();
        p.id = 7L;
        p.name = name;
        p.ean = ean;
        p.plu = plu;
        p.brand = "MARQUE";
        p.active = true;
        return p;
    }

    /**
     * Builds a current price with a tax-inclusive amount and a VAT rate.
     *
     * @param incTax the price including tax
     * @param vat the VAT rate
     * @return the wired price
     */
    private Price price(String incTax, String vat) {
        Price pr = new Price();
        pr.priceIncludingTax = new BigDecimal(incTax);
        pr.vat = new VatRate(1, new BigDecimal(vat), "Taux normal");
        return pr;
    }

    /**
     * A blank code shows the form alone: no info, not-found false (both guard
     * legs of the blank check taken as blank).
     */
    @Test
    void infoPageBlankCodeShowsFormOnly() {
        ArticleInfoResource resource = newResource();
        resource.infoPage("  ");
        verify(view).data(eq("info"), isNullArg());
        verify(view).data("notFound", false);
    }

    /**
     * A null code shows the form alone: no info, not-found false.
     */
    @Test
    void infoPageNullCodeShowsFormOnly() {
        ArticleInfoResource resource = newResource();
        resource.infoPage(null);
        verify(view).data(eq("info"), isNullArg());
        verify(view).data("notFound", false);
    }

    /**
     * A code matching an active product by EAN renders its info, not-found false.
     */
    @Test
    void infoPageFoundByEanRendersInfo() {
        ArticleInfoResource resource = newResource();
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(CODE)).thenReturn(product("LAIT", CODE, null));
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price("2.35", "0.20"));
            resource.infoPage(CODE);
            ArgumentCaptor<Object> info = ArgumentCaptor.forClass(Object.class);
            verify(view).data(eq("info"), info.capture());
            assertNotNull(info.getValue());
            assertEquals("LAIT", ((ArticleInfoResource.ArticleInfo) info.getValue()).getLabel());
            verify(view).data("notFound", false);
        }
    }

    /**
     * A code that matches no EAN falls back to the PLU lookup and renders the
     * product found there (EAN-null arm of the fallback).
     */
    @Test
    void infoPageFallsBackToPluLookup() {
        ArticleInfoResource resource = newResource();
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("42")).thenReturn(null);
            products.when(() -> Product.findActiveByPlu("42")).thenReturn(product("POMME", null, "42"));
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price("1.00", "0.055"));
            resource.infoPage("42");
            verify(view).data("notFound", false);
        }
    }

    /**
     * A code matching neither EAN nor PLU shows the not-found message with no info.
     */
    @Test
    void infoPageNotFoundShowsMessage() {
        ArticleInfoResource resource = newResource();
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan("999")).thenReturn(null);
            products.when(() -> Product.findActiveByPlu("999")).thenReturn(null);
            resource.infoPage("999");
            verify(view).data(eq("info"), isNullArg());
            verify(view).data("notFound", true);
        }
    }

    /**
     * {@link ArticleInfoResource.ArticleInfo} formats a priced article: price with
     * a French comma and VAT as a percentage; the identifiers pass through.
     */
    @Test
    void articleInfoFormatsAPricedArticle() {
        ArticleInfoResource.ArticleInfo info =
                new ArticleInfoResource.ArticleInfo(product("LAIT", CODE, "12"), price("2.35", "0.20"));
        assertEquals("LAIT", info.getLabel());
        assertEquals(CODE, info.getEan());
        assertEquals("12", info.getPlu());
        assertEquals("MARQUE", info.getBrand());
        assertEquals("2,35", info.getPriceFormatted());
        assertEquals("20 %", info.getVatFormatted());
    }

    /**
     * {@link ArticleInfoResource.ArticleInfo} shows dashes for an unpriced article
     * and blank identifiers (each null/blank arm).
     */
    @Test
    void articleInfoShowsDashesWhenUnpricedAndBlank() {
        ArticleInfoResource.ArticleInfo info =
                new ArticleInfoResource.ArticleInfo(product("DIVERS", "", ""), null);
        assertEquals("—", info.getEan());
        assertEquals("—", info.getPlu());
        assertEquals("—", info.getPriceFormatted());
        assertEquals("—", info.getVatFormatted());
    }

    /**
     * A Mockito nullable-argument matcher for the {@code data("info", null)} call.
     *
     * @return null, registered as an argument matcher
     */
    private static Object isNullArg() {
        return org.mockito.ArgumentMatchers.isNull();
    }
}
