/* checkout.js — fulfillment toggle, delivery quote, live total recalc.
 *
 * Loaded once at the bottom of templates/checkout.html. The subtotal in
 * cents is passed via a data attribute so this script never has to
 * parse formatted prices.
 */
(function () {
    'use strict';

    const TAX_RATE = 0.07;

    // The script tag itself carries the subtotal — read from the
    // currently-executing script element.
    const script = document.currentScript;
    const subtotalCents = parseInt(script.dataset.subtotalCents || '0', 10);

    const fulfillmentInputs = document.querySelectorAll('input[name="fulfillment"]');
    const deliverySection = document.getElementById('delivery-address-section');
    const addressInput = document.getElementById('address');
    const quoteBtn = document.getElementById('quote-btn');
    const quoteResult = document.getElementById('quote-result');
    const deliveryFeeInput = document.getElementById('delivery-fee-cents');

    const taxDisplay = document.getElementById('tax-display');
    const totalDisplay = document.getElementById('total-display');
    const deliveryRow = document.getElementById('delivery-row');
    const deliveryDisplay = document.getElementById('delivery-display');

    let deliveryFeeCents = 0;

    function fmt(cents) {
        return '$' + (cents / 100).toFixed(2);
    }

    function recalc() {
        const taxCents = Math.round(subtotalCents * TAX_RATE);
        const totalCents = subtotalCents + taxCents + deliveryFeeCents;

        if (taxDisplay) taxDisplay.textContent = fmt(taxCents);
        if (totalDisplay) totalDisplay.textContent = fmt(totalCents);

        if (deliveryFeeCents > 0) {
            deliveryRow.hidden = false;
            deliveryDisplay.textContent = fmt(deliveryFeeCents);
        } else {
            deliveryRow.hidden = true;
        }
    }

    function setFulfillment(value) {
        if (value === 'delivery') {
            deliverySection.hidden = false;
        } else {
            deliverySection.hidden = true;
            deliveryFeeCents = 0;
            if (deliveryFeeInput) deliveryFeeInput.value = '';
            if (quoteResult) quoteResult.textContent = '';
            recalc();
        }
    }

    fulfillmentInputs.forEach(function (input) {
        input.addEventListener('change', function () {
            setFulfillment(input.value);
        });
    });
    // Initialize from currently-checked radio
    const initial = document.querySelector('input[name="fulfillment"]:checked');
    if (initial) setFulfillment(initial.value);

    if (quoteBtn) {
        quoteBtn.addEventListener('click', async function () {
            const address = addressInput.value.trim();
            if (!address) return;

            quoteBtn.disabled = true;
            quoteResult.textContent = 'Getting a quote…';

            try {
                const res = await fetch('/checkout/quote-delivery', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ address: address })
                });
                if (!res.ok) throw new Error('quote failed');
                const data = await res.json();

                deliveryFeeCents = data.feeCents;
                deliveryFeeInput.value = data.feeCents;
                quoteResult.textContent = data.feeDisplay + ' · ETA ' + data.eta;
                quoteResult.classList.remove('quote-result-error');
                quoteResult.classList.add('quote-result-ok');
                recalc();
            } catch (e) {
                quoteResult.textContent =
                    "Sorry — we can't deliver to that address right now.";
                quoteResult.classList.remove('quote-result-ok');
                quoteResult.classList.add('quote-result-error');
                deliveryFeeCents = 0;
                deliveryFeeInput.value = '';
                recalc();
            } finally {
                quoteBtn.disabled = false;
            }
        });
    }

    // Initial recalc
    recalc();
})();
