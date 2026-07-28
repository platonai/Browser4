/**
 * Tests for node_ext_data.js — __pulsar_NodeExt class (per-node feature data).
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
    setupDOM();
});

function createNodeExt(node, overrides = {}) {
    const config = Object.assign({}, __pulsar_CONFIGS, overrides);
    return new __pulsar_NodeExt(node, config);
}

describe('__pulsar_NodeExt constructor', () => {
    test('initializes with config and node', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);

        expect(ext.node).toBe(div);
        expect(ext.config).toBeDefined();
        expect(ext.maxWidth).toBe(1920);
        expect(ext.rect).toBeNull();
        expect(ext.depth).toBe(0);
        expect(ext.sequence).toBe(0);
        expect(ext.styles).toEqual({});
        expect(ext.propertyNames).toEqual([]);
    });
});

describe('NodeExt geometry helpers', () => {
    test('left/top/width/height read from rect', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        ext.rect = new DOMRect(10, 20, 100, 200);

        expect(ext.left()).toBe(10);
        expect(ext.top()).toBe(20);
        expect(ext.width()).toBe(100);
        expect(ext.height()).toBe(200);
    });

    test('right = left + width', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        ext.rect = new DOMRect(10, 20, 100, 200);

        expect(ext.right()).toBe(110);
    });

    test('bottom = top + height', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        ext.rect = new DOMRect(10, 20, 100, 200);

        expect(ext.bottom()).toBe(220);
    });
});

describe('NodeExt visibility', () => {
    test('isVisible returns false when rect is null', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        ext.rect = null;

        expect(ext.isVisible()).toBe(false);
    });

    test('isHidden is inverse of isVisible', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        ext.rect = null;

        expect(ext.isHidden()).toBe(true);
    });
});

describe('NodeExt updateMaxWidth', () => {
    test('updateMaxWidth with no parent sets own maxWidth', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        // No parent nodeExt is set, so hasParent() returns false
        // updateMaxWidth only updates if hasParent() is true
        ext.updateMaxWidth(500);
        // maxWidth stays at viewPortWidth since no parent
        expect(ext.maxWidth).toBe(1920);
    });
});

describe('NodeExt overflow', () => {
    test('isOverflowHidden returns false when rect is null', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        ext.rect = null;

        expect(ext.isOverflowHidden()).toBe(false);
    });

    test('hasOverflowHidden checks styles', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);

        // No overflow style set
        expect(ext.hasOverflowHidden()).toBe(false);

        ext.styles['overflow'] = 'hidden';
        expect(ext.hasOverflowHidden()).toBe(true);
    });
});

describe('NodeExt formatDOMRect', () => {
    test('formats rect via __pulsar_utils__', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        ext.rect = new DOMRect(10.56, 20.34, 100.78, 200.12);

        const formatted = ext.formatDOMRect();
        expect(formatted).toBe('10.6 20.3 100.8 200.1');
    });
});

describe('NodeExt formatStyles', () => {
    test('joins styles with comma', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        ext.propertyNames = ['font-size', 'color'];
        ext.styles = { 'font-size': '16', 'color': '000000' };

        const formatted = ext.formatStyles();
        expect(formatted).toBe('16,000000');
    });
});

describe('NodeExt adjustDOMRect', () => {
    test('constrains rect width to maxWidth', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        ext.rect = new DOMRect(0, 0, 2000, 100);
        ext.maxWidth = 800;

        ext.adjustDOMRect();
        expect(ext.rect.width).toBe(800);
    });

    test('does nothing when rect is null', () => {
        const div = document.body.querySelector('div');
        const ext = createNodeExt(div);
        ext.rect = null;

        expect(() => ext.adjustDOMRect()).not.toThrow();
    });
});

describe('NodeExt attr', () => {
    test('returns attribute value for Element nodes', () => {
        const div = document.body.querySelector('div');
        div.setAttribute('data-test', 'hello');
        const ext = createNodeExt(div);

        expect(ext.attr('data-test')).toBe('hello');
    });

    test('returns null for non-Element nodes', () => {
        const textNode = document.body.querySelector('span').firstChild;
        const ext = createNodeExt(textNode);

        expect(ext.attr('data-test')).toBeNull();
    });
});
