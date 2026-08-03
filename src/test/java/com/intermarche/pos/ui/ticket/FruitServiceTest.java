package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link FruitService}.
 * <p>
 * The service is a single-statement facade over the {@link Product} Panache
 * finder. Under plain {@code mvn test} the static finder resolves to
 * {@link PanacheEntityBase} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}. The method carries no branch, so a
 * single test pins the exact query string and asserts the finder result is
 * returned verbatim.
 */
class FruitServiceTest {

    /**
     * {@code getPluProducts()} returns exactly the list produced by the
     * {@code plu is not null and active = true} Panache query, unaltered.
     */
    @Test
    void getPluProductsReturnsActivePluCatalog() {
        FruitService service = new FruitService();
        List<Product> catalog = List.of(mock(Product.class), mock(Product.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Product.list("plu is not null and active = true")).thenReturn(catalog);
            assertSame(catalog, service.getPluProducts());
        }
    }
}
