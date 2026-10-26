package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.TestMessages;
import jakarta.mail.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests of {@link TicketMailService}: the administered form of the sent ticket
 * (LC-08-02-07/08), the printed content it carries (LC-08-02-02) and the
 * no-relay-configured gate.
 *
 * <p>The transport itself is not exercised — sending a letter is not a unit test — but
 * everything that DECIDES what leaves is.
 */
class TicketMailServiceTest {

    /** A printer that renders a fixed text instead of driving a bridge. */
    private static class FakePrinter extends TicketPrinterService {

        /** How many times a ticket was rendered on the spot. */
        private int renders;

        /** {@inheritDoc} */
        @Override
        public String renderTicket(Ticket ticket, boolean duplicata, int duplicataNumber) {
            renders++;
            return "TICKET RENDU";
        }
    }

    /** The service under test. */
    private TicketMailService service;

    /** The stand-in printer. */
    private FakePrinter printer;

    /** The administered form supplier, mocked. */
    private PosSettingsService posSettingsService;

    /** The journal, mocked. */
    private TechnicalEventService technicalEventService;

    /**
     * Wires a fresh service with no relay configured before each test; the SMTP fields
     * (from, user, password) that would otherwise be null under plain mvn test are
     * primed so the send path never trips on an unconfigured @ConfigProperty.
     */
    @BeforeEach
    void setUp() {
        service = new TicketMailService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        printer = new FakePrinter();
        posSettingsService = Mockito.mock(PosSettingsService.class);
        technicalEventService = Mockito.mock(TechnicalEventService.class);
        service.ticketPrinterService = printer;
        service.posSettingsService = posSettingsService;
        service.technicalEventService = technicalEventService;
        service.host = Optional.empty();
        service.from = "ticket@test.local";
        service.user = Optional.empty();
        service.password = Optional.empty();
    }

    /**
     * Builds a ticket carrying (or not) its frozen printed form.
     *
     * @param formatted the frozen text, or null
     * @return the ticket
     */
    private Ticket ticketWith(String formatted) {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-000417";
        ticket.formattedContent = formatted;
        return ticket;
    }

    // --------------------------------------------------
    // normalizeFormat
    // --------------------------------------------------

    /**
     * The two non-default forms are recognized.
     */
    @Test
    void normalizeKeepsTheKnownForms() {
        assertEquals(TicketMailService.FORMAT_ATTACHMENT,
                TicketMailService.normalizeFormat("ATTACHMENT"));
        assertEquals(TicketMailService.FORMAT_BOTH, TicketMailService.normalizeFormat("BOTH"));
    }

    /**
     * Case and surrounding blanks are ignored.
     */
    @Test
    void normalizeIgnoresCaseAndBlanks() {
        assertEquals(TicketMailService.FORMAT_BOTH, TicketMailService.normalizeFormat("  both "));
    }

    /**
     * The body form is recognized as itself.
     */
    @Test
    void normalizeKeepsTheBodyForm() {
        assertEquals(TicketMailService.FORMAT_BODY, TicketMailService.normalizeFormat("BODY"));
    }

    /**
     * A missing value falls back to the message body (null arm).
     */
    @Test
    void normalizeNullFallsBackToTheBody() {
        assertEquals(TicketMailService.FORMAT_BODY, TicketMailService.normalizeFormat(null));
    }

    /**
     * An unknown value falls back to the message body, which every mail client reads.
     */
    @Test
    void normalizeUnknownFallsBackToTheBody() {
        assertEquals(TicketMailService.FORMAT_BODY, TicketMailService.normalizeFormat("PDF"));
    }

    /**
     * A blank value falls back to the message body.
     */
    @Test
    void normalizeBlankFallsBackToTheBody() {
        assertEquals(TicketMailService.FORMAT_BODY, TicketMailService.normalizeFormat("   "));
    }

    // --------------------------------------------------
    // contentOf
    // --------------------------------------------------

    /**
     * The frozen printed form is what travels: it is what the customer's paper says.
     */
    @Test
    void contentOfPrefersTheFrozenForm() {
        assertEquals("TICKET GELE", service.contentOf(ticketWith("TICKET GELE")));
        assertEquals(0, printer.renders);
    }

    /**
     * A ticket closed before the field existed is rendered on the spot rather than
     * refused (null arm).
     */
    @Test
    void contentOfRendersWhenTheFormIsMissing() {
        assertEquals("TICKET RENDU", service.contentOf(ticketWith(null)));
        assertEquals(1, printer.renders);
    }

    /**
     * A blank frozen form is treated as missing (blank arm of the same guard).
     */
    @Test
    void contentOfRendersWhenTheFormIsBlank() {
        assertEquals("TICKET RENDU", service.contentOf(ticketWith("   ")));
        assertEquals(1, printer.renders);
    }

    // --------------------------------------------------
    // isConfigured
    // --------------------------------------------------

    /**
     * A register with no relay sends nothing (absent arm).
     */
    @Test
    void withoutARelayNothingIsConfigured() {
        service.host = Optional.empty();
        assertFalse(service.isConfigured());
    }

    /**
     * A blank relay host counts as none (blank arm of the same guard).
     */
    @Test
    void aBlankRelayCountsAsNone() {
        service.host = Optional.of("   ");
        assertFalse(service.isConfigured());
    }

    /**
     * A named relay is what lets a letter leave.
     */
    @Test
    void aNamedRelayIsConfigured() {
        service.host = Optional.of("smtp.magasin.local");
        assertTrue(service.isConfigured());
    }

    // --------------------------------------------------
    // send
    // --------------------------------------------------

    /**
     * Without a relay the send is journalled only and answers false: the
     * {@code !isConfigured()} guard is true, so the transport is never reached and the
     * journal still records that the customer asked for their receipt.
     */
    @Test
    void sendWithoutARelayIsJournalledOnlyAndReturnsFalse() {
        service.host = Optional.empty();
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BODY");
        assertFalse(service.send(ticketWith("TICKET GELE"), "client@example.com"));
        Mockito.verify(technicalEventService).log(TechnicalEvent.EventType.DIGITAL_TICKET_SENT,
                "C04-000417 -> client@example.com");
    }

    /**
     * With a relay and the body form the letter leaves and the send answers true: the
     * {@code !isConfigured()} guard is false, {@code FORMAT_BODY.equals(format)} is true
     * (body branch) and {@code account.isBlank()} is true (no-credentials session).
     */
    @Test
    void sendInBodyFormLetsTheLetterLeave() {
        service.host = Optional.of("smtp.magasin.local");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BODY");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            assertTrue(service.send(ticketWith("TICKET GELE"), "client@example.com"));
            transport.verify(() -> Transport.send(ArgumentMatchers.any()));
        }
    }

    /**
     * The attachment form takes the multipart branch: {@code FORMAT_BODY.equals(format)}
     * is false and the {@code FORMAT_BOTH.equals(format)} ternary is false, so the body
     * carries the "en pièce jointe" line rather than the ticket text.
     */
    @Test
    void sendAsAttachmentTakesTheMultipartBranch() {
        service.host = Optional.of("smtp.magasin.local");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("ATTACHMENT");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            assertTrue(service.send(ticketWith("TICKET GELE"), "client@example.com"));
            transport.verify(() -> Transport.send(ArgumentMatchers.any()));
        }
    }

    /**
     * The both form takes the multipart branch with the ticket text in the body: the
     * {@code FORMAT_BOTH.equals(format)} ternary is true.
     */
    @Test
    void sendAsBothPutsTheContentInTheBody() {
        service.host = Optional.of("smtp.magasin.local");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BOTH");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            assertTrue(service.send(ticketWith("TICKET GELE"), "client@example.com"));
            transport.verify(() -> Transport.send(ArgumentMatchers.any()));
        }
    }

    /**
     * A relay that refuses the letter is caught and answered with false: the transport
     * throws and the catch arm of the try/catch is taken, the sale otherwise undisturbed.
     */
    @Test
    void sendReturnsFalseWhenTheRelayRefuses() {
        service.host = Optional.of("smtp.magasin.local");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BODY");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            transport.when(() -> Transport.send(ArgumentMatchers.any()))
                    .thenThrow(new RuntimeException("relay down"));
            assertFalse(service.send(ticketWith("TICKET GELE"), "client@example.com"));
        }
    }

    /**
     * A configured SMTP account takes the authenticated-session branch:
     * {@code account.isBlank()} is false, so the session is built with an Authenticator.
     */
    @Test
    void sendWithCredentialsAuthenticatesTheSession() {
        service.host = Optional.of("smtp.magasin.local");
        service.user = Optional.of("smtp-user");
        service.password = Optional.of("secret");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BODY");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            assertTrue(service.send(ticketWith("TICKET GELE"), "client@example.com"));
            transport.verify(() -> Transport.send(ArgumentMatchers.any()));
        }
    }
    /** LC-08-02: SMTP authentication supplies the administered account and secret on demand without contacting a server. */
    @Test
    void smtpAuthenticatorSuppliesTheAdministeredCredentials() throws Exception {
        service.host = Optional.of("smtp.magasin.local");
        service.user = Optional.of("smtp-user");
        service.password = Optional.of("secret");
        var factory = TicketMailService.class.getDeclaredMethod("session");
        factory.setAccessible(true);
        var session = (jakarta.mail.Session) factory.invoke(service);
        var authentication = session.requestPasswordAuthentication(null, 25, "smtp", "prompt", "default-user");
        org.junit.jupiter.api.Assertions.assertEquals("smtp-user", authentication.getUserName());
        org.junit.jupiter.api.Assertions.assertEquals("secret", authentication.getPassword());
    }

    /** LC-08-02-06: a non-sale copy keeps its exact frozen body and administered MIME attachment. */
    @Test
    void sendsFrozenCustomerDocumentInBothForms() throws Exception {
        service.host = Optional.of("localhost");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BOTH");
        var document = new com.intermarche.pos.domain.sale.CustomerDocument();
        document.reference = "AV-42"; document.content = "CUSTOMER COPY\r\n42,00 EUR";
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            final jakarta.mail.Message[] captured = new jakarta.mail.Message[1];
            transport.when(() -> Transport.send(ArgumentMatchers.any())).thenAnswer(call -> {
                captured[0] = call.getArgument(0); return null;
            });
            assertTrue(service.sendDocument(document, "client@example.test"));
            var message = captured[0];
            assertTrue(message.getSubject().contains("AV-42"));
            assertEquals("client@example.test", message.getAllRecipients()[0].toString());
            var parts = (jakarta.mail.Multipart) message.getContent();
            assertEquals(document.content, parts.getBodyPart(0).getContent());
            assertEquals(document.content, parts.getBodyPart(1).getContent());
            assertEquals("ticket-AV-42.txt", parts.getBodyPart(1).getFileName());
            Mockito.verify(technicalEventService).log(TechnicalEvent.EventType.DIGITAL_TICKET_SENT,
                    "AV-42 -> client@example.test");
            assertEquals(0, printer.renders);
        }
    }

    /** Logo-bearing BODY/BOTH use related CID images; attachments are autonomous HTML with the same frozen PNG. */
    @Test
    void rendersLogosInEveryAdministeredMailFormat() throws Exception {
        service.host = Optional.of("localhost");
        String frozen = "[[IMAGE 8 1 gA==]]\nTOTAL <script> 42.00\n[[IMAGE 8 1 gA==]]";
        for (String format : java.util.List.of("BODY", "ATTACHMENT", "BOTH")) {
            Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn(format);
            try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
                final jakarta.mail.internet.MimeMessage[] captured = new jakarta.mail.internet.MimeMessage[1];
                transport.when(() -> Transport.send(ArgumentMatchers.any())).thenAnswer(call -> {
                    captured[0] = call.getArgument(0); return null;
                });
                assertTrue(service.send(ticketWith(frozen), "client@example.test"));
                var message = captured[0]; message.saveChanges();
                jakarta.mail.Multipart content = (jakarta.mail.Multipart) message.getContent();
                if (!format.equals("BODY")) {
                    assertTrue(message.isMimeType("multipart/mixed"));
                    var attachment = content.getBodyPart(1);
                    assertEquals("ticket-C04-000417.html", attachment.getFileName());
                    assertTrue(attachment.isMimeType("text/html"));
                    String html = (String) attachment.getContent();
                    assertTrue(html.contains("data:image/png;base64,"));
                    assertTrue(html.contains("TOTAL &lt;script&gt; 42.00"));
                    assertFalse(html.contains("[[IMAGE")); assertFalse(html.contains("cid:"));
                    if (format.equals("ATTACHMENT")) {
                        assertTrue(((String) content.getBodyPart(0).getContent()).contains("C04-000417"));
                        continue;
                    }
                    content = (jakarta.mail.Multipart) content.getBodyPart(0).getContent();
                }
                assertTrue(content.getContentType().startsWith("multipart/alternative"));
                assertEquals("TOTAL <script> 42.00\n", content.getBodyPart(0).getContent());
                var related = (jakarta.mail.Multipart) content.getBodyPart(1).getContent();
                assertEquals(3, related.getCount());
                String html = (String) related.getBodyPart(0).getContent();
                assertFalse(html.contains("data:image")); assertFalse(html.contains("[[IMAGE"));
                for (int rank = 0; rank < 2; rank++) {
                    String cid = "receipt-logo-" + rank + "@impos.local";
                    assertTrue(html.contains("cid:" + cid));
                    var image = related.getBodyPart(rank + 1);
                    assertEquals("<" + cid + ">", image.getHeader("Content-ID")[0]);
                    assertEquals(jakarta.mail.Part.INLINE, image.getDisposition());
                    assertTrue(image.isMimeType("image/png"));
                    assertNotNull(javax.imageio.ImageIO.read(image.getInputStream()));
                }
            }
        }
    }

    /** An invalid frozen raster is removed from plain MIME without losing surrounding customer text. */
    @Test
    void invalidLogoFallsBackToCleanTextMail() throws Exception {
        service.host = Optional.of("localhost");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BODY");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            final jakarta.mail.Message[] captured = new jakarta.mail.Message[1];
            transport.when(() -> Transport.send(ArgumentMatchers.any())).thenAnswer(call -> {
                captured[0] = call.getArgument(0); return null;
            });
            assertTrue(service.send(ticketWith("[[IMAGE 8 1 A]]\nTOTAL 1.00"), "client@example.test"));
            assertEquals("TOTAL 1.00", captured[0].getContent());
        }
    }

}
