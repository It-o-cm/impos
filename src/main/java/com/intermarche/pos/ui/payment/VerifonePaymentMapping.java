package com.intermarche.pos.ui.payment;

import java.util.HashMap;
import java.util.Map;
import com.intermarche.pos.domain.payment.PaymentTypes;

/** LC-07-07-04: administered application identities select the recorded settlement. */
public final class VerifonePaymentMapping {
    /** Utility class. */
    private VerifonePaymentMapping() { }

    /** Resolves known D16 first, then D46, retaining CARD for an unknown application.
     * @param rules semicolon-separated TAG:code=TENDER mappings
     * @param primary D16 value
     * @param alternate D46 fallback value
     * @return registered tender key
     */
    public static String resolve(String rules, String primary, String alternate) {
        Map<String, String> mappings = new HashMap<>();
        if (rules != null) {
            for (String rule : rules.split(";")) {
                String[] pair = rule.split("=", -1);
                if (pair.length != 2) {
                    continue;
                }
                String method = pair[1].trim();
                if (PaymentTypes.forKey(method) != null) {
                    mappings.put(pair[0].trim(), method);
                }
            }
        }
        String resolved = primary == null ? null : mappings.get("D16:" + primary);
        if (resolved == null) {
            resolved = alternate == null ? null : mappings.get("D46:" + alternate);
        }
        return resolved == null ? "CARD" : resolved;
    }
}
