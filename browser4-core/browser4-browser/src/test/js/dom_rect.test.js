/**
 * Tests for dom_rect.js — Rectangle formatting utilities.
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
    loadScript('dom_text.js');
    ensureConfig();
});

beforeEach(() => {
    setupDOM();
});

describe('formatRect', () => {
    test('formats coordinates as space-separated integers (default)', () => {
        const result = __pulsar_utils__.formatRect(10.56, 20.34, 100.78, 200.12);
        // left=Math.round(20.34)=20, top=Math.round(10.56)=11,
        // width=Math.round(100.78)=101, height=Math.round(200.12)=200
        expect(result).toBe('20 11 101 200');
    });

    test('formats coordinates as compact base-36 integers when VI_COMPRESSION=base36', () => {
        var config = window.__pulsar_CONFIGS || __pulsar_DEFAULT_CONFIGS;
        var saved = config.VI_COMPRESSION;
        config.VI_COMPRESSION = 'base36';
        try {
            const result = __pulsar_utils__.formatRect(10.56, 20.34, 100.78, 200.12);
            // left=20→"k", top=11→"b", width=101→"2t", height=200→"5k"
            expect(result).toBe('k,b,2t,5k');
        } finally {
            config.VI_COMPRESSION = saved;
        }
    });

    test('returns false for zero dimensions', () => {
        const result = __pulsar_utils__.formatRect(10, 20, 0, 0);
        expect(result).toBe(false);
    });
});

describe('formatDOMRect', () => {
    test('formats DOMRect as space-separated integers (default)', () => {
        const rect = new DOMRect(10.56, 20.34, 100.78, 200.12);
        const result = __pulsar_utils__.formatDOMRect(rect);
        // left=11, top=20, width=101, height=200
        expect(result).toBe('11 20 101 200');
    });

    test('formats DOMRect as compact base-36 integers when VI_COMPRESSION=base36', () => {
        var config = window.__pulsar_CONFIGS || __pulsar_DEFAULT_CONFIGS;
        var saved = config.VI_COMPRESSION;
        config.VI_COMPRESSION = 'base36';
        try {
            const rect = new DOMRect(10.56, 20.34, 100.78, 200.12);
            const result = __pulsar_utils__.formatDOMRect(rect);
            // left=11→"b", top=20→"k", width=101→"2t", height=200→"5k"
            expect(result).toBe('b,k,2t,5k');
        } finally {
            config.VI_COMPRESSION = saved;
        }
    });

    test('returns false for null rect', () => {
        expect(__pulsar_utils__.formatDOMRect(null)).toBe(false);
    });

    test('returns false for zero-dimension rect', () => {
        const rect = new DOMRect(0, 0, 0, 0);
        expect(__pulsar_utils__.formatDOMRect(rect)).toBe(false);
    });
});

describe('formatDOMRectList', () => {
    test('returns JSON-like array for empty list', () => {
        document.body.innerHTML = '<div></div>';
        const div = document.body.querySelector('div');
        // getClientRects on an empty div returns an empty DOMRectList
        const rects = div.getClientRects();
        const result = __pulsar_utils__.formatDOMRectList(rects);
        expect(result).toContain('[');
        expect(result).toContain(']');
    });
});

describe('getClientRect', () => {
    test('returns false or DOMRect for element node (jsdom has no layout)', () => {
        const div = document.body.querySelector('div');
        const rect = __pulsar_utils__.getClientRect(div);
        // In jsdom, elements have 0×0 bounds, so getElementClientRect returns false.
        // In a real browser, this would be a DOMRect.
        expect(rect === false || rect instanceof DOMRect).toBe(true);
    });

    test('returns null for unsupported node types', () => {
        const comment = document.createComment('test');
        document.body.appendChild(comment);
        expect(__pulsar_utils__.getClientRect(comment)).toBeNull();
        document.body.removeChild(comment);
    });
});

describe('getElementClientRect', () => {
    test('returns false or DOMRect for element (jsdom has no layout)', () => {
        const div = document.body.querySelector('div');
        const rect = __pulsar_utils__.getElementClientRect(div);
        expect(rect === false || rect instanceof DOMRect).toBe(true);
    });
});
