package com.intermarche.pos.ui;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cross-screen observer injection without changes to fragments or data (LC-13-05-01). */
class TouchUsageWriterTest {

    /** Training is opt-in for each rendering fixture (LC-13-05-04). */
    private boolean training;

    /**
     * Runs the response writer against an in-memory output stream.
     * @param type the outgoing content type
     * @param body the response document
     * @return the written response (LC-13-05-01)
     * @throws Exception on a test fixture or writer failure
     */
    private String render(MediaType type, String body) throws Exception {
        WriterInterceptorContext context = mock(WriterInterceptorContext.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<OutputStream> stream = new AtomicReference<>(output);
        when(context.getMediaType()).thenReturn(type);
        when(context.getOutputStream()).thenAnswer(call -> stream.get());
        doAnswer(call -> { stream.set(call.getArgument(0)); return null; }).when(context).setOutputStream(any());
        when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        doAnswer(call -> { stream.get().write(body.getBytes(StandardCharsets.UTF_8)); return null; })
                .when(context).proceed();
        TouchUsageWriter writer = new TouchUsageWriter();
        writer.state = new PosState();
        writer.state.trainingMode = training;
        writer.trainingPolicy = mock(TrainingPolicyService.class);
        when(writer.trainingPolicy.rules()).thenReturn(java.util.List.of("path:/pay", "key:</script>"));
        writer.i18n = mock(Messages.class);
        when(writer.i18n.get("op.fonctionIndisponibleFormation")).thenReturn("Blocked <here>");
        when(writer.i18n.get("training.policyClose")).thenReturn("OK");
        writer.aroundWriteTo(context);
        assertSame(output, context.getOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }

    /** LC-13-05-01: complete operator HTML receives the observer before its closing body. */
    @Test
    void completeHtml() throws Exception {
        assertEquals("<html><body>screen<script src=\"/supervisor/key-usage.js\" defer></script></body></html>",
                render(MediaType.TEXT_HTML_TYPE, "<html><body>screen</body></html>"));
    }

    /** LC-13-05-01: HTML fragments remain fragments and do not create duplicate listeners. */
    @Test
    void htmlFragment() throws Exception {
        assertEquals("<span>row</span>", render(MediaType.TEXT_HTML_TYPE, "<span>row</span>"));
    }

    /** LC-13-05-01: unspecified content types pass through unmodified. */
    @Test
    void nullContentType() throws Exception {
        assertEquals("data", render(null, "data"));
    }

    /** LC-13-05-01: JSON and other machine surfaces never receive markup. */
    @Test
    void nonHtmlContentType() throws Exception {
        assertEquals("{}", render(MediaType.APPLICATION_JSON_TYPE, "{}"));
    }

    /** LC-13-05-01: a failed underlying writer restores its original stream. */
    @Test
    void writerFailureRestoresStream() throws Exception {
        WriterInterceptorContext context = mock(WriterInterceptorContext.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        when(context.getMediaType()).thenReturn(MediaType.TEXT_HTML_TYPE);
        when(context.getOutputStream()).thenReturn(output);
        doThrow(new IOException("fake failure")).when(context).proceed();
        assertThrows(IOException.class, () -> new TouchUsageWriter().aroundWriteTo(context));
        verify(context).setOutputStream(output);
    }
    /** Training policy is embedded before key actions and cannot escape its JSON script element. */
    @Test
    void trainingOperatorDocument() throws Exception {
        training = true;
        String html = render(MediaType.TEXT_HTML_TYPE, "<html><body><main class=\"pos-terminal\"></main></body></html>");
        assertTrue(html.contains("id=\"training-policy\""));
        assertTrue(html.contains("/pos/training-policy.js"));
        assertTrue(html.contains("key:\\u003c/script>"));
        assertFalse(html.contains("key:</script>"));
        assertTrue(html.indexOf("/pos/training-policy.js") < html.indexOf("/supervisor/key-usage.js"));
    }

    /** BackOffice and customer documents never receive the cashier training guard. */
    @Test
    void trainingNonOperatorDocument() throws Exception {
        training = true;
        assertFalse(render(MediaType.TEXT_HTML_TYPE, "<html><body>back office</body></html>").contains("training-policy"));
    }
}
