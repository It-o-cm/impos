package com.intermarche.pos.ui.hardware.scanner;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * Minimal JNA binding to the C library for the Linux input layer (evdev):
 * open the device node, take the exclusive grab, read raw input events. Kept
 * to the three calls the scanner reader needs — this is a driver-side
 * boundary, not a general libc facade.
 */
public interface LinuxInput extends Library {

    /** The bound C library instance. */
    LinuxInput C = Native.load("c", LinuxInput.class);

    /** {@code open(2)} flag: read-only. */
    int O_RDONLY = 0;

    /**
     * {@code EVIOCGRAB} ioctl request: exclusive grab of an event device —
     * the kernel stops routing its events anywhere else (console, X, the
     * kiosk browser), so a scanned code is never ALSO typed into whatever
     * has the focus. Value is {@code _IOW('E', 0x90, int)}.
     */
    long EVIOCGRAB = 0x40044590L;

    /** Size in bytes of {@code struct input_event} on 64-bit Linux. */
    int INPUT_EVENT_SIZE = 24;

    /** Event type {@code EV_KEY}: a key press or release. */
    short EV_KEY = 1;

    /**
     * Opens a file.
     *
     * @param path the file path
     * @param flags the open flags
     * @return the file descriptor, or -1 on failure
     */
    int open(String path, int flags);

    /**
     * Performs an ioctl carrying an int argument.
     *
     * @param fd the file descriptor
     * @param request the ioctl request
     * @param arg the int argument
     * @return 0 on success, -1 on failure
     */
    int ioctl(int fd, long request, int arg);

    /**
     * Reads bytes from a file descriptor.
     *
     * @param fd the file descriptor
     * @param buffer the destination buffer
     * @param count the maximum number of bytes to read
     * @return the number of bytes read, 0 at end of stream, -1 on failure
     */
    int read(int fd, byte[] buffer, int count);

    /**
     * Closes a file descriptor.
     *
     * @param fd the file descriptor
     * @return 0 on success, -1 on failure
     */
    int close(int fd);
}
