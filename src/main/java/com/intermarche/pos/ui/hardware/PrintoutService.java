package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.sale.PrintedTicket;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps EVERY ticket the register generates, in its 42-column format, and
 * pushes it to the store node for third-party services (LC-08-02-02): the
 * customer's papers and the store's technical tickets alike (LC-08-03-08).
 *
 * <p>The recording sits at the one place every paper goes through — the printer
 * of {@link HardwareService} — plus the customer copies a print policy keeps from
 * the printer, which are generated all the same. The register function that
 * produced the paper is read on the call stack, which is what lets a technical
 * ticket be told from a customer's one without every printing method having to
 * declare it; a ticket printed in training mode is marked as such.
 *
 * <p>Tolerant by construction: a recording that fails is logged and the paper
 * prints anyway — keeping a copy must never cost the customer a ticket.
 */
@ApplicationScoped
public class PrintoutService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PrintoutService.class);

    /**
     * The register functions that print the STORE's technical tickets
     * (LC-08-03-08): session opening and closing, X and Z reports, operator
     * report, settlement voucher, unknown article, withdrawal, transfer, coin
     * supply, cash movement, float count, change order, abandoned sale, operator
     * badge.
     */
    static final Set<String> STORE_SOURCES = Set.of(
            "SessionCloseService.printReceipt",
            "TicketPrinterService.printSessionReport",
            "TicketPrinterService.printOperatorReport",
            "TicketPrinterService.printSettlementVoucher",
            "TicketPrinterService.printUnknownArticleTicket",
            "TicketPrinterService.printWithdrawalTicket",
            "TicketPrinterService.printTransferTicket",
            "TicketPrinterService.printDepositTicket",
            "TicketPrinterService.printMovementTicket",
            "TicketPrinterService.printFloatCountTicket",
            "TicketPrinterService.printChangeOrder",
            "TicketPrinterService.printAbandonTicket",
            "TicketPrinterService.printOperatorBadge");

    /**
     * The methods that only relay a paper someone else composed: the source is
     * the caller beyond them.
     */
    private static final Set<String> RELAYS = Set.of("TicketPrinterService.printRenderedTicket",
            "TicketPrinterService.printAssociatedCustomerCopy");

    /** The register state, to tell a training ticket. */
    @Inject
    PosState state;

    /** The register's identifier. */
    @Inject
    TicketNumberService ticketNumberService;

    /** Queues the push to the store node. */
    @Inject
    SyncOutboxService syncOutboxService;

    /**
     * Records a ticket the register generated.
     *
     * @param content the ticket, 42-column text
     */
    public void record(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String source = source();
        PrintedTicket.Audience audience = audienceOf(source);
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                PrintedTicket printout = new PrintedTicket();
                printout.terminalId = ticketNumberService.getTerminalId();
                printout.source = source;
                printout.audience = audience;
                printout.content = content;
                printout.issuedAt = LocalDateTime.now();
                printout.persist();
                syncOutboxService.enqueue(SyncOutbox.EntityType.PRINTOUT, printout.id);
            });
        } catch (RuntimeException e) {
            LOGGER.warnf("Ticket imprimé non conservé (%s) : %s", source, e.getMessage());
        }
    }

    /**
     * The register function that produced the paper being recorded: the first
     * frame of the call stack outside this class, the printer and the relays,
     * as {@code Class.method}.
     *
     * @return the source, or {@code unknown} when the stack names none
     */
    String source() {
        Optional<String> caller = StackWalker.getInstance().walk(frames -> frames
                .map(frame -> simpleName(frame.getClassName()) + "." + frame.getMethodName())
                .filter(PrintoutService::isSource)
                .findFirst());
        return caller.orElse("unknown");
    }

    /**
     * Whether a frame of the call stack names the register function that
     * produced a paper, rather than this class, the printer, a relay, a CDI
     * proxy or a lambda.
     *
     * @param frame the frame, as {@code SimpleClass.method}
     * @return true when the frame is the source
     */
    static boolean isSource(String frame) {
        return !frame.startsWith("PrintoutService.")
                && !frame.startsWith("HardwareService.")
                && !frame.contains("_Subclass")
                && !frame.contains("_ClientProxy")
                && !frame.contains(".lambda$")
                && !RELAYS.contains(frame);
    }

    /**
     * Who a paper is meant for: a training ticket in training mode, the store
     * for its technical tickets, the customer otherwise.
     *
     * @param source the register function that produced the paper
     * @return the audience
     */
    PrintedTicket.Audience audienceOf(String source) {
        if (state.trainingMode) {
            return PrintedTicket.Audience.TRAINING;
        }
        return STORE_SOURCES.contains(source) ? PrintedTicket.Audience.STORE : PrintedTicket.Audience.CUSTOMER;
    }

    /**
     * The simple name of a class, nested classes included.
     *
     * @param className the binary name
     * @return the part after the last dot
     */
    static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
