package com.intermarche.pos.ui.hardware;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * MicroProfile REST client toward the register's peripherals: scale,
 * customer line display, cash drawer and receipt printer.
 * <p>
 * THE HARDWARE BOUNDARY IS HTTP: this interface is the whole driver
 * contract, and the {@code hardware-api} config key decides who implements
 * it — in development the simulator's endpoints, on a real till a hardware
 * bridge exposing the same paths in front of the physical devices. Swapping
 * simulator for real peripherals is a base-URL change, no code.
 * Plain-text payloads on purpose: the contract stays curl-able and trivial
 * to reimplement.
 */
@Path("/api/hardware")
@RegisterRestClient(configKey = "hardware-api")
public interface HardwareClient {

    /**
     * Reads actual printer health (LC-13-02-09/-10).
     * @return OK, or the printer's fault marker
     */
    @GET
    @Path("/printer/status")
    @Produces(MediaType.TEXT_PLAIN)
    String getPrinterStatus();


    /**
     * Arms supervisor-approved closed-session control (LC-13-03-01/-06).
     * @param form the URL-encoded authorised data
     * @return the one-shot bridge permit token
     */
    @POST
    @Path("/control/arm")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    String armWorkstation(String form);

    /** Revokes control permits before opening a cash session (LC-13-03-01/-06). */
    @POST
    @Path("/control/revoke")
    void revokeWorkstation();


    /**
     * Calls a peripheral maintenance operation (LC-13-02-01/-07).
     * @param device the peripheral role
     * @param action reset, test, status or stop
     * @param text localised text for a printer test
     * @return the diagnostic status, empty for a completed printer operation
     */
    @POST
    @Path("/maintenance/{device}/{action}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    String maintenance(@jakarta.ws.rs.PathParam("device") String device,
            @jakarta.ws.rs.PathParam("action") String action, String text);


    /**
     * Reads the current weight from the scale.
     *
     * @return the weight in kilograms, French or dot decimal
     */
    @GET
    @Path("/weight")
    @Produces(MediaType.TEXT_PLAIN)
    String getWeight();

    /**
     * Reads the weight from the scale AFTER applying a software tare
     * (LC-14-01-03/04/05/07). The register sends the tare it resolved; the
     * scale prioritises it over any hardware tare (LC-14-01-06) and returns the
     * NET weight, which the register records as-is without re-deducting.
     *
     * @param tareKg the software tare to apply, in kilograms, dot decimal
     * @return the NET weight in kilograms, French or dot decimal
     */
    @GET
    @Path("/weight-with-tare")
    @Produces(MediaType.TEXT_PLAIN)
    String getWeightWithTare(@QueryParam("tare") String tareKg);

    /**
     * Shows a text on the customer line display.
     *
     * @param text the text to display
     */
    @POST
    @Path("/display")
    @Consumes(MediaType.TEXT_PLAIN)
    void setDisplay(String text);

    /**
     * Fires the drawer-opening pulse.
     */
    @POST
    @Path("/drawer/open")
    void openDrawer();

    /**
     * Reads the physical drawer state.
     *
     * @return "OPEN" when the drawer is open, anything else otherwise
     */
    @GET
    @Path("/drawer/status")
    @Produces(MediaType.TEXT_PLAIN)
    String getDrawerStatus();

    /**
     * Sends a formatted receipt to the printer.
     *
     * @param content the 42-column formatted text
     */
    @POST
    @Path("/printer/print")
    @Consumes(MediaType.TEXT_PLAIN)
    void printTicket(String content);

    /**
     * Sends one sheet to the register's SLIP STATION, the micr printer that
     * takes one sheet at a time (BO-03-04-14, LC-08-04-12).
     *
     * @param content the sheet, as formatted text
     */
    @POST
    @Path("/slip/print")
    @Consumes(MediaType.TEXT_PLAIN)
    void printSlip(String content);

    /**
     * Cuts the printer paper.
     */
    @POST
    @Path("/printer/cut")
    void cutPaper();

    /**
     * Starts a card payment on the terminal.
     * <p>
     * Returns as soon as the payment is under way, NOT when it ends: a card
     * payment waits for the cardholder and can take minutes, which no HTTP
     * request should be held open for. The outcome is read from
     * {@link #getPaymentStatus()}.
     *
     * @param amountCents the amount to debit, in cents
     */
    @POST
    @Path("/payment")
    @Consumes(MediaType.TEXT_PLAIN)
    void startPayment(String amountCents);

    /** Requests the LC-07 documented Verifone c2 refund.
     * @param amountCents refunded cents
     */
    @POST
    @Path("/payment/credit")
    @Consumes(MediaType.TEXT_PLAIN)
    void startCredit(String amountCents);

    /** Sends LC-07-08-02 forced VWAY4 debit.
     * @param cents requested cents
     */
    @POST @Path("/payment/degraded") @Consumes(MediaType.TEXT_PLAIN)
    void startDegradedPayment(String cents);

    /** Sends LC-07-08-02 forced VWAY4 credit.
     * @param cents credited cents
     */
    @POST @Path("/payment/credit/degraded") @Consumes(MediaType.TEXT_PLAIN)
    void startDegradedCredit(String cents);

    /** Sends LC-07-06-02 total and eligible cents separated by a semicolon.
     * @param amounts total;eligible cents
     */
    @POST @Path("/payment/combined") @Consumes(MediaType.TEXT_PLAIN)
    void startCombinedPayment(String amounts);

    /**
     * Reads where the card payment stands.
     * <p>
     * One {@code key=value} per line: {@code state} (IDLE, RUNNING or DONE),
     * {@code line1} and {@code line2} — what the terminal shows the cardholder
     * right now — then {@code result}, {@code approved}, {@code failure} and
     * {@code answer} once the payment is over.
     *
     * @return the status block
     */
    @GET
    @Path("/payment")
    @Produces(MediaType.TEXT_PLAIN)
    String getPaymentStatus();

    /**
     * Starts a cheque reading.
     * <p>
     * Returns as soon as the reader is waiting for the document, NOT when it has read
     * it: the reading waits for a person to offer a cheque and take it back. The
     * outcome is read from {@link #getChequeStatus()}.
     *
     * @param endorsement what the bridge prints on the cheque while the reader still
     *                    holds it, one line per newline; empty prints nothing
     */
    @POST
    @Path("/cheque")
    @Consumes(MediaType.TEXT_PLAIN)
    void startCheque(String endorsement);

    /** Starts LC-07-05-06 slip-front printing without registering a payment.
     * @param text administered cheque front
     */
    @POST @Path("/cheque/front") @Consumes(MediaType.TEXT_PLAIN)
    void printChequeFront(String text);

    /**
     * Reads where the cheque reading stands.
     * <p>
     * One {@code key=value} per line: {@code state} (IDLE, RUNNING or DONE),
     * {@code failure}, then the magnetic line and its French zones —
     * {@code raw}, {@code bank}, {@code branch}, {@code account}, {@code serial}.
     *
     * @return the status block
     */
    @GET
    @Path("/cheque")
    @Produces(MediaType.TEXT_PLAIN)
    String getChequeStatus();
    /**
     * Starts a deposit on the cash changer for the amount due (LC-07-15-01).
     * <p>
     * Returns as soon as the machine accepts coins and notes, NOT when the
     * customer has paid: the outcome is followed with
     * {@link #getCashChangerDeposit()}. The machine closes the deposit by itself
     * once the amount due is reached, and dispenses the change.
     *
     * @param dueCents the amount due, in cents
     */
    @POST
    @Path("/cashchanger/deposit")
    @Consumes(MediaType.TEXT_PLAIN)
    void startCashChangerDeposit(String dueCents);

    /**
     * Reads where the cash changer deposit stands (LC-07-15-01/02).
     * <p>
     * One {@code key=value} per line: {@code state} (IDLE, RUNNING or DONE),
     * {@code due}, {@code inserted} and {@code change} in cents,
     * {@code cancelled} (true when everything was given back) and
     * {@code failure}.
     *
     * @return the status block
     */
    @GET
    @Path("/cashchanger/deposit")
    @Produces(MediaType.TEXT_PLAIN)
    String getCashChangerDeposit();

    /**
     * Closes the running deposit on what was inserted, without change: the
     * customer pays the rest with another tender (LC-07-15-01).
     */
    @POST
    @Path("/cashchanger/deposit/fix")
    void fixCashChangerDeposit();

    /**
     * Cancels the running deposit: the machine gives back everything inserted.
     */
    @POST
    @Path("/cashchanger/deposit/cancel")
    void cancelCashChangerDeposit();

    /**
     * Reads the denominations the cash changer reports out of its thresholds,
     * administered on the machine's own back office (LC-13-01-01).
     * <p>
     * One {@code key=value} per line: {@code low} and {@code high}, the
     * denominations in cents below their low level and above their high level,
     * comma separated; {@code status} (OK, NEAREMPTY or EMPTY) and {@code full}
     * (OK, NEARFULL or FULL), the machine-wide levels.
     *
     * @return the levels block
     */
    @GET
    @Path("/cashchanger/levels")
    @Produces(MediaType.TEXT_PLAIN)
    String getCashChangerLevels();

    /**
     * Has the cash changer DISPENSE an amount for a cash withdrawal
     * (BO-11-03-03): the machine hands the notes and coins out and reports what
     * it actually dispensed, {@code dispensed=<cents>}.
     *
     * @param cents the amount asked for, in cents
     * @return the outcome block
     */
    @POST
    @Path("/cashchanger/dispense")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    String dispenseFromCashChanger(String cents);

    /**
     * Has the cash changer take a cash LOAN in (BO-11-03-03): the float is fed
     * into the machine, which reports what it accepted, {@code accepted=<cents>}.
     *
     * @param cents the amount brought, in cents
     * @return the outcome block
     */
    @POST
    @Path("/cashchanger/replenish")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    String replenishCashChanger(String cents);

    /**
     * Reads the cash level of the cash changer (BO-11-03-03): one
     * {@code key=value} per line, {@code total} in cents, then each
     * denomination in cents and how many of it the machine holds.
     *
     * @return the inventory block
     */
    @GET
    @Path("/cashchanger/inventory")
    @Produces(MediaType.TEXT_PLAIN)
    String getCashChangerInventory();

    /**
     * Hands the hardware module the driver configuration administered for this
     * register (BO-03-09-11, -14, -15, -16, -17, -18, -19, -21, -22, -29): one
     * {@code key=value} per line — drawer, scale, scanners, printers, payment
     * terminal and coin machine. The module applies what it drives.
     *
     * @param configuration the configuration block
     */
    @POST
    @Path("/configuration")
    @Consumes(MediaType.TEXT_PLAIN)
    void configure(String configuration);

    /**
     * Prints a page on the SECOND printer plugged on this register, A4 or roll
     * (BO-03-09-22).
     *
     * @param queue the local print queue the page goes to
     * @param page the page, HTML for an A4 printer, text for a roll
     */
    @POST
    @Path("/printer2/print")
    @Consumes(MediaType.TEXT_PLAIN)
    void printOnSecondPrinter(@QueryParam("queue") String queue, String page);
}
