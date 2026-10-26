(function () {
    'use strict';
    let previous;
    let previousPristine;
    let active;

    /** LC-04-05-01: route the shared touch pad into exactly the selected criterion. */
    function focusField(button) {
        const name = button.dataset.field;
        const input = document.getElementById('criteria-' + name);
        document.querySelectorAll('.criteria-field').forEach(field => field.classList.remove('active'));
        button.classList.add('active');
        active = button;
        window.PosInput.setup({
            displayId: 'criteria-' + name + '-display', hiddenId: input.id,
            containerId: 'criteriaKeyboard', keyboardType: button.dataset.keyboard,
            mode: button.dataset.mode, maxLength: 64, initialValue: input.value
        });
        if (button.dataset.keyboard === 'searchAlpha') {
            ['-', '/'].forEach(key => {
                const punctuation = document.createElement('button');
                punctuation.type = 'button';
                punctuation.className = 'pos-key pos-key--action';
                punctuation.textContent = key;
                punctuation.addEventListener('click', () => window.PosInput.handleKey(key));
                document.getElementById('criteriaKeyboard').firstElementChild.appendChild(punctuation);
            });
        }
    }

    /** Restores the original search pad so closing the dialog cannot steal later simple-search keystrokes. */
    function toggle() {
        if (document.getElementById('ticketCriteria').open) {
            previous = {...window.PosInput.config, initialValue: window.PosInput.buffer};
            previousPristine = window.PosInput.pristine;
            focusField(document.querySelector('[data-field="number"]'));
        } else if (previous) {
            window.PosInput.setup(previous);
            window.PosInput.pristine = previousPristine;
            previous = null;
        }
    }

    /** Clearing the filters also clears hidden posted values and the selected tender. */
    function reset() {
        document.querySelectorAll('#criteriaForm input').forEach(input => {
            input.value = '';
            document.getElementById(input.id + '-display').textContent = '';
        });
        document.getElementById('criteria-paymentMethod').value = '';
        focusField(active);
    }

    /** Installs touch actions once on either of the two search screens. */
    function boot() {
        const details = document.getElementById('ticketCriteria');
        if (!details) return;
        details.addEventListener('toggle', toggle);
        document.querySelectorAll('[data-field]').forEach(button => button.addEventListener('click', () => focusField(button)));
        document.getElementById('criteriaClose').addEventListener('click', () => { details.open = false; });
        document.getElementById('criteriaReset').addEventListener('click', reset);
    }
    boot();
})();
