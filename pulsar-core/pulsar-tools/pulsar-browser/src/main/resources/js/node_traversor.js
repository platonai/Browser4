/**
 * Created by vincent on 16-5-17.
 * Updated to use JavaScript built-in TreeWalker API
 */

"use strict";

/**
 * Create a new traversor using JavaScript's built-in TreeWalker API.
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

window.__pulsar_ = window.__pulsar_ || function () {}
window.__pulsar_.__pulsar_NodeTraversor = __pulsar_NodeTraversor

/**
 * Start a depth-first traverse of the root and all of its descendants using TreeWalker.
 * Maintains the same visiting order as the original implementation (head on entry, tail on exit).
 * @param root {Node} the root node point to traverse.
 */
__pulsar_NodeTraversor.prototype.traverse = function(root) {
    let visitor = this.visitor;
    visitor.stopped = false;

    if (!visitor.tail) {
        // empty function
        visitor.tail = function () {}
    }

    // Use TreeWalker API to traverse all nodes in document order
    let walker = document.createTreeWalker(
        root,
        NodeFilter.SHOW_ALL,  // Show all nodes
        null,
        false
    );

    let node = walker.currentNode;  // Start at root
    let depth = 0;

    while (!visitor.stopped && node != null) {
        visitor.head(node, depth);
        
        if (node.childNodes.length > 0) {
            // Has children, go to first child
            node = walker.firstChild();
            depth++;
        } else {
            // No children, process tail and move to next sibling or parent's sibling
            while (node.nextSibling == null && depth > 0) {
                visitor.tail(node, depth);
                node = walker.parentNode();
                depth--;
            }
            visitor.tail(node, depth);
            if (node === root) {
                break;
            }
            node = walker.nextSibling();
        }
    }
};
