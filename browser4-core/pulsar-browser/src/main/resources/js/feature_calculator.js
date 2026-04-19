/**
 * Created by Vincent on 16-5-17.
 *
 * NodeVisitor: used with NodeTraversor together
 */

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
            node.setAttribute(attrName, '1');
        }

        if (nodeExt.isOverflowHidden() || (nodeExt.hasParent() && nodeExt.parent().node.hasAttribute(this.config.ATTR_OVERFLOW_HIDDEN))) {
            // node.toggleAttribute(ATTR_OVERFLOW_HIDDEN, true);
            node.setAttribute(this.config.ATTR_OVERFLOW_HIDDEN, '1');
        }
    }

    // all descendant nodes should be smaller than this one
    if (nodeExt.hasOverflowHidden()) {
        // TODO: also update max height
        nodeExt.updateMaxWidth(nodeExt.rect.width);
    } else {
        nodeExt.updateMaxWidth(this.config.viewPortWidth);
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
        if (config.ATTR_ELEMENT_NODE_DATA) {
            let data = nodeExt.formatDOMRect() + "|" + nodeExt.sequence + "|" + nodeExt.formatStyles()
            NodeOps.setAttributeIfNotBlank(node, config.ATTR_ELEMENT_NODE_DATA, data);
        }

        NodeOps.setAttributeIfNotBlank(node, config.ATTR_ELEMENT_NODE_VI, nodeExt.formatDOMRect());

        // calculate the rectangle of each child text node
        for (let i = 0; i < node.childNodes.length; ++i) {
            let childNodeExt = node.childNodes[i].__pulsar_nodeExt;
            if (childNodeExt && NodeOps.isText(childNodeExt.node)) {
                // 'tv' is short for 'text node vision information'
                NodeOps.setAttributeIfNotBlank(node, config.ATTR_TEXT_NODE_VI + i, childNodeExt.formatDOMRect());
            }
        }
    }

    if (this.debug > 0) {
        this.addDebugInfo()
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
    let cw = parent.getAttribute('_cw');
    let width = 0;
    if (!cw) {
        let text = __pulsar_utils__.getTextContent(node);
        if (text.length > 0) {
            width = __pulsar_utils__.getElementTextWidth(text, parent);
            cw = Math.round(width / text.length * 10) / 10;
            parent.setAttribute('_cw', cw.toString())
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
            __pulsar_utils__.addTuple(node, config.ATTR_DEBUG, "tl" + i, node.textContent.length);
        }
    } else {
        let descend = __pulsar_utils__.getIntAttribute(node, "_d", 0);
        __pulsar_utils__.increaseIntAttribute(node.parentElement, '_d', 1);
    }
};
