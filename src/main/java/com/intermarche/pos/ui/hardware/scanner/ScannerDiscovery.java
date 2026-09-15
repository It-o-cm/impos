package com.intermarche.pos.ui.hardware.scanner;

import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Autodiscovery of barcode scanners across BOTH input channels, so a fleet
 * needs no per-till scanner reconfiguration: a scanner in USB keyboard mode
 * (an evdev {@code eventN} node) and one in HID mode (a {@code hidrawN} node)
 * are both found and each routed to the right {@link ScannerSource}.
 * <p>
 * Discovery reads names from SYSFS — {@code /sys/class/input/<node>/device/name}
 * for evdev, the {@code HID_NAME} of {@code /sys/class/hidraw/<node>/device/uevent}
 * for hidraw — and keeps only the devices whose name matches a scanner
 * signature (configurable; matching by name, not "has keys", is what keeps
 * the grab off a genuine keyboard). No native call is made here.
 * <p>
 * A scanner in keyboard mode ALSO exposes a hidraw node; it must be read on
 * exactly ONE channel. So a hidraw scanner is dropped when the SAME physical
 * USB device already offers an evdev scanner node (keyboard mode wins),
 * matched by the shared USB port path of their {@code phys}. A scanner that
 * offers only hidraw (HID mode) has no evdev twin and is kept.
 */
public class ScannerDiscovery {

    /** The class logger. */
    private static final Logger LOGGER = Logger.getLogger(ScannerDiscovery.class);

    /** The input channel a discovered device is read on. */
    public enum Channel {
        /** USB keyboard mode: an evdev {@code eventN} node. */
        EVDEV,
        /** HID mode: a raw {@code hidrawN} node. */
        HIDRAW
    }

    /**
     * One discovered scanner: the channel it is read on, its device node path
     * and its advertised name.
     *
     * @param channel the input channel
     * @param path the device node path (under {@code /dev})
     * @param name the advertised device name
     */
    public record DiscoveredDevice(Channel channel, String path, String name) {
    }

    /** {@code /sys/class/input}. */
    private final File inputClassDir;

    /** {@code /sys/class/hidraw}. */
    private final File hidrawClassDir;

    /** {@code /dev}. */
    private final File devDir;

    /** The lower-cased name fragments that identify a scanner. */
    private final List<String> patterns;

    /**
     * Builds a discovery over the standard system directories.
     *
     * @param patterns the scanner name fragments (case-insensitive)
     */
    public ScannerDiscovery(List<String> patterns) {
        this(new File("/sys/class/input"), new File("/sys/class/hidraw"), new File("/dev"), patterns);
    }

    /**
     * Builds a discovery over explicit directories (test seam).
     *
     * @param inputClassDir the input class directory
     * @param hidrawClassDir the hidraw class directory
     * @param devDir the device node directory
     * @param patterns the scanner name fragments (case-insensitive)
     */
    ScannerDiscovery(File inputClassDir, File hidrawClassDir, File devDir, List<String> patterns) {
        this.inputClassDir = inputClassDir;
        this.hidrawClassDir = hidrawClassDir;
        this.devDir = devDir;
        this.patterns = patterns.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Whether a device name matches any configured scanner signature.
     *
     * @param deviceName the device's advertised name, or null
     * @return true when the name contains a configured fragment
     */
    boolean matches(String deviceName) {
        if (deviceName == null) {
            return false;
        }
        String lower = deviceName.toLowerCase(Locale.ROOT);
        return patterns.stream().anyMatch(lower::contains);
    }

    /**
     * Reduces a {@code phys} string to the USB device it belongs to, by
     * dropping the trailing interface segment: {@code usb-0000:00:14.0-3/input0}
     * becomes {@code usb-0000:00:14.0-3}, so the several interfaces of one
     * scanner (its keyboard node and its hidraw node) share a key. Returns
     * null for a null or interface-less phys.
     *
     * @param phys the raw phys string, or null
     * @return the USB device key, or null
     */
    static String usbKey(String phys) {
        if (phys == null || phys.isBlank()) {
            return null;
        }
        int slash = phys.indexOf('/');
        return slash < 0 ? phys : phys.substring(0, slash);
    }

    /**
     * Reads a sysfs file, returning its trimmed content or null when it is
     * absent or unreadable.
     *
     * @param file the file to read
     * @return the trimmed content, or null
     */
    private String read(File file) {
        try {
            return Files.readString(file.toPath()).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reads one field of a uevent file (lines {@code KEY=value}).
     *
     * @param uevent the uevent file
     * @param key the field name
     * @return the field value, or null when absent
     */
    private String ueventField(File uevent, String key) {
        String content = read(uevent);
        if (content == null) {
            return null;
        }
        for (String line : content.split("\n")) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    /**
     * Lists the child node names of a class directory that start with a
     * prefix, in stable name order.
     *
     * @param classDir the sysfs class directory
     * @param prefix the node-name prefix ({@code event} or {@code hidraw})
     * @return the matching node names, sorted
     */
    private List<String> nodes(File classDir, String prefix) {
        File[] children = classDir.listFiles((dir, name) -> name.startsWith(prefix));
        if (children == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (File child : children) {
            names.add(child.getName());
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Discovers the evdev-channel scanners and records the USB keys they
     * occupy, so the hidraw pass can avoid their twins.
     *
     * @param found the list to append discovered devices to
     * @param evdevKeys the set to fill with the USB keys of evdev scanners
     */
    private void discoverEvdev(List<DiscoveredDevice> found, Set<String> evdevKeys) {
        for (String node : nodes(inputClassDir, "event")) {
            File deviceDir = new File(inputClassDir, node + "/device");
            String name = read(new File(deviceDir, "name"));
            if (!matches(name)) {
                continue;
            }
            String key = usbKey(read(new File(deviceDir, "phys")));
            if (key != null) {
                evdevKeys.add(key);
            }
            String path = new File(devDir, node).getAbsolutePath();
            LOGGER.infof("Douchette (clavier) detectee: %s (%s)", name, path);
            found.add(new DiscoveredDevice(Channel.EVDEV, path, name));
        }
    }

    /**
     * Discovers the hidraw-channel scanners, skipping any whose USB device
     * already offers an evdev scanner node (keyboard mode wins).
     *
     * @param found the list to append discovered devices to
     * @param evdevKeys the USB keys already claimed on the evdev channel
     */
    private void discoverHidraw(List<DiscoveredDevice> found, Set<String> evdevKeys) {
        for (String node : nodes(hidrawClassDir, "hidraw")) {
            File uevent = new File(hidrawClassDir, node + "/device/uevent");
            String name = ueventField(uevent, "HID_NAME");
            if (!matches(name)) {
                continue;
            }
            String key = usbKey(ueventField(uevent, "HID_PHYS"));
            if (key != null && evdevKeys.contains(key)) {
                continue;
            }
            String path = new File(devDir, node).getAbsolutePath();
            LOGGER.infof("Douchette (hidraw) detectee: %s (%s)", name, path);
            found.add(new DiscoveredDevice(Channel.HIDRAW, path, name));
        }
    }

    /**
     * Discovers every scanner present now, across both channels, evdev first
     * then hidraw (so the dedup can consult the evdev keys).
     *
     * @return the discovered devices; empty when none matched
     */
    public List<DiscoveredDevice> discover() {
        List<DiscoveredDevice> found = new ArrayList<>();
        Set<String> evdevKeys = new HashSet<>();
        discoverEvdev(found, evdevKeys);
        discoverHidraw(found, evdevKeys);
        if (found.isEmpty()) {
            LOGGER.warn("Aucune douchette detectee a l'autodecouverte du materiel.");
        }
        return found;
    }
}
