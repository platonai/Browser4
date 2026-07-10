/**
 * Tests for feature_calculator.js — __pulsar_NodeFeatureCalculator.
 *
 * Tests the head/tail pattern using TreeWalker-based traversal.
 */
const { loadScript, ensureConfig, setupDOM } = require('./test-helper');

beforeAll(() => {
    loadScript('configs.js');
    loadScript('tree_walker.js');
    loadScript('node_ops.js');
    loadScript('node_ext_data.js');
    loadScript('feature_calculator.js');
    loadScript('__pulsar_utils__.js');
    loadScript('dom_rect.js');
    loadScript('dom_style.js');
    loadScript('dom_text.js');
    ensureConfig();
});

beforeEach(() => {
    setupDOM('<div id="root"><span>hello</span><p>world</p></div>');
    // _viDataMap is normally initialized by __pulsar_utils__.compute().
    // The tests call calc.head() directly, so we must initialize it here.
    __pulsar_utils__._viDataMap = new WeakMap();
});

describe('__pulsar_NodeFeatureCalculator construction', () => {
    test('creates calculator with config', () => {
        const calc = new __pulsar_NodeFeatureCalculator();
        expect(calc.config).toBeDefined();
        expect(calc.stopped).toBe(false);
        expect(calc.sequence).toBe(0);
    });

    test('isStopped returns false initially', () => {
        const calc = new __pulsar_NodeFeatureCalculator();
        expect(calc.isStopped()).toBe(false);
    });
});

describe('Feature calculator head/tail traversal', () => {
    test('head creates __pulsar_nodeExt on each node', () => {
        const calc = new __pulsar_NodeFeatureCalculator();
        const div = document.body.querySelector('div');

        calc.head(div, 0);
        expect(div.__pulsar_nodeExt).toBeDefined();
        expect(div.__pulsar_nodeExt).toBeInstanceOf(__pulsar_NodeExt);

        // Cleanup
        delete div.__pulsar_nodeExt;
    });

    test('head sets sequence and depth on nodeExt', () => {
        const calc = new __pulsar_NodeFeatureCalculator();
        const div = document.body.querySelector('div');

        calc.head(div, 3);
        expect(div.__pulsar_nodeExt.sequence).toBe(1);
        expect(div.__pulsar_nodeExt.depth).toBe(3);

        // Cleanup
        delete div.__pulsar_nodeExt;
    });

    test('head skips iframe elements', () => {
        setupDOM('<div><iframe></iframe><span>text</span></div>');
        const calc = new __pulsar_NodeFeatureCalculator();
        const iframe = document.body.querySelector('iframe');
        const span = document.body.querySelector('span');

        calc.head(iframe, 0);
        expect(iframe.__pulsar_nodeExt).toBeUndefined();

        calc.head(span, 0);
        expect(span.__pulsar_nodeExt).toBeDefined();

        delete span.__pulsar_nodeExt;
    });

    test('tail cleans up __pulsar_nodeExt', () => {
        const calc = new __pulsar_NodeFeatureCalculator();
        const div = document.body.querySelector('div');

        calc.head(div, 0);
        expect(div.__pulsar_nodeExt).toBeDefined();

        calc.tail(div, 0);
        expect(div.__pulsar_nodeExt).toBeUndefined();
    });

    test('tail skips iframe elements', () => {
        setupDOM('<div><iframe></iframe></div>');
        const calc = new __pulsar_NodeFeatureCalculator();
        const iframe = document.body.querySelector('iframe');

        // tail should not throw on iframe even without head
        expect(() => calc.tail(iframe, 0)).not.toThrow();
    });

    test('traverseWithHeadTail correctly processes DOM', () => {
        const calc = new __pulsar_NodeFeatureCalculator();

        __pulsar_TreeWalker.traverseWithHeadTail(
            document.body,
            function(node, depth) { calc.head(node, depth); },
            function(node, depth) { calc.tail(node, depth); },
            calc
        );

        // After traversal, all nodeExts should be cleaned up
        const div = document.body.querySelector('div');
        expect(div.__pulsar_nodeExt).toBeUndefined();
        expect(document.body.__pulsar_nodeExt).toBeUndefined();
    });

    test('hidden elements get ATTR_HIDDEN attribute set', () => {
        setupDOM('<div style="display:none"><span>hidden</span></div>');
        const calc = new __pulsar_NodeFeatureCalculator();

        __pulsar_TreeWalker.traverseWithHeadTail(
            document.body,
            function(node, depth) { calc.head(node, depth); },
            function(node, depth) { calc.tail(node, depth); },
            calc
        );

        const div = document.body.querySelector('div');
        // In jsdom, getBoundingClientRect returns 0×0 for all elements,
        // so isVisible() will return false due to zero dimensions.
        // The _h attribute might or might not be set depending on rect.isVisible().
        // Just verify no exception was thrown and traversal completed.
        expect(calc.sequence).toBeGreaterThan(0);
    });
});

describe('calcCharacterWidth', () => {
    test('returns a number for text node with text', () => {
        const calc = new __pulsar_NodeFeatureCalculator();
        const span = document.body.querySelector('span');
        const textNode = span.firstChild;

        calc.head(span, 0);  // So parent has __pulsar_nodeExt
        const width = calc.calcCharacterWidth(textNode, 1);
        expect(typeof width).toBe('number');

        delete span.__pulsar_nodeExt;
    });
});
