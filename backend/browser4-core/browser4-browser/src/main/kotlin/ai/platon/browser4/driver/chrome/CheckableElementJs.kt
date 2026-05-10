package ai.platon.browser4.driver.chrome

/**
 * Shared JS function declarations for checkable elements.
 *
 * Keep check/uncheck/isChecked semantics aligned across driver layers.
 */
object CheckableElementJs {
    private const val RESOLVE_TARGET_AND_CHECKABLE = """
        const host = this;
        const resolveTarget = (element) => {
            if (!element) return null;
            if (element instanceof HTMLLabelElement) {
                return element.control
                    || (element.htmlFor ? document.getElementById(element.htmlFor) : null)
                    || element.querySelector('input[type="checkbox"], input[type="radio"], [role="checkbox"], [role="radio"], [role="switch"]');
            }
            return element;
        };

        const target = resolveTarget(host);
        if (!target) return null;

        const role = (target.getAttribute('role') || '').toLowerCase();
        const type = target instanceof HTMLInputElement ? (target.type || '').toLowerCase() : '';
        const isNativeCheckable = target instanceof HTMLInputElement && (type === 'checkbox' || type === 'radio');
        const isAriaCheckable = role === 'checkbox' || role === 'radio' || role === 'switch';
        if (!isNativeCheckable && !isAriaCheckable) return null;
    """

    val IS_CHECKED_FUNCTION_DECLARATION: String = """
        function() {
            $RESOLVE_TARGET_AND_CHECKABLE

            if (isNativeCheckable) {
                return !!target.checked;
            }

            return target.getAttribute('aria-checked') === 'true';
        }
    """.trimIndent()

    val SET_CHECKED_FUNCTION_DECLARATION: String = """
        function(shouldCheck) {
            $RESOLVE_TARGET_AND_CHECKABLE

            const isChecked = () => {
                if (isNativeCheckable) {
                    return !!target.checked;
                }
                return target.getAttribute('aria-checked') === 'true';
            };

            const current = isChecked();
            if (current === shouldCheck) return current;

            const clickTarget = host instanceof HTMLElement ? host : target;
            if (clickTarget instanceof HTMLElement) {
                clickTarget.click();
            } else if (target instanceof HTMLElement) {
                target.click();
            }

            return isChecked();
        }
    """.trimIndent()
}
