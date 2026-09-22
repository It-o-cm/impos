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

    /**
     * {@code usbKey} of a blank (non-null) phys is null — covers the
     * {@code phys.isBlank()} true leg of the {@code phys == null || phys.isBlank()}
     * guard, the leg the non-null non-blank cases leave uncovered.
     */
    @Test
    void usbKeyOfBlankPhysIsNull() {
        assertEquals(null, ScannerDiscovery.usbKey("   "));
    }

    /**
     * An evdev scanner whose {@code phys} file is absent is still discovered on
     * the EVDEV channel but claims no USB key — covers the {@code key == null}
     * (false) leg of the evdev USB-key guard, since {@code read} of the missing
     * phys returns null and {@code usbKey(null)} is null.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void evdevScannerWithoutPhysIsKeptWithNoUsbKey(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        File deviceDir = new File(input, "event3/device");
        deviceDir.mkdirs();
        Files.writeString(new File(deviceDir, "name").toPath(), "Zebra DS2208 Scanner\n");
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertEquals(ScannerDiscovery.Channel.EVDEV, found.get(0).channel());
        assertTrue(found.get(0).path().endsWith("/event3"));
    }

    /**
     * A hidraw scanner whose uevent carries no {@code HID_PHYS} line is kept —
     * covers the {@code key == null} (false) short-circuit leg of the hidraw
     * dedup guard, and the loop-exhaustion (key-not-found) leg of the uevent
     * field scan, since the {@code HID_PHYS} lookup walks every line to its end.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void hidrawScannerWithoutPhysIsKept(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        File deviceDir = new File(hidraw, "hidraw2/device");
        deviceDir.mkdirs();
        Files.writeString(new File(deviceDir, "uevent").toPath(),
                "HID_ID=0003:000005E0:00000890\nHID_NAME=Datalogic Handheld Scanner\n");
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertEquals(ScannerDiscovery.Channel.HIDRAW, found.get(0).channel());
        assertTrue(found.get(0).path().endsWith("/hidraw2"));
    }

    /**
     * A hidraw node whose {@code uevent} file is absent is ignored — covers the
     * {@code content == null} (true) leg of the uevent field reader, since
     * {@code read} of the missing file returns null and the name resolves to
     * null, which does not match.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void hidrawNodeWithoutUeventIsIgnored(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        new File(hidraw, "hidraw9/device").mkdirs();
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertTrue(found.isEmpty());
    }

    /** A key bitmask carrying the digits and Enter: the typing interface. */
    private static final String TYPING_KEYS = "1000000000000007 ff9f207ac14057ff febeffdfffefffff fffffffffffffffe";

    /** A key bitmask of a consumer-control interface: high codes only. */
    private static final String CONSUMER_KEYS = "1 c000 100000 0";

    /**
     * Builds a sysfs evdev node carrying a key-capability bitmask.
     *
     * @param inputDir the input class directory
     * @param node the node name (eventN)
     * @param name the device name
     * @param phys the device phys
     * @param keyBitmap the {@code capabilities/key} content
     * @throws Exception on file-creation failure
     */
    private void evdevNode(File inputDir, String node, String name, String phys, String keyBitmap)
            throws Exception {
        evdevNode(inputDir, node, name, phys);
        File capabilities = new File(inputDir, node + "/device/capabilities");
        capabilities.mkdirs();
        Files.writeString(new File(capabilities, "key").toPath(), keyBitmap + "\n");
    }

    /**
     * The case that produced the double scans: ONE scanner in keyboard mode
     * publishing three interfaces under the same USB device. Only the one that
     * can type a barcode is read, so the code arrives once.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void oneScannerWithThreeInterfacesIsReadOnce(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event3", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input0",
                TYPING_KEYS);
        evdevNode(input, "event4", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input1",
                CONSUMER_KEYS);
        evdevNode(input, "event5", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input2",
                CONSUMER_KEYS);
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertTrue(found.get(0).path().endsWith("/event3"));
    }

    /**
     * The typing interface is elected whatever its position in the group: here
     * it is the LAST node, so a rule of "keep the first" would read the wrong
     * one and the lane would go silent.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void theTypingInterfaceIsElectedEvenWhenItComesLast(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event3", "Zebra DS2208 Scanner", "usb-0000:00:14.0-1/input0",
                CONSUMER_KEYS);
        evdevNode(input, "event4", "Zebra DS2208 Scanner", "usb-0000:00:14.0-1/input1",
                TYPING_KEYS);
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertTrue(found.get(0).path().endsWith("/event4"));
    }

    /**
     * When NO interface advertises the typing keys — capabilities unreadable on
     * an unusual model — the lowest-numbered node is read rather than none:
     * the fallback leg of the election.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void withoutCapabilitiesTheLowestNumberedInterfaceIsRead(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event7", "Newland Scanner", "usb-0000:00:14.0-2/input0");
        evdevNode(input, "event8", "Newland Scanner", "usb-0000:00:14.0-2/input1");
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertTrue(found.get(0).path().endsWith("/event7"));
    }

    /**
     * The fallback orders by NODE NUMBER, not by name: a string sort would put
     * {@code event10} before {@code event2} and read the wrong interface.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void theFallbackOrdersNodesNumerically(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event10", "Opticon Scanner", "usb-0000:00:14.0-4/input1");
        evdevNode(input, "event2", "Opticon Scanner", "usb-0000:00:14.0-4/input0");
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertTrue(found.get(0).path().endsWith("/event2"));
    }

    /**
     * An interface whose bitmask carries the digits but NOT Enter fails the
     * test — the partial-mask leg: the mask is all-or-nothing, a device that
     * cannot end a barcode does not type one.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void digitsWithoutEnterDoNotTypeABarcode(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event3", "Honeywell Scanner", "usb-0000:00:14.0-5/input0", "ffc");
        evdevNode(input, "event4", "Honeywell Scanner", "usb-0000:00:14.0-5/input1", TYPING_KEYS);
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertTrue(found.get(0).path().endsWith("/event4"));
    }

    /**
     * An unparsable bitmask is treated as "does not type" instead of faulting
     * the whole discovery — the {@code NumberFormatException} leg.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void anUnparsableBitmaskIsNotTyping(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event3", "Datalogic Scanner", "usb-0000:00:14.0-6/input0", "zzzz");
        evdevNode(input, "event4", "Datalogic Scanner", "usb-0000:00:14.0-6/input1", TYPING_KEYS);
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertTrue(found.get(0).path().endsWith("/event4"));
    }

    /**
     * A blank bitmask file is treated as "does not type" — the
     * {@code isBlank} leg of the guard, distinct from the absent-file leg.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void aBlankBitmaskIsNotTyping(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event3", "Datalogic Scanner", "usb-0000:00:14.0-7/input0", "   ");
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertTrue(found.get(0).path().endsWith("/event3"));
    }

    /**
     * TWO scanners on two USB ports stay two devices: the grouping is per
     * physical device, it never merges distinct scanners.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void twoScannersOnTwoPortsAreBothRead(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event3", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input0",
                TYPING_KEYS);
        evdevNode(input, "event4", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input1",
                CONSUMER_KEYS);
        evdevNode(input, "event9", "Zebra DS2208 Scanner", "usb-0000:00:14.0-2/input0",
                TYPING_KEYS);
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(2, found.size());
        assertTrue(found.get(0).path().endsWith("/event3"));
        assertTrue(found.get(1).path().endsWith("/event9"));
    }

    /**
     * The hidraw twin is still deduped against an evdev interface that was
     * NOT elected: the USB key is claimed by the device, not by the surviving
     * node, so dropping an interface never reopens the other channel.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void theHidrawTwinIsDedupedAgainstADroppedInterface(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        evdevNode(input, "event3", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input0",
                TYPING_KEYS);
        evdevNode(input, "event4", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input1",
                CONSUMER_KEYS);
        hidrawNode(hidraw, "hidraw5", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input1");
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertEquals(ScannerDiscovery.Channel.EVDEV, found.get(0).channel());
        assertTrue(found.get(0).path().endsWith("/event3"));
    }

    /**
     * An interface with no {@code phys} is kept beside a grouped device rather
     * than folded into it — the {@code key == null} leg, with a group present
     * so both arms of the branch run in one discovery.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void anInterfaceWithoutPhysIsKeptBesideAGroupedDevice(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        File orphan = new File(input, "event1/device");
        orphan.mkdirs();
        Files.writeString(new File(orphan, "name").toPath(), "Opticon Scanner\n");
        evdevNode(input, "event3", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input0",
                TYPING_KEYS);
        evdevNode(input, "event4", "Symbol Bar Code Scanner", "usb-0000:00:14.0-1/input1",
                CONSUMER_KEYS);
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(2, found.size());
        assertTrue(found.get(0).path().endsWith("/event1"));
        assertTrue(found.get(1).path().endsWith("/event3"));
    }

    /** The report descriptor of the label interface read off the till: INPUT of 64 bytes. */
    private static final String LABEL_DESCRIPTOR =
            "06 45 ff 0a 00 4a a1 01 0a 01 4a 75 08 95 0b 15 00 26 ff 00 91 02 0a 02 4a 95 40 81 02 c0";

    /** The report descriptor of its secondary interface: INPUT of 8 bytes. */
    private static final String SECONDARY_DESCRIPTOR =
            "06 45 ff 0a 00 a0 a1 01 75 08 96 07 01 15 00 26 ff 00 0a 01 a0 91 02 75 08 95 08 0a 02 a0 81 02 c0";

    /**
     * Turns a hex dump into the bytes a report descriptor file holds.
     *
     * @param hex the space-separated hex bytes
     * @return the bytes
     */
    private static byte[] descriptorBytes(String hex) {
        String[] tokens = hex.trim().split("\\s+");
        byte[] bytes = new byte[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            bytes[i] = (byte) Integer.parseInt(tokens[i], 16);
        }
        return bytes;
    }

    /**
     * Builds a sysfs hidraw node carrying a report descriptor.
     *
     * @param hidrawDir the hidraw class directory
     * @param node the node name (hidrawN)
     * @param name the HID_NAME
     * @param phys the HID_PHYS
     * @param descriptor the report descriptor as a hex dump, or null for none
     * @throws Exception on file-creation failure
     */
    private void hidrawNode(File hidrawDir, String node, String name, String phys, String descriptor)
            throws Exception {
        hidrawNode(hidrawDir, node, name, phys);
        if (descriptor != null) {
            Files.write(new File(hidrawDir, node + "/device/report_descriptor").toPath(),
                    descriptorBytes(descriptor));
        }
    }

    /**
     * The real case of the reference till: ONE scanner in HID mode publishing
     * two hidraw interfaces under the same USB device. Only the one carrying
     * the labels — the largest declared INPUT report — is read, so a barcode
     * arrives once.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void oneHidScannerWithTwoInterfacesIsReadOnce(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        hidrawNode(hidraw, "hidraw2", "Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-5.1/input0", LABEL_DESCRIPTOR);
        hidrawNode(hidraw, "hidraw3", "Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-5.1/input1", SECONDARY_DESCRIPTOR);
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertTrue(found.get(0).path().endsWith("/hidraw2"));
    }

    /**
     * The label interface is elected whatever its position: here the largest
     * INPUT report is on the SECOND node, so a rule of "keep the first" would
     * read the wrong one and the lane would go silent.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void theLargestInputInterfaceIsElectedEvenWhenItComesLast(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        hidrawNode(hidraw, "hidraw2", "Zebra DS2208 Scanner",
                "usb-0000:00:14.0-6/input0", SECONDARY_DESCRIPTOR);
        hidrawNode(hidraw, "hidraw3", "Zebra DS2208 Scanner",
                "usb-0000:00:14.0-6/input1", LABEL_DESCRIPTOR);
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertTrue(found.get(0).path().endsWith("/hidraw3"));
    }

    /**
     * Without any readable descriptor both interfaces measure zero and the
     * FIRST is read — the tie-breaking arm, which is also what happens on a
     * sysfs that does not expose the descriptor at all.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void withoutDescriptorsTheFirstHidInterfaceIsRead(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        hidrawNode(hidraw, "hidraw4", "Newland Scanner", "usb-0000:00:14.0-7/input1");
        hidrawNode(hidraw, "hidraw2", "Newland Scanner", "usb-0000:00:14.0-7/input0");
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(1, found.size());
        assertTrue(found.get(0).path().endsWith("/hidraw2"));
    }

    /**
     * Two scanners on two USB ports stay two devices on the hidraw channel
     * too: the grouping is per physical device and never merges them.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void twoHidScannersOnTwoPortsAreBothRead(@TempDir File root) throws Exception {
        File input = new File(root, "input");
        File hidraw = new File(root, "hidraw");
        File dev = new File(root, "dev");
        dev.mkdirs();
        hidrawNode(hidraw, "hidraw2", "Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-5.1/input0", LABEL_DESCRIPTOR);
        hidrawNode(hidraw, "hidraw3", "Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-5.1/input1", SECONDARY_DESCRIPTOR);
        hidrawNode(hidraw, "hidraw8", "Zebra DS2208 Scanner",
                "usb-0000:00:14.0-6/input0", LABEL_DESCRIPTOR);
        List<ScannerDiscovery.DiscoveredDevice> found =
                new ScannerDiscovery(input, hidraw, dev, PATTERNS).discover();
        assertEquals(2, found.size());
        assertTrue(found.get(0).path().endsWith("/hidraw2"));
        assertTrue(found.get(1).path().endsWith("/hidraw8"));
    }

    /**
     * The measured INPUT size is the one the descriptor declares, read item
     * by item: 64 bytes for the label interface, 8 for the secondary one.
     * An unreadable descriptor measures zero rather than faulting.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void theInputReportSizeIsReadFromTheDescriptor(@TempDir File root) throws Exception {
        File hidraw = new File(root, "hidraw");
        hidrawNode(hidraw, "hidraw2", "Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-5.1/input0", LABEL_DESCRIPTOR);
        hidrawNode(hidraw, "hidraw3", "Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-5.1/input1", SECONDARY_DESCRIPTOR);
        hidrawNode(hidraw, "hidraw4", "Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-5.1/input2");
        ScannerDiscovery discovery = new ScannerDiscovery(
                new File(root, "input"), hidraw, new File(root, "dev"), PATTERNS);
        assertEquals(64, discovery.inputReportBytes(new File(hidraw, "hidraw2/device")));
        assertEquals(8, discovery.inputReportBytes(new File(hidraw, "hidraw3/device")));
        assertEquals(0, discovery.inputReportBytes(new File(hidraw, "hidraw4/device")));
    }

    /**
     * A descriptor carrying a LONG item is walked past it rather than
     * misread: the long-item prefix announces its own length, and the INPUT
     * that follows is still measured.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void aLongItemIsSkippedByItsDeclaredLength(@TempDir File root) throws Exception {
        File hidraw = new File(root, "hidraw");
        hidrawNode(hidraw, "hidraw2", "Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-5.1/input0",
                "fe 02 aa bb cc 75 08 95 20 81 02 c0");
        ScannerDiscovery discovery = new ScannerDiscovery(
                new File(root, "input"), hidraw, new File(root, "dev"), PATTERNS);
        assertEquals(32, discovery.inputReportBytes(new File(hidraw, "hidraw2/device")));
    }

    /**
     * A descriptor truncated right after a long-item prefix ends the walk
     * instead of reading past the array — the guard no real descriptor takes.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void aTruncatedLongItemEndsTheWalk(@TempDir File root) throws Exception {
        File hidraw = new File(root, "hidraw");
        hidrawNode(hidraw, "hidraw2", "Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-5.1/input0", "75 08 95 10 81 02 fe");
        ScannerDiscovery discovery = new ScannerDiscovery(
                new File(root, "input"), hidraw, new File(root, "dev"), PATTERNS);
        assertEquals(16, discovery.inputReportBytes(new File(hidraw, "hidraw2/device")));
    }

    /**
     * An item declaring a four-byte payload (size code 3) is walked past
     * correctly — the {@code dataSize == 3} arm of the prefix decoding.
     *
     * @param root a temporary directory
     * @throws Exception on file-creation failure
     */
    @Test
    void aFourByteItemIsWalkedPast(@TempDir File root) throws Exception {
        File hidraw = new File(root, "hidraw");
        hidrawNode(hidraw, "hidraw2", "Symbol Bar Code Scanner::EA",
                "usb-0000:00:14.0-5.1/input0", "27 ff ff 00 00 75 08 95 04 81 02 c0");
        ScannerDiscovery discovery = new ScannerDiscovery(
                new File(root, "input"), hidraw, new File(root, "dev"), PATTERNS);
        assertEquals(4, discovery.inputReportBytes(new File(hidraw, "hidraw2/device")));
    }

    /**
     * {@code nodeNumber} reads the trailing number, and answers
     * {@link Integer#MAX_VALUE} for a name that ends with no digit — the leg
     * no real sysfs node takes, so no ordering rule silently depends on it.
     */
    @Test
    void nodeNumberReadsTheTrailingNumber() {
        assertEquals(2, ScannerDiscovery.nodeNumber("event2"));
        assertEquals(10, ScannerDiscovery.nodeNumber("event10"));
        assertEquals(Integer.MAX_VALUE, ScannerDiscovery.nodeNumber("event"));
    }

    /**
     * A trailing number too large for an int falls back to
     * {@link Integer#MAX_VALUE} instead of faulting the sort — the
     * {@code NumberFormatException} leg of {@code nodeNumber}.
     */
    @Test
    void nodeNumberOfAnOverflowingNameIsLast() {
        assertEquals(Integer.MAX_VALUE, ScannerDiscovery.nodeNumber("event99999999999999"));
    }
}
