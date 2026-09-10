package com.intermarche.pos.ui.hardware.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EvdevKeyboardSource}: the evdev open/grab lifecycle
 * driven through the real production JNA binding over real (fake) nodes, and
 * the record-splitting decode that feeds key presses to the embedded keyboard
 * decoder. Plain JUnit 5, no Quarkus boot and no mocking of the native
 * collaborator (the {@code LinuxInput.C} singleton is exercised for real over
 * temp files); assertions are on the exact returned descriptor sign and on the
 * exact decoded code string.
 */
class EvdevKeyboardSourceTest {

    /**
     * Writes one 24-byte little-endian {@code struct input_event} record into
     * the buffer at the given base offset (the 16-byte timeval is left zeroed;
     * only type, code and value are laid out, matching {@link InputEvent}).
     *
     * @param buffer the destination buffer holding at least one record from base
     * @param base the offset of the record's first byte
     * @param type the event type field
     * @param code the key code field
     * @param value the value field (1 press, 0 release)
     */
    private static void putRecord(byte[] buffer, int base, short type, short code, int value) {
        buffer[base + 16] = (byte) (type & 0xFF);
        buffer[base + 17] = (byte) ((type >> 8) & 0xFF);
        buffer[base + 18] = (byte) (code & 0xFF);
        buffer[base + 19] = (byte) ((code >> 8) & 0xFF);
        buffer[base + 20] = (byte) (value & 0xFF);
        buffer[base + 21] = (byte) ((value >> 8) & 0xFF);
        buffer[base + 22] = (byte) ((value >> 16) & 0xFF);
        buffer[base + 23] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * The path accessor returns the exact node path handed to the constructor.
     * Covers {@code path()} (no branch).
     */
    @Test
    void pathReturnsTheConstructorNode() {
        EvdevKeyboardSource source = new EvdevKeyboardSource("/dev/input/event42", code -> {});
        assertEquals("/dev/input/event42", source.path());
    }

    /**
     * The read buffer holds 32 whole 24-byte records. Covers {@code readSize()}
     * (no branch): 24 * 32 == 768.
     */
    @Test
    void readSizeIsThirtyTwoWholeRecords() {
        EvdevKeyboardSource source = new EvdevKeyboardSource("/dev/input/event42", code -> {});
        assertEquals(768, source.readSize());
    }

    /**
     * Opening a node that does not exist makes the native open fail, so the
     * source reports failure without attempting a grab. Covers the
     * {@code fd < 0} TRUE arm of {@code open()}.
     */
    @Test
    void openReturnsMinusOneWhenNodeCannotBeOpened() {
        EvdevKeyboardSource source = new EvdevKeyboardSource(
                "/dev/does-not-exist-evdev-keyboard-node", code -> {});
        assertEquals(-1, source.open());
    }

    /**
     * Opening a real regular file succeeds (fd >= 0) but the {@code EVIOCGRAB}
     * ioctl is not supported on a non-evdev descriptor, so it returns non-zero;
     * the source drops the descriptor and reports failure. Covers the
     * {@code fd < 0} FALSE arm and the {@code ioctl != 0} TRUE arm of
     * {@code open()}.
     *
     * @param tmp a temporary directory
     * @throws Exception on file failure
     */
    @Test
    void openReturnsMinusOneWhenGrabIsRefused(@TempDir Path tmp) throws Exception {
        Path node = tmp.resolve("regular-file-node");
        Files.write(node, new byte[8]);
        EvdevKeyboardSource source = new EvdevKeyboardSource(node.toString(), code -> {});
        assertEquals(-1, source.open());
    }

    /**
     * Closing a descriptor drops the grab and closes it without throwing; a
     * regular-file descriptor is opened for real and released. Covers
     * {@code close(int)} (no branch) end to end.
     *
     * @param tmp a temporary directory
     * @throws Exception on file failure
     */
    @Test
    void closeDropsGrabAndClosesWithoutThrowing(@TempDir Path tmp) throws Exception {
        Path node = tmp.resolve("closable-node");
        Files.write(node, new byte[8]);
        int fd = LinuxInput.C.open(node.toString(), LinuxInput.O_RDONLY);
        assertTrue(fd >= 0);
        EvdevKeyboardSource source = new EvdevKeyboardSource(node.toString(), code -> {});
        source.close(fd);
    }

    /**
     * A chunk of several records is split into 24-byte events: a mapped key
     * PRESS is appended, a key RELEASE (value 0) is ignored, and the ENTER
     * press emits the assembled code once. Covers the {@code i < records}
     * loop-entered arm and BOTH arms of {@code event.isKeyPress()} in
     * {@code decode()}.
     */
    @Test
    void decodeFeedsKeyPressesAndEmitsCompletedCode() {
        List<String> codes = new ArrayList<>();
        Consumer<String> sink = codes::add;
        EvdevKeyboardSource source = new EvdevKeyboardSource("/dev/input/event42", sink);
        byte[] buffer = new byte[LinuxInput.INPUT_EVENT_SIZE * 3];
        putRecord(buffer, 0, LinuxInput.EV_KEY, (short) 3, 1);
        putRecord(buffer, LinuxInput.INPUT_EVENT_SIZE, LinuxInput.EV_KEY, (short) 3, 0);
        putRecord(buffer, LinuxInput.INPUT_EVENT_SIZE * 2, LinuxInput.EV_KEY, (short) 28, 1);
        source.decode(buffer, buffer.length, code -> {});
        assertEquals(1, codes.size());
        assertEquals("2", codes.get(0));
    }

    /**
     * A read of zero bytes yields zero records, so the loop body never runs and
     * nothing reaches the decoder. Covers the {@code i < records}
     * loop-not-entered arm of {@code decode()}.
     */
    @Test
    void decodeWithNoBytesEmitsNothing() {
        List<String> codes = new ArrayList<>();
        Consumer<String> sink = codes::add;
        EvdevKeyboardSource source = new EvdevKeyboardSource("/dev/input/event42", sink);
        source.decode(new byte[LinuxInput.INPUT_EVENT_SIZE], 0, code -> {});
        assertEquals(0, codes.size());
    }
}
