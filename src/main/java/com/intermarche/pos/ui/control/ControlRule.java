package com.intermarche.pos.ui.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One administered REGISTER ALERT (BO-04-03-08/09): what it watches — a
 * register function, a payment method, an article or a family, a barcode
 * prefix, the register gone offline, an expired date read on a GS1 code — and
 * what the register does when it fires — a yes/no question, a supervisor's
 * validation, an information alert to the back office, or a refusal — with
 * the message it shows, whether the cashier is notified and how many times it
 * may fire in one transaction.
 * <p>
 * Written one per line in {@code control.alert-rules}:
 * {@code target = TYPE ; text=message ; notify ; max=1 ; profiles=Chef de caisse}.
 *
 * @param key the rule's identity in the transaction (its target, normalised)
 * @param kind the kind of target: function, payment, article, barcode, offline or expired
 * @param value what the target names inside its kind (route, method key, EAN, prefix), possibly empty
 * @param type what the register does when the rule fires
 * @param text the message shown, or null for the register's own
 * @param notifies whether the cashier is notified as well
 * @param max how many times the rule may fire in a transaction, 0 for no limit
 * @param profiles the validating profiles of a supervisor validation, possibly empty
 */
public record ControlRule(String key, String kind, String value, Type type, String text,
                          boolean notifies, int max, List<String> profiles) {

    /** What the register does when a rule fires (BO-04-03-09). */
    public enum Type {
        /** A yes/no question asked at the till. */
        YESNO,
        /** A supervisor's validation, the gesture held until it is given. */
        SUPERVISOR,
        /** An information alert sent to the back office; the gesture goes on. */
        BACKOFFICE,
        /** The gesture is refused. */
        BLOCK
    }

    /**
     * Parses the administered rules, one per line, ignoring the lines it
     * cannot read: a typo costs one alert, never the register.
     *
     * @param raw the administered text, possibly null
     * @return the readable rules, in their administered order
     */
    public static List<ControlRule> parse(String raw) {
        List<ControlRule> rules = new ArrayList<>();
        if (raw == null) {
            return rules;
        }
        for (String line : raw.split("\\r?\\n|\\\\n")) {
            ControlRule rule = parseLine(line);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }

    /**
     * Parses one rule line.
     *
     * @param line the line
     * @return the rule, or null when the line is blank or unreadable
     */
    static ControlRule parseLine(String line) {
        int equals = line.indexOf('=');
        if (equals < 0) {
            return null;
        }
        String target = line.substring(0, equals).trim();
        String[] parts = line.substring(equals + 1).split(";");
        Type type = typeOf(parts[0]);
        if (target.isEmpty() || type == null) {
            return null;
        }
        int colon = target.indexOf(':');
        String kind = (colon < 0 ? target : target.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        String value = colon < 0 ? "" : target.substring(colon + 1).trim();
        String text = null;
        boolean notify = false;
        int max = 0;
        List<String> profiles = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String option = parts[i].trim();
            int sign = option.indexOf('=');
            String name = (sign < 0 ? option : option.substring(0, sign)).trim().toLowerCase(Locale.ROOT);
            String argument = sign < 0 ? "" : option.substring(sign + 1).trim();
            if ("notify".equals(name)) {
                notify = true;
            } else if ("text".equals(name) && !argument.isEmpty()) {
                text = argument;
            } else if ("max".equals(name)) {
                max = number(argument);
            } else if ("profiles".equals(name)) {
                for (String profile : argument.split(",")) {
                    if (!profile.isBlank()) {
                        profiles.add(profile.trim());
                    }
                }
            }
        }
        // A route keeps its case — a path is case-sensitive and the journal
        // shows it as typed —; any other target is named whatever its case.
        String named = "function".equals(kind) ? value : value.toUpperCase(Locale.ROOT);
        return new ControlRule(kind + ":" + named, kind, value, type, text, notify, max, profiles);
    }

    /**
     * Reads a rule type, ignoring case.
     *
     * @param raw the typed type
     * @return the type, or null when it names none
     */
    static Type typeOf(String raw) {
        String typed = raw.trim().toUpperCase(Locale.ROOT);
        for (Type type : Type.values()) {
            if (type.name().equals(typed)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Reads a positive count, 0 when unreadable or negative.
     *
     * @param raw the typed count
     * @return the count
     */
    static int number(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Whether this rule watches a function route: {@code function:METHOD path},
     * the path matched exactly or, with a {@code /*} suffix, with its sub-paths;
     * or {@code payment:KEY}, which watches the settlement route of that
     * method ({@code POST /action/pay-key}).
     *
     * @param method the HTTP method of the request
     * @param path the request path
     * @return true when the rule watches that function
     */
    public boolean watchesFunction(String method, String path) {
        if ("payment".equals(kind)) {
            return "POST".equalsIgnoreCase(method)
                    && path.equalsIgnoreCase("/action/pay-" + value.toLowerCase(Locale.ROOT));
        }
        if (!"function".equals(kind)) {
            return false;
        }
        String[] route = value.split("\\s+", 2);
        if (route.length != 2 || !route[0].equalsIgnoreCase(method)) {
            return false;
        }
        String target = route[1].replaceAll("/+$", "");
        return target.endsWith("/*")
                ? path.equals(target.substring(0, target.length() - 2))
                        || path.startsWith(target.substring(0, target.length() - 1))
                : path.equals(target);
    }

    /**
     * Whether this rule watches a function, the form read for a tender the
     * store added (BO-03-02-01): {@code payment:CODE} also watches {@code POST
     * /action/pay-tender} posted with that tender's code.
     *
     * @param method the HTTP method of the request
     * @param path the request path
     * @param body the posted form, possibly empty
     * @return true when the rule watches that function
     */
    public boolean watchesFunction(String method, String path, String body) {
        return watchesFunction(method, path) || ("payment".equals(kind) && "POST".equalsIgnoreCase(method)
                && "/action/pay-tender".equals(path) && value.equalsIgnoreCase(ControlGateFilter.field(body, "code")));
    }

    /**
     * Whether this rule watches an article: {@code article:EAN}, or
     * {@code article:F<code>} for every article of a family.
     *
     * @param ean the article's EAN, possibly null
     * @param familyCode the article's family code, possibly null
     * @return true when the rule watches that article
     */
    public boolean watchesArticle(String ean, String familyCode) {
        if (!"article".equals(kind) || value.isEmpty()) {
            return false;
        }
        if (value.length() > 1 && (value.charAt(0) == 'F' || value.charAt(0) == 'f')) {
            return value.substring(1).equals(familyCode);
        }
        return value.equals(ean);
    }

    /**
     * Whether this rule watches a scanned code: {@code barcode:prefix*} for the
     * codes beginning with the prefix, {@code barcode:code} for that code alone.
     *
     * @param code the code as read
     * @return true when the rule watches that code
     */
    public boolean watchesBarcode(String code) {
        if (!"barcode".equals(kind) || value.isEmpty() || code == null) {
            return false;
        }
        return value.endsWith("*") ? code.startsWith(value.substring(0, value.length() - 1)) : code.equals(value);
    }
}
