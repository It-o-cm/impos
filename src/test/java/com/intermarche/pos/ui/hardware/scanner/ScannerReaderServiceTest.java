package com.intermarche.pos.ui.hardware.scanner;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ScannerReaderService}: the boot-time enable guard, the
 * autodiscovery loop routing each channel to the right source, the per-device
 * open/read/close lifecycle driven through the real sources over real (fake)
 * device nodes, and the loopback push whose failures must never kill a reader
 * thread. Plain JUnit 5 + Mockito, no Quarkus boot; the loopback endpoint is a
 * throwaway in-process {@link HttpServer}, the device nodes are real temp files
 * opened through the production JNA binding, and the discovery collaborator is
 * intercepted with {@link Mockito#mockConstruction}.
 */
class ScannerReaderServiceTest {

    /** The default retail name signatures, mirroring the config default. */
    private static final List<String> PATTERNS = List.of("symbol", "zebra", "scanner");

    /**
     * Reads a private field of the service by reflection (the reader threads
     * and opened-device lists are private and have no accessor).
     *
     * @param service the service instance
     * @param name the field name
     * @return the field value
     * @throws Exception on reflection failure
     */
    private static Object field(ScannerReaderService service, String name) throws Exception {
        Field f = ScannerReaderService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(service);
    }

    /**
     * Invokes the private {@code readLoop} of the service by reflection so a
     * single deterministic iteration can be driven without the boot path.
     *
     * @param service the service instance
     * @param source the scanner source
     * @param fd the open file descriptor
     * @throws Exception on reflection failure
     */
    private static void readLoop(ScannerReaderService service, ScannerSource source, int fd) throws Exception {
        Method m = ScannerReaderService.class.getDeclaredMethod("readLoop", ScannerSource.class, int.class);
        m.setAccessible(true);
        m.invoke(service, source, fd);
    }

    /**
     * Builds a service wired to a given sink URL and the default patterns, in
     * the enabled state, so each test starts from an isolated instance.
     *
     * @param sinkUrl the loopback endpoint
     * @return the configured service
     */
    private static ScannerReaderService service(String sinkUrl) {
        ScannerReaderService service = new ScannerReaderService();
        service.enabled = true;
        service.namePatterns = PATTERNS;
        service.sinkUrl = sinkUrl;
        return service;
    }

    /**
     * A discovery double is installed for every {@code new ScannerDiscovery}
     * built inside {@code onStart}, returning the given devices from
     * {@code discover()}.
     *
     * @param devices the devices the intercepted discovery reports
     * @return the mocked-construction scope
     */
    private static MockedConstruction<ScannerDiscovery> discoveryReturning(
            List<ScannerDiscovery.DiscoveredDevice> devices) {
        return Mockito.mockConstruction(ScannerDiscovery.class,
                (mock, ctx) -> Mockito.when(mock.discover()).thenReturn(devices));
    }

    /**
     * The reader stays completely idle when disabled: no discovery, no reader
     * thread. Covers the {@code !enabled} TRUE arm of {@code onStart}.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void disabledReaderStartsNothing() throws Exception {
        ScannerReaderService service = service("http://127.0.0.1:1/nope");
        service.enabled = false;
        service.onStart(null);
        assertTrue(((List<?>) field(service, "readers")).isEmpty());
        assertTrue(((List<?>) field(service, "openDevices")).isEmpty());
    }

    /**
     * Enabled but with no scanner present: the discovery loop body is never
     * entered, no reader thread is started. Covers the {@code !enabled} FALSE
     * arm and the loop-not-entered arm.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void enabledWithNoDeviceStartsNoReader() throws Exception {
        ScannerReaderService service = service("http://127.0.0.1:1/nope");
        try (MockedConstruction<ScannerDiscovery> ignored = discoveryReturning(List.of())) {
            service.onStart(null);
        }
        assertTrue(((List<?>) field(service, "readers")).isEmpty());
        assertTrue(((List<?>) field(service, "openDevices")).isEmpty());
    }

    /**
     * A discovered EVDEV scanner whose grab cannot be taken (the node does not
     * exist, so the native open fails) is skipped: the loop body runs, the
     * source is built for the EVDEV channel, its open returns negative and the
     * reader is not registered. Covers the loop-entered arm, the {@code EVDEV}
     * switch case and the {@code fd < 0} TRUE arm of {@code startReader}.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void evdevDeviceThatCannotOpenIsSkipped() throws Exception {
        ScannerReaderService service = service("http://127.0.0.1:1/nope");
        ScannerDiscovery.DiscoveredDevice device = new ScannerDiscovery.DiscoveredDevice(
                ScannerDiscovery.Channel.EVDEV, "/dev/does-not-exist-scanner-evdev", "Symbol Scanner");
        try (MockedConstruction<ScannerDiscovery> ignored = discoveryReturning(List.of(device))) {
            service.onStart(null);
        }
        assertTrue(((List<?>) field(service, "readers")).isEmpty());
        assertTrue(((List<?>) field(service, "openDevices")).isEmpty());
    }

    /**
     * A discovered HIDRAW scanner backed by a real readable node is opened, a
     * reader thread reads its bytes once (decoded but not a valid barcode, so
     * nothing is pushed), then hits end-of-file and ends; the device stays
     * registered for shutdown release. Covers the {@code HIDRAW} switch case,
     * the {@code fd < 0} FALSE arm, the {@code while(running)} TRUE arm, both
     * arms of {@code read <= 0} and the inner {@code if(running)} TRUE arm; the
     * closing {@code stop()} then covers the {@code stop} loop-entered arm.
     *
     * @param tmp a temporary directory
     * @throws Exception on reflection or file failure
     */
    @Test
    void hidrawDeviceIsOpenedReadOnceAndReleasedOnStop(@TempDir Path tmp) throws Exception {
        Path node = tmp.resolve("hidraw-node");
        Files.write(node, new byte[8]);
        ScannerReaderService service = service("http://127.0.0.1:1/nope");
        ScannerDiscovery.DiscoveredDevice device = new ScannerDiscovery.DiscoveredDevice(
                ScannerDiscovery.Channel.HIDRAW, node.toString(), "Zebra Barcode");
        try (MockedConstruction<ScannerDiscovery> ignored = discoveryReturning(List.of(device))) {
            service.onStart(null);
        }
        @SuppressWarnings("unchecked")
        List<Thread> readers = (List<Thread>) field(service, "readers");
        assertEquals(1, readers.size());
        readers.get(0).join(5000);
        assertFalse(readers.get(0).isAlive());
        assertEquals(1, ((List<?>) field(service, "openDevices")).size());
        service.stop();
        assertTrue(((List<?>) field(service, "openDevices")).isEmpty());
        assertTrue(((List<?>) field(service, "readers")).isEmpty());
    }

    /**
     * The read loop exits at once when the running flag is already cleared, so
     * a stopped reader never touches the descriptor. Covers the
     * {@code while(running)} FALSE arm of {@code readLoop}.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void readLoopExitsImmediatelyWhenNotRunning() throws Exception {
        ScannerReaderService service = service("http://127.0.0.1:1/nope");
        service.stop();
        FakeSource source = new FakeSource();
        readLoop(service, source, 999);
        assertEquals(0, source.decodeCalls);
    }

    /**
     * Whether a reader thread is currently blocked inside the native read of
     * {@code readLoop} (its stack shows {@code readLoop} above a JNA frame), so
     * the test can flip {@code running} exactly while the loop is parked past
     * its {@code while(running)} check — no sleep, a pure spin on the state.
     *
     * @param thread the reader thread
     * @return true when the thread is inside the native read
     */
    private static boolean isBlockedInNativeRead(Thread thread) {
        boolean sawReadLoop = false;
        boolean sawNative = false;
        for (StackTraceElement e : thread.getStackTrace()) {
            if (e.getClassName().equals(ScannerReaderService.class.getName())
                    && e.getMethodName().equals("readLoop")) {
                sawReadLoop = true;
            }
            if (e.getClassName().startsWith("com.sun.jna")) {
                sawNative = true;
            }
        }
        return sawReadLoop && sawNative;
    }

    /**
     * The shutdown race: a reader parked in a blocking read wakes with a
     * non-positive result AFTER {@code running} has been cleared, and returns
     * silently without logging the end-of-read warning. The reader is parked on
     * an empty FIFO opened read-write (so the read blocks), {@code running} is
     * cleared while it is parked, then the descriptor is closed to wake the
     * read with {@code -1}. Covers the inner {@code if(running)} FALSE arm of
     * {@code readLoop}.
     *
     * @param tmp a temporary directory
     * @throws Exception on reflection, process or file failure
     */
    @Test
    void readLoopEndsSilentlyWhenStoppedDuringRead(@TempDir Path tmp) throws Exception {
        Path fifo = tmp.resolve("scanner-fifo");
        assertEquals(0, new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start().waitFor());
        AtomicReference<Integer> writeFd = new AtomicReference<>();
        Thread opener = new Thread(() -> writeFd.set(LinuxInput.C.open(fifo.toString(), 1)), "test-fifo-writer");
        opener.setDaemon(true);
        opener.start();
        int readFd = LinuxInput.C.open(fifo.toString(), LinuxInput.O_RDONLY);
        opener.join(5000);
        assertTrue(readFd >= 0);
        assertTrue(writeFd.get() != null && writeFd.get() >= 0);
        ScannerReaderService service = service("http://127.0.0.1:1/nope");
        FakeSource source = new FakeSource();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                readLoop(service, source, readFd);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "test-scanner-reader");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!isBlockedInNativeRead(reader) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(isBlockedInNativeRead(reader));
        service.stop();
        LinuxInput.C.close(writeFd.get());
        reader.join(5000);
        LinuxInput.C.close(readFd);
        assertFalse(reader.isAlive());
        assertEquals(null, failure.get());
        assertEquals(0, source.decodeCalls);
    }

    /**
     * {@code stop} on a reader that opened nothing simply clears the running
     * flag and leaves the empty device list untouched. Covers the {@code stop}
     * loop-not-entered arm.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void stopWithNoOpenedDevicesIsANoOp() throws Exception {
        ScannerReaderService service = service("http://127.0.0.1:1/nope");
        service.stop();
        assertEquals(Boolean.FALSE, field(service, "running"));
        assertTrue(((List<?>) field(service, "openDevices")).isEmpty());
    }

    /**
     * A code pushed to a reachable loopback endpoint is delivered verbatim and
     * the send returns without throwing. Covers the success path of
     * {@code pushCode} (the catch clause is not entered).
     *
     * @throws Exception on server failure
     */
    @Test
    void pushCodeDeliversToReachableSink() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> received = new AtomicReference<>();
        server.createContext("/api/pos/scan", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            ScannerReaderService service = service(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/pos/scan");
            service.pushCode("3178530403022");
            assertEquals("3178530403022", received.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * A push to an unreachable endpoint is swallowed, never propagated to the
     * reader thread. Covers the catch clause and the {@code instanceof
     * InterruptedException} FALSE arm of {@code pushCode}.
     */
    @Test
    void pushCodeSwallowsDeliveryFailure() {
        ScannerReaderService service = service("http://127.0.0.1:1/api/pos/scan");
        service.pushCode("failing-code");
        assertFalse(Thread.currentThread().isInterrupted());
    }

    /**
     * When the sending thread is already interrupted, the push fails with an
     * {@link InterruptedException} which is swallowed but the interrupt status
     * is restored so the caller can observe the cancellation. Covers the
     * {@code instanceof InterruptedException} TRUE arm of {@code pushCode}.
     */
    @Test
    void pushCodeRestoresInterruptStatusOnInterruption() {
        ScannerReaderService service = service("http://127.0.0.1:1/api/pos/scan");
        Thread.currentThread().interrupt();
        service.pushCode("interrupted-code");
        boolean interrupted = Thread.interrupted();
        assertTrue(interrupted);
    }

    /**
     * A minimal in-memory {@link ScannerSource} used to drive the read loop
     * without any native descriptor: it records the decode calls it receives.
     */
    private static final class FakeSource implements ScannerSource {

        /** The number of {@code decode} invocations observed. */
        private int decodeCalls;

        /**
         * The node path this fake pretends to read.
         *
         * @return a fixed fake path
         */
        @Override
        public String path() {
            return "/dev/fake-scanner";
        }

        /**
         * Reports a positive descriptor so the caller treats the open as a
         * success.
         *
         * @return a fixed positive descriptor
         */
        @Override
        public int open() {
            return 999;
        }

        /**
         * Ignores the close request (no native descriptor is held).
         *
         * @param fd the descriptor to release
         */
        @Override
        public void close(int fd) {
        }

        /**
         * A small read buffer, enough for the loop to allocate.
         *
         * @return a fixed buffer size
         */
        @Override
        public int readSize() {
            return 8;
        }

        /**
         * Records that a decode was requested.
         *
         * @param buffer the buffer just read
         * @param length the number of bytes read
         * @param sink the code receiver
         */
        @Override
        public void decode(byte[] buffer, int length, Consumer<String> sink) {
            decodeCalls++;
        }
    }
}
