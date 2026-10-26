package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Engine;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.ReflectionValueResolver;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** LC-01-05-01: frozen forms remain inert escaped data until the browser resumes their approved POST. */
class FunctionApprovalTemplateTest {
    /** Repeated fields and special characters survive the real Qute continuation template. */
    @Test
    void escapedRepeatedFieldsAndSingleToken() throws Exception {
        var messages = TestMessages.french();
        var engine = Engine.builder().addDefaults().addValueResolver(new ReflectionValueResolver())
                .addResultMapper(new io.quarkus.qute.HtmlEscaper(List.of("text/html")))
                .addNamespaceResolver(NamespaceResolver.builder("inject").resolve(context -> messages).build()).build();
        String html = engine.parse(Files.readString(Path.of("src/main/resources/templates/approved-function.html")),
                io.quarkus.qute.Variant.forContentType("text/html"))
                .data("target", "/sale-tax-id?x=1&y=2").data("token", "once")
                .data("fields", Map.of("tag", List.of("one", "two"), "name", List.of("\"><script>bad</script>"))).render();
        assertTrue(html.contains("action=\"/sale-tax-id?x=1&amp;y=2\""));
        assertTrue(html.contains("name=\"tag\" value=\"one\""));
        assertTrue(html.contains("name=\"tag\" value=\"two\""));
        assertFalse(html.contains("<script>bad</script>"));
        assertEquals(1,html.split("name=\"_posApproval\"",-1).length-1);
        assertTrue(html.contains("requestSubmit()"));
    }
}
