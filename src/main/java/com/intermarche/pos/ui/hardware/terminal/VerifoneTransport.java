package com.intermarche.pos.ui.hardware.terminal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP transport to the local Verifone monetique client. Owns the socket
 * plumbing — connection, timeouts, teardown — which is protocol-independent.
 * <p>
 * WHAT IS PENDING THE PROTOCOL SPECIFICATION: {@link #exchange(String)} —
 * the wire framing of a frame (length prefix vs STX/ETX + LRC checksum),
 * the acknowledgment discipline and whether the client pushes intermediate
 * status frames before the final response. Until the specification is
 * available it fails fast with a clear message rather than guessing a
 * framing that would desynchronize a real terminal.
 */
public class VerifoneTransport {

    /** Host of the local monetique client. */
    private final String host;

    /** Port of the local monetique client. */
    private final int port;

    /** Socket connect/read timeout in milliseconds. */
    private final int timeoutMs;

    /**
     * Creates a transport to the given monetique client endpoint.
     *
     * @param host the monetique client host (usually localhost)
     * @param port the monetique client port
     * @param timeoutMs the connect/read timeout in milliseconds
     */
    public VerifoneTransport(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Checks that the monetique client answers on its TCP port — the
     * protocol-independent part of a health probe.
     *
     * @return true when the TCP connection succeeds within the timeout
     */
    public boolean isReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Sends one request frame and returns the final response frame.
     *
     * @param requestFrame the encoded {@code {TAG}value} request
     * @return the raw response frame
     * @throws IOException never yet — the method fails before any I/O
     * @throws UnsupportedOperationException always, until the wire framing
     *         is known (protocol specification required)
     */
    public String exchange(String requestFrame) throws IOException {
        // TODO(spec Verifone): open the socket, apply the wire framing
        // (length prefix or STX/ETX + LRC), write requestFrame, read frames
        // until the final one, return it. Deliberately NOT guessed.
        throw new UnsupportedOperationException(
                "Verifone wire framing unknown - protocol specification required");
    }
}
