/**
 * Created by Vincent on 16-5-17.
 */

"use strict";

/**
 * Create a new traversor.
 *
 * NOTE: document.createTreeWalker is OK
 *
 * @param visitor {Object} a class implementing the {@link __pulsar_NodeFeatureCalculator} interface, to be called when visiting each node.
 */
let __pulsar_NodeTraversor = function(visitor) {
    this.visitor = visitor;
    this.options = {
        diagnosis : false
    };

    if (arguments.length > 1) {
        this.options = arguments[1];
    }
}

/**
 * Start a depth-first traverse of the root and all of its descendants.
 * @param root {Node} the root node point to traverse.
 */
__pulsar_NodeTraversor.prototype.traverse = function(root) {
    let node = root
    let depth = 0
    let visitor = this.visitor
    visitor.stopped = false

    if (!visitor.tail) {
        // empty function
        visitor.tail = function () {}
    }

    while (!visitor.stopped && node != null) {
        visitor.head(node, depth);
        if (node.childNodes.length > 0) {
            node = node.childNodes[0];
            depth++;
        } else {
            while (node.nextSibling == null && depth > 0) {
                visitor.tail(node, depth);
                node = node.parentNode;
                depth--;
            }
            visitor.tail(node, depth);
            if (node === root)
                break;
            node = node.nextSibling;
        }
    }
};
