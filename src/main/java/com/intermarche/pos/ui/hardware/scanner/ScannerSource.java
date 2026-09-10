package com.intermarche.pos.ui.hardware.scanner;

import java.util.function.Consumer;

/**
 * One readable scanner, in whatever mode it presents itself — the abstraction
 * that lets the reader handle a fleet where some tills run their scanner as a
 * USB keyboard (evdev) and others as a raw HID device (hidraw), with no
 * per-till reconfiguration. A source owns the decode state of its one device;
 * the reader owns the thread and the read buffer.
 */
public interface ScannerSource {

    /** The device node path this source reads. */
    String path();

    /**
     * Opens the device for reading, acquiring whatever hold the mode needs
     * (the exclusive grab for an evdev keyboard; a plain open for hidraw).
     *
     * @return the file descriptor, or a negative value on failure
     */
    int open();

    /**
     * Releases the device (dropping the grab where one was taken) and closes
     * the descriptor.
     *
     * @param fd the file descriptor returned by {@link #open()}
     */
    void close(int fd);

    /**
     * The read buffer size this source wants — one or more whole records of
     * its wire format.
     *
     * @return the buffer size in bytes
     */
    int readSize();

    /**
     * Decodes one {@code read(2)} chunk, emitting every completed barcode to
     * the sink. Called only from this source's reader thread, so the decode
     * state need not be thread-safe.
     *
     * @param buffer the buffer just read into
     * @param length the number of bytes actually read
     * @param sink the receiver of each completed code
     */
    void decode(byte[] buffer, int length, Consumer<String> sink);
}
