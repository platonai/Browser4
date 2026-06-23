"use strict";

/**
 * Computed style and color utilities for the Browser4 runtime.
 * Attaches methods to the already-defined __pulsar_utils__ object.
 *
 * Load order: must be loaded AFTER __pulsar_utils__.js.
 */

/**
 * Get the computed style of an element matching a CSS selector.
 *
 * @param selector {string} The selector to get the element from.
 * @param propertyNames {String|Array}
 * @return {Object|Boolean|null}
 * */
__pulsar_utils__.queryComputedStyle = function(selector, propertyNames) {
    let ele = document.querySelector(selector);
    if (!ele) {
        return null;
    }

    return this.getComputedStyle(ele, propertyNames)
};

/**
 * Get the computed style of a node.
 *
 * Example result:
 * {color=f, background-color=007bff, font-size=16px, ...}
 *
 * @param node {Node|Element|Text}
 * @param propertyNames {String|Array}
 * @return {Object|Boolean|null}
 * */
__pulsar_utils__.getComputedStyle = function(node, propertyNames) {
    if (typeof propertyNames === 'string') {
        propertyNames = [propertyNames]
    }

    if (node.nodeType === Node.ELEMENT_NODE) {
        let styles = {};
        let computedStyle = window.getComputedStyle(node, null);
        propertyNames.forEach(propertyName =>
            styles[propertyName] = this.getPropertyValue(computedStyle, propertyName)
        );
        return styles
    } else {
        return null
    }
};

/**
 * Get a simplified property value from a computed style declaration.
 *
 * @param style {CSSStyleDeclaration}
 * @param propertyName {String}
 * @return {String}
 * */
__pulsar_utils__.getPropertyValue = function(style, propertyName) {
    let value = style.getPropertyValue(propertyName);

    if (!value || value === '') {
        return ''
    }

    if (propertyName === 'font-size') {
        value = value.substring(0, value.lastIndexOf('px'))
    } else if (propertyName === 'color' || propertyName === 'background-color') {
        value = this.shortenHex(__pulsar_utils__.rgb2hex(value));
        // skip prefix '#'
        value = value.substring(1)
    }

    return value
};

/**
 * Convert CSS rgb(a) color string to hex.
 *
 * Example: rgb(255, 255, 0) -> #ffff00
 *
 * @param rgb {String}
 * @return {String}
 * */
__pulsar_utils__.rgb2hex = function(rgb) {
    let parts = rgb.match(/^rgba?[\s+]?\([\s+]?(\d+)[\s+]?,[\s+]?(\d+)[\s+]?,[\s+]?(\d+)[\s+]?/i);
    return (parts && parts.length === 4) ? "#" +
        ("0" + parseInt(parts[1],10).toString(16)).slice(-2) +
        ("0" + parseInt(parts[2],10).toString(16)).slice(-2) +
        ("0" + parseInt(parts[3],10).toString(16)).slice(-2) : '';
};

/**
 * Convert CSS hex color to shorthand hex if all pairs match.
 *
 * Example: #ffcc00 -> #fc0, #cccccc -> #ccc, #aaaaaa -> #a
 *
 * @param hex {String}
 * @return {String}
 * */
__pulsar_utils__.shortenHex = function(hex) {
    if ((hex.charAt(1) === hex.charAt(2))
        && (hex.charAt(3) === hex.charAt(4))
        && (hex.charAt(5) === hex.charAt(6))) {
        hex = "#" + hex.charAt(1) + hex.charAt(3) + hex.charAt(5);
    }

    // the most simple case: all chars are the same
    if (hex.length === 4) {
        let c = hex.charAt(1);
        if (hex.charAt(2) === c && hex.charAt(3) === c) {
            return '#' + c
        }
    }

    return hex
};
