package com.intermarche.pos.ui.hardware.scanner;

import org.jboss.logging.Logger;

import java.util.function.Consumer;

/**
 * Scanner source for a device in HID mode (IBM hand-held / HID-POS / SNAPI):
 * it presents a {@code /dev/hidrawN} node and no keyboard event node, so it
 * is read as raw HID reports and decoded by a {@link HidBarcodeDecoder}. No
 * grab is needed — a hidraw node is already the exclusive raw channel to the
 * device, and its reports never reach the keyboard focus.
 */
public class HidrawScannerSource implements ScannerSource {

    /** The class logger. */
    private static final Logger LOG = Logger.getLogger(HidrawScannerSource.class);

    /** The hidraw device node path. */
    private final String path;

    /** The per-device report decoder. */
    private final HidBarcodeDecoder decoder;

    /**
     * Builds a source over one hidraw node, delivering codes to the sink.
     *
     * @param path the {@code /dev/hidrawN} path
     * @param sink the receiver of each completed code
     */
    public HidrawScannerSource(String path, Consumer<String> sink) {
        this.path = path;
        this.decoder = new HidBarcodeDecoder(sink);
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
     * Opens the hidraw node for reading (no grab — hidraw is already an
     * exclusive raw channel).
     *
     * @return the file descriptor, or -1 on failure
     */
    @Override
    public int open() {
        int fd = LinuxInput.C.open(path, LinuxInput.O_RDONLY);
        if (fd < 0) {
            LOG.errorf("Ouverture douchette (hidraw) impossible: %s", path);
        }
        return fd;
    }

    /**
     * Closes the descriptor.
     *
     * @param fd the file descriptor
     */
    @Override
    public void close(int fd) {
        LinuxInput.C.close(fd);
    }

    /**
     * A 256-byte buffer — larger than any single HID barcode report.
     *
     * @return the read buffer size
     */
    @Override
    public int readSize() {
        return 256;
    }

    /**
     * Feeds the report just read to the decoder.
     *
     * @param buffer the report bytes
     * @param length the number of bytes read
     * @param sink the code receiver (already wired into the decoder)
     */
    @Override
    public void decode(byte[] buffer, int length, Consumer<String> sink) {
        decoder.feed(buffer, length);
    }
}
