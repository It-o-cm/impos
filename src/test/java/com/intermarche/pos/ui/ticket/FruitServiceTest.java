package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link FruitService}.
 * <p>
 * Under plain {@code mvn test} the static Panache finder resolves to
 * {@link PanacheEntityBase} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}; the hierarchy-resolved family flag
 * lookup is a static of {@link ProductFamily} and is intercepted the same
 * way. The service holds one filter: only products whose family hierarchy
 * carries the Fruits &amp; Légumes flag survive the aisle check.
 */
class FruitServiceTest {

    /**
     * {@code getPluProducts()} pins the exact Panache query (variable weight,
     * PLU present, active) and keeps only the products whose family hierarchy
     * carries {@link FruitService#FRUITS_VEGETABLES_FLAG} — the flagged
     * product survives, the unflagged one is dropped (both arms of the flag
     * guard).
     */
    @Test
    void getPluProductsKeepsOnlyFlaggedVariableWeightCatalog() {
        FruitService service = new FruitService();
        Product flagged = mock(Product.class);
        Product unflagged = mock(Product.class);
        List<Product> catalog = List.of(flagged, unflagged);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            panache.when(() -> Product.list(
                    "variableWeight = true and plu is not null and active = true")).thenReturn(catalog);
            families.when(() -> ProductFamily.productHasFlag(flagged, FruitService.FRUITS_VEGETABLES_FLAG))
                    .thenReturn(true);
            families.when(() -> ProductFamily.productHasFlag(unflagged, FruitService.FRUITS_VEGETABLES_FLAG))
                    .thenReturn(false);
            List<Product> result = service.getPluProducts();
            assertEquals(1, result.size());
            assertSame(flagged, result.get(0));
        }
    }

    /**
     * {@code getPluProducts()} returns an empty list when the finder matches
     * nothing (loop never entered) — the grid renders empty, it does not
     * fault.
     */
    @Test
    void getPluProductsEmptyCatalogYieldsEmptyGrid() {
        FruitService service = new FruitService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            panache.when(() -> Product.list(
                    "variableWeight = true and plu is not null and active = true")).thenReturn(List.of());
            assertTrue(service.getPluProducts().isEmpty());
        }
    }
}
