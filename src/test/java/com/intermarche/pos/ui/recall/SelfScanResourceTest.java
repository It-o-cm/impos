package com.intermarche.pos.ui.recall;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SelfScanResource}, targeting 100% branch coverage.
 * <p>
 * The template chain records every bound value, the control service is a mock,
 * the state and the messages are real. Branch enumeration: the page without a
 * basket and with one under each control (none, partial drawn or asked, total
 * asked or provoked), the vouchers (absent list, null voucher, null amount,
 * label or none), the polled data with and without a basket, and the three
 * gestures with the routing of the loading outcome.
 */
class SelfScanResourceTest {

    /** The values the page bound, by name. */
    private Map<String, Object> bound;

    /**
     * A resource whose template records what it binds.
     *
     * @return the resource
     */
    private SelfScanResource resource() {
        bound = new HashMap<>();
        SelfScanResource resource = new SelfScanResource();
        resource.i18n = TestMessages.french();
        resource.state = new PosState();
        resource.selfScanService = mock(SelfScanService.class);
        resource.selfscan = mock(Template.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.selfscan.data(anyString(), any())).thenAnswer(inv -> {
            bound.put(inv.getArgument(0), inv.getArgument(1));
            return view;
        });
        when(view.data(anyString(), any())).thenAnswer(inv -> {
            bound.put(inv.getArgument(0), inv.getArgument(1));
            return view;
        });
        return resource;
    }

    /**
     * A basket of 2 x 1.50 and a null line, with the given vouchers.
     *
     * @param vouchers the vouchers, possibly null
     * @return the basket
     */
    private RecalledDocument basket(List<RecalledDocument.Voucher> vouchers) {
        List<RecalledDocument.Line> lines = new ArrayList<>(Arrays.asList(
                new RecalledDocument.Line("301", "LAIT", new BigDecimal("2"), new BigDecimal("1.50"), null),
                null));
        RecalledDocument basket = new RecalledDocument(RecalledDocument.Kind.SELF_SCAN, "SS1", lines, null);
        basket.vouchers = vouchers;
        return basket;
    }

    /**
     * Without a basket the page binds nothing to show.
     */
    @Test
    void thePageWithoutBasket() {
        SelfScanResource resource = resource();
        resource.page();
        assertEquals(null, bound.get("control"));
        assertEquals(List.of(), bound.get("rows"));
        assertEquals("", bound.get("modeLabel"));
        assertEquals("", bound.get("progress"));
    }

    /**
     * A basket without control shows its total, its vouchers — named, or
     * named by default — and no progress.
     */
    @Test
    void thePageOfAnUncontrolledBasket() {
        SelfScanResource resource = resource();
        List<RecalledDocument.Voucher> vouchers = new ArrayList<>(Arrays.asList(
                new RecalledDocument.Voucher("B1", "BON 1", new BigDecimal("1.00")),
                new RecalledDocument.Voucher("B2", null, new BigDecimal("0.50")),
                new RecalledDocument.Voucher("B3", "SANS", null),
                null));
        SelfScanControl control = new SelfScanControl(basket(vouchers), RecalledDocument.CONTROL_NONE, 1);
        resource.state.selfScan = control;
        List<Map<String, String>> rows = List.of(Map.of("label", "LAIT"));
        when(resource.selfScanService.rows(control)).thenReturn(rows);
        resource.page();
        assertSame(control, bound.get("control"));
        assertSame(rows, bound.get("rows"));
        assertEquals("AUCUN CONTRÔLE", bound.get("modeLabel"));
        assertEquals("3,00 €", bound.get("total"));
        assertEquals(List.of(Map.of("label", "BON 1", "amount", "1,00 €"),
                Map.of("label", "BON DE RÉDUCTION", "amount", "0,50 €")), bound.get("vouchers"));
        assertEquals("", bound.get("progress"));
    }

    /**
     * A partial control says how many articles it asks for, whether it was
     * drawn or asked, and its progress; a basket without vouchers shows none.
     */
    @Test
    void thePageOfAPartialControl() {
        SelfScanResource resource = resource();
        SelfScanControl control = new SelfScanControl(basket(null), RecalledDocument.CONTROL_PARTIAL, 2);
        control.drawn = true;
        control.partialChecked = 1;
        resource.state.selfScan = control;
        resource.page();
        assertEquals("CONTRÔLE PARTIEL : RE-SCANNER 2 ARTICLE(S) — tiré au sort", bound.get("modeLabel"));
        assertEquals("1 / 2", bound.get("progress"));
        assertEquals(List.of(), bound.get("vouchers"));
        control.drawn = false;
        resource.page();
        assertEquals("CONTRÔLE PARTIEL : RE-SCANNER 2 ARTICLE(S) — demandé par l'appareil", bound.get("modeLabel"));
    }

    /**
     * A total control is named as asked or as provoked.
     */
    @Test
    void thePageOfATotalControl() {
        SelfScanResource resource = resource();
        SelfScanControl control = new SelfScanControl(basket(List.of()), RecalledDocument.CONTROL_TOTAL, 1);
        resource.state.selfScan = control;
        resource.page();
        assertEquals("CONTRÔLE TOTAL : RE-SCANNER TOUS LES ARTICLES", bound.get("modeLabel"));
        control.provoked = true;
        resource.page();
        assertEquals("CONTRÔLE TOTAL PROVOQUÉ : RE-SCANNER TOUS LES ARTICLES", bound.get("modeLabel"));
    }

    /**
     * The polled data carries the version and whether a basket waits.
     */
    @Test
    void thePolledData() {
        SelfScanResource resource = resource();
        resource.state.version = 4L;
        assertEquals(Map.of("version", 4L, "active", false), resource.data());
        resource.state.selfScan = new SelfScanControl(basket(null), RecalledDocument.CONTROL_NONE, 1);
        assertEquals(Map.of("version", 4L, "active", true), resource.data());
    }

    /**
     * Provoking the total control and putting the basket aside delegate and
     * route; loading routes back to the screen while the partial control is
     * pending, to the sale otherwise.
     */
    @Test
    void theGestures() {
        SelfScanResource resource = resource();
        assertEquals("/selfscan", resource.provokeTotal().getLocation().toString());
        verify(resource.selfScanService).provokeTotal(resource.state);
        assertEquals("/", resource.abandon().getLocation().toString());
        verify(resource.selfScanService).abandon(resource.state);
        when(resource.selfScanService.load(resource.state)).thenReturn(SelfScanService.Outcome.PARTIAL_PENDING);
        assertEquals("/selfscan", resource.load().getLocation().toString());
        when(resource.selfScanService.load(resource.state)).thenReturn(SelfScanService.Outcome.LOADED);
        assertEquals("/", resource.load().getLocation().toString());
    }
}
