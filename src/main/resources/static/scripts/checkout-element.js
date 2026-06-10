/* ============================================================
 *  Checkout page — lazy-mount Stripe Elements flow.
 *
 *  Behavior
 *  --------
 *   - On page load, the server has created the Order but NOT a PaymentIntent.
 *     We POST /checkout/init-payment to lazily create (or fetch) the PI,
 *     then mount the Payment Element with the returned client_secret. This
 *     keeps bots, crawlers, and prefetchers from burning Stripe API calls.
 *   - When fulfillment, delivery quote, or save-card toggle changes, we POST
 *     to /checkout/update-intent (debounced) to refresh the Order + push the
 *     new amount up to Stripe. The same client_secret stays valid.
 *   - Delivery fee is authoritative on the server: the quote endpoint
 *     persists a quoteId on the Order, and update-intent re-derives the fee
 *     from that quoteId. The client never sends a fee amount.
 *   - On "Pay", we call elements.submit() + stripe.confirmPayment(); Stripe
 *     redirects to /order/confirm on success.
 *
 *  Logging
 *  -------
 *   - DEBUG = true: console.group/debug at every lifecycle point + an
 *     in-page debug panel toggleable with Ctrl+Shift+D.
 *   - Flip DEBUG to false (or wire to a server-rendered flag) for prod.
 * ============================================================ */

(() => {
    'use strict';

    // ----- Bootstrap from data attributes -----
    const script = document.currentScript;

    const DEBUG = script.dataset.debug === 'true';
    const TAX_RATE = parseFloat(script.dataset.taxRate) || 0.07;
    const UPDATE_DEBOUNCE_MS = 300;

    const cfg = {
        subtotalCents:   parseInt(script.dataset.subtotalCents || '0', 10),
        stripeKey:       script.dataset.stripeKey || '',
        orderId:         script.dataset.orderId || '',
        orderTotalCents: parseInt(script.dataset.orderTotalCents || '0', 10),
        initUrl:         script.dataset.initUrl,
        csrfHeader:      script.dataset.csrfHeader,
        csrfToken:       script.dataset.csrfToken,
        returnUrlBase:   script.dataset.returnUrl
    };

    // ----- Logger -----
    const log = {
        info:  (...a) => DEBUG && console.info('[checkout]', ...a),
        debug: (...a) => DEBUG && console.debug('[checkout]', ...a),
        warn:  (...a) => console.warn('[checkout]', ...a),
        error: (...a) => console.error('[checkout]', ...a),
        group: (label, fn) => {
            if (!DEBUG) { try { fn(); } catch (e) { console.error(e); } return; }
            console.group('[checkout] ' + label);
            try { fn(); } finally { console.groupEnd(); }
        }
    };

    log.group('init', () => {
        log.debug('config', cfg);
        log.debug('Stripe.js global available:', typeof window.Stripe !== 'undefined');
    });

    // ----- Bail-out guard if essentials are missing -----
    if (!cfg.stripeKey) {
        log.error('Missing Stripe publishable key — cannot mount Element.', cfg);
        showFatalError(
            'Payment system could not initialize. Please refresh, or contact support if this persists.');
        wireDebugPanel('fatal');
        return;
    }
    if (!cfg.initUrl) {
        log.error('Missing data-init-url on the checkout script tag — template misconfiguration.', cfg);
        showFatalError(
            'Payment system could not initialize. Please refresh, or contact support if this persists.');
        wireDebugPanel('missing-init-url');
        return;
    }
    if (typeof window.Stripe === 'undefined') {
        log.error('Stripe.js did not load. Check network / tracker blocking.');
        showFatalError(
            'Payment scripts were blocked. Disable strict tracking protection for this page and reload.');
        wireDebugPanel('stripe-blocked');
        return;
    }

    const stripe = Stripe(cfg.stripeKey);

    // ----- State -----
    const state = {
        clientSecret: null,             // populated by initPayment()
        orderId:      cfg.orderId,
        paymentIntentId: null,
        currentTotalCents: cfg.orderTotalCents,
        elements: null,
        paymentElement: null,
        mounted: false,
        lastFetch: null,
        lastError: null
    };

    // ----- Element refs -----
    const $form             = document.getElementById('checkout-form');
    const $errBox           = document.getElementById('checkout-error');
    const $fulfillment      = document.querySelectorAll('input[name="fulfillment"]');
    const $deliverySec      = document.getElementById('delivery-address-section');
    const $newBlock         = document.getElementById('new-address-block');
    const $selectedAddr     = document.getElementById('selectedAddressId');
    const $quoteBtn         = document.getElementById('quote-btn');
    const $quoteResult      = document.getElementById('quote-result');
    const $deliveryQuoteId  = document.getElementById('delivery-quote-id');
    const $deliveryRow      = document.getElementById('delivery-row');
    const $deliveryDisp     = document.getElementById('delivery-display');
    const $taxDisp          = document.getElementById('tax-display');
    const $totalDisp        = document.getElementById('total-display');
    const $payBtn           = document.getElementById('pay-btn');
    const $saveCardInput    = document.getElementById('saveCard');

    // ----- Helpers -----
    const fmt = (cents) => '$' + (cents / 100).toFixed(2);
    const getFulfillment = () =>
        Array.from($fulfillment).find(r => r.checked)?.value || 'pickup';
    const isDelivery = () => getFulfillment() === 'delivery';
    const isUsingSavedAddress = () =>
        $selectedAddr && $selectedAddr.value && $selectedAddr.value !== '__new__';
    const hasDeliveryQuote = () =>
        !!($deliveryQuoteId && $deliveryQuoteId.value);
    const csrfHeaders = (extra) => ({
        ...extra,
        [cfg.csrfHeader]: cfg.csrfToken
    });

    function showError(msg) {
        state.lastError = msg;
        $errBox.textContent = msg;
        $errBox.hidden = false;
        $errBox.scrollIntoView({ behavior: 'smooth', block: 'center' });
        renderDebug();
    }
    function showFatalError(msg) {
        // Used before $errBox might be queried; fall back to alert if needed.
        const el = document.getElementById('checkout-error');
        if (el) { el.textContent = msg; el.hidden = false; }
    }
    function clearError() {
        state.lastError = null;
        $errBox.hidden = true;
        $errBox.textContent = '';
        renderDebug();
    }
    function setPayBusy(busy) {
        $payBtn.disabled = busy;
        $payBtn.textContent = busy ? 'Processing…' : 'Pay →';
    }

    // ----- Totals (display only; server is source of truth on submit) -----
    function recomputeTotals(serverTotals) {
        if (serverTotals) {
            // Trust server values when present.
            state.currentTotalCents = serverTotals.totalCents;
            $taxDisp.textContent = fmt(serverTotals.taxCents);
            $totalDisp.textContent = fmt(serverTotals.totalCents);
            if (serverTotals.deliveryFeeCents > 0) {
                $deliveryRow.hidden = false;
                $deliveryDisp.textContent = fmt(serverTotals.deliveryFeeCents);
            } else {
                $deliveryRow.hidden = true;
            }
        } else {
            // Local estimate — used before any server response. The server
            // owns delivery fees now (via the quoteId flow), so we don't
            // estimate delivery locally; we just hide the row until the
            // next update-intent returns authoritative totals.
            const tax = Math.round(cfg.subtotalCents * TAX_RATE);
            const total = cfg.subtotalCents + tax;
            state.currentTotalCents = total;
            $taxDisp.textContent = fmt(tax);
            $totalDisp.textContent = fmt(total);
            $deliveryRow.hidden = true;
        }
        renderDebug();
    }

    // ----- Lazy PaymentIntent init -----
    async function initPayment() {
        log.group('init-payment', () => log.debug('POST', cfg.initUrl));
        const res = await fetch(cfg.initUrl, {
            method: 'POST',
            headers: csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({})
        });
        state.lastFetch = { url: cfg.initUrl, status: res.status };
        if (!res.ok) {
            const txt = await res.text();
            throw new Error(txt || ('HTTP ' + res.status));
        }
        const data = await res.json();
        state.clientSecret = data.clientSecret;
        state.paymentIntentId = data.paymentIntentId;
        state.currentTotalCents = data.orderTotalCents;
        log.debug('init-payment result', {
            pi: data.paymentIntentId,
            total: data.orderTotalCents
        });
        renderDebug();
    }

    // ----- Mount Payment Element (after lazy init) -----
    function mountElement() {
        log.group('mount Element', () => {
            try {
                state.elements = stripe.elements({
                    clientSecret: state.clientSecret,
                    appearance: {
                        theme: 'stripe',
                        variables: {
                            fontFamily: 'inherit',
                            borderRadius: '8px'
                        }
                    }
                });
                state.paymentElement = state.elements.create('payment', {
                    layout: 'tabs',
                    fields: {
                        billingDetails: {
                            name: 'auto',
                            email: 'auto',
                            phone: 'auto',
                            address: 'auto'
                        }
                    }
                });
                state.paymentElement.on('ready', () => {
                    log.info('Payment Element ready');
                    state.mounted = true;
                    renderDebug();
                });
                state.paymentElement.on('loaderror', (event) => {
                    log.error('Payment Element load error', event);
                    showError('Payment form failed to load: ' + (event.error?.message || 'unknown'));
                });
                state.paymentElement.mount('#payment-element');
                log.info('Payment Element mount() called');
            } catch (e) {
                log.error('Mount threw', e);
                showError('Could not initialize the payment form.');
            }
        });
    }

    // ----- Build payload for /checkout/update-intent -----
    function collectFormData() {
        const form = new FormData($form);
        const addrPayload = isUsingSavedAddress()
            ? { selectedAddressId: $selectedAddr.value, address: null }
            : {
                selectedAddressId: null,
                address: {
                    label:      document.getElementById('addr-label')?.value || null,
                    line1:      document.getElementById('addr-line1')?.value?.trim() || null,
                    line2:      document.getElementById('addr-line2')?.value?.trim() || null,
                    city:       document.getElementById('addr-city')?.value?.trim() || null,
                    state:      document.getElementById('addr-state')?.value?.trim() || null,
                    postalCode: document.getElementById('addr-zip')?.value?.trim() || null,
                    country:    'US',
                    notes:      null
                }
            };
        return {
            name:              (form.get('name')  || '').toString().trim(),
            email:             (form.get('email') || '').toString().trim(),
            phone:             (form.get('phone') || '').toString().trim(),
            fulfillment:       getFulfillment(),
            selectedAddressId: isDelivery() ? addrPayload.selectedAddressId : null,
            address:           isDelivery() ? addrPayload.address : null,
            saveAddress:       isDelivery() && !!document.getElementById('saveAddress')?.checked,
            saveCard:          !!$saveCardInput?.checked,
            deliveryQuoteId:   isDelivery() && hasDeliveryQuote() ? $deliveryQuoteId.value : null
        };
    }

    // ----- Debounced update -----
    let updateTimer = null;
    function scheduleUpdate(reason) {
        if (updateTimer) clearTimeout(updateTimer);
        updateTimer = setTimeout(() => fireUpdate(reason), UPDATE_DEBOUNCE_MS);
    }

    async function fireUpdate(reason) {
        const body = collectFormData();
        log.group('update-intent (' + reason + ')', () => log.debug('body', body));
        try {
            const res = await fetch('/checkout/update-intent', {
                method: 'POST',
                headers: csrfHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify(body)
            });
            state.lastFetch = { url: '/checkout/update-intent', status: res.status, reason };
            if (!res.ok) {
                const txt = await res.text();
                throw new Error(txt || ('HTTP ' + res.status));
            }
            const totals = await res.json();
            log.debug('totals', totals);
            recomputeTotals(totals);
            clearError();
        } catch (e) {
            log.error('update-intent failed', e);
            showError('Could not update order. ' + (e.message || ''));
        }
    }

    // ----- Fulfillment toggle -----
    $fulfillment.forEach(r => r.addEventListener('change', () => {
        log.debug('fulfillment changed →', getFulfillment());
        $deliverySec.hidden = !isDelivery();
        if (!isDelivery()) {
            // Leaving delivery — drop any in-flight quote.
            if ($deliveryQuoteId) $deliveryQuoteId.value = '';
            $quoteResult.textContent = '';
        }
        recomputeTotals();
        scheduleUpdate('fulfillment');
    }));

    // ----- Save card toggle -----
    if ($saveCardInput) {
        $saveCardInput.addEventListener('change', () => {
            log.debug('saveCard toggled →', $saveCardInput.checked);
            scheduleUpdate('save-card');
        });
    }

    // ----- Saved-address vs new-address toggle -----
    if ($selectedAddr) {
        $selectedAddr.addEventListener('change', () => {
            const newMode = $selectedAddr.value === '__new__';
            $newBlock.hidden = !newMode;
            // Changing address invalidates the quote — user must re-quote.
            if ($deliveryQuoteId) $deliveryQuoteId.value = '';
            $quoteResult.textContent = '';
            recomputeTotals();
            // No update-intent call here; we wait until they get a new quote.
        });
    }

    // ----- Delivery quote -----
    if ($quoteBtn) {
        $quoteBtn.addEventListener('click', async () => {
            clearError();
            $quoteResult.textContent = 'Getting quote…';

            let addressLine;
            if (isUsingSavedAddress()) {
                const opt = $selectedAddr.options[$selectedAddr.selectedIndex];
                addressLine = opt.textContent.replace(/^.*?— /, '').trim();
            } else {
                const body = collectFormData();
                const a = body.address;
                if (!a || !a.line1 || !a.city || !a.state || !a.postalCode) {
                    showError('Please fill in the delivery address first.');
                    $quoteResult.textContent = '';
                    return;
                }
                addressLine = [a.line1, a.line2, a.city]
                        .filter(Boolean).join(', ')
                    + ', ' + a.state + ' ' + a.postalCode;
            }

            log.group('quote-delivery', () => log.debug('address', addressLine));
            try {
                const res = await fetch('/checkout/quote-delivery', {
                    method: 'POST',
                    headers: csrfHeaders({ 'Content-Type': 'application/json' }),
                    body: JSON.stringify({ address: addressLine })
                });
                state.lastFetch = { url: '/checkout/quote-delivery', status: res.status };
                if (!res.ok) throw new Error('Quote failed');
                const q = await res.json();
                log.debug('quote', q);
                if ($deliveryQuoteId) $deliveryQuoteId.value = q.quoteId;
                $quoteResult.textContent = `${q.feeDisplay} · ETA ${q.eta} (${q.provider})`;
                // Totals will be refreshed by the update-intent below — no
                // need to display a local estimate in the meantime.
                scheduleUpdate('delivery-quote');
            } catch (e) {
                log.error('quote failed', e);
                showError('Sorry — we couldn\'t quote that address. Try again or use pickup.');
                $quoteResult.textContent = '';
            }
        });
    }

    // ----- Pay -----
    $payBtn.addEventListener('click', async () => {
        clearError();
        setPayBusy(true);
        log.group('pay', () => log.debug('state', state));
        try {
            // Make sure any pending updates have flushed.
            if (updateTimer) {
                clearTimeout(updateTimer);
                await fireUpdate('pre-pay-flush');
            }

            if (!state.mounted) {
                showError('Payment form is still loading — please wait a moment and try again.');
                setPayBusy(false);
                return;
            }

            const { error: submitErr } = await state.elements.submit();
            if (submitErr) {
                log.warn('elements.submit error', submitErr);
                showError(submitErr.message || 'Please complete payment details.');
                setPayBusy(false);
                return;
            }

            const returnUrl = window.location.origin + cfg.returnUrlBase
                + '?order_id=' + encodeURIComponent(state.orderId);

            log.info('Confirming payment, return_url=', returnUrl);
            const { error } = await stripe.confirmPayment({
                elements: state.elements,
                clientSecret: state.clientSecret,
                confirmParams: { return_url: returnUrl }
            });

            // We only reach here on immediate error; success → redirect.
            if (error) {
                log.warn('confirmPayment error', error);
                showError(error.message || 'Payment was not completed.');
                setPayBusy(false);
            }
        } catch (e) {
            log.error('pay handler threw', e);
            showError('Something went wrong. Please try again.');
            setPayBusy(false);
        }
    });

    // ============================================================
    //  Debug panel
    // ============================================================
    function wireDebugPanel(initialNote) {
        const panel = document.getElementById('debug-panel');
        if (!panel) return;

        document.addEventListener('keydown', (e) => {
            if (e.ctrlKey && e.shiftKey && (e.key === 'D' || e.key === 'd')) {
                e.preventDefault();
                panel.hidden = !panel.hidden;
                if (!panel.hidden) renderDebug();
            }
        });
        document.getElementById('debug-close')?.addEventListener('click', () => {
            panel.hidden = true;
        });
        document.getElementById('debug-refresh')?.addEventListener('click', renderDebug);

        if (initialNote) {
            log.warn('Debug panel initial note:', initialNote);
        }
    }

    function renderDebug() {
        const panel = document.getElementById('debug-panel');
        if (!panel || panel.hidden) return;
        const set = (id, val) => {
            const el = document.getElementById(id);
            if (el) el.textContent = (val === null || val === undefined) ? '—' : String(val);
        };
        set('dbg-stripejs',  typeof window.Stripe !== 'undefined' ? 'loaded' : 'BLOCKED');
        set('dbg-pk',        cfg.stripeKey ? (cfg.stripeKey.slice(0, 10) + '…') : 'missing');
        set('dbg-cs',        state.clientSecret ? (state.clientSecret.slice(0, 24) + '…') : 'missing');
        set('dbg-order',     state.orderId);
        set('dbg-pi',        state.paymentIntentId);
        set('dbg-elem',      state.mounted ? 'mounted' : 'mounting');
        set('dbg-fetch',     state.lastFetch
            ? `${state.lastFetch.url} → ${state.lastFetch.status}${state.lastFetch.reason ? ' (' + state.lastFetch.reason + ')' : ''}`
            : '—');
        set('dbg-err',       state.lastError);
        set('dbg-total',     fmt(state.currentTotalCents));
    }

    // ============================================================
    //  Boot
    // ============================================================
    wireDebugPanel(null);
    $deliverySec.hidden = !isDelivery();
    recomputeTotals();
    initPayment()
        .then(mountElement)
        .catch(e => {
            log.error('init-payment failed', e);
            showError('Could not initialize the payment form. Please refresh.');
        });
    log.info('Boot complete (Element mount deferred until init-payment resolves)');
})
();