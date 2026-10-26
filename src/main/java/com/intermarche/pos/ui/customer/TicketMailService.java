package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
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

    private static final Logger LOGGER = Logger.getLogger(TicketMailService.class);

    /** The ticket travels in the message body. */
    public static final String FORMAT_BODY = "BODY";

    /** The ticket travels as an attached file. */
    public static final String FORMAT_ATTACHMENT = "ATTACHMENT";

    /** The ticket travels both ways. */
    public static final String FORMAT_BOTH = "BOTH";


    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

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
        LOGGER.info("Entering method isConfigured");
        LOGGER.info("Exiting method isConfigured");
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
        LOGGER.info("Entering method normalizeFormat with raw: " + raw);
        if (raw == null) {
            LOGGER.info("Exiting method normalizeFormat");
            return FORMAT_BODY;
        }
        String value = raw.trim().toUpperCase();
        if (value.equals(FORMAT_ATTACHMENT) || value.equals(FORMAT_BOTH)) {
            LOGGER.info("Exiting method normalizeFormat");
            return value;
        }
        LOGGER.info("Exiting method normalizeFormat");
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
        LOGGER.info("Entering method contentOf with ticket: " + ticket);
        if (ticket.formattedContent != null && !ticket.formattedContent.isBlank()) {
            LOGGER.info("Exiting method contentOf");
            return ticket.formattedContent;
        }
        LOGGER.info("Exiting method contentOf");
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
        LOGGER.info("Entering method send with ticket: " + ticket + ", address: " + address);
        return deliver(ticket.ticketNumber, address, contentOf(ticket));
    }

    /** Sends a frozen customer copy using the same administered MIME format.
     * @param document issued customer copy selected by its database identifier
     * @param address validated recipient address
     * @return whether SMTP accepted the message
     */
    public boolean sendDocument(com.intermarche.pos.domain.sale.CustomerDocument document, String address) {
        return deliver(document.reference, address, document.content);
    }

    /** Delivers customer content and journals the request, including relay failures.
     * @param reference issued reference
     * @param address recipient address
     * @param content frozen customer content
     * @return whether SMTP accepted the message
     */
    private boolean deliver(String reference, String address, String content) {
        String format = normalizeFormat(posSettingsService.ticketEmailFormat());
        technicalEventService.log(TechnicalEvent.EventType.DIGITAL_TICKET_SENT,
                reference + " -> " + address);
        if (!isConfigured()) {
            LOGGER.infof("Ticket %s envoyé à %s (aucun relais SMTP configuré : journalisé seulement)",
                    reference, address);
            LOGGER.info("Exiting method send");
            return false;
        }
        try {
            Transport.send(buildDocument(reference, address, content, format));
            LOGGER.infof("Ticket %s envoyé à %s (%s)", reference, address, format);
            LOGGER.info("Exiting method send");
            return true;
        } catch (Exception e) {
            LOGGER.warnf("Envoi du ticket %s à %s impossible : %s",
                    reference, address, e.getMessage());
            LOGGER.info("Exiting method send");
            return false;
        }
    }

    /** Builds the common customer document MIME message.
     * @param reference issued reference
     * @param address recipient
     * @param content frozen customer copy
     * @param format normalized BODY, ATTACHMENT or BOTH
     * @return message ready for transport
     * @throws Exception when MIME assembly fails
     */
    private MimeMessage buildDocument(String reference, String address, String content, String format)
            throws Exception {
        MimeMessage message = new MimeMessage(session());
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(address));
        message.setSubject(i18n.format("mail.objet", reference), "UTF-8");
        var rendered = CustomerReceiptRendering.render(content);
        if (!rendered.logos().isEmpty()) {
            return buildWithLogos(message, reference, rendered, format);
        }
        content = rendered.text();
        if (FORMAT_BODY.equals(format)) {
            message.setText(content, "UTF-8");
            return message;
        }
        MimeMultipart parts = new MimeMultipart();
        MimeBodyPart body = new MimeBodyPart();
        // An attachment-only send still carries a readable line: a message whose body
        // is empty reads as a mistake, and some clients hide the attachment entirely.
        body.setText(FORMAT_BOTH.equals(format) ? content
                : i18n.format("mail.corpsPieceJointe", reference), "UTF-8");
        parts.addBodyPart(body);
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setText(content, "UTF-8");
        attachment.setFileName("ticket-" + reference + ".txt");
        attachment.setDisposition(MimeBodyPart.ATTACHMENT);
        parts.addBodyPart(attachment);
        message.setContent(parts);
        return message;
    }

    /** Adds embedded logo images to customer formats without exposing printer directives.
     * @param message common addressed message
     * @param reference issued document reference
     * @param rendered readable formats from the frozen source
     * @param format administered BODY, ATTACHMENT or BOTH
     * @return completed MIME message
     * @throws Exception when MIME assembly fails
     */
    private MimeMessage buildWithLogos(MimeMessage message, String reference,
                                      CustomerReceiptRendering.Rendered rendered, String format) throws Exception {
        if (FORMAT_BODY.equals(format)) {
            message.setContent(logoBody(rendered));
            return message;
        }
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        if (FORMAT_BOTH.equals(format)) {
            body.setContent(logoBody(rendered));
        } else {
            body.setText(i18n.format("mail.corpsPieceJointe", reference), "UTF-8");
        }
        mixed.addBodyPart(body);
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setContent("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body>"
                + rendered.html() + "</body></html>", "text/html; charset=UTF-8");
        attachment.setFileName("ticket-" + reference + ".html");
        attachment.setDisposition(MimeBodyPart.ATTACHMENT);
        mixed.addBodyPart(attachment);
        message.setContent(mixed);
        return message;
    }

    /** Builds text/HTML alternatives with CID-linked PNGs, requiring no remote image fetch.
     * @param rendered escaped receipt and frozen PNGs
     * @return message alternatives with related image parts
     * @throws Exception when a MIME part cannot be assembled
     */
    private MimeMultipart logoBody(CustomerReceiptRendering.Rendered rendered) throws Exception {
        MimeMultipart alternatives = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText(rendered.text(), "UTF-8");
        alternatives.addBodyPart(plain);
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart html = new MimeBodyPart();
        String content = rendered.html();
        for (var logo : rendered.logos()) {
            content = content.replaceFirst(java.util.regex.Pattern.quote(logo.dataUri()),
                    java.util.regex.Matcher.quoteReplacement("cid:" + logo.contentId()));
        }
        html.setContent(content, "text/html; charset=UTF-8");
        related.addBodyPart(html);
        for (var logo : rendered.logos()) {
            MimeBodyPart image = new MimeBodyPart();
            image.setDataHandler(new jakarta.activation.DataHandler(
                    new jakarta.mail.util.ByteArrayDataSource(logo.png(), "image/png")));
            image.setHeader("Content-ID", "<" + logo.contentId() + ">");
            image.setDisposition(MimeBodyPart.INLINE);
            related.addBodyPart(image);
        }
        MimeBodyPart htmlWithImages = new MimeBodyPart();
        htmlWithImages.setContent(related);
        alternatives.addBodyPart(htmlWithImages);
        return alternatives;
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
