package com.intermarche.pos.ui.cash;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashCalculatorResource} (EXP-04).
 * <p>
 * The resource is a thin facade: it serves the float-calculator page with the
 * calculator's banknotes and the count page's coins and rolls, and records
 * nothing. The {@link CashCountService} is a real instance (its coin and roll
 * lists are the arithmetic reused here) and the Qute {@link Template} is a mock
 * whose fluent {@code data(...)} chain records the pairs into a sink so the fed
 * data can be asserted. No database and no Quarkus context is booted.
 */
class CashCalculatorResourceTest {

    /**
     * Builds a resource with a recording template and a real count service.
     *
     * @param sink the map the recorded key-and-value pairs are written into
     * @return the resource under test
     */
    private CashCalculatorResource newResource(Map<String, Object> sink) {
        CashCalculatorResource resource = new CashCalculatorResource();
        resource.cashCountService = new CashCountService();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(instance.data(anyString(), any())).thenAnswer(invocation -> {
            sink.put(invocation.getArgument(0), invocation.getArgument(1));
            return instance;
        });
        Template template = mock(Template.class);
        when(template.data(anyString(), any())).thenAnswer(invocation -> {
            sink.put(invocation.getArgument(0), invocation.getArgument(1));
            return instance;
        });
        resource.cashCalculator = template;
        return resource;
    }

    /**
     * {@code calculatorPage} feeds the calculator banknotes plus the count
     * page's coins and rolls, and returns the rendered view.
     */
    @Test
    void calculatorPageFeedsTheDenominations() {
        Map<String, Object> sink = new LinkedHashMap<>();
        CashCalculatorResource resource = newResource(sink);
        resource.calculatorPage();
        assertEquals(resource.cashCountService.getCoins().size(), ((List<?>) sink.get("coins")).size());
        assertEquals(resource.cashCountService.getRolls().size(), ((List<?>) sink.get("rolls")).size());
        assertEquals(6, ((List<?>) sink.get("bills")).size());
    }

    /**
     * The calculator banknotes run from 200 € down to 5 €, the notes a cashier
     * may hold when making up the float (the Z count stops at 50 €).
     */
    @Test
    void calculatorBillsRunFromTwoHundredDownToFive() {
        CashCalculatorResource resource = new CashCalculatorResource();
        List<CashItem> bills = resource.calculatorBills();
        assertEquals(6, bills.size());
        assertEquals("b200", bills.get(0).id);
        assertEquals(200.0, bills.get(0).value);
        assertEquals("b5", bills.get(bills.size() - 1).id);
        assertEquals(5.0, bills.get(bills.size() - 1).value);
    }
}
