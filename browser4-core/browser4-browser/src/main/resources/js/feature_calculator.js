/**
 * Created by Vincent on 16-5-17.
 *
 * NodeVisitor: used with NodeTraversor together
 */

/**
 * Merge computed visual-information properties into the WeakMap entry for a node.
 * Used by NodeFeatureCalculator to store vi, _h, _oh, tvN, etc. without mutating
 * the live DOM.  The serializer later reads this map to inject attributes into
 * the captured HTML.
 *
 * @param node {Element} the DOM element
 * @param props {Object} properties to merge (vi, hidden, overflowHidden, textNodeRects, charWidth, elementData)
 */
function _storeViData(node, props) {
    if (!node || node.nodeType !== Node.ELEMENT_NODE) return;
    var map = __pulsar_utils__._viDataMap;
    var existing = map.get(node) || {};
    // Shallow merge is sufficient: each property is independently set by
    // calcSelfIndicator / tail / calcCharacterWidth and never overlapping.
    for (var key in props) {
        if (props.hasOwnProperty(key)) {
            existing[key] = props[key];
        }
    }
    map.set(node, existing);
}

/**
 * Check if a parent element has overflow-hidden, first consulting the WeakMap
 * (where _storeViData records it) and falling back to a DOM attribute check
 * for backward compatibility.
 *
 * @param parentNode {Element} the parent element
 * @param config {Object} the config with ATTR_OVERFLOW_HIDDEN
 * @return {boolean}
 */
function _isParentOverflowHidden(parentNode, config) {
    var viData = __pulsar_utils__._viDataMap.get(parentNode);
    if (viData && viData.overflowHidden) return true;
    return parentNode.hasAttribute(config.ATTR_OVERFLOW_HIDDEN);
}

/**
 * Create a new NodeFeatureCalculator
 */
let __pulsar_NodeFeatureCalculator = function() {
    this.stopped = false;

    this.config = __pulsar_utils__.getConfig()

    this.debug = this.config.debug;

    this.sequence = 0;
};

/**
 * Check if stopped
 */
__pulsar_NodeFeatureCalculator.prototype.isStopped = function() {
    return this.stopped;
};

/**
 * Enter the element for the first time
 * @param node {Node} the node to enter
 * @param  depth {Number} the depth in the DOM
 */
__pulsar_NodeFeatureCalculator.prototype.head = function(node, depth) {
    // Previously we relied on Node.prototype injections. Now we use NodeOps / NodeExt.
    if (NodeOps.isIFrame(node)) {
        return
    }

    ++this.sequence;

    node.__pulsar_nodeExt = new __pulsar_NodeExt(node, this.config);

    this.calcSelfIndicator(node, depth);
};

/**
 * Calculate the features of the Node itself
 * @param node {Node|Text|HTMLElement} the node to enter
 * @param  depth {Number} the depth in the DOM
 */
__pulsar_NodeFeatureCalculator.prototype.calcSelfIndicator = function(node, depth) {
    let nodeExt = node.__pulsar_nodeExt;

    if (NodeOps.isText(node)) {
        this.calcCharacterWidth(node, depth);
    }

    nodeExt.depth = depth;
    nodeExt.sequence = this.sequence;

    if (NodeOps.isElement(node)) {
        // Browser computed styles. Only leaf elements matter
        nodeExt.propertyNames = this.config.propertyNames || [];
        let morePropertyNames = nodeExt.propertyNames.concat("overflow");
        nodeExt.styles = __pulsar_utils__.getComputedStyle(node, morePropertyNames);
    }

    // Calculate the rectangle of this node
    nodeExt.rect = NodeOps.getRect(node);

    if (NodeOps.isElement(node)) {
        // "hidden" seems not defined properly,
        // In some cases, the parent element is "hidden", but the children are not expected to be hidden.
        // for example, ul tag often have a zero dimension.
        if (nodeExt.isHidden()) {
            // TODO: if there is already a `_h` attribute, use a longer one
            let attrName = this.config.ATTR_HIDDEN
            if (node.hasAttribute(attrName)) {
                attrName = "_ps_" + this.config.ATTR_HIDDEN
            }
            _storeViData(node, {hidden: {attrName: attrName}});
        }

        if (nodeExt.isOverflowHidden() || (nodeExt.hasParent() && _isParentOverflowHidden(nodeExt.parent().node, this.config))) {
            _storeViData(node, {overflowHidden: true});
        }
    }

    // all descendant nodes should be smaller than this one
    if (nodeExt.hasOverflowHidden()) {
        // TODO: also update max height
        nodeExt.updateMaxWidth(nodeExt.rect ? nodeExt.rect.width : 0);
    } else {
        nodeExt.updateMaxWidth(window.innerWidth || this.config.viewPortWidth);
    }

    nodeExt.adjustDOMRect();
};

/**
 * Leaving the element
 *
 * @param node {Node|Element} the node visited
 * @param  depth {Number} the depth in the DOM
 */
__pulsar_NodeFeatureCalculator.prototype.tail = function(node, depth) {
    if (NodeOps.isIFrame(node)) {
        return
    }

    let config = this.config
    let nodeExt = node.__pulsar_nodeExt;
    if (!nodeExt) {
        return
    }

    if (NodeOps.isElement(node)) {
        var viProps = {};

        if (config.ATTR_ELEMENT_NODE_DATA) {
            let data = nodeExt.formatDOMRect() + "|" + nodeExt.sequence + "|" + nodeExt.formatStyles()
            viProps.elementData = data;
        }

        viProps.vi = nodeExt.formatDOMRect();

        // calculate the rectangle of each child text node
        var textNodeRects = [];
        for (let i = 0; i < node.childNodes.length; ++i) {
            let childNodeExt = node.childNodes[i].__pulsar_nodeExt;
            if (childNodeExt && NodeOps.isText(childNodeExt.node)) {
                // 'tv' is short for 'text node vision information'
                textNodeRects[i] = childNodeExt.formatDOMRect();
            }
        }
        if (textNodeRects.length > 0) {
            viProps.textNodeRects = textNodeRects;
        }

        _storeViData(node, viProps);
    }

    if (this.debug > 0) {
        this.addDebugInfo(node)
    }

    delete node.__pulsar_nodeExt
};

/**
 * Calculate the width of the text node, this is a complement of the rectangle information, can be used for debugging
 *
 * @param node {Node} the node to enter
 * @param  depth {Number} the depth in the DOM
 * @return {Number}
 */
__pulsar_NodeFeatureCalculator.prototype.calcCharacterWidth = function(node, depth) {
    let parent = node.parentElement;
    // Read char-width from the WeakMap (no DOM pollution)
    var parentViData = __pulsar_utils__._viDataMap.get(parent);
    let cw = parentViData ? parentViData.charWidth : null;
    let width = 0;
    if (!cw) {
        let text = __pulsar_utils__.getTextContent(node);
        if (text.length > 0) {
            width = __pulsar_utils__.getElementTextWidth(text, parent);
            cw = Math.round(width / text.length * 10) / 10;
            _storeViData(parent, {charWidth: cw.toString()});
        }
    }
    return width
};

__pulsar_NodeFeatureCalculator.prototype.addDebugInfo = function(node) {
    if (!node.__pulsar_nodeExt) {
        return
    }

    let config = this.config;
    let nodeExt = node.__pulsar_nodeExt;

    if (NodeOps.isText(node)) {
        // 'tl' is short for 'text length', it's used to diagnosis
        if (node.textContent) {
            __pulsar_utils__.addTuple(node, config.ATTR_DEBUG, "tl" + nodeExt.sequence, node.textContent.length);
        }
    } else {
        let descend = __pulsar_utils__.getIntAttribute(node, "_d", 0);
        __pulsar_utils__.increaseIntAttribute(node.parentElement, '_d', 1);
    }
};
