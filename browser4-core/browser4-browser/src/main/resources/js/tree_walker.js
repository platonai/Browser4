"use strict";

/**
 * TreeWalker-based DOM traversal utilities.
 *
 * Replaces the hand-written __pulsar_NodeTraversor with the browser-native
 * document.createTreeWalker API. The original traversor used an iterative
 * depth-first walk and supported both head(node, depth) and tail(node, depth)
 * callbacks. For simple iteration (forEach, count, findFirst) the native
 * TreeWalker is a direct replacement. For the head/tail pattern used by
 * __pulsar_NodeFeatureCalculator, we use a two-pass approach: collect all
 * nodes in pre-order via TreeWalker, then forward pass calls head() and
 * reverse pass calls tail().
 *
 * This is correct because:
 *   - head() creates node.__pulsar_nodeExt (per-node feature data)
 *   - tail() reads node.__pulsar_nodeExt, formats attributes, then deletes it
 *   - No tail callback depends on another tail having executed first
 *   - The parent-child relationship is already captured by the node tree
 */
let __pulsar_TreeWalker = {};

/**
 * Execute an action for every node in the subtree (depth-first pre-order).
 *
 * @param {Node} root - The root node to traverse.
 * @param {function(Node): void} action - Called for each node, including root.
 */
__pulsar_TreeWalker.forEach = function(root, action) {
    action(root);
    let walker = document.createTreeWalker(root, NodeFilter.SHOW_ALL);
    while (walker.nextNode()) {
        action(walker.currentNode);
    }
};

/**
 * Execute an action for every Element node in the subtree.
 *
 * @param {Node} root - The root node to traverse.
 * @param {function(Element): void} action - Called for each element.
 */
__pulsar_TreeWalker.forEachElement = function(root, action) {
    if (root.nodeType === Node.ELEMENT_NODE) {
        action(root);
    }
    let walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    while (walker.nextNode()) {
        action(walker.currentNode);
    }
};

/**
 * Count nodes in the subtree that satisfy a predicate.
 *
 * @param {Node} root - The root node to traverse.
 * @param {function(Node): boolean} predicate - Return true to count the node.
 * @returns {number} The count of matching nodes.
 */
__pulsar_TreeWalker.count = function(root, predicate) {
    let c = 0;
    if (predicate(root)) {
        c++;
    }
    let walker = document.createTreeWalker(root, NodeFilter.SHOW_ALL);
    while (walker.nextNode()) {
        if (predicate(walker.currentNode)) {
            c++;
        }
    }
    return c;
};

/**
 * Find the first node in the subtree that satisfies a predicate.
 * Short-circuits as soon as a match is found.
 *
 * @param {Node} root - The root node to traverse.
 * @param {function(Node): boolean} predicate - Return true for the desired node.
 * @returns {Node|null} The first matching node, or null if none found.
 */
__pulsar_TreeWalker.findFirst = function(root, predicate) {
    if (predicate(root)) {
        return root;
    }
    let walker = document.createTreeWalker(root, NodeFilter.SHOW_ALL);
    while (walker.nextNode()) {
        if (predicate(walker.currentNode)) {
            return walker.currentNode;
        }
    }
    return null;
};

/**
 * Collect all nodes in the subtree in depth-first pre-order.
 *
 * @param {Node} root - The root node to traverse.
 * @returns {Node[]} Array of nodes in pre-order.
 */
__pulsar_TreeWalker.collectNodes = function(root) {
    let nodes = [root];
    let walker = document.createTreeWalker(root, NodeFilter.SHOW_ALL);
    while (walker.nextNode()) {
        nodes.push(walker.currentNode);
    }
    return nodes;
};

/**
 * Compute the depth of a node relative to a root by walking the parent chain.
 *
 * @param {Node} node - The node whose depth to compute.
 * @param {Node} root - The root of the subtree.
 * @returns {number} The depth (number of edges from root to node).
 */
__pulsar_TreeWalker.depth = function(node, root) {
    let d = 0;
    let p = node;
    while (p && p !== root) {
        d++;
        p = p.parentNode;
    }
    return d;
};

/**
 * Traverse a subtree with head (enter) and tail (leave) callbacks.
 *
 * Uses a two-pass approach:
 *   1. Collect all nodes in pre-order via TreeWalker
 *   2. Forward pass: call head(node, depth) for each node
 *   3. Reverse pass: call tail(node, depth) for each node
 *
 * This replaces the custom __pulsar_NodeTraversor for the head/tail pattern
 * used by __pulsar_NodeFeatureCalculator.
 *
 * Stopped early if headFn or tailFn sets this.stopped = true on the context.
 *
 * @param {Node} root - The root node to traverse.
 * @param {function(Node, number): void} headFn - Called when entering a node.
 * @param {function(Node, number): void} tailFn - Called when leaving a node.
 * @param {Object} [context] - Optional `this` context for callbacks.
 */
__pulsar_TreeWalker.traverseWithHeadTail = function(root, headFn, tailFn, context) {
    let nodes = __pulsar_TreeWalker.collectNodes(root);
    let stopped = false;
    let ctx = context || {};

    // Forward pass: head
    for (let i = 0; i < nodes.length; i++) {
        if (stopped) {
            break;
        }
        let d = i === 0 ? 0 : __pulsar_TreeWalker.depth(nodes[i], root);
        headFn.call(ctx, nodes[i], d);
        stopped = ctx.stopped || false;
    }

    // Reverse pass: tail
    for (let i = nodes.length - 1; i >= 0; i--) {
        if (stopped) {
            break;
        }
        let d = i === 0 ? 0 : __pulsar_TreeWalker.depth(nodes[i], root);
        tailFn.call(ctx, nodes[i], d);
    }
};
