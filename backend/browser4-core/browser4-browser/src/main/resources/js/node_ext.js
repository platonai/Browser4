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
 * @param node {Node}
 * @param predicate {Function}
 * @return {Number}
 */
NodeOps.count = function(node, predicate) {
    let c = 0;
    let visitor = function () {};
    visitor.head = function (n, depth) {
        if (predicate(n)) {
            ++c;
        }
    };

    new __pulsar_NodeTraversor(visitor).traverse(node);
    return c;
};

/**
 * @param node {Node}
 * @param action {Function}
 */
NodeOps.forEach = function(node, action) {
    let visitor = {};
    visitor.head = function (n, depth) {
        action(n)
    };
    new __pulsar_NodeTraversor(visitor).traverse(node);
};

/**
 * @param node {Node}
 * @param action {Function}
 */
NodeOps.forEachElement = function(node, action) {
    let visitor = {};
    visitor.head = function (n, depth) {
        if (n.nodeType === Node.ELEMENT_NODE) {
            action(n)
        }
    };
    new __pulsar_NodeTraversor(visitor).traverse(node);
};

/**
 * @param node {Node}
 * @param pattern {RegExp}
 * @return {Element|null}
 */
NodeOps.findMatches = function(node, pattern) {
    let visitor = {};

    let result = null;
    visitor.head = function (n, depth) {
        if (n instanceof HTMLElement) {
            let text = n.textContent;
            if (text.match(pattern)) {
                result = n;
                visitor.stopped = true;
            }
        }
    };

    new __pulsar_NodeTraversor(visitor).traverse(node);

    return result;
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
 * @param node {Node}
 * @return {String}
 */
NodeOps.cleanText = function(node) {
    if (!node || node.textContent == null) {
        return "";
    }
    let text = node.textContent.replace(/\s+/g, ' ');
    // remove &nbsp;
    text = text.replace(/\u00A0/g, ' ');
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
 * 1-based screen number in the viewport
 * @param node {Node}
 * @return {Number}
 */
NodeOps.nScreen = function(node) {
    const rect = NodeOps.getRect(node);
    if (!rect) return 0;
    const config = __pulsar_utils__.getConfig();
    const viewPortHeight = config.viewPortHeight;
    let ns = rect.y / viewPortHeight;
    return Math.ceil(ns);
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

let __pulsar_NodeExt = function (node, config) {
    /**
     * The config
     * */
    this.config = config;
    /**
     * Desired property names of computed styles
     * Array
     * */
    this.propertyNames = [];
    /**
     * Computed styles
     * Map
     * */
    this.styles = {};
    /**
     * Max width for all descendants, if an element have property overflow:hidden, then
     * all it's descendants should hide the parts overflowed.
     * Number
     * */
    this.maxWidth = config.viewPortWidth;
    /**
     * The rectangle of this node
     * DOMRect
     * */
    this.rect = null;
    /**
     * Integer
     * */
    this.depth = 0;
    /**
     * Sequence
     * */
    this.sequence = 0;
    /**
     * Node
     * */
    this.node = node;
};

/**
 * Check if it's visible
 * https://stackoverflow.com/questions/19669786/check-if-element-is-visible-in-dom
 * @return {boolean}
 * */
__pulsar_NodeExt.prototype.isVisible = function() {
    // NodeExt is created by __pulsar_NodeFeatureCalculator, which should have already
    // populated nodeExt.rect and nodeExt.styles.

    // If we can't resolve a rectangle, treat it as not visible.
    // (This also avoids throwing in overflow checks.)
    if (!this.rect) {
        return false
    }

    // For non-elements (e.g. Text nodes), rely on the estimated rect.
    if (!(this.node instanceof Element)) {
        return this.rect.width > 0 && this.rect.height > 0 && !this.isOverflowHidden()
    }

    // Style checks
    const style = getComputedStyle(this.node);
    if (!style) {
        // Defensive: if style is unavailable, fall back to geometry.
        return this.rect.width > 0 && this.rect.height > 0 && !this.isOverflowHidden()
    }

    if (style.display === "none") {
        return false
    }

    // visibility:collapse is mainly for table rows/cols, treat as hidden too.
    if (style.visibility === "hidden" || style.visibility === "collapse") {
        return false
    }

    // Align with __pulsar_utils__.getVisibleTextContent(): opacity 0 means not visible.
    if (style.opacity === "0") {
        return false
    }

    // display:contents doesn't generate a box for itself; fall back to util's recursive check.
    if (style.display === "contents") {
        return __pulsar_utils__.isElementVisible(this.node)
    }

    // Geometry checks
    if (this.rect.width <= 0 || this.rect.height <= 0) {
        return false
    }

    // Finally, apply overflow hidden clipping from ancestor constraints.
    return !this.isOverflowHidden()
};

__pulsar_NodeExt.prototype.isHidden = function() {
    return !this.isVisible();
};

/**
 * @return {boolean}
 * */
__pulsar_NodeExt.prototype.isOverflown = function() {
    return this.node.scrollHeight > this.node.clientHeight || this.node.scrollWidth > this.node.clientWidth;
};

/**
 * @return {boolean}
 * */
__pulsar_NodeExt.prototype.isOverflowHidden = function() {
    // Be defensive: rect might be unavailable for some nodes.
    if (!this.rect) {
        return false
    }

    let p = this.parent();
    if (p == null || !p.rect) {
        return false
    }

    let maxWidth = this.config.viewPortWidth;

    // If an ancestor constrains maxWidth (overflow hidden), and this node is completely outside
    // the ancestor's box, then consider it overflow-hidden.
    if (p.maxWidth >= maxWidth) {
        return false
    }

    const horizontallyOut = this.left() >= p.right() || this.right() <= p.left();
    const verticallyOut = this.top() >= p.bottom() || this.bottom() <= p.top();

    return horizontallyOut || verticallyOut;
};

/**
 * @return {boolean}
 * */
__pulsar_NodeExt.prototype.hasOverflowHidden = function() {
    return this.styles["overflow"] === "hidden";
};

/**
 * @return {boolean}
 * */
__pulsar_NodeExt.prototype.hasParent = function() {
    return this.node.parentElement != null && this.parent() != null;
};

/**
 * @return {__pulsar_NodeExt}
 * */
__pulsar_NodeExt.prototype.parent = function() {
    return this.node.parentElement.__pulsar_nodeExt;
};

/**
 * Get left
 * */
__pulsar_NodeExt.prototype.left = function() {
    return this.rect.left
};

/**
 * Get right
 * */
__pulsar_NodeExt.prototype.right = function() {
    return this.left() + this.width()
};

/**
 * Get top
 * */
__pulsar_NodeExt.prototype.top = function() {
    return this.rect.top
};

/**
 * Get bottom
 * */
__pulsar_NodeExt.prototype.bottom = function() {
    return this.top() + this.height()
};

/**
 * Get width
 * */
__pulsar_NodeExt.prototype.width = function() {
    return this.rect.width
};

/**
 * Get height
 * */
__pulsar_NodeExt.prototype.height = function() {
    return this.rect.height
};

/**
 * @param width {Number|null}
 * */
__pulsar_NodeExt.prototype.updateMaxWidth = function(width) {
    if (this.hasParent()) {
        this.maxWidth = Math.min(this.parent().maxWidth, width);
    }
};

/**
 * Get the attribute value
 * @param attrName {String}
 * @return {String|null}
 * */
__pulsar_NodeExt.prototype.attr = function(attrName) {
    if (NodeOps.isElement(this.node)) {
        return this.node.getAttribute(attrName)
    }
    return null
};

/**
 * Get the formatted rect
 * */
__pulsar_NodeExt.prototype.formatDOMRect = function() {
    return __pulsar_utils__.formatDOMRect(this.rect)
};

/**
 * Get the formatted rect
 * @return string
 * */
__pulsar_NodeExt.prototype.formatStyles = function() {
    return this.propertyNames.map(propertyName => this.styles[propertyName]).join(",")
};

/**
 * Adjust the node's DOMRect
 * If the child element larger than the parent and the parent have overflow:hidden style,
 * the child element's DOMRect should be adjusted
 * */
__pulsar_NodeExt.prototype.adjustDOMRect = function() {
    if (this.rect) {
        this.rect.width = Math.min(this.rect.width, this.maxWidth);
    }
};

__pulsar_NodeExt.prototype.__pulsar_getRect = function() {
    return NodeOps.getRect(this.node);
};

__pulsar_NodeExt.prototype.__pulsar_maybeClickable = function() {
    return NodeOps.maybeClickable(this.node);
};
