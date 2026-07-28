/**
 * Tests for tree_walker.js — TreeWalker-based DOM traversal utilities.
 */
const { loadScript, setupDOM } = require('./test-helper');

beforeAll(() => {
    loadScript('tree_walker.js');
});

beforeEach(() => {
    setupDOM();
});

describe('__pulsar_TreeWalker.forEach', () => {
    test('calls action for every node including root', () => {
        const visited = [];
        __pulsar_TreeWalker.forEach(document.body, (node) => {
            visited.push(node.nodeName || '#text');
        });
        expect(visited).toContain('BODY');
        expect(visited).toContain('DIV');
        expect(visited).toContain('SPAN');
        expect(visited).toContain('P');
    });

    test('visits nodes in pre-order', () => {
        const visited = [];
        __pulsar_TreeWalker.forEach(document.body, (node) => {
            if (node.nodeType === Node.ELEMENT_NODE) {
                visited.push(node.nodeName);
            }
        });
        // Pre-order: root, then children left-to-right
        expect(visited[0]).toBe('BODY');
        expect(visited[1]).toBe('DIV');
        expect(visited[2]).toBe('SPAN');
        expect(visited[3]).toBe('P');
    });
});

describe('__pulsar_TreeWalker.forEachElement', () => {
    test('only calls action for Element nodes', () => {
        const visited = [];
        __pulsar_TreeWalker.forEachElement(document.body, (el) => {
            visited.push(el.nodeName);
        });
        // All entries should be element names, no '#text'
        visited.forEach(name => {
            expect(name).not.toBe('#text');
            expect(name).toMatch(/^[A-Z]+$/);
        });
    });

    test('includes root if it is an Element', () => {
        const visited = [];
        __pulsar_TreeWalker.forEachElement(document.body, (el) => {
            visited.push(el.nodeName);
        });
        expect(visited[0]).toBe('BODY');
    });
});

describe('__pulsar_TreeWalker.count', () => {
    test('counts matching nodes', () => {
        const count = __pulsar_TreeWalker.count(document.body, (node) => {
            return node.nodeType === Node.ELEMENT_NODE;
        });
        // BODY, DIV, SPAN, P = 4 elements
        expect(count).toBe(4);
    });

    test('returns 0 when no nodes match', () => {
        const count = __pulsar_TreeWalker.count(document.body, () => false);
        expect(count).toBe(0);
    });
});

describe('__pulsar_TreeWalker.findFirst', () => {
    test('returns first matching node', () => {
        const result = __pulsar_TreeWalker.findFirst(document.body, (node) => {
            return node.nodeName === 'SPAN';
        });
        expect(result).not.toBeNull();
        expect(result.nodeName).toBe('SPAN');
    });

    test('returns null when no match', () => {
        const result = __pulsar_TreeWalker.findFirst(document.body, (node) => {
            return node.nodeName === 'TABLE';
        });
        expect(result).toBeNull();
    });

    test('short-circuits after finding match', () => {
        let callCount = 0;
        const result = __pulsar_TreeWalker.findFirst(document.body, (node) => {
            callCount++;
            return node.nodeName === 'DIV';
        });
        expect(result).not.toBeNull();
        // Should stop after finding DIV (body then div)
        expect(callCount).toBe(2);
    });
});

describe('__pulsar_TreeWalker.collectNodes', () => {
    test('returns array of all nodes in pre-order', () => {
        setupDOM('<div><span>A</span><span>B</span></div>');
        const nodes = __pulsar_TreeWalker.collectNodes(document.body);
        // BODY, DIV, SPAN, #text(A), SPAN, #text(B) = 6 nodes
        expect(nodes.length).toBe(6);
        expect(nodes[0]).toBe(document.body);
    });
});

describe('__pulsar_TreeWalker.depth', () => {
    test('returns 0 for direct child of root', () => {
        const div = document.body.querySelector('div');
        const depth = __pulsar_TreeWalker.depth(div, document.body);
        expect(depth).toBe(1);
    });

    test('returns correct depth for nested nodes', () => {
        const span = document.body.querySelector('span');
        const depth = __pulsar_TreeWalker.depth(span, document.body);
        expect(depth).toBe(2); // body > div > span
    });

    test('returns 0 for root itself', () => {
        const depth = __pulsar_TreeWalker.depth(document.body, document.body);
        expect(depth).toBe(0);
    });
});

describe('__pulsar_TreeWalker.traverseWithHeadTail', () => {
    test('calls head for each node in pre-order', () => {
        const headNodes = [];
        const tailNodes = [];
        __pulsar_TreeWalker.traverseWithHeadTail(
            document.body,
            (node, depth) => headNodes.push(node.nodeName || '#text'),
            (node, depth) => tailNodes.push(node.nodeName || '#text')
        );
        // head should visit in pre-order
        expect(headNodes[0]).toBe('BODY');
        expect(headNodes[1]).toBe('DIV');
        // tail should visit in post-order (reverse pre-order = children before parents)
        expect(tailNodes[tailNodes.length - 1]).toBe('BODY');
    });

    test('tail visits children before parents', () => {
        const headNodes = [];
        const tailNodes = [];
        __pulsar_TreeWalker.traverseWithHeadTail(
            document.body,
            (node, depth) => headNodes.push(node.nodeName),
            (node, depth) => tailNodes.push(node.nodeName)
        );
        // head is pre-order, tail is post-order
        // For tree: BODY [DIV [SPAN, P]]
        // Head: BODY, DIV, SPAN, P
        // Tail: SPAN, P, DIV, BODY (children before parents)
        const divIdx = tailNodes.indexOf('DIV');
        const spanIdx = tailNodes.indexOf('SPAN');
        const pIdx = tailNodes.indexOf('P');
        expect(spanIdx).toBeLessThan(divIdx);
        expect(pIdx).toBeLessThan(divIdx);
    });

    test('passes correct depth to callbacks', () => {
        const depths = [];
        __pulsar_TreeWalker.traverseWithHeadTail(
            document.body,
            (node, depth) => depths.push({ name: node.nodeName || '#text', depth }),
            () => {}
        );
        const bodyEntry = depths.find(d => d.name === 'BODY');
        const divEntry = depths.find(d => d.name === 'DIV');
        const spanEntry = depths.find(d => d.name === 'SPAN');

        expect(bodyEntry.depth).toBe(0);
        expect(divEntry.depth).toBe(1);
        expect(spanEntry.depth).toBe(2);
    });

    test('stops when context.stopped is set to true', () => {
        const headNodes = [];
        const ctx = { stopped: false };
        __pulsar_TreeWalker.traverseWithHeadTail(
            document.body,
            function(node, depth) {
                headNodes.push(node.nodeName);
                if (node.nodeName === 'SPAN') {
                    this.stopped = true;
                }
            },
            () => {},
            ctx
        );
        // Should stop after SPAN, not visit P
        expect(headNodes).toContain('SPAN');
        expect(headNodes).not.toContain('P');
    });
});
