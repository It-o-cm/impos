package com.intermarche.pos.ui.hardware.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ScannerDiscovery}: the name matching that keeps the
 * grab off genuine keyboards, the USB-key reduction that dedups a scanner's
 * channels, and the two-channel discovery driven off a faked sysfs tree.
 */
class ScannerDiscoveryTest {

    /** The default retail signatures. */
    private static final List<String> PATTERNS =
            List.of("symbol", "zebra", "honeywell", "datalogic", "scanner");

    /** A discovery over the standard directories, for the pure-method tests. */
    private final ScannerDiscovery discovery =
            new ScannerDiscovery(new File("/sys/class/input"), new File("/sys/class/hidraw"),
                    new File("/dev"), PATTERNS);

    /**
     * A Symbol/Zebra scanner name matches (case-insensitive substring).
     */
    @Test
    void matchesSymbolScanner() {
        assertTrue(discovery.matches("Symbol Technologies, Inc, 2008 Symbol Bar Code Scanner::EA"));
    }

    /**
     * A brand named in another case still matches.
     */
    @Test
    void matchesRegardlessOfCase() {
        assertTrue(discovery.matches("HONEYWELL Voyager 1450g"));
    }

    /**
     * A genuine USB keyboard does NOT match — never grabbed.
     */
    @Test
    void doesNotMatchAKeyboard() {
        assertFalse(discovery.matches("CHICONY HP Basic USB Keyboard"));
    }

    /**
     * A null name (unreadable device) does not match.
     */
    @Test
    void doesNotMatchNull() {
        assertFalse(discovery.matches(null));
    }

    /**
     * {@code usbKey} drops the interface segment so the several interfaces of
     * one scanner share a key; a null or interface-less phys is handled.
     */
    @Test
    void usbKeyReducesToTheUsbDevice() {
        assertEquals("usb-0000:00:14.0-3", ScannerDiscovery.usbKey("usb-0000:00:14.0-3/input0"));
        assertEquals("usb-0000:00:14.0-3", ScannerDiscovery.usbKey("usb-0000:00:14.0-3"));
        assertEquals(null, ScannerDiscovery.usbKey(null));
    }

    /**
     * Builds a sysfs evdev node: {@code <input>/<node>/device/{name,phys}}.
     *
     * @param inputDir the input class directory
     * @param node the node name (eventN)
     * @param name the device name
     * @param phys the device phys
     * @throws Exception on file-creation failure
     */
    private void evdevNode(File inputDir, String node, String name, String phys) throws Exception {
        File deviceDir = new File(inputDir, node + "/device");
        deviceDir.mkdirs();
        Files.writeString(new File(deviceDir, "name").toPath(), name + "\n");
        Files.writeString(new File(deviceDir, "phys").toPath(), phys + "\n");
    }

    /**
     * Builds a sysfs hidraw node: {@code <hidraw>/<node>/device/uevent}.
     *
     * @param hidrawDir the hidraw class directory
     * @param node the node name (hidrawN)
     * @param name the HID_NAME
     * @param phys the HID_PHYS
     * @throws Exception on file-creation failure
     */
    private void hidrawNode(File hidrawDir, String node, String name, String phys) throws Exception {
        File deviceDir = new File(hidrawDir, node + "/device");
        deviceDir.mkdirs();
        Files.writeString(new File(deviceDir, "uevent").toPath(),
                "HID_ID=0003:000005E0:00000890\nHID_NAME=" + name + "\nHID_PHYS=" + phys + "\n");
    }

    /**
     * A scanner offering only a hidraw node (HID mode, no evdev twin) is
     * discovered on the HIDRAW channel; a genuine keyboard on both classes is
     * ignored on both.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void discoversHidOnlyScannerAndIgnoresKeyboard(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event0", "CHICONY HP Basic USB Keyboard", "usb-0000:00:14.0-3/input0");
        hidrawNode(hidraw, "hidraw0", "CHICONY HP Basic USB Keyboard", "usb-0000:00:14.0-3/input0");
        hidrawNode(hidraw, "hidraw5", "Symbol Technologies, Inc, 2008 Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-1/input0");
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertEquals(ScannerDiscovery.Channel.HIDRAW, found.get(0).channel());
        assertTrue(found.get(0).path().endsWith("/hidraw5"));
    }

    /**
     * A scanner in keyboard mode (evdev node AND a hidraw twin on the same USB
     * device) is read on the EVDEV channel only — the hidraw twin is deduped
     * away by the shared USB key.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void keyboardModeScannerIsReadOnceOnEvdev(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event7", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input0");
        hidrawNode(hidraw, "hidraw5", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input0");
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertEquals(ScannerDiscovery.Channel.EVDEV, found.get(0).channel());
        assertTrue(found.get(0).path().endsWith("/event7"));
    }

    /**
     * Absent class directories yield no devices rather than faulting.
     *
     * @param root a temporary directory
     */
    @Test
    void absentDirectoriesYieldNothing(@TempDir File root) {
        List<ScannerDiscovery.DiscoveredDevice> found = new ScannerDiscovery(
                new File(root, "nope-input"), new File(root, "nope-hidraw"),
                new File(root, "dev"), PATTERNS).discover();
        assertTrue(found.isEmpty());
    }
}
