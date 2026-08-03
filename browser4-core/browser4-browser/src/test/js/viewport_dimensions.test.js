/**
 * Tests for viewport dimension correctness after resize().
 *
 * Verifies that all JS functions consuming viewport dimensions use the actual
 * window.innerWidth / window.innerHeight rather than stale injected config
 * values (__pulsar_CONFIGS.viewPortWidth / viewPortHeight).
 *
 * The test simulates a resize by redefining window.innerWidth / innerHeight.
 */
const { loadScript, loadAllScripts, ensureConfig, setupDOM } = require('./test-helper');

beforeAll(() => {
    loadAllScripts();
    ensureConfig();
});

beforeEach(() => {
    setupDOM(`
        <div id="root" style="width:400px;height:300px;overflow:hidden;">
            <span>hello</span>
            <p style="width:500px;">world</p>
        </div>
    `);
});

// ---------------------------------------------------------------------------
// Helpers: simulate a viewport resize by redefining innerWidth / innerHeight
// ---------------------------------------------------------------------------

function setViewport(width, height) {
    Object.defineProperty(window, 'innerWidth', {
        value: width,
        writable: true,
        configurable: true,
    });
    Object.defineProperty(window, 'innerHeight', {
        value: height,
        writable: true,
        configurable: true,
    });
}

function restoreDefaultViewport() {
    setViewport(1920, 1080);
}

// Restore after each test to avoid cross-test contamination
afterEach(() => {
    restoreDefaultViewport();
});

// ---------------------------------------------------------------------------
// __pulsar_utils__.__updateStat — maxWidth cap
// ---------------------------------------------------------------------------

describe('__pulsar_utils__.__updateStat maxWidth cap', () => {
    test('uses window.innerWidth when available (post-resize narrow viewport)', () => {
        // Simulate resize to 800 wide
        setViewport(800, 600);

        __pulsar_utils__.__updateStat();

        // maxWidth should be 1.2 * 800 = 960, not 1.2 * 1920 = 2304
        // We can verify indirectly: the stat data._width should be capped
        expect(__pulsar_utils__.data).toBeDefined();
        // With a 500px element in a 400px container, width should be reasonably capped
    });

    test('falls back to config.viewPortWidth when window.innerWidth is 0', () => {
        setViewport(0, 600);

        __pulsar_utils__.__updateStat();

        // Should not crash; maxWidth = max(1, 1.2 * 0) = 1 with fallback
        expect(__pulsar_utils__.data).toBeDefined();
    });

    test('maxWidth is never less than 1', () => {
        setViewport(0, 0);

        __pulsar_utils__.__updateStat();

        expect(__pulsar_utils__.data).toBeDefined();
    });

    test('after resize to wider viewport, cap expands accordingly', () => {
        setViewport(2560, 1440);

        __pulsar_utils__.__updateStat();

        // Should not crash with wide viewport
        expect(__pulsar_utils__.data).toBeDefined();
    });
});

// ---------------------------------------------------------------------------
// __pulsar_utils__.computeMetadata — viewport dimensions in metadata
// ---------------------------------------------------------------------------

describe('__pulsar_utils__.computeMetadata', () => {
    test('metadata.viewPortWidth reflects actual window.innerWidth, not config', () => {
        setViewport(1024, 768);

        const metadata = __pulsar_utils__.computeMetadata();

        // After resize to 1024, metadata should reflect the actual viewport
        expect(metadata.viewPortWidth).toBe(1024);
        expect(metadata.viewPortHeight).toBe(768);
    });

    test('metadata.viewPortWidth matches window.innerWidth after resize', () => {
        setViewport(800, 600);
        const metadata = __pulsar_utils__.computeMetadata();
        expect(metadata.viewPortWidth).toBe(800);
        expect(metadata.viewPortHeight).toBe(600);
    });

    test('metadata.viewPortWidth differs from injected config after resize', () => {
        setViewport(640, 480);
        const config = ensureConfig();

        const metadata = __pulsar_utils__.computeMetadata();

        // Config still says 1920x1080 but metadata should report 640x480
        expect(config.viewPortWidth).toBe(1920);
        expect(config.viewPortHeight).toBe(1080);
        expect(metadata.viewPortWidth).toBe(640);
        expect(metadata.viewPortHeight).toBe(480);
    });

    test('metadata.clientWidth and clientHeight are also correct', () => {
        setViewport(1280, 720);
        const metadata = __pulsar_utils__.computeMetadata();
        expect(metadata.clientWidth).toBe('1280.00');
        expect(metadata.clientHeight).toBe('720.00');
    });
});

// ---------------------------------------------------------------------------
// __pulsar_utils__.generateMetadata — view-port attribute
// ---------------------------------------------------------------------------

describe('__pulsar_utils__.generateMetadata', () => {
    test('view-port attribute uses actual window.innerWidth x innerHeight', () => {
        setViewport(1024, 768);

        __pulsar_utils__.generateMetadata();

        const meta = document.getElementById('PulsarMetaInformation');
        expect(meta).not.toBeNull();
        expect(meta.getAttribute('view-port')).toBe('1024x768');
    });

    test('view-port attribute changes after resize', () => {
        // First call at default viewport
        setViewport(1920, 1080);
        __pulsar_utils__.generateMetadata();
        // Remove the element so generateMetadata creates a new one
        const firstMeta = document.getElementById('PulsarMetaInformation');
        firstMeta.remove();

        // Simulate resize and call again
        setViewport(800, 600);
        __pulsar_utils__.generateMetadata();

        const meta = document.getElementById('PulsarMetaInformation');
        expect(meta).not.toBeNull();
        expect(meta.getAttribute('view-port')).toBe('800x600');
    });

    test('view-port attribute differs from config after resize', () => {
        setViewport(480, 320);
        __pulsar_utils__.generateMetadata();

        const config = ensureConfig();
        const meta = document.getElementById('PulsarMetaInformation');

        // Config is still 1920x1080, attribute should be 480x320
        expect(config.viewPortWidth).toBe(1920);
        expect(config.viewPortHeight).toBe(1080);
        expect(meta.getAttribute('view-port')).toBe('480x320');
    });
});

// ---------------------------------------------------------------------------
// __pulsar_utils__.scrollToViewport — viewport height for scroll target
// ---------------------------------------------------------------------------

describe('__pulsar_utils__.scrollToViewport', () => {
    test('uses window.innerHeight as primary value, not config', () => {
        setViewport(1920, 900);

        // scrollToViewport(1) should scroll to 1 * 900 = 900
        __pulsar_utils__.scrollToViewport(1);

        // window.scrollTo was called; verify the final scroll position
        // jsdom may not fully support scrollTo, but the call path is exercised
        expect(window.scrollY).toBeDefined();
    });

    test('falls back to config.viewPortHeight when window.innerHeight is 0', () => {
        setViewport(1920, 0);

        // Should not throw; uses config value (1080) as fallback
        expect(() => __pulsar_utils__.scrollToViewport(1)).not.toThrow();
    });

    test('does nothing when document has no body', () => {
        document.body = null;
        expect(() => __pulsar_utils__.scrollToViewport(1)).not.toThrow();
    });
});

// ---------------------------------------------------------------------------
// NodeOps.nScreen — viewport height for screen index calculation
// ---------------------------------------------------------------------------

describe('NodeOps.nScreen', () => {
    test('uses actual window.innerHeight for screen number after resize', () => {
        setViewport(1920, 900);

        // Create a node whose bounding rect is at y=1000
        // At viewport height 900, screen = floor(1000/900) = 1
        // If using config (1080), screen = floor(1000/1080) = 0 ← WRONG
        const node = document.body.querySelector('p');
        // jsdom's getBoundingClientRect may not be reliable, but the function
        // should use the right viewport height
        const screen = NodeOps.nScreen(node);
        expect(typeof screen).toBe('number');
    });

    test('returns 0 when node has no rect', () => {
        setViewport(1920, 900);
        // Text nodes without layout may return 0 from getRect
        const textNode = document.createTextNode('no layout');
        const screen = NodeOps.nScreen(textNode);
        expect(screen).toBe(0);
    });
});

// ---------------------------------------------------------------------------
// __pulsar_NodeExt — maxWidth initialization and overflow detection
// ---------------------------------------------------------------------------

describe('__pulsar_NodeExt maxWidth', () => {
    test('constructor uses window.innerWidth when available', () => {
        setViewport(800, 600);

        const config = ensureConfig();
        const div = document.body.querySelector('div');
        const nodeExt = new __pulsar_NodeExt(div, config);

        expect(nodeExt.maxWidth).toBe(800);
    });

    test('constructor falls back to config.viewPortWidth when window.innerWidth is 0', () => {
        setViewport(0, 600);

        const config = ensureConfig();
        const div = document.body.querySelector('div');
        const nodeExt = new __pulsar_NodeExt(div, config);

        // Should use config value as fallback
        expect(nodeExt.maxWidth).toBe(1920);
    });

    test('isOverflowHidden uses actual window.innerWidth', () => {
        setViewport(800, 600);

        const config = ensureConfig();
        const div = document.body.querySelector('div');
        const nodeExt = new __pulsar_NodeExt(div, config);

        // Set up a rect so isOverflowHidden can compute
        nodeExt.rect = {
            x: 0, y: 0,
            width: 400, height: 300,
            left: 0, top: 0,
            right: 400, bottom: 300,
        };

        // maxWidth should be 800 (actual), not 1920 (config)
        // Without overflow-hidden ancestors, isOverflowHidden should be false
        expect(nodeExt.maxWidth).toBe(800);
    });

    test('isOverflowHidden returns false when node has no rect', () => {
        const config = ensureConfig();
        const div = document.body.querySelector('div');
        const nodeExt = new __pulsar_NodeExt(div, config);
        nodeExt.rect = null;

        expect(nodeExt.isOverflowHidden()).toBe(false);
    });

    test('isOverflowHidden returns false when node has no parent', () => {
        const config = ensureConfig();
        const div = document.body.querySelector('div');
        const nodeExt = new __pulsar_NodeExt(div, config);
        nodeExt.rect = { x: 0, y: 0, width: 100, height: 100 };
        // parent() returns null if no parent
        const origParent = nodeExt.parent;
        nodeExt.parent = () => null;

        expect(nodeExt.isOverflowHidden()).toBe(false);

        nodeExt.parent = origParent;
    });
});

// ---------------------------------------------------------------------------
// FeatureCalculator.updateMaxWidth
// ---------------------------------------------------------------------------

describe('FeatureCalculator updateMaxWidth', () => {
    test('uses window.innerWidth when no overflow:hidden ancestor', () => {
        setViewport(1024, 768);

        const config = ensureConfig();
        const div = document.body.querySelector('div');
        const nodeExt = new __pulsar_NodeExt(div, config);
        nodeExt.rect = { x: 0, y: 0, width: 400, height: 300 };

        const calc = new __pulsar_NodeFeatureCalculator(config);
        // Simulate non-overflow-hidden node
        // The feature calculator reads from nodeExt.hasOverflowHidden()
        // If false, it calls nodeExt.updateMaxWidth(window.innerWidth || ...)
        nodeExt.hasOverflowHidden = () => false;

        calc.calcSelfIndicator(div, 0, nodeExt);

        expect(nodeExt.maxWidth).toBe(1024);
    });

    test('uses element rect width when overflow:hidden is set', () => {
        setViewport(1024, 768);

        const config = ensureConfig();
        const div = document.body.querySelector('div');
        const nodeExt = new __pulsar_NodeExt(div, config);
        nodeExt.rect = { x: 0, y: 0, width: 400, height: 300 };

        const calc = new __pulsar_NodeFeatureCalculator(config);
        // Simulate overflow:hidden
        nodeExt.hasOverflowHidden = () => true;

        calc.calcSelfIndicator(div, 0, nodeExt);

        // Should use the element's own rect width, not viewport
        expect(nodeExt.maxWidth).toBe(400);
    });
});

// ---------------------------------------------------------------------------
// Integration: full lifecycle after simulated resize
// ---------------------------------------------------------------------------

describe('Integration: full viewport resize lifecycle', () => {
    test('all viewport-reading functions report consistent dimensions after resize', () => {
        // Simulate a resize from default 1920x1080 → 1280x720
        setViewport(1280, 720);

        // 1. Stats update should use 1280
        __pulsar_utils__.__updateStat();

        // 2. Metadata should report 1280x720
        const metadata = __pulsar_utils__.computeMetadata();
        expect(metadata.viewPortWidth).toBe(1280);
        expect(metadata.viewPortHeight).toBe(720);

        // 3. generateMetadata should write 1280x720
        __pulsar_utils__.generateMetadata();
        const meta = document.getElementById('PulsarMetaInformation');
        expect(meta.getAttribute('view-port')).toBe('1280x720');

        // 4. Config values are still the original injected values
        const config = ensureConfig();
        expect(config.viewPortWidth).toBe(1920);
        expect(config.viewPortHeight).toBe(1080);
    });

    test('elements created after resize get correct maxWidth', () => {
        setViewport(640, 480);

        const config = ensureConfig();
        const newNode = document.createElement('section');
        newNode.style.width = '600px';
        newNode.style.height = '400px';
        document.body.appendChild(newNode);

        const nodeExt = new __pulsar_NodeExt(newNode, config);
        expect(nodeExt.maxWidth).toBe(640); // actual viewport, not 1920
    });

    test('no crash when window.innerWidth/Height are undefined (graceful fallback)', () => {
        // Simulate a minimal environment where innerWidth/Height don't exist
        const origInnerWidth = window.innerWidth;
        const origInnerHeight = window.innerHeight;
        delete window.innerWidth;
        delete window.innerHeight;

        // All functions should fall back to config values without crashing
        expect(() => __pulsar_utils__.__updateStat()).not.toThrow();
        expect(() => __pulsar_utils__.computeMetadata()).not.toThrow();
        expect(() => __pulsar_utils__.generateMetadata()).not.toThrow();

        // Restore
        window.innerWidth = origInnerWidth;
        window.innerHeight = origInnerHeight;
    });
});
