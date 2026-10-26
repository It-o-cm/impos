package com.intermarche.pos.ui.cash;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides the denomination lists of the drawer-count page (Z closing).
 * <p>
 * The lists encode a till convention: banknotes stop at 50 € (larger notes
 * are not kept in the drawer), and coin rolls carry the value of the WHOLE
 * roll (a 2 € roll counts 50 €) so the cashier counts unopened fond-de-
 * caisse rolls in one tap. Denomination ids are the keys of the
 * {@code countDetail} JSON persisted on the closed session.
 */
@ApplicationScoped
public class CashCountService {

    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The administered roll composition (BO-03-02-42). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;


    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashCountService.class);

    /**
     * Returns the banknote denominations, largest first.
     *
     * @return the banknote entries
     */
    public List<CashItem> getBills() {
        LOGGER.info("Entering method getBills");
        List<CashItem> items = new ArrayList<>();
        items.add(new CashItem("b50", i18n.get("cash.billet50"), 50.0));
        items.add(new CashItem("b20", i18n.get("cash.billet20"), 20.0));
        items.add(new CashItem("b10", i18n.get("cash.billet10"), 10.0));
        items.add(new CashItem("b5",  i18n.get("cash.billet5"),  5.0));
        LOGGER.info("Exiting method getBills");
        return items;
    }

    /**
     * Returns the coin denominations, largest first.
     *
     * @return the coin entries
     */
    public List<CashItem> getCoins() {
        LOGGER.info("Entering method getCoins");
        List<CashItem> items = new ArrayList<>();
        items.add(new CashItem("c2",   i18n.get("cash.piece2"),    2.0));
        items.add(new CashItem("c1",   i18n.get("cash.piece1"),    1.0));
        items.add(new CashItem("c050", i18n.get("cash.piece050"), 0.5));
        items.add(new CashItem("c020", i18n.get("cash.piece020"), 0.2));
        items.add(new CashItem("c010", i18n.get("cash.piece010"), 0.1));
        items.add(new CashItem("c005", i18n.get("cash.piece005"), 0.05));
        items.add(new CashItem("c002", i18n.get("cash.piece002"), 0.02));
        items.add(new CashItem("c001", i18n.get("cash.piece001"), 0.01));
        LOGGER.info("Exiting method getCoins");
        return items;
    }

    /**
     * Returns the coin-roll denominations, each valued at the whole roll.
     *
     * @return the roll entries
     */
    public List<CashItem> getRolls() {
        LOGGER.info("Entering method getRolls");
        List<CashItem> items = new ArrayList<>();
        // BO-03-02-42: the rolls are the ADMINISTERED composition — face value,
        // amount and number of coins — the very one the change order uses.
        List<ChangeOrderCalculator.RollValue> composition = posSettingsService == null
                ? List.of()
                : ChangeOrderCalculator.parseRollValues(posSettingsService.changeOrderRollValues());
        if (!composition.isEmpty()) {
            for (ChangeOrderCalculator.RollValue roll : composition) {
                items.add(new CashItem(rollId(roll.faceValue()),
                        i18n.format("cash.rouleauCompose", roll.faceValue().stripTrailingZeros().toPlainString(),
                                String.valueOf(roll.coinCount()), roll.rollValue().setScale(2).toPlainString()),
                        roll.rollValue().doubleValue()));
            }
            LOGGER.info("Exiting method getRolls");
            return items;
        }
        items.add(new CashItem("r2",   i18n.get("cash.rouleau2"),  50.0));
        items.add(new CashItem("r1",   i18n.get("cash.rouleau1"),  25.0));
        items.add(new CashItem("r050", i18n.get("cash.rouleau050"), 20.0));
        items.add(new CashItem("r020", i18n.get("cash.rouleau020"),  8.0));
        items.add(new CashItem("r010", i18n.get("cash.rouleau010"),  4.0));
        items.add(new CashItem("r005", i18n.get("cash.rouleau005"),  2.0));
        LOGGER.info("Exiting method getRolls");
        return items;
    }

    /**
     * The id a roll of the given face value is counted under: {@code r2} and
     * {@code r1} for the euro coins, {@code r050} … {@code r001} for the cents —
     * the ids the count detail always used.
     *
     * @param face the face value, strictly positive
     * @return the roll id
     */
    static String rollId(java.math.BigDecimal face) {
        if (face.compareTo(java.math.BigDecimal.ONE) >= 0) {
            return "r" + face.stripTrailingZeros().toPlainString();
        }
        return "r" + String.format("%03d", face.movePointRight(2).intValue());
    }

    /**
     * Turns a {@code countDetail} JSON into the printable lines of a count: one
     * label-and-amount pair per denomination actually counted, in the order of the
     * denominations handed in.
     *
     * <p>The ids are the keys of that JSON and the values are quantities, so the
     * amount of a line is the denomination's value times its quantity — a roll
     * counting for the whole roll, as the lists above encode. A denomination
     * counted ZERO times is not a line: a slip listing every coin of the euro at
     * zero says nothing the cashier can check.
     *
     * <p>The parse is deliberately forgiving: an entry that is not a pair, a
     * quantity that is not a number, an id no denomination carries — each is
     * skipped rather than failing the slip, because the detail is built in the
     * browser and a slip is better incomplete than absent.
     *
     * @param detail the per-denomination JSON, possibly null or blank
     * @param items the denominations the figures refer to
     * @return the printable lines, empty when nothing was counted
     */
    public List<String[]> denominationLines(String detail, List<CashItem> items) {
        LOGGER.info("Entering method denominationLines with detail: " + detail);
        List<String[]> lines = new ArrayList<>();
        if (detail == null || detail.isBlank()) {
            LOGGER.info("Exiting method denominationLines");
            return lines;
        }
        String body = detail.replace("{", "").replace("}", "").replace("\"", "");
        for (String entry : body.split(",")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) {
                continue;
            }
            String id = parts[0].trim();
            int quantity;
            try {
                quantity = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (quantity <= 0) {
                continue;
            }
            for (CashItem item : items) {
                if (item.id.equals(id)) {
                    java.math.BigDecimal amount = java.math.BigDecimal.valueOf(item.value)
                            .multiply(java.math.BigDecimal.valueOf(quantity));
                    lines.add(new String[] {item.label + " x" + quantity,
                            String.format("%.2f", amount).replace('.', ',') + " E"});
                    break;
                }
            }
        }
        LOGGER.info("Exiting method denominationLines");
        return lines;
    }
}