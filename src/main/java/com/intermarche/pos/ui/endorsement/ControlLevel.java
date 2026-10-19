package com.intermarche.pos.ui.endorsement;

/**
 * WHAT A CONTROL POINT DOES when the gesture it watches is about to run
 * (BO-10-02-01 to -10, -16 and -17).
 *
 * <p>The questionnaire asks the same thing of ten different gestures: "un
 * type d'alerte pouvant être non bloquant ou bloquant avec validation ou pas
 * superviseur". That sentence names four outcomes and not three, because
 * "bloquant" splits in two: a gesture a supervisor can unlock, and one nobody
 * at the register can. They are the four values below, and they are the same
 * four for every control point — an operator who learns them on the line
 * cancellation knows them on the card payment.
 *
 * <p>The level is READ, never computed: {@link EndorsementService} resolves it
 * against who is standing at the register — a supervisor already logged in, or
 * a supervisor session open on the ticket, turns {@link #ENDORSE} into
 * {@link #NONE} before the caller ever sees it. So a caller switches on four
 * values and never asks itself who is operating.
 */
public enum ControlLevel {

    /** The gesture runs, unwatched. */
    NONE,

    /**
     * The gesture runs, and the register says so: a non-blocking alert, which
     * is what the questionnaire calls "non bloquant". The operator is told, the
     * sale goes on, and the journal keeps the trace.
     */
    WARN,

    /**
     * The gesture is REFUSED, and no credential at the register lifts it. The
     * shop has decided this gesture does not happen here.
     */
    BLOCK,

    /**
     * The gesture is refused UNTIL a supervisor endorses it — the register's
     * historical behaviour for the gestures that already carried an
     * endorsement flag.
     */
    ENDORSE;

    /**
     * Reads an administered level.
     *
     * <p>Case and surrounding blanks do not matter, because this value is typed
     * into a back-office field by a person. Anything else — a blank, a level a
     * back office knows and this register does not — answers the fallback the
     * caller names, which is the control point's own default: a value nobody
     * can read must not silently remove a control, nor silently add one.
     *
     * @param raw the administered value, possibly null or blank
     * @param fallback the level to answer when the value cannot be read
     * @return the level, never null
     */
    public static ControlLevel of(String raw, ControlLevel fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim().toUpperCase();
        for (ControlLevel level : values()) {
            if (level.name().equals(value)) {
                return level;
            }
        }
        return fallback;
    }

    /**
     * Whether the gesture may run as it stands.
     *
     * @return true for {@link #NONE} and {@link #WARN}, the two levels that let
     *         the gesture through
     */
    public boolean allows() {
        return this == NONE || this == WARN;
    }
}
