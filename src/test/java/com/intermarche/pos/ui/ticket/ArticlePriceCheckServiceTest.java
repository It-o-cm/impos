package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.balance.TareService;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.scanner.WeightedEanScanHandler;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Decimal, preweighed and live-scale price checks never receive a writable ticket. */
class ArticlePriceCheckServiceTest {
    /** Wires only the existing scale and decoding collaborators. @return the service */
    private ArticlePriceCheckService service() {
        var service = new ArticlePriceCheckService();
        service.hardware = mock(HardwareService.class);
        service.tares = mock(TareService.class);
        service.labels = mock(WeightedEanScanHandler.class);
        return service;
    }
    /** Builds a current price. @param value the tax-inclusive price @return the price */
    private Price price(String value) {
        var price = new Price();
        price.priceIncludingTax = value == null ? null : new BigDecimal(value);
        return price;
    }
    /** Decimal entry accepts a comma, rounds only the total and never reads the scale. */
    @Test void decimalQuantityUsesCurrentPriceWithoutHardware() {
        var service = service();
        var check = service.calculate(new Product(), price("2.35"), " 1,125 ", null);
        assertEquals("1,125", check.quantity());
        assertEquals("2,64", check.total());
        assertFalse(check.weighted()); assertTrue(check.editable()); assertNull(check.errorKey());
        verifyNoInteractions(service.hardware, service.tares);
    }
    /** Blank/null input means one unit; positive decimals never get silently truncated. */
    @Test void plainProductsDefaultToOneAndRetainDecimalPrecision() {
        var service = service();
        for (String value : new String[] {null, "", "  "}) {
            assertEquals("2,35", service.calculate(new Product(), price("2.35"), value, null).total());
        }
        assertEquals("0,0001", service.calculate(new Product(), price("10000"), "0.0001", null).quantity());
    }
    /** Nonpositive and malformed entries refuse a calculation instead of showing a false total. */
    @Test void invalidDecimalEntriesShowNoQuantityOrTotal() {
        var service = service();
        for (String value : new String[] {"0", "-1", "abc", "1,2,3"}) {
            var check = service.calculate(new Product(), price("2"), value, null);
            assertEquals("articleinfo.invalidQuantity", check.errorKey());
            assertEquals("—", check.total()); assertEquals("—", check.quantity());
            assertTrue(check.editable());
        }
        verifyNoInteractions(service.hardware, service.tares);
    }
    /** A live weighing sends only article/default tare and keeps the returned net weight intact. */
    @Test void weighingUsesNetWeightAndNeverConsumesSaleTare() {
        var service = service(); var product = new Product(); product.variableWeight = true;
        when(service.tares.resolve(product, null)).thenReturn(new BigDecimal("0.020"));
        when(service.hardware.awaitWeighing(new BigDecimal("0.020"))).thenReturn(0.875);
        var check = service.calculate(product, price("3.80"), null, null);
        assertEquals("0,875", check.quantity()); assertEquals("3,33", check.total());
        assertTrue(check.weighted()); assertNull(check.errorKey());
        verify(service.tares).resolve(product, null); verifyNoMoreInteractions(service.tares);
        assertEquals("1,90", service.calculate(product, price("3.80"), "0,5", null).total());
        verify(service.hardware, times(1)).awaitWeighing(any());
    }
    /** Zero, negative, nonfinite and sub-gram scale readings never produce a total. */
    @Test void invalidScaleReadingsAreReportedWithoutASaleMutation() {
        var service = service(); var product = new Product(); product.variableWeight = true;
        for (double value : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 0.0001}) {
            when(service.hardware.awaitWeighing(null)).thenReturn(value);
            var check = service.calculate(product, price("2"), null, null);
            assertEquals("op.poidsInvalide", check.errorKey()); assertEquals("—", check.total());
        }
    }
    /** Label total remains authoritative; its weight is derived from the current per-kilo price. */
    @Test void priceLabelKeepsPrintedTotalAndDerivesWeight() {
        var service = service();
        var label = new WeightedEanScanHandler.PriceCheckLabel("12", new BigDecimal("1.50"), null);
        var check = service.calculate(new Product(), price("3"), "999", label);
        assertEquals("0,5", check.quantity()); assertEquals("1,50", check.total());
        assertTrue(check.weighted()); assertFalse(check.editable()); assertNull(check.errorKey());
        verifyNoInteractions(service.hardware, service.tares);
    }
    /** A net-weight label is calculated directly, with no additional software tare. */
    @Test void weightLabelUsesItsNetWeightWithoutScaleOrTare() {
        var service = service();
        var label = new WeightedEanScanHandler.PriceCheckLabel("12", null, new BigDecimal("0.875"));
        var check = service.calculate(new Product(), price("3.80"), null, label);
        assertEquals("3,33", check.total()); assertEquals("0,875", check.quantity());
        assertFalse(check.editable()); verifyNoInteractions(service.hardware, service.tares);
    }
    /** Invalid label totals and weights cannot become an apparently valid consultation. */
    @Test void nonpositiveLabelValuesAreRefused() {
        var service = service();
        for (String value : new String[] {"0", "-1"}) {
            for (boolean amount : new boolean[] {true, false}) {
                var number = new BigDecimal(value);
                var label = new WeightedEanScanHandler.PriceCheckLabel("12", amount ? number : null, amount ? null : number);
                var check = service.calculate(new Product(), price("3"), null, label);
                assertEquals("articleinfo.invalidQuantity", check.errorKey()); assertFalse(check.editable());
            }
        }
    }
    /** Missing, null and negative catalog prices never invent an inverse weight. */
    @Test void missingPricesKeepUnknownResultsExplicit() {
        var service = service();
        for (Price price : new Price[] {null, price(null), price("-1")}) {
            var plain = service.calculate(new Product(), price, null, null);
            assertEquals("articleinfo.unpriced", plain.errorKey()); assertEquals("—", plain.total());
            var label = new WeightedEanScanHandler.PriceCheckLabel("12", new BigDecimal("1.50"), null);
            var result = service.calculate(new Product(), price, null, label);
            assertEquals("—", result.quantity()); assertEquals("1,50", result.total());
        }
    }
    /** A free article has a valid zero total; a zero per-kilo price cannot derive an inverse weight. */
    @Test void zeroCatalogPriceIsFreeWithoutDividingByZero() {
        var service = service();
        var plain = service.calculate(new Product(), price("0"), "2.5", null);
        assertEquals("0,00", plain.total()); assertNull(plain.errorKey());
        var label = new WeightedEanScanHandler.PriceCheckLabel("12", new BigDecimal("1.50"), null);
        assertEquals("—", service.calculate(new Product(), price("0"), null, label).quantity());
    }

    /** Decoding delegates to the configured read-only label path. */
    @Test void decodeUsesTheExistingConfiguredLayouts() {
        var service = service();
        var label = new WeightedEanScanHandler.PriceCheckLabel("12", null, BigDecimal.ONE);
        when(service.labels.priceCheckLabel("123")).thenReturn(label);
        assertSame(label, service.decode("123")); verifyNoInteractions(service.hardware, service.tares);
    }
}
