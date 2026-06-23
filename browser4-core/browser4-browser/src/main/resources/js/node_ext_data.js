"use strict";

/**
 * Per-node feature data used during DOM traversal.
 *
 * An instance is attached to each DOM node (as node.__pulsar_nodeExt) during
 * traversal by __pulsar_NodeFeatureCalculator.head() and cleaned up in tail().
 * It holds computed geometry, styles, visibility, and overflow information
 * for the node.
 *
 * Depends on: NodeOps (from node_ops.js), __pulsar_utils__ (from __pulsar_utils__.js)
 */
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
 * Check if this node is completely outside an overflow-hidden ancestor's box.
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
