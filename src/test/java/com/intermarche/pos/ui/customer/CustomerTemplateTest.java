package com.intermarche.pos.ui.customer;

import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.service.PosSettingsService;
import io.quarkus.qute.Engine;
import io.quarkus.qute.NamespaceResolver;
import io.quarkus.qute.ReflectionValueResolver;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/** LC-13-06-06: render the actual customer template with the real bilingual resolver. */
class CustomerTemplateTest {
    /** Static mentions use both real bundles; the total contains no escaped HTML entity. */
    @Test
    void customerMentionsRenderBothLanguages() throws Exception {
        Messages messages = TestMessages.french();
        var settingsField = Messages.class.getDeclaredField("posSettingsService");
        settingsField.setAccessible(true);
        PosSettingsService settings = (PosSettingsService) settingsField.get(messages);
        when(settings.customerSecondLanguage()).thenReturn("nl");
        var engine = Engine.builder().addDefaults().addValueResolver(new ReflectionValueResolver())
                .addResultMapper(new io.quarkus.qute.HtmlEscaper(java.util.List.of("text/html")))
                .addNamespaceResolver(NamespaceResolver.builder("inject").resolve(context -> messages).build()).build();
        engine.putTemplate("theme", engine.parse(Files.readString(Path.of("src/main/resources/templates/theme.html"))));
        String html = engine.parse(Files.readString(Path.of("src/main/resources/templates/customer.html")),
                io.quarkus.qute.Variant.forContentType("text/html")).data("posTheme", "dark")
                .data("customerCss", "").render();
        assertTrue(html.contains(messages.both("customer.merci")));
        assertTrue(html.contains(messages.both("customer.total")));
        assertFalse(html.contains("&amp;nbsp;"));
        assertFalse(messages.both("customer.merci").equals(messages.get("customer.merci")));
    }
    /** LC-02-03-11: the real acknowledgement template escapes catalog text and preserves the message identifier. */
    @Test
    void articleMessageIsEscapedAndOnlyOffersAcknowledgement() throws Exception {
        Messages messages = TestMessages.french();
        var engine = Engine.builder().addDefaults().addValueResolver(new ReflectionValueResolver())
                .addResultMapper(new io.quarkus.qute.HtmlEscaper(java.util.List.of("text/html")))
                .addNamespaceResolver(NamespaceResolver.builder("inject").resolve(context -> messages).build()).build();
        engine.putTemplate("theme", engine.parse(Files.readString(Path.of("src/main/resources/templates/theme.html"))));
        var template = engine.parse(Files.readString(Path.of("src/main/resources/templates/article-message.html")),
                io.quarkus.qute.Variant.forContentType("text/html"));
        var state = new com.intermarche.pos.ui.PosState();
        state.blockingArticleMessage = "Check <script> and & package";
        state.blockingArticleMessageId = "message-1";
        String html = template.data("state", state).data("posTheme", "dark").render();
        assertTrue(html.contains("Check &lt;script&gt; and &amp; package"));
        assertTrue(html.contains("value=\"message-1\""));
        assertTrue(html.contains("method=\"post\" action=\"/action/article-message/ack\""));
        assertTrue(html.contains(messages.get("articlemessage.notRecorded")));
        state.blockingArticleMessage = null;
        html = template.data("state", state).data("posTheme", "dark").render();
        assertFalse(html.contains("/action/article-message/ack"));
        assertTrue(html.contains(messages.get("articlemessage.return")));
    }

}
