package com.intermarche.pos.ui.hardware.terminal;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VerifoneFrameCodec}: the {@code {TAG}value} syntax
 * is the stable part of the Verifone skeleton, so every arm of the decoder
 * (empty input, single tag, multiple tags, empty value, malformed tail,
 * ignored prefix) and the encoder round-trip are pinned here.
 */
class VerifoneFrameCodecTest {

    /**
     * {@code decode} returns an empty map on null input (null arm).
     */
    @Test
    void decodeNullReturnsEmpty() {
        assertTrue(VerifoneFrameCodec.decode(null).isEmpty());
    }

    /**
     * {@code decode} returns an empty map on blank input (blank arm).
     */
    @Test
    void decodeBlankReturnsEmpty() {
        assertTrue(VerifoneFrameCodec.decode("   ").isEmpty());
    }

    /**
     * {@code decode} reads a single tag whose value runs to the end of the
     * frame (no-next-tag arm).
     */
    @Test
    void decodeSingleTag() {
        Map<String, String> tags = VerifoneFrameCodec.decode("{D16}72");
        assertEquals(1, tags.size());
        assertEquals("72", tags.get("D16"));
    }

    /**
     * {@code decode} reads multiple tags in order, each value stopping at
     * the next '{' (next-tag arm).
     */
    @Test
    void decodeMultipleTagsInOrder() {
        Map<String, String> tags = VerifoneFrameCodec.decode("{D16}72{D5Y}SN123{VWAY}4");
        assertEquals(3, tags.size());
        assertEquals("72", tags.get("D16"));
        assertEquals("SN123", tags.get("D5Y"));
        assertEquals("4", tags.get("VWAY"));
        assertEquals("[D16, D5Y, VWAY]", tags.keySet().toString());
    }

    /**
     * {@code decode} keeps an empty value for a tag immediately followed by
     * another tag.
     */
    @Test
    void decodeEmptyValue() {
        Map<String, String> tags = VerifoneFrameCodec.decode("{D16}{D46}fallback");
        assertEquals("", tags.get("D16"));
        assertEquals("fallback", tags.get("D46"));
    }

    /**
     * {@code decode} ignores text before the first tag and a malformed
     * unclosed tail (break arm).
     */
    @Test
    void decodeIgnoresPrefixAndMalformedTail() {
        Map<String, String> tags = VerifoneFrameCodec.decode("noise{D16}72{BROKEN");
        assertEquals(1, tags.size());
        assertEquals("72", tags.get("D16"));
    }

    /**
     * {@code encode} writes tags in order, a null value written empty, and
     * the result decodes back to the same map (round trip).
     */
    @Test
    void encodeRoundTrip() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("D16", "72");
        tags.put("D46", null);
        tags.put("D5Y", "SN9");
        String frame = VerifoneFrameCodec.encode(tags);
        assertEquals("{D16}72{D46}{D5Y}SN9", frame);
        Map<String, String> back = VerifoneFrameCodec.decode(frame);
        assertEquals("72", back.get("D16"));
        assertEquals("", back.get("D46"));
        assertEquals("SN9", back.get("D5Y"));
    }
}
