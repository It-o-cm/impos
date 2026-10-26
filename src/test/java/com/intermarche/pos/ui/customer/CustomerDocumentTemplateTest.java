package com.intermarche.pos.ui.customer;

import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Engine;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.Variant;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** LC-08-02-06: the real page renders frozen customer text as escaped data. */
class CustomerDocumentTemplateTest {
    /** Verifies the real includes, tactile entry, selected identifiers and untrusted preview escaping. */
    @Test
    void rendersCustomerSelectionAndEscapesDocumentContent() throws Exception {
        var messages = TestMessages.french();
        var engine = Engine.builder().addDefaults().addValueResolver(new ReflectionValueResolver())
                .addResultMapper(new io.quarkus.qute.HtmlEscaper(List.of("text/html")))
                .addNamespaceResolver(NamespaceResolver.builder("inject").resolve(context -> messages).build()).build();
        for (String include : List.of("theme", "keyboard-lib")) {
            engine.putTemplate(include, engine.parse(Files.readString(Path.of("src/main/resources/templates/" + include + ".html"))));
        }
        String html = engine.parse(Files.readString(Path.of("src/main/resources/templates/customer-documents.html")),
                Variant.forContentType("text/html"))
                .data("posTheme", "dark").data("posKeyboardLayout", "AZERTY").data("documents", List.of(Map.of("id", 42L,
                        "reference", "AV-42", "label", "Avoir", "date", "22/10/2026 10:30",
                        "content", new io.quarkus.qute.RawString(CustomerReceiptRendering.render(
                                "[[IMAGE 8 1 gA==]]\nCUSTOMER <script>alert(1)</script>").html()))))
                .data("address", "client@example.test").data("error", "").data("outcome", "")
                .data("page", 1).data("previous", 0).data("next", 2)
                .data("hasPrevious", true).data("hasNext", true).render();
        assertTrue(html.contains("name=\"documentId\" value=\"42\" required"));
        assertTrue(html.contains("CUSTOMER &lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(html.contains("CUSTOMER <script>"));
        assertTrue(html.contains("src=\"data:image/png;base64,"));
        assertFalse(html.contains("[[IMAGE"));
        assertTrue(html.contains("/customer-documents?page=0"));
        assertTrue(html.contains("/customer-documents?page=2"));
        assertTrue(html.contains("keyboardType: 'email'"));
        assertTrue(html.contains(messages.get("customerdocs.title")));
        assertFalse(html.contains("NOT_FOUND"));
    }
}
