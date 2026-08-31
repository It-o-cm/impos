package com.intermarche.pos.ui.hardware.terminal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encoder/decoder of the Verifone tag frames exchanged with the local
 * monetique client. The observable format in the requirements is a sequence
 * of {@code {TAG}value} pairs (e.g. {@code {D16}72}); this codec reads and
 * writes that shape, preserving tag order.
 * <p>
 * WHAT IS PENDING THE PROTOCOL SPECIFICATION: the transport framing around
 * a frame (length prefix vs STX/ETX + LRC), the character set, the exact
 * tag dictionary and the request layouts (debit, credit, abandon, c18, c2g,
 * c2h). This codec is the stable part: the {@code {TAG}value} syntax.
 */
public final class VerifoneFrameCodec {

    /** Non-instantiable: static codec. */
    private VerifoneFrameCodec() {
    }

    /**
     * Decodes a frame into its ordered tag → value map. Text before the
     * first tag is ignored; a tag's value runs until the next '{' or the
     * end of the frame.
     *
     * @param frame the raw frame text, or null
     * @return the ordered tag map (empty on null/blank input)
     */
    public static Map<String, String> decode(String frame) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (frame == null || frame.isBlank()) return tags;
        int i = frame.indexOf('{');
        while (i >= 0) {
            int close = frame.indexOf('}', i + 1);
            if (close < 0) break; // malformed tail: ignored
            String tag = frame.substring(i + 1, close);
            int next = frame.indexOf('{', close + 1);
            String value = next >= 0
                    ? frame.substring(close + 1, next)
                    : frame.substring(close + 1);
            tags.put(tag, value);
            i = next;
        }
        return tags;
    }

    /**
     * Encodes an ordered tag → value map into a frame.
     *
     * @param tags the ordered tags (null values written empty)
     * @return the {@code {TAG}value} concatenation
     */
    public static String encode(Map<String, String> tags) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : tags.entrySet()) {
            sb.append('{').append(e.getKey()).append('}');
            if (e.getValue() != null) sb.append(e.getValue());
        }
        return sb.toString();
    }
}
