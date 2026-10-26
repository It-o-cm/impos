package com.intermarche.pos.ui.customer;

import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Engine;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.ReflectionValueResolver;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Renders the actual entry mask and its shared keyboard includes (LC-08-06-02/-03). */
class SaleTaxIdTemplateTest {

    /**
     * The default is escaped in the form and read from the DOM, never inserted in executable script;
     * the shared keyboard include receives the administered layout (LC-09-01-09); the screen
     * carries its continuation, the scan counter and the scan hint (LC-08-06-01/-05).
     */
    @Test
    void rendersPrefillAndSafeErrorWithoutInvoice() throws Exception {
        var messages = TestMessages.french();
        var engine = Engine.builder().addDefaults().addValueResolver(new ReflectionValueResolver())
                .addResultMapper(new io.quarkus.qute.HtmlEscaper(List.of("text/html")))
                .addNamespaceResolver(NamespaceResolver.builder("inject").resolve(context -> messages).build()).build();
        for (String include : List.of("theme", "keyboard-lib")) {
            engine.putTemplate(include, engine.parse(Files.readString(Path.of("src/main/resources/templates/" + include + ".html"))));
        }
        var template = engine.parse(Files.readString(Path.of("src/main/resources/templates/sale-tax-id.html")),
                io.quarkus.qute.Variant.forContentType("text/html"));
        String html = template.data("value", "999999990").data("error", "").data("posTheme", "clair")
                .data("posKeyboardLayout", "QWERTY")
                .data("next", "pay").data("scans", 3L).data("scanHint", true).render();
        assertTrue(html.contains("name=\"taxId\" value=\"999999990\""));
        assertTrue(html.contains("initialValue:document.getElementById('taxIdHidden').value"));
        assertTrue(html.contains("action=\"/sale-tax-id\""));
        assertTrue(html.contains("layout: 'QWERTY',"));
        assertTrue(html.contains("name=\"next\" value=\"pay\""));
        assertTrue(html.contains("let knownScans = Number('3');"));
        assertTrue(html.contains(messages.get("saletaxid.scanHint")));
        assertFalse(html.contains("action=\"/invoice"));
        html = template.data("value", "\"><script>alert(1)</script>").data("error", "<bad>").data("posTheme", "clair")
                .data("posKeyboardLayout", "QWERTY")
                .data("next", "pay").data("scans", 3L).data("scanHint", true).render();
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;bad&gt;"));
        assertTrue(html.contains("role=\"alert\""));
    }
}
