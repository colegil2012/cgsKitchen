// Live price update on menu cards: sum data-delta on selected
// option inputs and add to the form's data-base-cents.
// Also wires the "Customize" chevron to expand/collapse the options panel.
(function () {
    const fmt = (cents) => {
        const sign = cents < 0 ? '-' : '';
        return sign + '$' + (Math.abs(cents) / 100).toFixed(2);
    };

    document.querySelectorAll('.menu-card__form').forEach((form) => {
        const base = parseInt(form.dataset.baseCents || '0', 10);
        const totalEl = form.querySelector('.menu-card__price-total');
        const liveEl  = form.querySelector('.menu-card__price-live');

        const update = () => {
            let delta = 0;
            form.querySelectorAll('select').forEach((sel) => {
                const opt = sel.options[sel.selectedIndex];
                if (opt && opt.dataset.delta) delta += parseInt(opt.dataset.delta, 10);
            });
            form.querySelectorAll('input[type="checkbox"]:checked').forEach((cb) => {
                if (cb.dataset.delta) delta += parseInt(cb.dataset.delta, 10);
            });
            if (totalEl) totalEl.textContent = fmt(base + delta);
            if (liveEl) {
                if (delta !== 0) {
                    liveEl.hidden = false;
                    liveEl.textContent = '(' + (delta > 0 ? '+' : '') + fmt(delta) + ')';
                } else {
                    liveEl.hidden = true;
                    liveEl.textContent = '';
                }
            }
        };

        form.addEventListener('change', update);
        update();

        // Chevron toggle for options panel
        const toggle = form.querySelector('.menu-card__options-toggle');
        const panel  = form.querySelector('.menu-card__options');
        if (toggle && panel) {
            toggle.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const expanded = toggle.getAttribute('aria-expanded') === 'true';
                toggle.setAttribute('aria-expanded', expanded ? 'false' : 'true');
                panel.hidden = expanded;
            });
        }
    });

    // Auto-expand the options panel if the URL targets a specific item
    // so customers arriving from /menu#sand-shep see it ready to customize.
    if (location.hash) {
        const target = document.querySelector(location.hash);
        if (target && target.classList.contains('menu-card')) {
            const toggle = target.querySelector('.menu-card__options-toggle');
            if (toggle && toggle.getAttribute('aria-expanded') !== 'true') {
                toggle.click();
            }
        }
    }
})();