package ${package}.integration

/**
 * Browse-phase event handler.
 *
 * Browse events cover the full browser automation lifecycle: navigation,
 * scrolling, interaction, and RPA. This class demonstrates the handler
 * pattern. You can also implement the browse handlers directly in your
 * auto-configuration class (see [PluginAutoConfiguration]).
 *
 * The 17 browse event hooks (in execution order):
 *   onWillLaunchBrowser -> onBrowserLaunched -> onWillFetch ->
 *   onWillNavigate -> onNavigated -> onWillInteract ->
 *   onWillCheckDocumentState -> onDocumentFullyLoaded ->
 *   onWillScroll -> onDidScroll -> onDocumentSteady (best for RPA) ->
 *   onWillComputeFeature -> onFeatureComputed -> onDidInteract ->
 *   onWillStopTab -> onTabStopped -> onFetched
 */
open class MyBrowseEventHandler {
    // Add your browse event handling logic here.
    // Example:
    //
    // fun onDocumentSteady(page: WebPage, driver: WebDriver) {
    //     val title = driver.title()
    //     val content = driver.pageSource()
    //     // ... process content ...
    // }
}
