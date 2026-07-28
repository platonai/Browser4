/**
 * Tests for node_ops.js — NodeOps static DOM helpers.
 */
const { loadScript, loadAllScripts, ensureConfig, setupDOM } = require('./test-helper');

beforeAll(() => {
    loadScript('tree_walker.js');
    loadScript('configs.js');
    loadScript('node_ext_data.js');
    loadScript('feature_calculator.js');
    // node_ops depends on __pulsar_utils__ for getClientRect, getConfig
    loadScript('__pulsar_utils__.js');
    loadScript('dom_rect.js');
    loadScript('dom_style.js');
    loadScript('dom_text.js');
    loadScript('node_ops.js');
    ensureConfig();
});

beforeEach(() => {
    setupDOM();
});

describe('NodeOps type checks', () => {
    test('isText returns true for text nodes', () => {
        const textNode = document.body.querySelector('span').firstChild;
        expect(NodeOps.isText(textNode)).toBe(true);
    });

    test('isText returns false for elements', () => {
        const div = document.body.querySelector('div');
        expect(NodeOps.isText(div)).toBe(false);
    });

    test('isElement returns true for Element nodes', () => {
        const div = document.body.querySelector('div');
        expect(NodeOps.isElement(div)).toBe(true);
    });

    test('isElement returns false for text nodes', () => {
        const textNode = document.body.querySelector('span').firstChild;
        expect(NodeOps.isElement(textNode)).toBe(false);
    });

    test('bestElement returns element itself for elements', () => {
        const span = document.body.querySelector('span');
        expect(NodeOps.bestElement(span)).toBe(span);
    });

    test('bestElement returns parentElement for text nodes', () => {
        const textNode = document.body.querySelector('span').firstChild;
        expect(NodeOps.bestElement(textNode)).toBe(textNode.parentElement);
    });

    test('bestElement returns null for null input', () => {
        expect(NodeOps.bestElement(null)).toBeNull();
    });

    test('isDiv returns true for DIV elements', () => {
        const div = document.body.querySelector('div');
        expect(NodeOps.isDiv(div)).toBe(true);
    });

    test('isDiv returns false for non-DIV elements', () => {
        const span = document.body.querySelector('span');
        expect(NodeOps.isDiv(span)).toBe(false);
    });

    test('isImage checks nodeName', () => {
        const elem = document.createElement('img');
        document.body.appendChild(elem);
        expect(NodeOps.isImage(elem)).toBe(true);
        document.body.removeChild(elem);
    });

    test('isAnchor checks nodeName', () => {
        const elem = document.createElement('a');
        document.body.appendChild(elem);
        expect(NodeOps.isAnchor(elem)).toBe(true);
        document.body.removeChild(elem);
    });

    test('isIFrame checks nodeName', () => {
        const elem = document.createElement('iframe');
        document.body.appendChild(elem);
        expect(NodeOps.isIFrame(elem)).toBe(true);
        document.body.removeChild(elem);
    });

    test('isTile returns true for images', () => {
        const elem = document.createElement('img');
        document.body.appendChild(elem);
        expect(NodeOps.isTile(elem)).toBe(true);
        document.body.removeChild(elem);
    });
});

describe('NodeOps.cleanText', () => {
    test('collapses whitespace', () => {
        const node = { textContent: '  hello   world  ' };
        expect(NodeOps.cleanText(node)).toBe('hello world');
    });

    test('removes non-breaking spaces', () => {
        const node = { textContent: 'hello world' };
        expect(NodeOps.cleanText(node)).toBe('hello world');
    });

    test('returns empty string for null/empty input', () => {
        expect(NodeOps.cleanText(null)).toBe('');
        expect(NodeOps.cleanText({ textContent: null })).toBe('');
    });
});

describe('NodeOps.isShortText', () => {
    test('returns false for non-text nodes', () => {
        const div = document.body.querySelector('div');
        expect(NodeOps.isShortText(div)).toBe(false);
    });

    test('returns true for short text (1-9 chars)', () => {
        // Create a text node with short content
        setupDOM('<div><span>Hi</span></div>');
        const textNode = document.body.querySelector('span').firstChild;
        expect(NodeOps.isShortText(textNode)).toBe(true);
    });
});

describe('NodeOps.isNumberLike', () => {
    test('returns false for non-short-text', () => {
        const div = document.body.querySelector('div');
        expect(NodeOps.isNumberLike(div)).toBe(false);
    });
});

describe('NodeOps count and forEach', () => {
    test('count delegates to TreeWalker', () => {
        const count = NodeOps.count(document.body, (node) => {
            return node.nodeType === Node.ELEMENT_NODE;
        });
        expect(count).toBeGreaterThan(0);
    });

    test('forEach visits all nodes', () => {
        const visited = [];
        NodeOps.forEach(document.body, (node) => {
            visited.push(node);
        });
        expect(visited.length).toBeGreaterThan(0);
        expect(visited[0]).toBe(document.body);
    });

    test('forEachElement only visits Element nodes', () => {
        const visited = [];
        NodeOps.forEachElement(document.body, (el) => {
            visited.push(el);
        });
        visited.forEach(el => {
            expect(el.nodeType).toBe(Node.ELEMENT_NODE);
        });
    });

    test('findMatches returns element with matching text', () => {
        setupDOM('<div><span>UniqueTarget42</span><p>Other</p></div>');
        // findMatches checks n.textContent which includes descendant text.
        // DIV contains "UniqueTarget42" via SPAN child, so DIV matches first.
        const div = document.body.querySelector('div');
        const result = NodeOps.findMatches(div, /UniqueTarget42/);
        expect(result).not.toBeNull();
        expect(result.nodeName).toBe('DIV');
    });

    test('findMatches returns inner element when searching from it', () => {
        setupDOM('<div><span>OnlyHere</span><p>Other</p></div>');
        // Search from span itself — only span's own textContent matches
        const span = document.body.querySelector('span');
        const result = NodeOps.findMatches(span, /OnlyHere/);
        expect(result).not.toBeNull();
        expect(result.nodeName).toBe('SPAN');
    });

    test('findMatches returns null when no match', () => {
        const result = NodeOps.findMatches(document.body, /NonExistent12345/);
        expect(result).toBeNull();
    });
});

describe('NodeOps.setAttributeIfNotBlank', () => {
    test('sets attribute on HTMLElement with non-blank value', () => {
        const div = document.body.querySelector('div');
        NodeOps.setAttributeIfNotBlank(div, 'data-test', 'hello');
        expect(div.getAttribute('data-test')).toBe('hello');
    });

    test('does not set attribute for blank value', () => {
        const div = document.body.querySelector('div');
        NodeOps.setAttributeIfNotBlank(div, 'data-test', '   ');
        expect(div.hasAttribute('data-test')).toBe(false);
    });
});

describe('NodeOps.maybeClickable', () => {
    test('returns false for null input', () => {
        expect(NodeOps.maybeClickable(null)).toBe(false);
    });

    test('returns false for non-anchor elements', () => {
        const div = document.body.querySelector('div');
        expect(NodeOps.maybeClickable(div)).toBe(false);
    });
});
