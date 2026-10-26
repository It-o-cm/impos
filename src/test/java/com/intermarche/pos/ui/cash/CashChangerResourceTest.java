package com.intermarche.pos.ui.cash;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.CashChangerService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashChangerResource} (BO-11-03-03): the cash level
 * page hands the template the machine's inventory, its alert and whether the
 * movements go through it.
 */
class CashChangerResourceTest {

    /** The page carries the inventory, the alert and the movement mode. */
    @Test
    void thePageCarriesTheCashLevel() {
        CashChangerResource resource = new CashChangerResource();
        resource.cashChanger = mock(Template.class);
        resource.cashChangerService = mock(CashChangerService.class);
        resource.state = new PosState();
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.cashChanger.data(anyString(), any())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        CashChangerService.Inventory inventory = new CashChangerService.Inventory(new java.math.BigDecimal("10.00"), List.of());
        when(resource.cashChangerService.inventory()).thenReturn(inventory);
        when(resource.cashChangerService.levelAlert()).thenReturn("MONNAYEUR PRESQUE VIDE");
        when(resource.cashChangerService.movementsViaMachine()).thenReturn(true);
        Assertions.assertSame(instance, resource.page());
        verify(instance).data("inventory", inventory);
        verify(instance).data("alert", "MONNAYEUR PRESQUE VIDE");
        verify(instance).data("viaMachine", true);
    }
}
