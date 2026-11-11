"use strict";

/**
 * Rectangle formatting and query utilities for the Browser4 runtime.
 * Attaches methods to the already-defined __pulsar_utils__ object.
 *
 * Load order: must be loaded AFTER __pulsar_utils__.js.
 */

/**
 * Format rectangle from individual coordinates.
 *
 * Compression mode is controlled by the VI_COMPRESSION config option:
 * - "none" (default): space-separated decimals
 *   Example: "124 457 201 51"
 * - "base36": comma-separated base-36 integers, ~56% smaller
 *   Example: "3g,cp,5k,1f" = left:124, top:457, width:200, height:51
 *
 * Output order: left,top,width,height (matches DOMRect / formatDOMRect).
 *
 * @param top {Number}
 * @param left {Number}
 * @param width {Number}
 * @param height {Number}
 * @return {String|Boolean} Formatted string or false if zero dimensions.
 * */
__pulsar_utils__.formatRect = function(top, left, width, height) {
    if (width === 0 && height === 0) {
        return false;
    }

    var config = this.getConfig ? this.getConfig() : __pulsar_DEFAULT_CONFIGS;
    var compression = config.VI_COMPRESSION || 'none';

    if (compression === 'base36') {
        return ''
            + Math.round(left).toString(36) + ','
            + Math.round(top).toString(36) + ','
            + Math.round(width).toString(36) + ','
            + Math.round(height).toString(36);
    }

    // Legacy space-separated integer format
    return ''
        + Math.round(left) + ' '
        + Math.round(top) + ' '
        + Math.round(width) + ' '
        + Math.round(height);
};

/**
 * Format a DOMRect object.
 *
 * Compression mode is controlled by the VI_COMPRESSION config option:
 * - "none" (default): space-separated decimals
 *   Example: "124 457 201 51"
 * - "base36": comma-separated base-36 integers, ~56% smaller
 *   Example: "3g,cp,5k,1f" = left:124, top:457, width:200, height:51
 *
 * Output order: left,top,width,height.
 *
 * @param rect {DOMRect}
 * @return {String|Boolean} Formatted string or false if zero dimensions.
 * */
__pulsar_utils__.formatDOMRect = function(rect) {
    if (!rect || (rect.width === 0 && rect.height === 0)) {
        return false;
    }

    var config = this.getConfig ? this.getConfig() : __pulsar_DEFAULT_CONFIGS;
    var compression = config.VI_COMPRESSION || 'none';

    if (compression === 'base36') {
        return ''
            + Math.round(rect.left).toString(36) + ','
            + Math.round(rect.top).toString(36) + ','
            + Math.round(rect.width).toString(36) + ','
            + Math.round(rect.height).toString(36);
    }

    // Legacy space-separated integer format
    return ''
        + Math.round(rect.left) + ' '
        + Math.round(rect.top) + ' '
        + Math.round(rect.width) + ' '
        + Math.round(rect.height);
};

/**
 * Format a DOMRectList object.
 *
 * @param rectList {DOMRectList}
 * @return {String} JSON-like formatted string.
 * */
__pulsar_utils__.formatDOMRectList = function(rectList) {
    if (!rectList) {
        return '[]';
    }

    let r = "["
    for (let i = 0; i < rectList.length; ++i) {
        r += "{"
        r += this.formatDOMRect(rectList.item(i))
        r += "}, "
    }
    r += "]"

    return r
};

/**
 * Get the formatted client rects for an element matching the selector.
 * The result is the smallest rectangle which contains the entire element,
 * including the padding, border and margin.
 *
 * @param selector {string} The selector to get the element from.
 * @return {String}
 * */
__pulsar_utils__.queryClientRects = function(selector) {
    let ele = document.querySelector(selector);
    if (!ele) {
        return null;
    }

    return this.formatDOMRectList(ele.getClientRects())
};

/**
 * Get the formatted client rect for an element matching the selector.
 *
 * The rect is relative to the viewport (the same space as
 * `getBoundingClientRect()`); use `getDocumentRect()` when absolute page
 * coordinates are needed.
 *
 * @param selector {string} The selector to get the element from.
 * @return {DOMRect|String|Boolean}
 * */
__pulsar_utils__.queryClientRect = function(selector) {
    let ele = document.querySelector(selector);
    if (!ele) {
        return null;
    }

    let rect = this.getClientRect(ele)
    return this.formatDOMRect(rect)
};

/**
 * Get the client rect for any node type.
 * Dispatches to the appropriate handler based on node type.
 *
 * The returned rect is relative to the viewport (the same space as
 * `getBoundingClientRect()`), so it changes with the scroll position.
 *
 * @param node {Node|Element|Text}
 * @return {DOMRect|Boolean|null}
 * */
__pulsar_utils__.getClientRect = function(node) {
    if (node.nodeType === Node.TEXT_NODE) {
        return this.getTextNodeClientRect(node)
    } else if (node.nodeType === Node.ELEMENT_NODE) {
        return this.getElementClientRect(node)
    } else {
        return null
    }
};

/**
 * Get the viewport (client) rect of an element, exactly as reported by
 * `getBoundingClientRect()`.
 *
 * @see https://idiallo.com/javascript/element-postion
 * @see https://stackoverflow.com/questions/442404/retrieve-the-position-x-y-of-an-html-element
 *
 * @param ele {Node|Element}
 * @return {DOMRect|Boolean}
 * */
__pulsar_utils__.getElementClientRect = function(ele) {
    let r = ele.getBoundingClientRect();

    if (r.width <= 0 || r.height <= 0) {
        return false
    }

    return new DOMRect(r.left, r.top, r.width, r.height);
};

/**
 * Get the viewport (client) rect of a text node.
 *
 * @param node {Node|Text}
 * @return {DOMRect|null}
 * */
__pulsar_utils__.getTextNodeClientRect = function(node) {
    let rect = null;
    let text = this.getTextContent(node);
    if (text.length > 0) {
        let range = document.createRange();
        range.selectNodeContents(node);
        let rects = range.getClientRects();
        if (rects.length > 0) {
            let r = rects[0];
            if (r.width > 0 && r.height > 0) {
                rect = new DOMRect(r.left, r.top, r.width, r.height);
            }
        }
    }

    return rect;
};

/**
 * Get the rect of any node in document (absolute page) coordinates:
 * the client rect plus the current scroll offsets.
 *
 * Document coordinates are independent of the scroll position, which is what
 * `vi` needs: the feature data can be computed at any scroll position and is
 * still comparable across captures.  `false` (zero-size element) and `null`
 * (unsupported node) are passed through unchanged.
 *
 * @param node {Node|Element|Text}
 * @return {DOMRect|Boolean|null}
 * */
__pulsar_utils__.getDocumentRect = function(node) {
    let rect = this.getClientRect(node);
    if (!rect) {
        return rect
    }

    // `scrollX`/`scrollY` are the standard properties; pageXOffset/pageYOffset
    // are the legacy aliases kept for older embedded browsers.
    const scrollX = window.scrollX || window.pageXOffset || 0;
    const scrollY = window.scrollY || window.pageYOffset || 0;

    return new DOMRect(rect.left + scrollX, rect.top + scrollY, rect.width, rect.height);
};
