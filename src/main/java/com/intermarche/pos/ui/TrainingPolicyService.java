package com.intermarche.pos.ui;

import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/** Shares the administered training rules between rendered keys and direct requests (LC-13-05-04). */
@ApplicationScoped
public class TrainingPolicyService {

    /** Existing five-level settings inheritance and SETTINGS distribution. */
    @Inject PosSettingsService settings;

    /**
     * Reads one rule per line, preserving handler semicolons inside a key identity.
     * @return trimmed rules; blank lines have no effect (LC-13-05-04)
     */
    public List<String> rules() {
        return settings.trainingBlockedKeys().lines().map(String::trim).filter(rule -> !rule.isEmpty()).toList();
    }

    /**
     * Matches an exact action path or an explicit descendant rule; never a raw string prefix.
     * @param path decoded absolute request path without query parameters
     * @return whether the training function is blocked (LC-13-05-04)
     */
    public boolean blocksPath(String path) {
        // LC-13-05-04: configuration cannot trap the operator in training or hide the error home.
        if (path.equals("/") || path.equals("/action/training")) return false;
        for (String rule : rules()) {
            if (!rule.startsWith("path:/")) continue;
            String target = rule.substring(5);
            if (target.endsWith("/*")) {
                String parent = target.substring(0, target.length() - 2);
                if (path.equals(parent) || path.startsWith(parent + "/")) return true;
            } else if (path.equals(target)) {
                return true;
            }
        }
        return false;
    }
}
