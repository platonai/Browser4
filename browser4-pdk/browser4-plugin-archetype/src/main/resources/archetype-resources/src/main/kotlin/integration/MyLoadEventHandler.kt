package ${package}.integration

/**
 * Load-phase event handler.
 *
 * Load events cover URL normalization, fetching, and parsing.
 *
 * The 9 load event hooks (in execution order):
 *   onNormalize -> onWillLoad -> onWillFetch -> onFetched ->
 *   onWillParse -> onWillParseHTMLDocument -> onHTMLDocumentParsed ->
 *   onParsed -> onLoaded
 */
open class MyLoadEventHandler {
    // Add your load-phase event handling logic here.
}
