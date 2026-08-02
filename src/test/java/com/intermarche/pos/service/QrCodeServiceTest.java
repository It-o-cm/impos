package com.intermarche.pos.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QrCodeService}.
 * <p>
 * The service is a pure text-in / SVG-out wrapper around the zero-dependency
 * Nayuki generator, so no collaborator is mocked and no Quarkus context is
 * booted. The only branch of the class is the dark/light test in
 * {@link QrCodeService#toSvg(String)} ({@code qr.getModule(x, y)}); every real
 * QR code carries both dark modules (the finder patterns) and light modules
 * (their separators), so a single encode exercises both arms as well as both
 * enclosing loops. Absolute expectations are asserted against the version-1
 * (21-module) code produced for a short input, whose quiet zone of
 * {@code BORDER = 3} yields a {@code 21 + 6 = 27} viewBox.
 */
class QrCodeServiceTest {

    /** The service under test; stateless, so a single fresh instance suffices. */
    private final QrCodeService service = new QrCodeService();

    /**
     * Verifies the full SVG envelope for a short input: XML prologue, the
     * {@code 0 0 27 27} viewBox of a version-1 code plus quiet zone, the white
     * background rectangle, a black path, and the closing tag. The top-left
     * finder module is always dark, so the path must open with the module drawn
     * at the border origin, proving the dark arm of {@code getModule} ran.
     */
    @Test
    void toSvgRendersWellFormedSvgForShortText() {
        String svg = service.toSvg("test");
        assertNotNull(svg);
        assertTrue(svg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), svg);
        assertTrue(svg.contains("viewBox=\"0 0 27 27\""), svg);
        assertTrue(svg.contains("shape-rendering=\"crispEdges\""), svg);
        assertTrue(svg.contains("<rect width=\"100%\" height=\"100%\" fill=\"#FFFFFF\"/>"), svg);
        assertTrue(svg.contains("<path d=\"M3,3h1v1h-1z"), svg);
        assertTrue(svg.contains("fill=\"#000000\"/>"), svg);
        assertTrue(svg.endsWith("</svg>"), svg);
    }

    /**
     * Verifies that distinct inputs yield distinct paths: a different payload
     * must change the drawn modules, guarding against a constant or ignored
     * argument. Both codes remain version 1, so both keep the {@code 27} viewBox.
     */
    @Test
    void toSvgEncodesTheGivenTextIntoThePath() {
        String first = service.toSvg("alpha");
        String second = service.toSvg("omega");
        assertTrue(first.contains("viewBox=\"0 0 27 27\""), first);
        assertTrue(second.contains("viewBox=\"0 0 27 27\""), second);
        assertNotEqualsSvg(first, second);
    }

    /**
     * Verifies that an empty payload is still a valid, well-formed SVG document:
     * the encoder emits a version-1 code (finder patterns only), so the envelope
     * and the {@code 0 0 27 27} viewBox are unchanged and the dark path is
     * non-empty.
     */
    @Test
    void toSvgEncodesEmptyStringAsWellFormedSvg() {
        String svg = service.toSvg("");
        assertTrue(svg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), svg);
        assertTrue(svg.contains("viewBox=\"0 0 27 27\""), svg);
        assertTrue(svg.contains("<path d=\"M3,3h1v1h-1z"), svg);
        assertTrue(svg.endsWith("</svg>"), svg);
    }

    /**
     * Asserts that two SVG strings differ, failing with both values attached.
     *
     * @param first  the first SVG document
     * @param second the second SVG document
     */
    private void assertNotEqualsSvg(String first, String second) {
        assertTrue(!first.equals(second), first + "\n!=\n" + second);
    }
}
