package com.intermarche.pos.ui.hardware.terminal;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VerifoneTransport}, the protocol-independent socket
 * plumbing. No real socket is ever opened: the {@code new Socket()} in
 * {@code isReachable()} is intercepted with {@code Mockito.mockConstruction}
 * so the connect call is a mocked no-op (both the success and the
 * IOException arm), and {@code exchange} fails before any I/O.
 */
class VerifoneTransportTest {

    /**
     * {@code isReachable()} returns true when the constructed socket connects
     * without throwing: the mocked construction leaves {@code connect} a
     * no-op, so the try-block reaches {@code return true} (L49-51).
     */
    @Test
    void isReachableReturnsTrueWhenConnectSucceeds() {
        try (MockedConstruction<Socket> mocked = Mockito.mockConstruction(Socket.class)) {
            VerifoneTransport transport = new VerifoneTransport("127.0.0.1", 1, 10);
            assertTrue(transport.isReachable());
            assertEquals(1, mocked.constructed().size());
        }
    }

    /**
     * {@code isReachable()} returns false when the constructed socket's
     * {@code connect} throws an IOException: the catch arm is taken and
     * {@code return false} is reached (L52-53).
     */
    @Test
    void isReachableReturnsFalseWhenConnectThrows() {
        try (MockedConstruction<Socket> mocked = Mockito.mockConstruction(Socket.class,
                (mock, context) -> Mockito.doThrow(new IOException("refused"))
                        .when(mock).connect(ArgumentMatchers.any(), ArgumentMatchers.anyInt()))) {
            VerifoneTransport transport = new VerifoneTransport("127.0.0.1", 1, 10);
            assertFalse(transport.isReachable());
            assertEquals(1, mocked.constructed().size());
        }
    }

    /**
     * {@code exchange(String)} throws {@link UnsupportedOperationException}
     * before any I/O until the wire framing is specified (L70).
     */
    @Test
    void exchangeThrowsUntilProtocolIsSpecified() {
        VerifoneTransport transport = new VerifoneTransport("127.0.0.1", 1, 10);
        assertThrows(UnsupportedOperationException.class, () -> transport.exchange("{X}1"));
    }
}
