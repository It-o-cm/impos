package com.intermarche.pos.ui.endorsement;

import jakarta.enterprise.context.RequestScoped;

/** One resource invocation's approval decision; never a reusable supervisor session (LC-01-05-01). */
@RequestScoped
public class FunctionApprovalContext {
    /** Private state is reached through methods so the CDI request proxy resolves the current request. */
    private boolean authorized;
    private boolean approved;

    /** Marks an explicit store exemption without pretending a supervisor approved it. */
    public void exempt() { authorized = true; }

    /** Marks a consumed supervisor continuation for this resource invocation only. */
    public void approve() { authorized = true; approved = true; }

    /** @return whether this invocation is approved or explicitly exempt (LC-01-05-01) */
    public boolean authorized() { return authorized; }

    /** @return whether a supervisor actually approved this invocation (LC-01-05-01) */
    public boolean approved() { return approved; }
}
