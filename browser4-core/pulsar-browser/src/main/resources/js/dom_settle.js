
(() => {
    try {
        const w = window;
        const key = '__DOM_STATE_VAR__';
        if (!w[key]) {
            const state = {
                stamp: 0,
                lastTs: (performance && performance.now) ? performance.now() : Date.now(),
                observer: null,
                eventsBound: false,
                getSignature: function() {
                    const rs = document.readyState;
                    const rsCode = rs === 'complete' ? 2 : (rs === 'interactive' ? 1 : 0);
                    // Pack into a 53-bit safe integer: (stamp << 2) | rsCode
                    return (this.stamp * 4) + rsCode;
                }
            };
            
            // Use Object.defineProperty to make it non-enumerable (hidden from for..in loop)
            Object.defineProperty(w, key, {
                value: state,
                enumerable: false,
                writable: true,
                configurable: true
            });

            const obs = new MutationObserver(() => {
                state.stamp++;
                state.lastTs = (performance && performance.now) ? performance.now() : Date.now();
            });
            
            // Observe subtree text/content/node additions; attributes are OFF to reduce noise
            const opts = { subtree: true, childList: true, characterData: true };
            // Intentionally DO NOT observe attributes or set attributeFilter
            obs.observe(document, opts);
            state.observer = obs;

            // Bind lifecycle/navigation-ish events once to bump the stamp on non-mutation transitions
            if (!state.eventsBound) {
                const bump = () => { try { state.stamp++; } catch(_) {} };
                document.addEventListener('readystatechange', bump, { once: false, passive: true });
                document.addEventListener('DOMContentLoaded', bump, { once: true, passive: true });
                window.addEventListener('load', bump, { once: false, passive: true });
                window.addEventListener('pageshow', bump, { once: false, passive: true });
                window.addEventListener('hashchange', bump, { once: false, passive: true });
                window.addEventListener('popstate', bump, { once: false, passive: true });
                document.addEventListener('visibilitychange', bump, { once: false, passive: true });
                state.eventsBound = true;
            }
        }
        return 1;
    } catch (e) {
        return -1;
    }
})()
