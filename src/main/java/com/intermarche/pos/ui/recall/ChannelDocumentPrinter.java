package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.service.DocumentTemplateService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prints, right after the settlement of a quote or an order of the commercial
 * management tool, the document the store administered for that moment
 * (BO-10-01-26): a delivery note, a collection slip — whatever layout the back
 * office wrote under that template code.
 *
 * <p>The layout is an ADMINISTERED template like every other document of the
 * register: what this class hands it is the settled sale already formatted —
 * the ticket number, its date, the codes of the documents it settled, its
 * total and its lines. A code naming no template, a template that has not
 * reached this register or one that does not render prints nothing rather
 * than failing the sale.
 */
@ApplicationScoped
public class ChannelDocumentPrinter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ChannelDocumentPrinter.class);

    /** The administered template code. */
    @Inject
    ChannelRules rules;

    /** Renders the administered template. */
    @Inject
    DocumentTemplateService documentTemplateService;

    /** The receipt printer. */
    @Inject
    HardwareService hardwareService;

    /**
     * Prints the administered document after the settlement of a sale loaded
     * from the commercial management tool (BO-10-01-26).
     *
     * @param state the current POS state, before it is cleared
     * @param ticketId the database id of the closed ticket
     * @return true when the document was printed
     */
    public boolean printAfterSettlement(PosState state, Long ticketId) {
        LOGGER.info("Entering method printAfterSettlement with ticketId: " + ticketId);
        if (!SalesChannel.COMMERCIAL.name().equals(state.ticket.recalledChannel)) {
            LOGGER.info("Exiting method printAfterSettlement (no commercial document)");
            return false;
        }
        String code = rules.commercialAutoPrintTemplate();
        Ticket ticket = code.isEmpty() ? null : Ticket.findById(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method printAfterSettlement (no template or no ticket)");
            return false;
        }
        String body = documentTemplateService.renderTemplate(DocumentTemplate.findByCode(code),
                data(state, ticket));
        if (body == null) {
            LOGGER.info("Exiting method printAfterSettlement (template not rendered)");
            return false;
        }
        hardwareService.printReceipt(body);
        hardwareService.cutPaper();
        LOGGER.info("Exiting method printAfterSettlement (printed)");
        return true;
    }

    /**
     * The values the template may read, already formatted.
     *
     * @param state the current POS state
     * @param ticket the closed ticket
     * @return the template data
     */
    private static Map<String, Object> data(PosState state, Ticket ticket) {
        Map<String, Object> data = new HashMap<>();
        data.put("ticketNumber", ticket.ticketNumber);
        data.put("date", ticket.closingDate == null ? "" : ticket.closingDate.toLocalDate().toString());
        List<String> codes = new ArrayList<>(state.recalledDocumentCodes);
        if (state.recalledOrderCode != null && !codes.contains(state.recalledOrderCode)) {
            codes.add(state.recalledOrderCode);
        }
        data.put("documentCodes", String.join(", ", codes));
        data.put("total", euros(ticket.totalIncludingTax));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("label", line.productLabel);
            row.put("quantity", line.quantity == null ? "" : line.quantity.stripTrailingZeros().toPlainString());
            row.put("total", euros(line.totalPrice));
            lines.add(row);
        }
        data.put("lines", lines);
        return data;
    }

    /**
     * Writes an amount for the paper.
     *
     * @param amount the amount, possibly null
     * @return the amount, two decimals, French comma
     */
    private static String euros(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
