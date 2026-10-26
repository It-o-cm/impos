package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForbiddenSaleMessage} (BO-10-07-20): the article's
 * message, the shop's default, the register's sentence, and a register built
 * without its parameters.
 */
class ForbiddenSaleMessageTest {

    /**
     * The article's own message wins over the shop's default, which wins over
     * the register's sentence; without parameters the register's sentence
     * stands.
     */
    @Test
    void theMostPreciseMessageIsShown() {
        PosSettingsService settings = mock(PosSettingsService.class);
        when(settings.forbiddenSaleMessage()).thenReturn("RETRAIT RAPPEL : VOIR ACCUEIL");
        Product plain = new Product();
        Product own = new Product();
        own.attributes.put(ProductAttributeCatalog.FORBIDDEN_SALE_MESSAGE, "LOT 12 RAPPELÉ");
        assertEquals("LOT 12 RAPPELÉ",
                ForbiddenSaleMessage.recall(TestMessages.french(), settings, own));
        assertEquals("RETRAIT RAPPEL : VOIR ACCUEIL",
                ForbiddenSaleMessage.recall(TestMessages.french(), settings, plain));
        assertEquals("ARTICLE EN RETRAIT/RAPPEL",
                ForbiddenSaleMessage.recall(TestMessages.french(), null, plain));
        assertEquals("PRODUIT INTERDIT À LA VENTE",
                ForbiddenSaleMessage.forbidden(TestMessages.french(), mock(PosSettingsService.class), plain));
    }
}
