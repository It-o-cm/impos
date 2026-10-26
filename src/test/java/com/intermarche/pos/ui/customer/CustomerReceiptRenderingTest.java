package com.intermarche.pos.ui.customer;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** LC-08-02-06/-07/-08: frozen raster interpretation, readable fallback and HTML escaping. */
class CustomerReceiptRenderingTest {
    /** Receipt packing uses high bits first and ignores padding bits beyond the stated width. */
    @Test void reconstructsFrozenRasterWithExactDimensionsAndDots() throws Exception {
        String source = "BEFORE\r\n [[IMAGE 9 2 gID/AA==]] \r\nAFTER <script> & \" '";
        var rendered = CustomerReceiptRendering.render(source);
        assertEquals("BEFORE\r\nAFTER <script> & \" '", rendered.text());
        assertEquals(1, rendered.logos().size());
        var logo = rendered.logos().get(0);
        assertEquals("receipt-logo-0@impos.local", logo.contentId());
        assertTrue(logo.dataUri().startsWith("data:image/png;base64,"));
        var image = ImageIO.read(new ByteArrayInputStream(logo.png()));
        assertEquals(9, image.getWidth()); assertEquals(2, image.getHeight());
        assertEquals(0xff000000, image.getRGB(0, 0));
        assertEquals(0xffffffff, image.getRGB(7, 0));
        assertEquals(0xff000000, image.getRGB(8, 0));
        assertEquals(0xff000000, image.getRGB(7, 1));
        assertEquals(0xffffffff, image.getRGB(8, 1));
        assertTrue(rendered.html().indexOf("BEFORE") < rendered.html().indexOf("<img"));
        assertTrue(rendered.html().indexOf("<img") < rendered.html().indexOf("AFTER"));
        assertTrue(rendered.html().contains("&lt;script&gt; &amp; &quot; &#39;"));
        assertFalse(rendered.html().contains("[[IMAGE"));
        assertTrue(rendered.html().contains("font-family:'Courier New',Courier,monospace"));
        assertTrue(source.contains("[[IMAGE"));
    }

    /** Empty/plain receipts preserve text exactly and untrusted text never becomes markup. */
    @Test void plainTextAndEmptyContentRemainReadable() {
        assertEquals("", CustomerReceiptRendering.render("").text());
        var rendered = CustomerReceiptRendering.render("TEXT <img src=x onerror=bad>\n[[BARCODE 123]]");
        assertTrue(rendered.logos().isEmpty());
        assertEquals("TEXT <img src=x onerror=bad>\n[[BARCODE 123]]", rendered.text());
        assertTrue(rendered.html().contains("&lt;img src=x onerror=bad&gt;"));
        assertFalse(rendered.html().contains("<img src=x"));
    }

    /** Invalid dimensions, excessive allocation, malformed framing/base64 and row lengths omit only the logo. */
    @Test void invalidLogosNeverExposePackedPayloadOrDiscardReceiptText() {
        for (String directive : new String[]{"[[IMAGE]]", "[[IMAGE bad]]", "[[IMAGE 8 1 gA==]",
                "[[IMAGE -1 1 gA==]]", "[[IMAGE 0 1 gA==]]", "[[IMAGE 8 0 gA==]]",
                "[[IMAGE 2000001 1 gA==]]", "[[IMAGE 999999999999999999 1 gA==]]",
                "[[IMAGE 8 1 A]]", "[[IMAGE 8 2 gA==]]"}) {
            var rendered = CustomerReceiptRendering.render("HEAD\n" + directive + "\nTAIL");
            assertEquals("HEAD\nTAIL", rendered.text(), directive);
            assertTrue(rendered.logos().isEmpty(), directive);
            assertFalse(rendered.html().contains("[[IMAGE"), directive);
        }
    }

    /** Multiple identical logos stay separate and retain document order and stable local identifiers. */
    @Test void multipleLogosKeepTheirPositions() {
        var rendered = CustomerReceiptRendering.render("[[IMAGE 8 1 gA==]]\nMIDDLE\n[[IMAGE 8 1 gA==]]");
        assertEquals(2, rendered.logos().size()); assertEquals("MIDDLE\n", rendered.text());
        assertEquals("receipt-logo-1@impos.local", rendered.logos().get(1).contentId());
        assertEquals(2, rendered.html().split("<img", -1).length - 1);
    }

    /** A PNG encoder error leaves the text available instead of failing the customer document. */
    @Test void pngEncodingFailureKeepsReadableReceipt() {
        try (var io = mockStatic(ImageIO.class)) {
            io.when(() -> ImageIO.write(any(java.awt.image.RenderedImage.class), eq("png"),
                    any(java.io.OutputStream.class))).thenThrow(new IOException("encoder unavailable"));
            var rendered = CustomerReceiptRendering.render("[[IMAGE 8 1 gA==]]\nTOTAL 1.00");
            assertEquals("TOTAL 1.00", rendered.text()); assertTrue(rendered.logos().isEmpty());
        }
    }
}
