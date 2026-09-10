package com.intermarche.pos.ui.hardware.scanner;

/**
 * One decoded {@code struct input_event} from the Linux input layer: the
 * three fields the scanner reader cares about, extracted from the 24-byte
 * little-endian record (a 16-byte timeval, then {@code __u16 type},
 * {@code __u16 code}, {@code __s32 value}). Timestamps are ignored.
 *
 * @param type the event type (EV_KEY, EV_SYN...)
 * @param code the key code for an EV_KEY event
 * @param value 1 press, 0 release, 2 autorepeat
 */
public record InputEvent(short type, short code, int value) {

    /** Byte offset of the {@code type} field in the record. */
    private static final int OFFSET_TYPE = 16;

    /** Byte offset of the {@code code} field in the record. */
    private static final int OFFSET_CODE = 18;

    /** Byte offset of the {@code value} field in the record. */
    private static final int OFFSET_VALUE = 20;

    /**
     * Whether this event is a key PRESS (the edge the decoder acts on;
     * releases and autorepeats are dropped so a held key or a release never
     * doubles a character).
     *
     * @return true when this is an EV_KEY event with value 1
     */
    public boolean isKeyPress() {
        return type == LinuxInput.EV_KEY && value == 1;
    }

    /**
     * Parses one 24-byte little-endian record at the given offset.
     *
     * @param buffer the buffer holding at least one record
     * @param base the offset of the record's first byte
     * @return the parsed event
     */
    public static InputEvent parse(byte[] buffer, int base) {
        short type = (short) ((buffer[base + OFFSET_TYPE] & 0xFF)
                | (buffer[base + OFFSET_TYPE + 1] & 0xFF) << 8);
        short code = (short) ((buffer[base + OFFSET_CODE] & 0xFF)
                | (buffer[base + OFFSET_CODE + 1] & 0xFF) << 8);
        int value = (buffer[base + OFFSET_VALUE] & 0xFF)
                | (buffer[base + OFFSET_VALUE + 1] & 0xFF) << 8
                | (buffer[base + OFFSET_VALUE + 2] & 0xFF) << 16
                | (buffer[base + OFFSET_VALUE + 3] & 0xFF) << 24;
        return new InputEvent(type, code, value);
    }
}
