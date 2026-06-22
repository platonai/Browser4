/**
 * get-unique-selector.js
 *
 * Generates a unique CSS selector for a DOM element.
 *
 * Usage:
 *   browser4-cli eval --file=get-unique-selector.js <ref>
 *
 * Example:
 *   browser4-cli eval --file=get-unique-selector.js e5
 *   # → "#main-content > div.product-card:nth-child(3) > .price"
 *
 * The element is resolved from the snapshot ref provided as the positional
 * argument to `browser4-cli eval`. The script runs in the page context with
 * the referenced element available as `element`.
 *
 * Strategy (in order of preference):
 *   1. If the element has an id → return "#the-id" immediately (shortest,
 *      fastest, and guaranteed unique).
 *   2. Walk up from the element, building a segment for each ancestor:
 *      - Use tag#id if the ancestor has an id → stop walking.
 *      - Use tag.class1.class2 for elements with stable classes.
 *      - Fall back to tag:nth-child(n) for positional disambiguation.
 *   3. Return the full selector path joined by " > ".
 */

(function () {
  // Resolve the element — browser4-cli provides the targeted element as
  // `element` when a ref argument is passed to eval.
  var el = (typeof element !== 'undefined') ? element : null;

  // Fallback: if element is not set, try to use the first argument.
  // (Some eval implementations pass the element as arguments[0].)
  if (!el && typeof arguments !== 'undefined' && arguments.length > 0) {
    el = arguments[0];
  }

  if (!el || el.nodeType !== Node.ELEMENT_NODE) {
    return 'ERROR: No element available. Pass a snapshot ref: eval --file=get-unique-selector.js e5';
  }

  /**
   * Escape a CSS selector string so it can be used literally in a selector.
   * Handles characters that are special in CSS selectors.
   */
  function cssEscape(value) {
    if (typeof CSS !== 'undefined' && CSS.escape) {
      return CSS.escape(value);
    }
    // Basic fallback for environments without CSS.escape
    return value.replace(/[!"#$%&'()*+,./:;<=>?@[\]^`{|}~]/g, '\\$&');
  }

  /**
   * Build a unique selector segment for a single element (without ancestors).
   */
  function segmentFor(el) {
    var tag = el.tagName.toLowerCase();

    // Prefer id — it's unique by definition.
    if (el.id) {
      return '#' + cssEscape(el.id);
    }

    // Use stable class names.
    // Only include classes that don't look auto-generated (heuristic).
    if (el.classList && el.classList.length > 0) {
      var classes = Array.from(el.classList)
        .filter(function (c) {
          // Skip classes that look auto-generated (CSS modules, styled-components, etc.)
          return !/[A-Z]/.test(c) &&            // no camelCase (likely JS-generated)
                 !/^[a-z]+-[a-z0-9]{6,}$/.test(c) &&  // no hash-suffix classes
                 c.indexOf('_') === -1 &&        // no underscored classes
                 c.length > 1;                   // skip single-char classes
        });

      if (classes.length > 0) {
        return tag + '.' + classes.map(cssEscape).join('.');
      }
    }

    // Fall back to nth-child for positional disambiguation.
    if (el.parentNode) {
      var siblings = Array.from(el.parentNode.children);
      var sameTagSiblings = siblings.filter(function (s) {
        return s.tagName === el.tagName;
      });

      if (sameTagSiblings.length > 1) {
        var index = sameTagSiblings.indexOf(el) + 1;
        return tag + ':nth-of-type(' + index + ')';
      }
    }

    return tag;
  }

  // Build the full selector path.
  var parts = [];
  var current = el;

  while (current && current.nodeType === Node.ELEMENT_NODE) {
    var seg = segmentFor(current);
    parts.unshift(seg);

    // Stop at an id — it's globally unique.
    if (current.id) {
      break;
    }

    // Stop at the body — the rest of the path adds no value.
    if (current.tagName.toLowerCase() === 'body') {
      break;
    }

    current = current.parentNode;
  }

  return parts.join(' > ');
})();
