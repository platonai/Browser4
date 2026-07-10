/**
 * Tests for dom_style.js — Computed style and color utilities.
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
    loadScript('dom_style.js');
    ensureConfig();
});

beforeEach(() => {
    setupDOM();
});

describe('rgb2hex', () => {
    test('converts rgb to hex', () => {
        expect(__pulsar_utils__.rgb2hex('rgb(255, 255, 0)')).toBe('#ffff00');
    });

    test('converts rgba to hex (ignoring alpha)', () => {
        expect(__pulsar_utils__.rgb2hex('rgba(255, 0, 0, 0.5)')).toBe('#ff0000');
    });

    test('handles spacing variations', () => {
        expect(__pulsar_utils__.rgb2hex('rgb(0,0,0)')).toBe('#000000');
    });

    test('returns empty string for invalid input', () => {
        expect(__pulsar_utils__.rgb2hex('not a color')).toBe('');
    });
});

describe('shortenHex', () => {
    test('shortens #ffcc00 to #fc0', () => {
        expect(__pulsar_utils__.shortenHex('#ffcc00')).toBe('#fc0');
    });

    test('shortens #ffcc00 to #fc0 (pair-wise shorten)', () => {
        expect(__pulsar_utils__.shortenHex('#ffcc00')).toBe('#fc0');
    });

    test('shortens fully uniform hex to single char', () => {
        // #cccccc → #ccc (pair-wise) → #c (all same after pair-wise)
        expect(__pulsar_utils__.shortenHex('#cccccc')).toBe('#c');
    });

    test('preserves non-shortenable hex', () => {
        expect(__pulsar_utils__.shortenHex('#123456')).toBe('#123456');
    });
});

describe('getComputedStyle', () => {
    test('returns null for text nodes', () => {
        const textNode = document.body.querySelector('span').firstChild;
        const result = __pulsar_utils__.getComputedStyle(textNode, ['font-size']);
        expect(result).toBeNull();
    });

    test('returns styles object for elements', () => {
        const div = document.body.querySelector('div');
        const result = __pulsar_utils__.getComputedStyle(div, ['font-size', 'color']);
        expect(result).not.toBeNull();
        expect(typeof result).toBe('object');
    });

    test('accepts string property name', () => {
        const div = document.body.querySelector('div');
        const result = __pulsar_utils__.getComputedStyle(div, 'font-size');
        expect(result).not.toBeNull();
    });
});

describe('getPropertyValue', () => {
    test('simplifies font-size to numeric px value', () => {
        const div = document.body.querySelector('div');
        const style = getComputedStyle(div);
        const value = __pulsar_utils__.getPropertyValue(style, 'font-size');
        // Should be a numeric string (px suffix stripped)
        expect(value).not.toContain('px');
    });
});

describe('queryComputedStyle', () => {
    test('returns null for non-matching selector', () => {
        expect(__pulsar_utils__.queryComputedStyle('#nonexistent', ['font-size'])).toBeNull();
    });
});
