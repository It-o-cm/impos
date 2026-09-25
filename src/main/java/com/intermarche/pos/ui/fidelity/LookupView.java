package com.intermarche.pos.ui.fidelity;

import java.util.List;

/**
 * What the card-search screen shows after a lookup: the matches to list, or
 * the sentence explaining why there are none.
 *
 * <p>A type of its own, and no longer nested in {@code FidelityService},
 * because the register STATE holds it. A state class reaching into a service
 * class to name the shape of its own field inverts the dependency — the state
 * is what services read and write, it must not depend on any of them — and it
 * closed a cycle between the two. The search result is DATA: it outlives the
 * call that produced it, it is re-rendered on every repaint of the screen, and
 * nothing about it belongs to the service that filled it.
 */
public class LookupView {

    /** The matches to list, or null when the search did not run. */
    public List<ImfidClient.LookupMatch> matches;

    /** The message to display (empty result, degraded, CRM mode), or null. */
    public String message;
}
