/**
 * Tests for dom_text.js — Text content utilities.
 */
const { loadScript, ensureConfig, setupDOM } = require('./test-helper');

beforeAll(() => {
    loadScript('configs.js');
    loadScript('tree_walker.js');
    loadScript('node_ops.js');
    loadScript('__pulsar_utils__.js');
    loadScript('dom_rect.js');
    loadScript('dom_text.js');
    ensureConfig();
});

beforeEach(() => {
    setupDOM();
});

describe('getCleanTextContent', () => {
    test('removes control characters', () => {
        expect(__pulsar_utils__.getCleanTextContent('hello\x00world')).toBe('hello world');
    });

    test('collapses multiple spaces', () => {
        expect(__pulsar_utils__.getCleanTextContent('hello    world')).toBe('hello world');
    });

    test('collapses newlines and tabs to spaces', () => {
        expect(__pulsar_utils__.getCleanTextContent('hello\n\tworld')).toBe('hello world');
    });

    test('trims leading and trailing whitespace', () => {
        expect(__pulsar_utils__.getCleanTextContent('  hello world  ')).toBe('hello world');
    });
});

describe('getMergedTextContent', () => {
    test('returns empty string for null input', () => {
        expect(__pulsar_utils__.getMergedTextContent(null)).toBe('');
    });

    test('returns content for a single node', () => {
        const span = document.body.querySelector('span');
        const result = __pulsar_utils__.getMergedTextContent(span);
        expect(typeof result).toBe('string');
    });

    test('merges content from multiple nodes', () => {
        setupDOM('<div><span>Hello</span><span>World</span></div>');
        const spans = document.body.querySelectorAll('span');
        const result = __pulsar_utils__.getMergedTextContent(spans);
        expect(result).toContain('Hello');
        expect(result).toContain('World');
    });
});

describe('getTextContent', () => {
    test('returns empty string for null node', () => {
        expect(__pulsar_utils__.getTextContent(null)).toBe('');
    });

    test('returns clean text from node', () => {
        const span = document.body.querySelector('span');
        const result = __pulsar_utils__.getTextContent(span);
        expect(typeof result).toBe('string');
    });
});

describe('getTextWidth', () => {
    test('returns a number for given text and font', () => {
        const width = __pulsar_utils__.getTextWidth('hello', '16px Arial');
        expect(typeof width).toBe('number');
        expect(width).toBeGreaterThan(0);
    });

    test('reuses canvas object', () => {
        __pulsar_utils__.getTextWidth('first', '16px Arial');
        const width = __pulsar_utils__.getTextWidth('second', '16px Arial');
        expect(typeof width).toBe('number');
        expect(__pulsar_utils__.getTextWidth.canvas).toBeDefined();
    });
});
