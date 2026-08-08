/**
 * Test helper: loads Browser4 JS source files in dependency order.
 *
 * The source files use global `let` declarations (no ES modules),
 * so we read each file and eval it in the global scope, respecting
 * the load order that DualWorldScriptLoader uses.
 */
const fs = require('fs');
const path = require('path');

const JS_DIR = path.resolve(__dirname, '..', '..', 'main', 'resources', 'js');

// Load order must match DualWorldScriptLoader.ISOLATED_WORLD_RESOURCES
const LOAD_ORDER = [
    'configs.js',
    'tree_walker.js',
    'node_ops.js',
    'node_ext_data.js',
    'feature_calculator.js',
    '__pulsar_utils__.js',
    'dom_rect.js',
    'dom_style.js',
    'dom_text.js',
];

// Track which files have been loaded
const loaded = new Set();

/**
 * Load a single source file by name.
 *
 * The source files use top-level `let` declarations (e.g. `let __pulsar_TreeWalker = {}`).
 * In a browser <script> tag, these become properties of the global (`window`) object.
 *
 * Jest's jsdom environment makes `window` === `globalThis`, so to replicate browser
 * behavior we inject a <script> element into the jsdom document. This causes `let`
 * declarations to become window properties — exactly as in a real browser.
 */
function loadScript(filename) {
    if (loaded.has(filename)) return;
    const filepath = path.join(JS_DIR, filename);
    if (!fs.existsSync(filepath)) {
        throw new Error(`Source file not found: ${filepath}`);
    }
    const code = fs.readFileSync(filepath, 'utf-8');

    // Ensure document.head exists (jsdom may not create it automatically)
    if (!document.head) {
        const head = document.createElement('head');
        document.documentElement.insertBefore(head, document.body);
    }

    // Some source files reference __pulsar_CONFIGS which is normally injected
    // by DualWorldScriptLoader.generatePredefinedJsConfig(). Set a default if missing.
    if (typeof window.__pulsar_CONFIGS === 'undefined') {
        window.__pulsar_CONFIGS = {
            propertyNames: ['font-size', 'color', 'background-color'],
            viewPortWidth: 1920,
            viewPortHeight: 1080,
            ATTR_HIDDEN: '_h',
            ATTR_OVERFLOW_HIDDEN: '_oh',
            ATTR_OVERFLOW_VISIBLE: '_ov',
            ATTR_ELEMENT_NODE_VI: 'vi',
            ATTR_ELEMENT_NODE_DATA: '',
            ATTR_TEXT_NODE_VI: 'tv',
            ATTR_DEBUG: '_d',
            debug: 0,
            VI_COMPRESSION: 'none',
        };
    }

    // Inject as a <script> element so declarations become window properties.
    // In jsdom, script elements without external src are executed synchronously.
    const script = document.createElement('script');
    script.textContent = code;
    document.head.appendChild(script);
    // Clean up — remove the script element after execution
    document.head.removeChild(script);

    loaded.add(filename);
}

/**
 * Load all source files in the correct dependency order.
 * Call this in beforeAll() of each test suite.
 */
function loadAllScripts() {
    LOAD_ORDER.forEach(loadScript);
}

/**
 * Reset the load tracker (for tests that need a fresh state).
 */
function resetLoaded() {
    loaded.clear();
}

/**
 * Set up a minimal document body for DOM testing.
 * Call this in beforeEach() of each test suite.
 */
function setupDOM(html = '<div id="root"><span>hello</span><p>world</p></div>') {
    document.body.innerHTML = html;
}

/**
 * Get the __pulsar_CONFIGS for tests that need config.
 */
function ensureConfig() {
    if (typeof window.__pulsar_CONFIGS !== 'undefined') {
        return window.__pulsar_CONFIGS;
    }
    // __pulsar_CONFIGS is typically set by DualWorldScriptLoader via CDP.
    // For tests, set a default.
    window.__pulsar_CONFIGS = {
        propertyNames: ['font-size', 'color', 'background-color'],
        viewPortWidth: 1920,
        viewPortHeight: 1080,
        ATTR_HIDDEN: '_h',
        ATTR_OVERFLOW_HIDDEN: '_oh',
        ATTR_OVERFLOW_VISIBLE: '_ov',
        ATTR_ELEMENT_NODE_VI: 'vi',
        ATTR_ELEMENT_NODE_DATA: '',
        ATTR_TEXT_NODE_VI: 'tv',
        ATTR_DEBUG: '_d',
        debug: 0,
        VI_COMPRESSION: 'none',
    };
    return window.__pulsar_CONFIGS;
}

module.exports = {
    JS_DIR,
    LOAD_ORDER,
    loadScript,
    loadAllScripts,
    resetLoaded,
    setupDOM,
    ensureConfig,
};
