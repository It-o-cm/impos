package com.intermarche.pos.ui.returnprocess;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.ui.recall.ChannelRules;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Brings an E-COMMERCE TICKET back to the register a customer returns it to
 * (BO-10-01-04, -05): an e-commerce order is booked by the store node on its
 * virtual register, so the ticket lives on the node and not in this register's
 * database. When the return search finds nothing locally and the number keyed
 * is one of the virtual register's, the register asks the node for that ticket
 * and keeps it as a closed ticket of its own, which the ordinary return then
 * takes back line by line — under the return types the store allows for the
 * e-commerce tickets.
 *
 * <p>Tolerant like every seam toward the node: no store configured, a node down
 * or a non-2xx answer import nothing, and the search simply finds nothing.
 */
@ApplicationScoped
public class EcommerceTicketClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EcommerceTicketClient.class);

    /** A lenient reader: the node may send fields this register does not use. */
    private static final ObjectMapper READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The store node's base URL; absent = no node, nothing imported. */
    @ConfigProperty(name = "pos.recall.document-url")
    Optional<String> documentUrl;

    /** Shared token presented to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The virtual register of the e-commerce transactions. */
    @Inject
    ChannelRules channelRules;

    /** The HTTP client toward the node. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * Imports the e-commerce ticket a keyed number names.
     *
     * @param number the number keyed in the return search, possibly null
     * @param operatorId the operator taking the return back, or null
     * @return the ticket, kept closed in this register's database, or null when
     *         the number is not an e-commerce one or the node does not serve it
     */
    @Transactional
    public Ticket importTicket(String number, Long operatorId) {
        LOGGER.info("Entering method importTicket with number: " + number);
        String terminal = channelRules.ecommerceTerminal();
        String keyed = number == null ? "" : number.trim();
        if (terminal.isEmpty() || !keyed.startsWith(terminal + "-") || operatorId == null) {
            LOGGER.info("Exiting method importTicket (not an e-commerce number)");
            return null;
        }
        Employee operator = Employee.findById(operatorId);
        Store store = Store.<Store>findAll().firstResult();
        TicketView view = operator == null || store == null ? null : fetch(keyed);
        if (view == null) {
            LOGGER.info("Exiting method importTicket (nothing imported)");
            return null;
        }
        Ticket ticket = new Ticket();
        ticket.ticketNumber = view.ticketNumber;
        ticket.terminalId = view.terminalId;
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.creationDate = date(view.creationDate);
        ticket.closingDate = date(view.closingDate);
        ticket.store = store;
        ticket.cashier = operator;
        ticket.totalIncludingTax = amount(view.totalIncludingTax);
        ticket.totalExcludingTax = amount(view.totalExcludingTax);
        ticket.totalVat = amount(view.totalVat);
        for (TicketViewLine row : view.lines) {
            TicketLine line = new TicketLine();
            line.lineNumber = row.lineNumber;
            line.lineUid = row.lineUid;
            line.ean = row.ean;
            line.productLabel = row.label == null ? "" : row.label;
            line.quantity = amount(row.quantity);
            line.unitPrice = amount(row.unitPrice);
            line.vatRate = amount(row.vatRate);
            line.totalPrice = amount(row.totalPrice);
            ticket.addLine(line);
        }
        ticket.itemCount = ticket.lines.size();
        save(ticket);
        LOGGER.info("Exiting method importTicket (imported " + ticket.ticketNumber + ")");
        return ticket;
    }

    /**
     * Keeps an imported ticket in this register's database.
     *
     * @param ticket the ticket
     */
    void save(Ticket ticket) {
        ticket.persist();
    }

    /**
     * Asks the node for an e-commerce ticket.
     *
     * @param number the ticket number
     * @return the ticket's view, or null when no node is configured, the node
     *         does not serve it or cannot be reached
     */
    TicketView fetch(String number) {
        if (documentUrl.isEmpty() || documentUrl.get().isBlank()) {
            return null;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(documentUrl.get().trim() + "/api/ecommerce/tickets/"
                            + URLEncoder.encode(number, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            token.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            return READER.readValue(response.body(), TicketView.class);
        } catch (Exception e) {
            LOGGER.warnf("Ticket e-commerce %s non récupéré : %s", number, e.getMessage());
            return null;
        }
    }

    /**
     * Reads a date the node states.
     *
     * @param value the ISO-8601 date, or null
     * @return the date, now when absent
     */
    private static LocalDateTime date(String value) {
        return value == null ? LocalDateTime.now() : LocalDateTime.parse(value);
    }

    /**
     * Reads an amount the node states.
     *
     * @param value the amount, or null
     * @return the amount, zero when absent
     */
    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** An e-commerce ticket as the store node serves it. */
    public static class TicketView {

        /** The ticket number. */
        public String ticketNumber;

        /** The virtual register it was booked on. */
        public String terminalId;

        /** When it was opened, ISO-8601, or null. */
        public String creationDate;

        /** When it was closed, ISO-8601, or null. */
        public String closingDate;

        /** Its total, TTC. */
        public BigDecimal totalIncludingTax;

        /** Its total, HT. */
        public BigDecimal totalExcludingTax;

        /** Its VAT. */
        public BigDecimal totalVat;

        /** Its lines. */
        public List<TicketViewLine> lines = new ArrayList<>();
    }

    /** One line of an e-commerce ticket as the store node serves it. */
    public static class TicketViewLine {

        /** The rank of the line. */
        public int lineNumber;

        /** The stable identity of the line. */
        public String lineUid;

        /** The EAN booked. */
        public String ean;

        /** The label booked. */
        public String label;

        /** The quantity. */
        public BigDecimal quantity;

        /** The unit price, TTC. */
        public BigDecimal unitPrice;

        /** The VAT rate. */
        public BigDecimal vatRate;

        /** The line total, TTC. */
        public BigDecimal totalPrice;
    }
}
