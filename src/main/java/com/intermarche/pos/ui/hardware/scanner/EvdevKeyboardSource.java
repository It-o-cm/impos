package com.intermarche.pos.ui.hardware.scanner;

import org.jboss.logging.Logger;

import java.util.function.Consumer;

/**
 * Scanner source for a device in USB HID KEYBOARD mode: it presents a
 * {@code /dev/input/eventN} node emitting key events, read through evdev with
 * the exclusive grab so a scanned code never also lands in whatever holds the
 * focus. Key events are decoded into codes by a {@link KeyboardScancodeDecoder}.
 */
public class EvdevKeyboardSource implements ScannerSource {

    /** The class logger. */
    private static final Logger LOGGER = Logger.getLogger(EvdevKeyboardSource.class);

    /** The event device node path. */
    private final String path;

    /** The per-device code assembler. */
    private final KeyboardScancodeDecoder decoder;

    /**
     * Builds a source over one event node, delivering codes to the sink.
     *
     * @param path the {@code /dev/input/eventN} path
     * @param sink the receiver of each completed code
     */
    public EvdevKeyboardSource(String path, Consumer<String> sink) {
        this.path = path;
        this.decoder = new KeyboardScancodeDecoder(sink);
    }

    /**
     * {@inheritDoc}
     *
     * @return the device path
     */
    @Override
    public String path() {
        return path;
    }

    /**
     * Opens the event node and takes the exclusive grab. A failed grab closes
     * the descriptor and reports failure — a device grabbed by someone else is
     * skipped, never half-open.
     *
     * @return the grabbed file descriptor, or -1 on failure
     */
    @Override
    public int open() {
        int fd = LinuxInput.C.open(path, LinuxInput.O_RDONLY);
        if (fd < 0) {
            LOGGER.errorf("Ouverture douchette (clavier) impossible: %s", path);
            return -1;
        }
        if (LinuxInput.C.ioctl(fd, LinuxInput.EVIOCGRAB, 1) != 0) {
            LOGGER.errorf("Prise exclusive (grab) impossible sur %s", path);
            LinuxInput.C.close(fd);
            return -1;
        }
        return fd;
    }

    /**
     * Drops the grab and closes the descriptor.
     *
     * @param fd the grabbed file descriptor
     */
    @Override
    public void close(int fd) {
        LinuxInput.C.ioctl(fd, LinuxInput.EVIOCGRAB, 0);
        LinuxInput.C.close(fd);
    }

    /**
     * A buffer of 32 input-event records — enough for a burst of key events.
     *
     * @return the read buffer size
     */
    @Override
    public int readSize() {
        return LinuxInput.INPUT_EVENT_SIZE * 32;
    }

    /**
     * Splits the chunk into 24-byte records and feeds each key press to the
     * decoder.
     *
     * @param buffer the buffer just read
     * @param length the number of bytes read
     * @param sink the code receiver (already wired into the decoder)
     */
    @Override
    public void decode(byte[] buffer, int length, Consumer<String> sink) {
        int records = length / LinuxInput.INPUT_EVENT_SIZE;
        for (int i = 0; i < records; i++) {
            InputEvent event = InputEvent.parse(buffer, i * LinuxInput.INPUT_EVENT_SIZE);
            if (event.isKeyPress()) {
                decoder.onKeyPress(event.code());
            }
        }
    }
}
