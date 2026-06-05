/**
 * Browser4 Runtime Bridge
 *
 * This is the minimal bridge between Page World and Isolated World.
 * It provides a safe communication channel without exposing the runtime.
 */
(function() {
    'use strict';

    /**
     * Browser4 Runtime API exposed only in Isolated World.
     * This object is NOT accessible from page JavaScript.
     */
    const Browser4Runtime = {
        version: '1.0.0',

        /**
         * Check if we're in the isolated world.
         */
        isIsolatedWorld: function() {
            // In isolated world, this will be defined
            // In page world, it won't exist
            return typeof __browser4_runtime__ !== 'undefined';
        },

        /**
         * Execute a function safely in the page context.
         * Used by runtime to interact with page DOM without exposing itself.
         */
        executeInPageWorld: function(fn, ...args) {
            // This will be implemented by CDP
            // For now, just a placeholder
            console.warn('executeInPageWorld not yet implemented');
        },

        /**
         * Get runtime information.
         */
        getInfo: function() {
            return {
                version: this.version,
                isolated: this.isIsolatedWorld(),
                timestamp: Date.now()
            };
        }
    };

    // Expose to the isolated world only
    if (typeof window !== 'undefined') {
        Object.defineProperty(window, '__browser4_runtime__', {
            value: Browser4Runtime,
            writable: false,
            enumerable: false,
            configurable: false
        });
    }

    return Browser4Runtime;
})();
