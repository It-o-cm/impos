package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.UnitFormat;
import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuantityDisplay} (BO-10-08-28, BO-10-08-29): the sale
 * screen's quantities written with the administered decimals and symbol.
 */
class QuantityDisplayTest {

    /**
     * Builds the bean over a settings mock answering a writing.
     *
     * @param format the administered writing, or null
     * @return the bean
     */
    private QuantityDisplay display(UnitFormat format) {
        QuantityDisplay display = new QuantityDisplay(mock(PosSettingsService.class));
        when(display.posSettingsService.unitFormat()).thenReturn(format);
        when(display.posSettingsService.weightSymbol()).thenReturn("KG");
        return display;
    }

    /** The screen line, its decomposition and a weight follow the administered writing. */
    @Test
    void screenQuantitiesFollowTheAdministeredWriting() {
        QuantityDisplay display = display(new UnitFormat(1, 2, "KG"));
        TicketState.TicketItem weighed = new TicketState.TicketItem("2000001", "1234", "POMMES",
                new BigDecimal("2.50"), new BigDecimal("0.756"), BigDecimal.ZERO);
        assertEquals("<span class='qty'>0,76 KG</span> POMMES", display.html(weighed));
        assertEquals("0,76 KG x 2,50 €/KG", display.detail(weighed));
        assertEquals("0,05 KG", display.weight(new BigDecimal("0.05")));
        assertEquals("", display.weight(null));
        assertEquals("KG", display.getWeightSymbol());
    }

    /** No writing administered is the register's historical one; the container builds the bean bare. */
    @Test
    void aMissingWritingIsTheHistoricalOne() {
        assertSame(UnitFormat.DEFAULT, display(null).format());
        assertNull(new QuantityDisplay().posSettingsService);
    }

    /**
     * A recorded line's weight follows the administered writing; a piece, a
     * line without article or without quantity keeps the line's own writing.
     */
    @Test
    void recordedLinesFollowTheAdministeredWriting() {
        QuantityDisplay display = display(new UnitFormat(1, 2, "KG"));
        com.intermarche.pos.domain.sale.TicketLine weighed = new com.intermarche.pos.domain.sale.TicketLine();
        weighed.quantity = new BigDecimal("0.756");
        weighed.product = new com.intermarche.pos.domain.catalog.Product();
        weighed.product.plu = "1234";
        assertEquals("0,76 KG", display.line(weighed));
        weighed.product.plu = "";
        assertEquals("x0,756", display.line(weighed));
        weighed.product.plu = null;
        weighed.quantity = new BigDecimal("2");
        assertEquals("x2", display.line(weighed));
        weighed.product = null;
        assertEquals("x2", display.line(weighed));
        weighed.quantity = null;
        assertEquals("", display.line(weighed));
    }

    /**
     * An amount is written the register's way — two decimals, half-up, a
     * comma — whatever shape it reaches the template in; null is empty and a
     * value that is not a number shows itself.
     */
    @Test
    void amountsAreWrittenTheRegistersWay() {
        QuantityDisplay display = display(null);
        assertEquals("2,76", display.money(new BigDecimal("2.7600")));
        assertEquals("150,00", display.money("150"));
        assertEquals("0,13", display.money(0.125d));
        assertEquals("7,00", display.money(7));
        assertEquals("", display.money(null));
        assertEquals("—", display.money("—"));
        assertEquals("true", display.money(Boolean.TRUE));
    }

    /**
     * A VAT rate held as a fraction and a percentage are written with two
     * decimals and their sign; null is empty and a non-number shows itself.
     */
    @Test
    void ratesAndPercentagesCarryTheirSign() {
        QuantityDisplay display = display(null);
        assertEquals("20,00 %", display.rate(new BigDecimal("0.2000")));
        assertEquals("", display.rate(null));
        assertEquals("x", display.rate("x"));
        assertEquals("5,00 %", display.percent(new BigDecimal("5")));
        assertEquals("", display.percent(null));
        assertEquals("x", display.percent("x"));
    }

    /**
     * A quantity follows the administered writing and a number keeps its own
     * decimals; null is empty and a non-number shows itself.
     */
    @Test
    void quantitiesAndNumbersAreWrittenWithAComma() {
        QuantityDisplay display = display(new UnitFormat(1, 2, "KG"));
        assertEquals("2", display.quantity(new BigDecimal("2.000")));
        assertEquals("2,5", display.quantity("2.50"));
        assertEquals("", display.quantity(null));
        assertEquals("x", display.quantity("x"));
        assertEquals("1,085", display.number(new BigDecimal("1.0850")));
        assertEquals("", display.number(null));
        assertEquals("x", display.number("x"));
    }
}
