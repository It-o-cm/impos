package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.service.PosSettingsService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ArticleCodeService} (BO-10-02-34/35): a line resolved
 * once, a line without code, a flagged article, a flagless one taking the
 * shop's parameter, read once per pass.
 */
class ArticleCodeServiceTest {

    /**
     * Builds a ticket line on the given EAN.
     *
     * @param ean the line's EAN, possibly null or empty
     * @return the line
     */
    private TicketState.TicketItem line(String ean) {
        return new TicketState.TicketItem(ean, null, "ARTICLE", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO);
    }

    /**
     * Each unresolved line is resolved from its article's flag or, flagless,
     * the shop's parameter; lines without a code show none; a resolved line is
     * left as it is.
     */
    @Test
    void resolveDecidesEachLineOnce() {
        ArticleCodeService service = new ArticleCodeService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.showEan()).thenReturn(true);
        TicketState ticket = new TicketState();
        TicketState.TicketItem flaggedOff = line("1111");
        TicketState.TicketItem flagless = line("2222");
        TicketState.TicketItem noCode = line(null);
        TicketState.TicketItem emptyCode = line("");
        TicketState.TicketItem resolved = line("3333");
        resolved.codeOnScreen = Boolean.FALSE;
        ticket.items.add(flaggedOff);
        ticket.items.add(flagless);
        ticket.items.add(noCode);
        ticket.items.add(emptyCode);
        ticket.items.add(resolved);
        Product off = new Product();
        off.attributes.put(ProductAttributeCatalog.CODE_ON_SCREEN, "false");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("1111")).thenReturn(off);
            products.when(() -> Product.findByEan("2222")).thenReturn(null);
            service.resolve(ticket);
        }
        assertFalse(flaggedOff.getCodeShown());
        assertTrue(flagless.getCodeShown());
        assertFalse(noCode.getCodeShown());
        assertFalse(emptyCode.getCodeShown());
        assertFalse(resolved.getCodeShown());
        verify(service.posSettingsService, times(1)).showEan();
    }
}
