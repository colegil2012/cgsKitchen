(function () {
    const recurringField = document.getElementById('recurring-field');
    const oneTime = document.getElementById('one-time-fields');
    const recurring = document.getElementById('recurring-fields');
    const segOneTime = document.getElementById('seg-one-time');
    const segRecurring = document.getElementById('seg-recurring');

    function applyType(isRecurring) {
        recurringField.value = isRecurring ? 'true' : 'false';
        oneTime.style.display = isRecurring ? 'none' : '';
        recurring.style.display = isRecurring ? '' : 'none';
        segOneTime.classList.toggle('admin__segment--active', !isRecurring);
        segRecurring.classList.toggle('admin__segment--active', isRecurring);
    }

    segOneTime.addEventListener('click', () => applyType(false));
    segRecurring.addEventListener('click', () => applyType(true));

    // Initial state from the bound field (set by fromEvent/fromSeries).
    applyType(recurringField.value === 'true');

    // Day rows: enable/disable the time inputs with the day checkbox.
    document.querySelectorAll('.day-enable').forEach(function (cb) {
        const day = cb.getAttribute('data-day');
        const start = document.querySelector('.day-start[data-day="' + day + '"]');
        const end = document.querySelector('.day-end[data-day="' + day + '"]');
        function sync() {
            const on = cb.checked;
            start.disabled = !on;
            end.disabled = !on;
            cb.closest('.admin__day-row').classList.toggle('admin__day-row--on', on);
        }
        cb.addEventListener('change', sync);
        sync();
    });
})();