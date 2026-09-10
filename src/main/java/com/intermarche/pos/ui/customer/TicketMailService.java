package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.Properties;

/**
 * Sends a customer's receipt by e-mail (LC-08-02-04, -07, -08).
 *
 * <p>What travels is the ticket AS PRINTED — the 42-column text frozen at the fiscal
 * moment (LC-08-02-02) — in the body of the message, as an attachment, or both, as
 * the back office administers it. A ticket closed before that field existed is
 * rendered on the spot rather than refused.
 *
 * <p>WITHOUT AN SMTP HOST NOTHING IS SENT AND NOTHING FAILS: the delivery is
 * journalled exactly as the mocked one always was. A register on a demo bench, or one
 * whose store has not been given a relay, must keep working — the operator's gesture
 * is the same, only the letter does not leave. The journal entry is written on both
 * paths, so the record of "the customer asked for their receipt" never depends on the
 * network.
 *
 * <p>Placement: ui.customer, next to the digital receipt it serves — every caller is
 * a screen flow, and the register's rule keeps pos.service for services that serve
 * other services.
 */
@ApplicationScoped
public class TicketMailService {

    private static final Logger LOG = Logger.getLogger(TicketMailService.class);

    /** The ticket travels in the message body. */
    public static final String FORMAT_BODY = "BODY";

    /** The ticket travels as an attached file. */
    public static final String FORMAT_ATTACHMENT = "ATTACHMENT";

    /** The ticket travels both ways. */
    public static final String FORMAT_BOTH = "BOTH";

    /** The SMTP relay host; absent = nothing leaves, the send is journalled only. */
    @ConfigProperty(name = "pos.mail.host")
    Optional<String> host;

    /** The SMTP relay port. */
    @ConfigProperty(name = "pos.mail.port", defaultValue = "25")
    int port;

    /** The SMTP account, absent on an open relay. */
    @ConfigProperty(name = "pos.mail.user")
    Optional<String> user;

    /** The SMTP password, absent on an open relay. */
    @ConfigProperty(name = "pos.mail.password")
    Optional<String> password;

    /** The sender address the store signs its receipts with. */
    @ConfigProperty(name = "pos.mail.from", defaultValue = "ticket@intermarche.local")
    String from;

    /** Whether the relay is reached over STARTTLS. */
    @ConfigProperty(name = "pos.mail.starttls", defaultValue = "false")
    boolean startTls;

    /** The administered form of the sent ticket (body, attachment, both). */
    @Inject
    PosSettingsService posSettingsService;

    /** Renders a ticket closed before the printed form was frozen on the row. */
    @Inject
    TicketPrinterService ticketPrinterService;

    /** Journals the send, whether or not a letter actually left. */
    @Inject
    TechnicalEventService technicalEventService;

    /**
     * Whether an SMTP relay is configured; without one the send is journalled only.
     *
     * @return true when a letter can actually leave
     */
    public boolean isConfigured() {
        return host.isPresent() && !host.get().isBlank();
    }

    /**
     * Normalizes the administered form to one of the three known ones; anything else
     * falls back to the message body, which every mail client can read.
     *
     * @param raw the administered value, possibly null or blank
     * @return {@link #FORMAT_BODY}, {@link #FORMAT_ATTACHMENT} or {@link #FORMAT_BOTH}
     */
    public static String normalizeFormat(String raw) {
        if (raw == null) {
            return FORMAT_BODY;
        }
        String value = raw.trim().toUpperCase();
        if (value.equals(FORMAT_ATTACHMENT) || value.equals(FORMAT_BOTH)) {
            return value;
        }
        return FORMAT_BODY;
    }

    /**
     * Returns the printed form of a ticket: the one frozen at the fiscal moment when
     * the row carries it, rendered on the spot otherwise.
     *
     * @param ticket the ticket to send
     * @return the ticket as text, never null
     */
    public String contentOf(Ticket ticket) {
        if (ticket.formattedContent != null && !ticket.formattedContent.isBlank()) {
            return ticket.formattedContent;
        }
        return ticketPrinterService.renderTicket(ticket, false, 0);
    }

    /**
     * Sends a ticket to a customer's address and journals the send.
     *
     * <p>The journal entry is written whatever happens to the letter: what it records
     * is that the customer asked for their receipt at this register, which is true
     * even when the relay is down. A delivery failure is logged and answered with
     * false — the operator is told the letter did not leave, and nothing else of the
     * sale is disturbed.
     *
     * @param ticket the closed ticket to send
     * @param address the customer's address, already validated by the caller
     * @return true when a letter actually left, false when it was journalled only or
     *         the relay refused it
     */
    public boolean send(Ticket ticket, String address) {
        String content = contentOf(ticket);
        String format = normalizeFormat(posSettingsService.ticketEmailFormat());
        technicalEventService.log(TechnicalEvent.EventType.DIGITAL_TICKET_SENT,
                ticket.ticketNumber + " -> " + address);
        if (!isConfigured()) {
            LOG.infof("Ticket %s envoyé à %s (aucun relais SMTP configuré : journalisé seulement)",
                    ticket.ticketNumber, address);
            return false;
        }
        try {
            Transport.send(build(ticket, address, content, format));
            LOG.infof("Ticket %s envoyé à %s (%s)", ticket.ticketNumber, address, format);
            return true;
        } catch (Exception e) {
            LOG.warnf("Envoi du ticket %s à %s impossible : %s",
                    ticket.ticketNumber, address, e.getMessage());
            return false;
        }
    }

    /**
     * Builds the message carrying a ticket.
     *
     * @param ticket the ticket sent
     * @param address the recipient address
     * @param content the ticket as text
     * @param format the normalized form of the send
     * @return the message, ready for the transport
     * @throws Exception when the address or the content cannot be assembled
     */
    private MimeMessage build(Ticket ticket, String address, String content, String format)
            throws Exception {
        MimeMessage message = new MimeMessage(session());
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(address));
        message.setSubject("Votre ticket " + ticket.ticketNumber, "UTF-8");
        if (FORMAT_BODY.equals(format)) {
            message.setText(content, "UTF-8");
            return message;
        }
        MimeMultipart parts = new MimeMultipart();
        MimeBodyPart body = new MimeBodyPart();
        // An attachment-only send still carries a readable line: a message whose body
        // is empty reads as a mistake, and some clients hide the attachment entirely.
        body.setText(FORMAT_BOTH.equals(format) ? content
                : "Votre ticket " + ticket.ticketNumber + " est en pièce jointe.", "UTF-8");
        parts.addBodyPart(body);
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setText(content, "UTF-8");
        attachment.setFileName("ticket-" + ticket.ticketNumber + ".txt");
        attachment.setDisposition(MimeBodyPart.ATTACHMENT);
        parts.addBodyPart(attachment);
        message.setContent(parts);
        return message;
    }

    /**
     * Opens the mail session toward the configured relay.
     *
     * @return the session, authenticated when an account is configured
     */
    private Session session() {
        Properties properties = new Properties();
        properties.put("mail.smtp.host", host.orElse(""));
        properties.put("mail.smtp.port", String.valueOf(port));
        properties.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        String account = user.orElse("");
        if (account.isBlank()) {
            return Session.getInstance(properties);
        }
        properties.put("mail.smtp.auth", "true");
        return Session.getInstance(properties, new jakarta.mail.Authenticator() {
            /**
             * Supplies the configured SMTP credentials.
             *
             * @return the account and its password
             */
            @Override
            protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(account, password.orElse(""));
            }
        });
    }
}
