/**
 * Jest setup file for Browser4 JS runtime tests.
 *
 * Polyfills browser APIs that jsdom may not fully implement:
 * - DOMRect constructor
 * - document.createTreeWalker (with NodeFilter constants)
 * - Canvas API (for text width measurement in getTextWidth)
 */

// ---------------------------------------------------------------------------
// DOMRect polyfill
// ---------------------------------------------------------------------------
if (typeof DOMRect === 'undefined') {
    global.DOMRect = class DOMRect {
        constructor(x = 0, y = 0, width = 0, height = 0) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.left = x;
            this.top = y;
            this.right = x + width;
            this.bottom = y + height;
        }
        toJSON() {
            return { x: this.x, y: this.y, width: this.width, height: this.height,
                     top: this.top, left: this.left, right: this.right, bottom: this.bottom };
        }
    };
}

// ---------------------------------------------------------------------------
// NodeFilter constants (if not provided by jsdom)
// ---------------------------------------------------------------------------
if (typeof NodeFilter === 'undefined') {
    global.NodeFilter = {
        FILTER_ACCEPT: 1,
        FILTER_REJECT: 2,
        FILTER_SKIP: 3,
        SHOW_ALL: 0xFFFFFFFF,
        SHOW_ELEMENT: 1,
        SHOW_ATTRIBUTE: 2,
        SHOW_TEXT: 4,
        SHOW_CDATA_SECTION: 8,
        SHOW_ENTITY_REFERENCE: 16,
        SHOW_ENTITY: 32,
        SHOW_PROCESSING_INSTRUCTION: 64,
        SHOW_COMMENT: 128,
        SHOW_DOCUMENT: 256,
        SHOW_DOCUMENT_TYPE: 512,
        SHOW_DOCUMENT_FRAGMENT: 1024,
        SHOW_NOTATION: 2048,
    };
}

// ---------------------------------------------------------------------------
// TreeWalker polyfill (jsdom may or may not support createTreeWalker)
// ---------------------------------------------------------------------------
if (typeof document.createTreeWalker !== 'function') {
    document.createTreeWalker = function(root, whatToShow, filter) {
        const walker = {
            root: root,
            whatToShow: whatToShow,
            filter: filter,
            currentNode: root,
            _visited: new Set(),
            _done: false,

            nextNode: function() {
                // Simple depth-first pre-order traversal
                if (this._done) return null;

                // Try first child
                if (this.currentNode.childNodes && this.currentNode.childNodes.length > 0) {
                    this.currentNode = this.currentNode.childNodes[0];
                } else {
                    // Try next sibling, walking up
                    let node = this.currentNode;
                    while (node && node !== root) {
                        if (node.nextSibling) {
                            this.currentNode = node.nextSibling;
                            break;
                        }
                        node = node.parentNode;
                    }
                    if (node === root || !node) {
                        this._done = true;
                        return null;
                    }
                }

                // Apply filter
                if (this.filter) {
                    let result;
                    if (typeof this.filter === 'function') {
                        result = this.filter(this.currentNode);
                    } else if (this.filter.acceptNode) {
                        result = this.filter.acceptNode(this.currentNode);
                    } else {
                        result = NodeFilter.FILTER_ACCEPT;
                    }
                    if (result === NodeFilter.FILTER_REJECT || result === NodeFilter.FILTER_SKIP) {
                        return this.nextNode();
                    }
                }

                // Check whatToShow
                if (this.whatToShow !== NodeFilter.SHOW_ALL) {
                    const typeMap = {
                        1: NodeFilter.SHOW_ELEMENT,
                        3: NodeFilter.SHOW_TEXT,
                        8: NodeFilter.SHOW_COMMENT,
                    };
                    const mask = typeMap[this.currentNode.nodeType] || 0;
                    if (!(this.whatToShow & mask)) {
                        return this.nextNode();
                    }
                }

                return this.currentNode;
            },

            previousNode: function() {
                // Not needed for tests
                return null;
            },

            parentNode: function() {
                // Not needed for tests
                return null;
            },

            firstChild: function() {
                // Not needed for tests
                return null;
            },

            lastChild: function() {
                // Not needed for tests
                return null;
            },

            previousSibling: function() {
                // Not needed for tests
                return null;
            },

            nextSibling: function() {
                // Not needed for tests
                return null;
            },
        };

        return walker;
    };
}

// ---------------------------------------------------------------------------
// Canvas API polyfill (for getTextWidth)
// ---------------------------------------------------------------------------
// jsdom's HTMLCanvasElement.getContext throws "not implemented".
// Override at the prototype level to provide a mock 2d context.
if (typeof HTMLCanvasElement !== 'undefined' && HTMLCanvasElement.prototype) {
    const origGetContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function(contextType, ...args) {
        // Try the real implementation first (it might work in some jsdom versions)
        try {
            const ctx = origGetContext.call(this, contextType, ...args);
            if (ctx) return ctx;
        } catch (_) {
            // Fall through to mock
        }

        // Return a mock 2d context with measureText support
        return {
            font: '',
            measureText: function(text) {
                // Approximate: each character is ~0.6 * fontSize wide
                const fontSizeMatch = this.font.match(/(\d+)px/);
                const fontSize = fontSizeMatch ? parseInt(fontSizeMatch[1]) : 16;
                return { width: text.length * fontSize * 0.6 };
            },
            fillRect: function() {},
            clearRect: function() {},
            getImageData: function() { return { data: new Uint8ClampedArray(0) }; },
            putImageData: function() {},
            createImageData: function() { return { data: new Uint8ClampedArray(0) }; },
            setTransform: function() {},
            drawImage: function() {},
            save: function() {},
            restore: function() {},
            scale: function() {},
            rotate: function() {},
            translate: function() {},
            transform: function() {},
            beginPath: function() {},
            closePath: function() {},
            moveTo: function() {},
            lineTo: function() {},
            stroke: function() {},
            fill: function() {},
            clip: function() {},
            quadraticCurveTo: function() {},
            bezierCurveTo: function() {},
            arc: function() {},
            arcTo: function() {},
            rect: function() {},
            fillText: function() {},
            strokeText: function() {},
        };
    };
}

// ---------------------------------------------------------------------------
// Global references for scripts that expect these to exist
// ---------------------------------------------------------------------------
global.HTMLElement = global.HTMLElement || class HTMLElement extends global.Element {};
