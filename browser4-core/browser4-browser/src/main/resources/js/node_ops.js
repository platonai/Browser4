"use strict";

// NOTE: Deliberately avoid injecting methods into Node.prototype.
// All helpers live in NodeOps or on __pulsar_NodeExt.prototype.

let NodeOps = {};

/**
 * Set attribute if it's not blank
 * @param node {Node|Element}
 * @param attrName {String}
 * @param attrValue {String}
 */
NodeOps.setAttributeIfNotBlank = function(node, attrName, attrValue) {
    if (node instanceof HTMLElement && attrValue && attrValue.trim().length > 0) {
        node.setAttribute(attrName, attrValue.trim())
    }
};

/**
 * Count nodes in subtree matching a predicate.
 * Delegates to TreeWalker-based traversal.
 *
 * @param node {Node}
 * @param predicate {Function}
 * @return {Number}
 */
NodeOps.count = function(node, predicate) {
    return __pulsar_TreeWalker.count(node, predicate);
};

/**
 * Execute an action for every node in the subtree.
 * Delegates to TreeWalker-based traversal.
 *
 * @param node {Node}
 * @param action {Function}
 */
NodeOps.forEach = function(node, action) {
    __pulsar_TreeWalker.forEach(node, action);
};

/**
 * Execute an action for every Element node in the subtree.
 * Delegates to TreeWalker-based traversal.
 *
 * @param node {Node}
 * @param action {Function}
 */
NodeOps.forEachElement = function(node, action) {
    __pulsar_TreeWalker.forEachElement(node, action);
};

/**
 * Find the first element in the subtree whose textContent matches a pattern.
 * Delegates to TreeWalker-based traversal with short-circuit.
 *
 * @param node {Node}
 * @param pattern {RegExp}
 * @return {Element|null}
 */
NodeOps.findMatches = function(node, pattern) {
    return __pulsar_TreeWalker.findFirst(node, function(n) {
        return n instanceof HTMLElement && n.textContent.match(pattern);
    });
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.isText = function(node) {
    return node && node.nodeType === Node.TEXT_NODE;
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.isElement = function(node) {
    return node && node.nodeType === Node.ELEMENT_NODE;
};

/**
 * @param node {Node}
 * @return {Element|null}
 */
NodeOps.bestElement = function(node) {
    if (!node) return null;
    if (NodeOps.isElement(node)) return node;
    return node.parentElement;
};

/**
 * Get cleaned text content: collapse whitespace and remove &nbsp;.
 * @param node {Node}
 * @return {String}
 */
NodeOps.cleanText = function(node) {
    if (!node || node.textContent == null) {
        return "";
    }
    let text = node.textContent.replace(/\s+/g, ' ');
    // remove &nbsp;
    text = text.replace(/ /g, ' ');
    return text.trim();
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.isShortText = function(node) {
    if (!NodeOps.isText(node)) return false;
    let text = NodeOps.cleanText(node);
    return text.length >= 1 && text.length <= 9;
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.isNumberLike = function(node) {
    if (!NodeOps.isShortText(node)) return false;

    let text = NodeOps.cleanText(node).replace(/\s+/g, '');
    // matches ￥3,412.25, ￥3,412.25, 3,412.25, 3412.25, etc
    return /.{0,4}((\d+),?)*(\d+)\.?\d+.{0,3}/.test(text);
};

/**
 * 0-based screen number in the viewport
 * @param node {Node}
 * @return {Number}
 */
NodeOps.nScreen = function(node) {
    const rect = NodeOps.getRect(node);
    if (!rect) return 0;
    const config = __pulsar_utils__.getConfig();
    const viewPortHeight = config.viewPortHeight;
    let ns = rect.y / viewPortHeight;
    return Math.floor(ns);
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.isDiv = function(node) {
    // HTML-uppercased qualified name
    return !!node && node.nodeName === "DIV";
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.isImage = function(node) {
    // HTML-uppercased qualified name
    return !!node && node.nodeName === "IMG";
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.isSmallImage = function(node) {
    if (!NodeOps.isImage(node)) {
        return false
    }

    const rect = NodeOps.getRect(node);
    if (!rect) {
        return true
    }

    return rect.width <= 50 || rect.height <= 50;
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.isAnchor = function(node) {
    // HTML-uppercased qualified name
    return !!node && node.nodeName === "A";
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.isIFrame = function(node) {
    return !!node && node.nodeName === "IFRAME";
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.isTile = function(node) {
    return NodeOps.isImage(node) || NodeOps.isText(node);
};

/**
 * Get the estimated rect of this node; if the node is not an element, return its parent element's rect.
 * @param node {Node}
 * @return {DOMRect|null}
 */
NodeOps.getRect = function(node) {
    let element = NodeOps.bestElement(node);
    if (element == null) {
        return null
    }

    let rect = __pulsar_utils__.getClientRect(element);

    if (NodeOps.isImage(element)) {
        if (!rect) {
            rect = new DOMRect(0, 0, 0, 0)
        }

        if (rect.width === 0) {
            let w = element.getAttribute("width");
            if (w && /\d+/.test(w)) {
                rect.width = Number.parseInt(w)
            }
        }

        if (rect.height === 0) {
            let h = element.getAttribute("height");
            if (h && /\d+/.test(h)) {
                rect.height = Number.parseInt(h)
            }
        }
    }

    return rect
};

/**
 * @param node {Node}
 * @return {boolean}
 */
NodeOps.maybeClickable = function(node) {
    let element = NodeOps.bestElement(node);
    if (element == null) {
        return false
    }
    if (!NodeOps.isAnchor(element)) {
        return false
    }

    let clickable = true;
    let rect = NodeOps.getRect(node);
    if (!rect) {
        return false
    }

    if (rect.x < 0 || rect.y < 0) {
        clickable = false
    }
    if (rect.width < 5 || rect.height < 5.0) {
        clickable = false
    }

    return clickable
};
