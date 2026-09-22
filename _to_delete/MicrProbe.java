import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jpos.MICR;
import jpos.JposException;
import jpos.events.DataEvent;
import jpos.events.DataListener;
import jpos.events.ErrorEvent;
import jpos.events.ErrorListener;

/**
 * Reads one cheque through the vendor's own JavaPOS service.
 *
 * <p>Throwaway probe, not production code: it answers whether the MICR reader of this
 * printer works at all and what it hands back, before anything is designed around it.
 * It waits for a cheque to be inserted, reads it, and ejects it.
 */
public final class MicrProbe {

    /** How long the operator has to insert the cheque, in milliseconds. */
    private static final int INSERTION_TIMEOUT = 40000;

    /** How long the operator has to take the cheque back, in milliseconds. */
    private static final int REMOVAL_TIMEOUT = 40000;

    /** How long to wait for the reading once the cheque is in, in seconds. */
    private static final int READ_TIMEOUT_SECONDS = 60;

    /** Not instantiable: this class is a command. */
    private MicrProbe() {
    }

    /**
     * Runs the probe.
     *
     * @param args the JavaPOS logical name, defaulting to MICRTMH6000
     * @throws Exception if anything at all goes wrong, printed with its stack
     */
    public static void main(String[] args) throws Exception {
        String logicalName = args.length > 0 ? args[0] : "MICRTMH6000";
        MICR micr = new MICR();
        CountDownLatch read = new CountDownLatch(1);
        micr.addDataListener(new DataListener() {
            @Override
            public void dataOccurred(DataEvent event) {
                read.countDown();
            }
        });
        micr.addErrorListener(new ErrorListener() {
            @Override
            public void errorOccurred(ErrorEvent event) {
                System.out.println("micr   erreur " + event.getErrorCode()
                        + " / " + event.getErrorCodeExtended());
                read.countDown();
            }
        });
        System.out.println("micr   ouverture de " + logicalName);
        micr.open(logicalName);
        micr.claim(5000);
        micr.setDeviceEnabled(true);
        micr.setDataEventEnabled(true);
        try {
            System.out.println("micr   INSEREZ LE CHEQUE (" + INSERTION_TIMEOUT / 1000 + " s)");
            micr.beginInsertion(INSERTION_TIMEOUT);
            micr.endInsertion();
            System.out.println("micr   cheque insere, lecture");
            if (!read.await(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                System.out.println("micr   aucune donnee apres " + READ_TIMEOUT_SECONDS + " s");
            } else {
                show(micr);
            }
            System.out.println("micr   RETIREZ LE CHEQUE");
            micr.beginRemoval(REMOVAL_TIMEOUT);
            micr.endRemoval();
        } finally {
            micr.setDeviceEnabled(false);
            micr.release();
            micr.close();
        }
    }

    /**
     * Prints everything the service decoded from the cheque.
     *
     * @param micr the open service
     * @throws JposException if a property cannot be read
     */
    private static void show(MICR micr) throws JposException {
        System.out.println("micr   brut          " + micr.getRawData());
        System.out.println("micr   compte        " + micr.getAccountNumber());
        System.out.println("micr   banque        " + micr.getBankNumber());
        System.out.println("micr   guichet       " + micr.getTransitNumber());
        System.out.println("micr   numero        " + micr.getSerialNumber());
        System.out.println("micr   type          " + micr.getCheckType());
        System.out.println("micr   pays          " + micr.getCountryCode());
        System.out.println("micr   montant       " + micr.getAmount());
        System.out.println("micr   EPC           " + micr.getEPC());
    }
}
