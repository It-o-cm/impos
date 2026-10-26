/* Visible keys keep their layout; configured refusals precede their action (LC-13-05-04). */
(function () {
    "use strict";

    var source = document.getElementById("training-policy");
    var policy = JSON.parse(source.textContent);

    /** Uses the same stable identities as the existing touch-usage report. */
    function keyOf(element) {
        if (element.dataset.posKey) return element.dataset.posKey;
        if (element.id) return location.pathname + ":" + element.id;
        var handler = element.getAttribute("onclick");
        if (handler) return location.pathname + ":" + handler;
        var form = element.closest("form");
        if (form) return location.pathname + ":" + form.getAttribute("action") + ":" + element.name + ":" + element.value;
        return location.pathname + ":" + element.getAttribute("href");
    }

    /** Finds the action of a navigation or submit key without reading entered values. */
    function pathOf(element) {
        var target = element.getAttribute("href");
        if (!target) {
            var form = element.closest("form");
            if (form) target = element.getAttribute("formaction") || form.getAttribute("action");
        }
        if (!target) return null;
        var url = new URL(target, location.href);
        return url.origin === location.origin ? url.pathname : null;
    }

    /** Exact keys and paths are independent; /* explicitly selects a path subtree. */
    function blocked(element) {
        if (element.closest("[data-training-ack]")) return false;
        var path = pathOf(element);
        // LC-13-05-04: the operator must always retain the way out of training.
        if (path === "/action/training" || path === "/") return false;
        var key = "key:" + keyOf(element).slice(0, 200);
        return policy.rules.some(function (rule) {
            if (rule === key) return true;
            if (!rule.startsWith("path:/") || path === null) return false;
            var target = rule.slice(5);
            if (target.endsWith("/*")) {
                var parent = target.slice(0, -2);
                return path === parent || path.startsWith(parent + "/");
            }
            return path === target;
        });
    }

    /** Shows a translated, touch-sized acknowledgement without dropping the current screen. */
    function explain() {
        var dialog = document.querySelector("dialog[data-training-ack]");
        if (!dialog) {
            dialog = document.createElement("dialog");
            dialog.dataset.trainingAck = "true";
            dialog.style.cssText = "max-width:36rem;margin:auto;padding:2rem;font:1.25rem sans-serif;border:3px solid #b3261e;border-radius:12px";
            var message = document.createElement("p");
            message.textContent = policy.message;
            var button = document.createElement("button");
            button.type = "button";
            button.textContent = policy.close;
            button.style.cssText = "min-height:56px;min-width:160px;font:inherit";
            button.addEventListener("click", function () { dialog.close(); });
            dialog.append(message, button);
            document.body.append(dialog);
        }
        dialog.showModal();
    }

    /** Cancels inline handlers and navigation before they can perform a refused action. */
    function checkClick(event) {
        var element = event.target.closest("[data-pos-key],button,a,input[type=submit]");
        if (!element || element.disabled) return;
        if (!blocked(element)) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        explain();
    }

    /** Handles keyboard form submission as well as clicks; direct HTTP paths are guarded server-side. */
    function checkSubmit(event) {
        var form = event.target;
        var action = event.submitter || form;
        if (!blocked(action)) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        explain();
    }

    document.addEventListener("click", checkClick, true);
    document.addEventListener("submit", checkSubmit, true);
})();
