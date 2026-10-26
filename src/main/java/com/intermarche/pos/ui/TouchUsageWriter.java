package com.intermarche.pos.ui;

import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Adds dynamic key counting to operator HTML without a fixed action list (LC-13-05-01). */
@Provider
@ConstrainedTo(RuntimeType.SERVER)
public class TouchUsageWriter implements WriterInterceptor {

    /** Training is a cross-screen key policy, like the existing usage observer. */
    @jakarta.inject.Inject PosState state;
    /** Administered rules share the server request guard (LC-13-05-04). */
    @jakarta.inject.Inject TrainingPolicyService trainingPolicy;
    /** Translated refusal and tactile acknowledgement. */
    @jakarta.inject.Inject Messages i18n;

    /** The script contains no operator text or payment data. */
    private static final String SCRIPT = "<script src=\"/supervisor/key-usage.js\" defer></script>";

    /**
     * Adds the observer only to complete HTML documents; all other payloads remain untouched.
     * @param context the rendered response
     * @throws IOException on output failure
     * @throws WebApplicationException on writer failure (LC-13-05-01)
     */
    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        MediaType type = context.getMediaType();
        if (type == null) {
            context.proceed();
            return;
        }
        if (!type.isCompatible(MediaType.TEXT_HTML_TYPE)) {
            context.proceed();
            return;
        }
        OutputStream original = context.getOutputStream();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        context.setOutputStream(captured);
        try {
            context.proceed();
        } finally {
            context.setOutputStream(original);
        }
        String html = captured.toString(StandardCharsets.UTF_8);
        int bodyEnd = html.lastIndexOf("</body>");
        if (bodyEnd >= 0) {
            String training = "";
            if (state.trainingMode && html.contains("pos-terminal")) {
                String data = new io.vertx.core.json.JsonObject()
                        .put("rules", trainingPolicy.rules())
                        .put("message", i18n.get("op.fonctionIndisponibleFormation"))
                        .put("close", i18n.get("training.policyClose"))
                        .encode().replace("<", "\\u003c");
                training = "<script type=\"application/json\" id=\"training-policy\">" + data
                        + "</script><script src=\"/pos/training-policy.js\" defer></script>";
            }
            html = html.substring(0, bodyEnd) + training + SCRIPT + html.substring(bodyEnd);
        }
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        context.getHeaders().remove("Content-Length");
        original.write(bytes);
    }
}
