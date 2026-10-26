package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.domain.store.CoinMachineProtocol;
import com.intermarche.pos.domain.store.ScaleProtocol;
import com.intermarche.pos.service.CheckoutPointService;
import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HardwareConfigurationService} (BO-03-09-11 to -29): the
 * block gathered with and without a checkout point and its peripherals, and the
 * push — first, unchanged, changed, failed.
 */
class HardwareConfigurationServiceTest {

    /**
     * Builds a service over mocked settings.
     *
     * @param point the register's checkout point, or null
     * @return the service
     */
    private HardwareConfigurationService service(CheckoutPoint point) {
        HardwareConfigurationService service = new HardwareConfigurationService();
        service.hardwareClient = mock(HardwareClient.class);
        service.settings = mock(PosSettingsService.class);
        service.checkoutPointService = mock(CheckoutPointService.class);
        when(service.checkoutPointService.own()).thenReturn(point);
        when(service.settings.hardwareEnabled(anyString())).thenReturn(true);
        when(service.settings.drawerAddress()).thenReturn("0x48F");
        when(service.settings.scannerDriver()).thenReturn("OPOS");
        when(service.settings.scannerNamePatterns()).thenReturn("datalogic");
        when(service.settings.scannerOposDevice()).thenReturn("DLS");
        when(service.settings.secondScannerEnabled()).thenReturn(true);
        when(service.settings.secondScannerNamePatterns()).thenReturn("zebra");
        when(service.settings.printerType()).thenReturn("EPSON_TM");
        when(service.settings.printerSpeed()).thenReturn(38400);
        when(service.settings.printerCodePage()).thenReturn(858);
        when(service.settings.printerPaperWidth()).thenReturn(80);
        when(service.settings.chequeFlip()).thenReturn(true);
        when(service.settings.secondPrinterEnabled()).thenReturn(true);
        when(service.settings.secondPrinterFormat()).thenReturn("A4");
        when(service.settings.secondPrinterConnection()).thenReturn("LOCAL");
        when(service.settings.secondPrinterAddress()).thenReturn("BUREAU");
        when(service.settings.paymentTerminalType()).thenReturn("WORLDLINE");
        when(service.settings.paymentTerminalAddress()).thenReturn("10.0.0.5:8200");
        when(service.settings.islandPaymentLabel()).thenReturn("ILOT1");
        return service;
    }

    /** With a point, its scale and coin machine are carried with the parameters. */
    @Test
    void theBlockCarriesThePointAndTheParameters() {
        CheckoutPoint point = new CheckoutPoint();
        point.scaleProtocol = ScaleProtocol.DIGI;
        point.scaleAddress = "10.0.0.8";
        point.scalePort = 4001;
        point.coinMachineProtocol = CoinMachineProtocol.GLORY;
        point.coinMachineAddress = "10.0.0.9";
        point.coinMachinePort = 5001;
        Map<String, String> block = service(point).configuration();
        assertEquals("true", block.get("drawer.enabled"));
        assertEquals("0x48F", block.get("drawer.address"));
        assertEquals("DIGI", block.get("scale.protocol"));
        assertEquals("10.0.0.8", block.get("scale.address"));
        assertEquals("4001", block.get("scale.port"));
        assertEquals("OPOS", block.get("scanner.driver"));
        assertEquals("true", block.get("scanner2.enabled"));
        assertEquals("EPSON_TM", block.get("printer.type"));
        assertEquals("38400", block.get("printer.speed"));
        assertEquals("true", block.get("printer.cheque-flip"));
        assertEquals("LOCAL", block.get("printer2.connection"));
        assertEquals("WORLDLINE", block.get("payment.type"));
        assertEquals("ILOT1", block.get("payment.island-label"));
        assertEquals("GLORY", block.get("coin-machine.protocol"));
        assertEquals("5001", block.get("coin-machine.port"));
        assertEquals(28, block.size());
    }

    /** Without a point, or with a point that describes nothing, the peripherals read NONE and empty. */
    @Test
    void withoutAPointThePeripheralsAreEmpty() {
        Map<String, String> bare = service(null).configuration();
        assertEquals("NONE", bare.get("scale.protocol"));
        assertEquals("", bare.get("scale.address"));
        assertEquals("", bare.get("scale.port"));
        assertEquals("NONE", bare.get("coin-machine.protocol"));
        assertEquals("", bare.get("coin-machine.address"));
        assertEquals("", bare.get("coin-machine.port"));
        CheckoutPoint empty = new CheckoutPoint();
        empty.scaleProtocol = null;
        empty.coinMachineProtocol = null;
        Map<String, String> blank = service(empty).configuration();
        assertEquals("NONE", blank.get("scale.protocol"));
        assertEquals("NONE", blank.get("coin-machine.protocol"));
        assertTrue(service(null).text().startsWith("drawer.enabled=true\ndrawer.address=0x48F\n"));
    }

    /** A block is pushed once, not again while unchanged, again once changed; a failure keeps trying. */
    @Test
    void theBlockIsPushedWhenItChanges() {
        HardwareConfigurationService service = service(null);
        assertTrue(service.push());
        assertFalse(service.push());
        when(service.settings.drawerAddress()).thenReturn("PRINTER");
        assertTrue(service.push());
        verify(service.hardwareClient, times(2)).configure(anyString());
        HardwareConfigurationService broken = service(null);
        doThrow(new IllegalStateException("down")).when(broken.hardwareClient).configure(anyString());
        assertFalse(broken.push());
        assertFalse(broken.push());
        verify(broken.hardwareClient, times(2)).configure(anyString());
    }

    /** The resource shows the block and pushes on demand. */
    @Test
    void theResourceShowsAndPushes() {
        HardwareConfigurationResource resource = new HardwareConfigurationResource();
        resource.hardwareConfigurationService = mock(HardwareConfigurationService.class);
        when(resource.hardwareConfigurationService.text()).thenReturn("a=b\n");
        when(resource.hardwareConfigurationService.push()).thenReturn(true, false);
        assertEquals("a=b\n", resource.configuration());
        assertEquals("PUSHED", resource.push());
        assertEquals("UNCHANGED", resource.push());
    }
}
