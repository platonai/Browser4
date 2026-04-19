// VisionUtils.js

/**
 * Parses an RGB or RGBA color string.
 *
 * This function attempts to parse a color string and returns an object containing its red, green, blue, and alpha components.
 * If the input string has an invalid format or contains values outside the valid range, it returns null.
 *
 * @param {string|Object} color - A color string like "rgb(r, g, b)" or "rgba(r, g, b, a)", or an object with r, g, b, a properties.
 * @returns {Object|null} - Returns an object {r, g, b, a} if parsing succeeds, otherwise returns null.
 */
function parseRGB(color) {
    // If input is already an object, return it directly
    if (typeof color === "object") {
        return color;
    }

    // If input is null, undefined, or not a string, return null
    if (color == null || typeof color !== "string") {
        return null;
    }

    // Match rgb/rgba patterns with optional spaces and anchor start/end of string
    const match = color.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)$/);
    if (!match) return null;

    const r = Number(match[1]);
    const g = Number(match[2]);
    const b = Number(match[3]);
    const a = match[4] !== undefined ? Number(match[4]) : 1;

    // Validate RGB values: must be integers between 0 and 255
    if (![r, g, b].every(n => Number.isInteger(n) && n >= 0 && n <= 255)) {
        return null;
    }

    // Validate alpha value: must be between 0 and 1
    if (a < 0 || a > 1) {
        return null;
    }

    return { r, g, b, a };
}

/**
 * Checks whether a given RGB color is transparent.
 *
 * @param {Object} rgb - An object containing r, g, b, a values.
 * @returns {boolean} - True if the color is transparent (alpha = 0), false otherwise.
 */
function isTransparent(rgb) {
    return !rgb || rgb.a === 0;
}

// --- Linearization & Relative Luminance ---

/**
 * Linearizes a color component value according to WCAG standards.
 *
 * Converts a color component from sRGB to linear RGB space.
 *
 * @param {number} c - The color component value (0-255).
 * @returns {number} - The linearized value.
 */
function linearize(c) {
    c = c / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/**
 * Compute the relative luminance of a color
 *
 * This function calculates the relative luminance of a color based on its RGB values. Relative luminance is used to
 * determine the brightness of a color on a display, suitable for accessibility considerations and contrast calculations.
 *
 * @param {Object} color - An object containing the RGB values of the color
 * @param {number} color.r - The red component value of the color, in the range [0, 1]
 * @param {number} color.g - The green component value of the color, in the range [0, 1]
 * @param {number} color.b - The blue component value of the color, in the range [0, 1]
 * @returns {number} The relative luminance of the color, ranging from 0 (black) to 1 (white)
 */
function relativeLuminance({ r, g, b }) {
    // Calculate relative luminance using the weighted sum of the linearized RGB values
    return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b);
}

/**
 * Compute the contrast ratio between two colors
 * */
function contrastRatio(l1, l2) {
    const [bright, dark] = l1 > l2 ? [l1, l2] : [l2, l1];
    return (bright + 0.05) / (dark + 0.05);
}

/**
 * Gets the effective background color of an element by checking itself and its ancestors.
 *
 * Traverses up the DOM tree until a non-transparent background color is found.
 * Falls back to white if no such color is found.
 *
 * @param {Element} ele - The HTML element to check.
 * @returns {Object} - An object containing the effective background color in RGB(A) form.
 */
function getEffectiveBgColor(ele) {
    while (ele && ele !== document) {
        const colorStr = getComputedStyle(ele).backgroundColor;
        const rgb = parseRGB(colorStr);
        if (rgb && !isTransparent(rgb)) {
            return rgb;
        }
        ele = ele.parentElement;
    }
    return { r: 255, g: 255, b: 255, a: 1 }; // fallback: white
}

/**
 * 获取有效的背景色元素
 * 该函数从给定的元素开始，向上遍历DOM树，直到找到一个具有非透明背景色的元素
 * 如果找到这样的元素，函数返回该元素及其背景色的RGB值
 * 如果没有找到，函数返回白色作为默认背景色和null作为元素
 *
 * @param {Element} ele - 起始的DOM元素
 * @returns {Object} - 包含背景色RGB值和对应元素的对象
 */
function getEffectiveBgElement(ele) {
    // 遍历DOM树，直到找到具有非透明背景色的元素或到达document根节点
    while (ele && ele !== document) {
        // 获取当前元素的背景色字符串
        const colorStr = getComputedStyle(ele).backgroundColor;
        // 解析背景色字符串为RGB对象
        const rgb = parseRGB(colorStr);
        // 如果RGB对象存在且背景色不透明，则返回当前元素和其背景色
        if (rgb && !isTransparent(rgb)) {
            return {bgColor: rgb, ele: ele};
        }
        // 如果当前元素的背景色是透明的，则移动到父元素继续检查
        ele = ele.parentElement;
    }
    // 如果没有找到非透明背景色的元素，返回白色作为默认背景色和null作为元素
    return {bgColor: {r: 255, g: 255, b: 255, a: 1}, ele: null}; // fallback: white
}

// --- Visibility & Viewport Checks ---

/**
 * Checks if an element is visible on the page.
 *
 * @param {Element} el - The element to check.
 * @returns {boolean} - True if the element is visible, false otherwise.
 */
function isVisible(el) {
    const style = getComputedStyle(el);
    return style.display !== "none" && style.visibility !== "hidden";
}

/**
 * Checks if an element is within the current viewport.
 *
 * @param {Element} el - The element to check.
 * @returns {boolean} - True if the element is within the viewport and meets size constraints.
 */
function isInViewport(el) {
    const rect = el.getBoundingClientRect();
    const area = rect.width * rect.height;
    const maxSize = window.innerWidth * window.innerHeight * 0.8;
    const minSize = 4;

    return (
        area > minSize &&
        area < maxSize &&
        rect.bottom > 0 &&
        rect.top < window.innerHeight &&
        rect.right > 0 &&
        rect.left < window.innerWidth
    );
}

/**
 * Computes the average background color of elements currently visible in the viewport.
 *
 * @returns {Object} - The weighted average background color as { r, g, b }.
 */
function computeAverageBackgroundColorInViewport() {
    let totalR = 0, totalG = 0, totalB = 0, totalWeight = 0;

    const walker = document.createTreeWalker(
        document.body,
        NodeFilter.SHOW_ELEMENT,
        {
            acceptNode: node => {
                if (!isVisible(node)) return NodeFilter.FILTER_SKIP;
                if (!isInViewport(node)) return NodeFilter.FILTER_SKIP;
                return NodeFilter.FILTER_ACCEPT;
            }
        }
    );

    let node = walker.nextNode();
    while (node) {
        const rect = node.getBoundingClientRect();
        const area = rect.width * rect.height;

        //  && rect.width < 800 && rect.height < 800
        if (node instanceof Element && rect.width > 2 && rect.height > 2 && rect.height < 800) {
            const bg = getEffectiveBgColor(node);
            const weight = area * bg.a;

            totalR += bg.r * weight;
            totalG += bg.g * weight;
            totalB += bg.b * weight;
            totalWeight += weight;
        }

        node = walker.nextNode();
    }

    if (totalWeight === 0) return { r: 255, g: 255, b: 255 };

    return {
        r: Math.round(totalR / totalWeight),
        g: Math.round(totalG / totalWeight),
        b: Math.round(totalB / totalWeight),
    };
}

/**
 * Analyzes the contrast between foreground and background colors.
 *
 * Uses the WCAG-defined contrast ratio formula and accessibility levels.
 *
 * @param {Object} foregroundRGB - Foreground color in RGB format.
 * @param {Object} backgroundRGB - Background color in RGB format.
 * @returns {Object} - Contains the contrast ratio and accessibility level.
 */
function analyzeContrast(foregroundRGB, backgroundRGB) {
    const l1 = relativeLuminance(foregroundRGB);
    const l2 = relativeLuminance(backgroundRGB);
    const ratio = contrastRatio(l1, l2);

    return {
        ratio: ratio.toFixed(2),
        level:
            ratio >= 7
                ? "AAA (Excellent)"
                : ratio >= 4.5
                    ? "AA (Acceptable)"
                    : ratio >= 3
                        ? "⚠️ Suitable only for large text"
                        : "❌ Insufficient contrast",
    };
}

/**
 * Determines if a given color is considered bright.
 *
 * Brightness is calculated using a simple luminance approximation.
 *
 * @param {Object} rgb - The color to evaluate.
 * @returns {boolean} - True if brightness > 128, indicating a bright color.
 */
function isColorBright(rgb) {
    if (!rgb) return false;
    const brightness = (rgb.r * 299 + rgb.g * 587 + rgb.b * 114) / 1000;
    return brightness > 128; // Values above 128 are considered bright
}

/**
 * Estimates the area of a text element based on its font size and text length.
 *
 * This function assumes a simple font size-to-area relationship and does not consider
 * actual font metrics or complex text layouts.
 *
 * @param {Element} node - The text element to estimate.
 * @returns {number} - An estimated area in pixels.
 */
function estimateTextArea(node) {
    const style = window.getComputedStyle(node);
    const fontSize = parseFloat(style.fontSize); // in px
    const textLength = (node.textContent || '').trim().length;

    if (!textLength || fontSize < 6) return 0;

    // 估算：字体宽度大概是字体大小的 0.5 ~ 0.6 倍
    const avgCharWidth = fontSize * 0.6;

    return avgCharWidth * fontSize * textLength; // 宽 * 高 * 字符数
}

function showFloatingLabel(node, text, options = {}) {
    const {
        background = 'gold',
        color = 'black',
        fontSize = '12px',
        offsetX = 0,
        offsetY = 0,
        zIndex = 9999
    } = options;

    const rect = node.getBoundingClientRect();

    const label = document.createElement('div');
    label.className = 'contrast-rank-label';
    label.textContent = text;

    Object.assign(label.style, {
        position: 'absolute',
        top: `${rect.top + window.scrollY + offsetY}px`,
        left: `${rect.left + window.scrollX + offsetX}px`,
        background,
        color,
        fontSize,
        fontWeight: 'bold',
        padding: '2px 4px',
        borderRadius: '4px',
        boxShadow: '0 0 2px rgba(0,0,0,0.5)',
        pointerEvents: 'none',
        zIndex
    });

    document.body.appendChild(label);
}

/**
 * Finds the top N elements with the highest contrast between foreground and background colors.
 *
 * This function traverses the DOM, calculates the contrast ratio for each visible element,
 * highlights the top N elements visually on the page, and logs detailed information to the console.
 *
 * @param {number} n - The number of top contrast elements to return.
 * @returns {Array} - An array of objects containing the element, contrast value, and color details.
 */
function findTopNMostContrastElements(n = 10) {
    const viewportHeight = window.innerHeight;
    const viewportWidth = window.innerWidth;

    const results = [];

    const walker = document.createTreeWalker(
        document.body,
        NodeFilter.SHOW_ELEMENT,
        {
            acceptNode(node) {
                const rect = node.getBoundingClientRect();
                const hasDirectText = Array.from(node.childNodes).some(n =>
                    n.nodeType === Node.TEXT_NODE && n.textContent.trim()
                );

                const tag = node.tagName?.toLowerCase();
                const isButtonLike =
                    tag === 'button' ||
                    tag === 'summary' ||
                    tag === 'label' ||
                    (tag === 'a' && node.hasAttribute('href')) ||
                    (tag === 'input' && /^(button|submit|reset)$/i.test(node.type));

                const hasAriaLabel = node.hasAttribute('aria-label');
                const hasTitle = node.hasAttribute('title');

                if (
                    (!hasDirectText && !isButtonLike && !hasAriaLabel && !hasTitle) ||
                    rect.width < 10 || rect.height < 10 ||
                    rect.height > 500 ||
                    rect.bottom < 0 || rect.top > viewportHeight ||
                    rect.right < 0 || rect.left > viewportWidth
                ) {
                    return NodeFilter.FILTER_SKIP;
                }
                return NodeFilter.FILTER_ACCEPT;
            }
        }
    );

    let node;
    while ((node = walker.nextNode())) {
        const fgColor = window.getComputedStyle(node).color;
        const background = getEffectiveBgElement(node);
        const bgColor = background.bgColor;
        const bgElement = background.ele

        if (!bgColor || bgColor === "transparent" || !fgColor) continue;

        const area = estimateTextArea(node);
        const bgRect = bgElement.getBoundingClientRect();
        const bgArea = bgRect.width * bgRect.height;
        const textLength = (node.textContent || '').trim().length;

        try {
            const bgRGB = parseRGB(bgColor);
            const fgRGB = parseRGB(fgColor);
            const l1 = relativeLuminance(bgRGB);
            const l2 = relativeLuminance(fgRGB);

            const contrast = contrastRatio(l1, l2);
            const weightedContrast = contrast * Math.log(area + 1);
            results.push({ node, contrast, bgRGB, fgRGB, area, bgArea, weightedContrast, textLength });
        } catch (e) {
            // 忽略无法解析的颜色
        }
    }

    const topN = results
        .sort((a, b) => b.weightedContrast - a.weightedContrast)
        .slice(0, n);

    topN.forEach(({ node, contrast, bgRGB, fgRGB, area, bgArea, weightedContrast, textLength }, idx) => {
        node.style.outline = "2px solid gold";
        showFloatingLabel(node, (idx + 1).toString())
        console.log(
            `#${idx + 1}: <${node.tagName.toLowerCase()}> area: ${Math.floor(area)} bgArea: ${Math.floor(bgArea)} ` +
            `textLength: ${textLength} weightedContrast: ${weightedContrast.toFixed(2)} `
            + `(fg: rgb(${fgRGB.r},${fgRGB.g},${fgRGB.b}) / bg: rgb(${bgRGB.r},${bgRGB.g},${bgRGB.b}))`
        );
    });

    return topN;
}

// --- 导出 API ---
window.VisionUtils = {
    parseRGB,
    getEffectiveBgColor,
    computeAverageBackgroundColorInViewport,
    analyzeContrast,
    relativeLuminance,
    contrastRatio,
    isColorBright,
    showFloatingLabel,
    findTopNMostContrastElements
};
