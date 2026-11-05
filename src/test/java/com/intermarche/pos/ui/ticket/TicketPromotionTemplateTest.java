package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Engine;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.ReflectionValueResolver;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** LC-03-01-04: render the real polled receipt with line-bound promotion labels. */
class TicketPromotionTemplateTest {
    /**
     * Builds the Qute engine the polled receipt is rendered with: reflection, raw output,
     * HTML escaping and the {@code inject:} namespace answering the French bundle and the
     * quantity writer.
     *
     * @return the engine
     */
    private static Engine engine() {
        return Engine.builder().addDefaults().addValueResolver(new ReflectionValueResolver())
                .addValueResolver(io.quarkus.qute.ValueResolvers.rawResolver())
                .addResultMapper(new io.quarkus.qute.HtmlEscaper(java.util.List.of("text/html")))
                .addNamespaceResolver(NamespaceResolver.builder("inject").resolve(context -> "units".equals(context.getName())
                        ? new QuantityDisplay(org.mockito.Mockito.mock(com.intermarche.pos.service.PosSettingsService.class))
                        : TestMessages.french()).build()).build();
    }

    /**
     * LC-09-01-11/13/15/17: a line the administered restricted tenders cover carries one
     * escaped mark per tender, under its own label; a line without mark and a cancelled
     * line carry none.
     *
     * @throws Exception if the template cannot be read
     */
    @Test
    void eligibleLineCarriesItsTenderMarks() throws Exception {
        var template = engine().parse(Files.readString(Path.of("src/main/resources/templates/ticket.html")), io.quarkus.qute.Variant.forContentType("text/html"));
        var state = new PosState();
        var meal = new TicketState.TicketItem("12345670", null, "Pasta", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO);
        var wine = new TicketState.TicketItem("76543210", null, "Wine", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO);
        state.ticket.items.add(meal);
        state.ticket.items.add(wine);
        var view = template.data("state", state).data("restrictedTenders", java.util.List.of())
                .data("tenderMarks", java.util.Map.of(meal.uid, java.util.List.of("Titre-restaurant", "Bons <A>")))
                .data("waitingLabel", "Waiting");
        String html = view.render();
        assertEquals(2, html.split("class=\"tender-mark\"", -1).length - 1);
        assertTrue(html.contains("<span class=\"tender-mark\">Éligible Titre-restaurant</span>"));
        assertTrue(html.contains("Éligible Bons &lt;A&gt;"));
        assertTrue(html.indexOf("Pasta") < html.indexOf("tender-mark"));
        assertTrue(html.indexOf("tender-mark") < html.indexOf("Wine"));
        meal.cancelled = true;
        assertFalse(view.render().contains("class=\"tender-mark\""));
    }

    /** Applied labels stay under their article, are escaped, and disappear on cancelled or undiscounted lines. */
    @Test
    void appliedPromotionIsEscapedAndBoundToItsArticle() throws Exception {
        var engine = engine();
        var template = engine.parse(Files.readString(Path.of("src/main/resources/templates/ticket.html")), io.quarkus.qute.Variant.forContentType("text/html"));
        var state = new PosState();
        var item = new TicketState.TicketItem("12345670", null, "Milk", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO);
        item.promotionLabel = "2 bought <1 free>";
        state.ticket.items.add(item);
        var view = template.data("state", state).data("showEan", false).data("restrictedTenders", java.util.List.of()).data("tenderMarks", java.util.Map.of()).data("waitingLabel", "Waiting");
        String html = view.render();
        assertTrue(html.contains("2 bought &lt;1 free&gt;"));
        assertFalse(html.contains("2 bought <1 free>"));
        assertTrue(html.indexOf("Milk") < html.indexOf("2 bought &lt;1 free&gt;"));
        assertTrue(html.contains("<span class='qty unit-qty'>x1</span> Milk"));
        assertEquals(1, html.split("class=\"promotion-label\"", -1).length - 1);
        item.cancelled = true;
        assertFalse(view.render().contains("class=\"promotion-label\""));
        item.cancelled = false;
        item.promotionLabel = null;
        assertFalse(view.render().contains("class=\"promotion-label\""));
    }
}
