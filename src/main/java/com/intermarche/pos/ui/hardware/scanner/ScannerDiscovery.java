package com.intermarche.pos.ui.hardware.scanner;

import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>
 * The SAME rule applies WITHIN the evdev channel, and it is what stops a code
 * from being read twice or three times. One scanner in keyboard mode publishes
 * several input interfaces — the keyboard itself, a consumer-control one, a
 * system-control one — all carrying the scanner's advertised name, so all
 * matching the signature. Opening every one of them means one reader thread
 * per interface and the same barcode pushed as many times: the double and
 * triple scans. Interfaces are therefore grouped by their shared USB key, and
 * exactly ONE survives per physical device: the interface that can actually
 * TYPE a barcode — the one whose key capabilities carry the digits and Enter —
 * or, if none advertises them, the lowest-numbered node, so an unusual model
 * is never silently dropped.
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
     * The key bits an interface must advertise to be the one that types a
     * barcode: {@code KEY_1}..{@code KEY_0} (2 to 11) and {@code KEY_ENTER}
     * (28). A consumer-control or system-control interface of the same
     * scanner carries only high key codes and fails this test.
     */
    private static final long TYPING_KEYS_MASK = 0x1000_0FFCL;

    /** Prefix of a HID long item, whose length byte follows it. */
    private static final int LONG_ITEM_PREFIX = 0xFE;

    /** HID item type: main. */
    private static final int TYPE_MAIN = 0;

    /** HID item type: global. */
    private static final int TYPE_GLOBAL = 1;

    /** HID main-item tag: INPUT. */
    private static final int TAG_INPUT = 0x8;

    /** HID global-item tag: REPORT SIZE. */
    private static final int TAG_REPORT_SIZE = 0x7;

    /** HID global-item tag: REPORT COUNT. */
    private static final int TAG_REPORT_COUNT = 0x9;

    /**
     * One evdev interface of one physical device, as discovery sees it.
     *
     * @param node the node name (eventN)
     * @param name the advertised device name
     * @param types whether this interface advertises the typing keys
     */
    private record Candidate(String node, String name, boolean types) {
    }

    /**
     * One hidraw interface of one physical device, as discovery sees it.
     *
     * @param node the node name (hidrawN)
     * @param name the advertised device name
     * @param inputBytes the size of its declared INPUT report, in bytes
     */
    private record HidCandidate(String node, String name, int inputBytes) {
    }

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
     * @return the matching node names, in node-number order
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
        names.sort(Comparator.comparingInt(ScannerDiscovery::nodeNumber).thenComparing(n -> n));
        return names;
    }

    /**
     * The number a node name ends with, used to order the nodes as the kernel
     * numbers them. A plain string sort puts {@code event10} before
     * {@code event2}, and "the lowest-numbered interface" is the fallback that
     * decides which one of a scanner's interfaces is read.
     *
     * @param node the node name
     * @return the trailing number, or {@link Integer#MAX_VALUE} when the name
     *         ends with no digit
     */
    static int nodeNumber(String node) {
        int start = node.length();
        while (start > 0 && Character.isDigit(node.charAt(start - 1))) {
            start--;
        }
        if (start == node.length()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(node.substring(start));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Whether an evdev interface advertises the keys needed to type a barcode.
     * <p>
     * Read from {@code capabilities/key}, the sysfs bitmask of the key codes
     * the interface can emit: groups of 64 bits in hexadecimal, most
     * significant group first, so the LAST group holds codes 0 to 63 — where
     * the digits and Enter live. Still sysfs only, no native call.
     *
     * @param deviceDir the interface's sysfs device directory
     * @return true when the digits and Enter are all advertised
     */
    private boolean typesBarcodes(File deviceDir) {
        String bitmap = read(new File(deviceDir, "capabilities/key"));
        if (bitmap == null || bitmap.isBlank()) {
            return false;
        }
        String[] groups = bitmap.trim().split("\\s+");
        try {
            long low = Long.parseUnsignedLong(groups[groups.length - 1], 16);
            return (low & TYPING_KEYS_MASK) == TYPING_KEYS_MASK;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Picks the ONE interface of a physical scanner that is read.
     * <p>
     * The first that advertises the typing keys, in node order; failing that,
     * the first of the group — dropping a scanner whose capabilities are not
     * readable would be worse than reading the wrong interface, which at least
     * shows up as silence rather than as nothing discovered.
     *
     * @param interfaces the interfaces of one physical device, in node order
     * @return the elected interface
     */
    private Candidate elected(List<Candidate> interfaces) {
        for (Candidate candidate : interfaces) {
            if (candidate.types()) {
                return candidate;
            }
        }
        return interfaces.get(0);
    }

    /**
     * Discovers the evdev-channel scanners and records the USB keys they
     * occupy, so the hidraw pass can avoid their twins.
     *
     * @param found the list to append discovered devices to
     * @param evdevKeys the set to fill with the USB keys of evdev scanners
     */
    private void discoverEvdev(List<DiscoveredDevice> found, Set<String> evdevKeys) {
        Map<String, List<Candidate>> byUsbDevice = new LinkedHashMap<>();
        for (String node : nodes(inputClassDir, "event")) {
            File deviceDir = new File(inputClassDir, node + "/device");
            String name = read(new File(deviceDir, "name"));
            if (!matches(name)) {
                continue;
            }
            Candidate candidate = new Candidate(node, name, typesBarcodes(deviceDir));
            String key = usbKey(read(new File(deviceDir, "phys")));
            if (key == null) {
                // No phys, no way to tell whose interface this is: keep it
                // rather than group it with a stranger.
                keep(found, candidate);
                continue;
            }
            evdevKeys.add(key);
            byUsbDevice.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate);
        }
        for (Map.Entry<String, List<Candidate>> device : byUsbDevice.entrySet()) {
            List<Candidate> interfaces = device.getValue();
            Candidate read = elected(interfaces);
            if (interfaces.size() > 1) {
                LOGGER.infof("Douchette %s: %d interfaces sur %s, lecture sur %s seulement"
                                + " (les autres publieraient le meme code)",
                        read.name(), interfaces.size(), device.getKey(), read.node());
            }
            keep(found, read);
        }
    }

    /**
     * Records one elected evdev interface as a discovered device.
     *
     * @param found the list to append to
     * @param candidate the elected interface
     */
    private void keep(List<DiscoveredDevice> found, Candidate candidate) {
        String path = new File(devDir, candidate.node()).getAbsolutePath();
        LOGGER.infof("Douchette (clavier) detectee: %s (%s)", candidate.name(), path);
        found.add(new DiscoveredDevice(Channel.EVDEV, path, candidate.name()));
    }

    /**
     * Discovers the hidraw-channel scanners, skipping any whose USB device
     * already offers an evdev scanner node (keyboard mode wins).
     *
     * @param found the list to append discovered devices to
     * @param evdevKeys the USB keys already claimed on the evdev channel
     */
    private void discoverHidraw(List<DiscoveredDevice> found, Set<String> evdevKeys) {
        Map<String, List<HidCandidate>> byUsbDevice = new LinkedHashMap<>();
        for (String node : nodes(hidrawClassDir, "hidraw")) {
            File deviceDir = new File(hidrawClassDir, node + "/device");
            File uevent = new File(deviceDir, "uevent");
            String name = ueventField(uevent, "HID_NAME");
            if (!matches(name)) {
                continue;
            }
            String key = usbKey(ueventField(uevent, "HID_PHYS"));
            if (key != null && evdevKeys.contains(key)) {
                continue;
            }
            HidCandidate candidate = new HidCandidate(node, name, inputReportBytes(deviceDir));
            if (key == null) {
                // No phys, no way to tell whose interface this is: keep it
                // rather than group it with a stranger.
                keepHidraw(found, candidate);
                continue;
            }
            byUsbDevice.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate);
        }
        for (Map.Entry<String, List<HidCandidate>> device : byUsbDevice.entrySet()) {
            List<HidCandidate> interfaces = device.getValue();
            HidCandidate read = electedHidraw(interfaces);
            if (interfaces.size() > 1) {
                LOGGER.infof("Douchette %s: %d interfaces hidraw sur %s, lecture sur %s"
                                + " (rapport d'entree de %d octets, le plus grand)",
                        read.name(), interfaces.size(), device.getKey(), read.node(),
                        read.inputBytes());
            }
            keepHidraw(found, read);
        }
    }

    /**
     * Picks the ONE hidraw interface of a physical scanner that is read.
     * <p>
     * A scanner in HID mode publishes several interfaces under one USB device
     * — on the reference unit, one carrying the labels and one for its
     * secondary channel — and reading both means one reader thread per
     * interface and the same barcode pushed twice. The one that carries the
     * labels is the one with the LARGEST declared INPUT report: a label needs
     * tens of bytes where the secondary channel declares a handful. The
     * measure comes from the device's own report descriptor, so no vendor
     * usage page is hard-coded and a unit of another brand is read the same
     * way. Ties go to the lowest-numbered node, which is also what happens
     * when no descriptor can be read at all.
     *
     * @param interfaces the hidraw interfaces of one physical device, in node
     *                   order
     * @return the elected interface
     */
    private HidCandidate electedHidraw(List<HidCandidate> interfaces) {
        HidCandidate best = interfaces.get(0);
        for (HidCandidate candidate : interfaces) {
            if (candidate.inputBytes() > best.inputBytes()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Records one elected hidraw interface as a discovered device.
     *
     * @param found the list to append to
     * @param candidate the elected interface
     */
    private void keepHidraw(List<DiscoveredDevice> found, HidCandidate candidate) {
        String path = new File(devDir, candidate.node()).getAbsolutePath();
        LOGGER.infof("Douchette (hidraw) detectee: %s (%s)", candidate.name(), path);
        found.add(new DiscoveredDevice(Channel.HIDRAW, path, candidate.name()));
    }

    /**
     * The total number of bytes the interface declares as INPUT, read from its
     * HID report descriptor.
     * <p>
     * The descriptor is a sequence of items: a one-byte prefix carrying a tag,
     * a type and a data length, then that many data bytes. Only three items
     * matter here — the global REPORT SIZE and REPORT COUNT, and the main
     * INPUT that consumes them — so the walk keeps the two running values and
     * adds their product on every INPUT. Everything else is skipped by its
     * declared length, which is what makes the walk safe on a descriptor
     * carrying items this code knows nothing about.
     *
     * @param deviceDir the interface's sysfs device directory
     * @return the declared INPUT size in bytes, 0 when the descriptor cannot
     *         be read
     */
    int inputReportBytes(File deviceDir) {
        byte[] descriptor;
        try {
            descriptor = Files.readAllBytes(new File(deviceDir, "report_descriptor").toPath());
        } catch (IOException e) {
            return 0;
        }
        int reportSize = 0;
        int reportCount = 0;
        long inputBits = 0;
        int i = 0;
        while (i < descriptor.length) {
            int prefix = descriptor[i] & 0xFF;
            i++;
            if (prefix == LONG_ITEM_PREFIX) {
                if (i >= descriptor.length) {
                    break;
                }
                i += 2 + (descriptor[i] & 0xFF);
                continue;
            }
            int dataSize = prefix & 0x03;
            if (dataSize == 3) {
                dataSize = 4;
            }
            long value = 0;
            for (int b = 0; b < dataSize && i + b < descriptor.length; b++) {
                value |= (long) (descriptor[i + b] & 0xFF) << (8 * b);
            }
            i += dataSize;
            int type = (prefix >> 2) & 0x03;
            int tag = (prefix >> 4) & 0x0F;
            if (type == TYPE_GLOBAL && tag == TAG_REPORT_SIZE) {
                reportSize = (int) value;
            } else if (type == TYPE_GLOBAL && tag == TAG_REPORT_COUNT) {
                reportCount = (int) value;
            } else if (type == TYPE_MAIN && tag == TAG_INPUT) {
                inputBits += (long) reportSize * reportCount;
            }
        }
        return (int) (inputBits / 8);
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
