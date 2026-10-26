package com.intermarche.pos.ui.customer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/** LC-08-02-06/-07/-08: renders frozen printer logos for screens and e-mail, without changing paper. */
public final class CustomerReceiptRendering {
    /** A malformed logo line must not expose its packed raster to the customer. */
    private static final Pattern LOGO_LINE = Pattern.compile("(?m)^[\\t ]*\\[\\[IMAGE\\b[^\\r\\n]*(?:\\r?\\n|$)");
    /** The printer's exact width/height/packed-row grammar. */
    private static final Pattern LOGO = Pattern.compile("\\[\\[IMAGE\\s+(\\d+)\\s+(\\d+)\\s+([A-Za-z0-9+/=]+)\\]\\]");
    /** Bounds raster allocation while accepting ordinary receipt logos, including tall ones. */
    private static final long MAX_PIXELS = 2_000_000;

    /** A pure rendering utility has no instances. */
    private CustomerReceiptRendering() { }

    /** A decoded frozen logo and the MIME identifier used only within its own message.
     * @param contentId safe local MIME identifier
     * @param png decoded raster encoded as PNG
     * @param dataUri self-contained preview/attachment image
     */
    public record Logo(String contentId, byte[] png, String dataUri) { }

    /** Customer formats derived from the same frozen source.
     * @param text readable text without image directives
     * @param html escaped HTML with self-contained logos
     * @param logos decoded images for MIME embedding
     */
    public record Rendered(String text, String html, List<Logo> logos) { }

    /** Renders each logo in place, preserving and escaping all surrounding text.
     * @param source non-null frozen printer content
     * @return customer-only formats; the source itself is never mutated
     */
    public static Rendered render(String source) {
        var matcher = LOGO_LINE.matcher(source);
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<Logo> logos = new ArrayList<>();
        int offset = 0;
        while (matcher.find()) {
            appendText(source.substring(offset, matcher.start()), text, html);
            Logo logo = decode(matcher.group().trim(), logos.size());
            if (logo != null) {
                logos.add(logo);
                html.append("<img alt=\"\" src=\"").append(logo.dataUri())
                        .append("\" style=\"display:block;max-width:100%;height:auto;margin:8px auto;background:white\">");
            }
            offset = matcher.end();
        }
        appendText(source.substring(offset), text, html);
        return new Rendered(text.toString(), html.toString(), List.copyOf(logos));
    }

    /** Appends receipt text as data, never trusting a document as HTML.
     * @param content exact text segment
     * @param text plain-text destination
     * @param html escaped HTML destination
     */
    private static void appendText(String content, StringBuilder text, StringBuilder html) {
        text.append(content);
        html.append("<pre style=\"white-space:pre-wrap;overflow-wrap:anywhere;font-family:'Courier New',Courier,monospace;width:max-content;max-width:100%;margin:0 auto\">")
                .append(content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        .replace("\"", "&quot;").replace("'", "&#39;"))
                .append("</pre>");
    }

    /** Decodes packed rows, with the leftmost dot in each byte's high bit, exactly like the printer.
     * @param directive complete logo directive
     * @param index image rank within this document
     * @return the frozen PNG, or null for an unreadable logo
     */
    private static Logo decode(String directive, int index) {
        var fields = LOGO.matcher(directive);
        if (!fields.matches()) {
            return null;
        }
        try {
            int width = Integer.parseInt(fields.group(1));
            int height = Integer.parseInt(fields.group(2));
            if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                return null;
            }
            int stride = (width + 7) / 8;
            byte[] bits = Base64.getDecoder().decode(fields.group(3));
            if ((long) stride * height != bits.length) {
                return null;
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    boolean black = (bits[y * stride + x / 8] & (0x80 >> (x % 8))) != 0;
                    image.setRGB(x, y, black ? 0xff000000 : 0xffffffff);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            byte[] png = output.toByteArray();
            return new Logo("receipt-logo-" + index + "@impos.local", png,
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        } catch (IllegalArgumentException | IOException failure) {
            return null;
        }
    }
}
