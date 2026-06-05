package ai.platon.pulsar.persist.model

import ai.platon.pulsar.common.config.AppConstants.DEFAULT_VIEWPORT
import ai.platon.pulsar.persist.gora.generated.GActiveDOMStat
import ai.platon.pulsar.persist.gora.generated.GActiveDOMStatus

/**
 * Records the status of a DOM in a real browser.
 * */
data class ActiveDOMStatus(
        val n: Int = 0,
        val scroll: Int = 0,
        val st: String = "",
        val r: String = "",
        val idl: String = "",
        val ec: String = ""
)

/**
 * The statistics of a DOM in a real browser.
 * */
data class ActiveDOMStat(
        val ni: Int = 0,
        val na: Int = 0,
        val nnm: Int = 0,
        val nst: Int = 0,
        val w: Int = 0,
        val h: Int = 0
)

/**
 * The Location interface represents the location (URL) of the object it is linked to. Changes done on it are reflected
 * on the object it relates to. Both the Document and Window interface have such a linked Location, accessible via
 * `Document.location` and `Window.location` respectively.
 *
 * @see [Location ](https://developer.mozilla.org/en-US/docs/Web/API/Location)
 * */
data class Location(
    var href: String = "",
    var origin: String = "",
    var protocol: String = "",
    var host: String = "",
    var hostname: String = "",
    var port: String = "",
    var pathname: String = "",
    var search: String = "",
    var hash: String = ""
)

/**
 * URLs of a document computed by javascript in a real browser.
 *
 * URLs and location properties in the browser:
 *
 * In the Document Object Model (DOM), the relationship between `document.URL`, `document.documentURI`,
 * `document.location`, and the URL displayed in the browser's address bar is as follows:
 * 1. `document.URL`:
 *    - This property returns the URL of the document as a string.
 *    - It is a read-only property and reflects the current URL of the document.
 *    - Changes to `document.location` will also update `document.URL`.
 * 2. `document.documentURI`:
 *    - This property returns the URI of the document.
 *    - It is also a read-only property and typically contains the same value as `document.URL`.
 *    - However, `document.documentURI` is defined to be the URI that was provided to the parser, which could
 *      potentially differ from `document.URL` in certain cases, although in practice, this is rare.
 * 3. `document.location`:
 *    - This property represents the location (URL) of the current page and allows you to manipulate the URL.
 *    - It is a read-write property, which means you can change it to navigate to a different page or to manipulate
 *      query strings, fragments, etc.
 *    - Changes to `document.location` will cause the browser to navigate to the new URL, updating both `document.URL`
 *      and the URL displayed in the address bar.
 * 4. URL displayed in the address bar:
 *    - The URL displayed in the browser's address bar is what users see and can edit directly.
 *    - It is typically synchronized with `document.URL` and `document.location.href` (a property of `document.location`).
 *    - When the page is loaded or when `document.location` is modified, the address bar is updated to reflect the new URL.
 * In summary, `document.URL` and `document.documentURI` are read-only properties that reflect the current URL of the
 * document, while `document.location` is a read-write property that not only reflects the current URL but also allows
 * you to navigate to a new one. The URL displayed in the address bar is a user-facing representation of the current
 * document's URL, which is usually in sync with `document.location`.
 * */
data class ActiveDOMUrls(
    /**
     * The entire URL of the document, including the protocol (like http://)
     *
     * This property is retrieved from javascript `document.URL`.
     */
    var URL: String = "",
    /**
     * In javascript, the baseURI is a property of Node, it's the absolute base URL of the
     * document containing the node. A baseURI is used to resolve relative URLs.
     *
     * This property is retrieved from javascript `document.baseURI`.
     *
     * The base URL is determined as follows:
     * 1. By default, the base URL is the location of the document
     *    (as determined by window.location).
     * 2. If the document has an `<base>` element, its href attribute is used.
     * */
    var baseURI: String = "",
    /**
     * Represents the location of a document.
     *
     * This property is retrieved from javascript `document.location.href`.
     * */
    var location: String = "",
    /**
     * In javascript, the `window.location`, or `document.location`, is a read-only property
     * returns a Location object, which contains information about the URL of the
     * document and provides methods for changing that URL and loading another URL.
     *
     * This property is retrieved from javascript `document.location`.
     *
     * To retrieve just the URL as a string, the read-only `document.URL` property can
     * also be used.
     *
     * @see [Location ](https://developer.mozilla.org/en-US/docs/Web/API/Location)
     * */
    var location2: Location? = null,
    /**
     * Returns the document location as a string.
     *
     * This property is retrieved from javascript `document.documentURI`.
     *
     * The documentURI property can be used on any document types. The document.URL
     * property can only be used on HTML documents.
     *
     * @see <a href='https://www.w3schools.com/jsref/prop_document_documenturi.asp'>
     *     HTML DOM Document documentURI</a>
     * */
    var documentURI: String = "",
    /**
     * Returns the URI of the page that linked to this page.
     */
    var referrer: String = "",
) {
    companion object {
        val DEFAULT = ActiveDOMUrls()
    }
}

data class ActiveDOMStatTrace(
    val status: ActiveDOMStatus? = ActiveDOMStatus(),
    val initStat: ActiveDOMStat? = ActiveDOMStat(),
    val lastStat: ActiveDOMStat? = ActiveDOMStat(),
    val initD: ActiveDOMStat? = ActiveDOMStat(),
    val lastD: ActiveDOMStat? = ActiveDOMStat()
) {
    override fun toString(): String {
        val s1 = initStat?:ActiveDOMStat()
        val s2 = lastStat?:ActiveDOMStat()
        val s3 = initD?:ActiveDOMStat()
        val s4 = lastD?:ActiveDOMStat()

        val s = String.format(
                "img: %s/%s/%s/%s, a: %s/%s/%s/%s, num: %s/%s/%s/%s, st: %s/%s/%s/%s, " +
                        "w: %s/%s/%s/%s, h: %s/%s/%s/%s",
                s1.ni, s2.ni, s3.ni, s4.ni,
                s1.na, s2.na, s3.na, s4.na,
                s1.nnm, s2.nnm, s3.nnm, s4.nnm,
                s1.nst, s2.nst, s3.nst, s4.nst,
                s1.w, s2.w, s3.w, s4.w,
                s1.h, s2.h, s3.h, s4.h
        )

        val st = status?:ActiveDOMStatus()
        return String.format("n:%s scroll:%s st:%s r:%s idl:%s\t%s\t(is,ls,id,ld)",
                st.n, st.scroll, st.st, st.r, st.idl, s)
    }

    companion object {
        val DEFAULT = ActiveDOMStatTrace()
    }
}

data class ActiveDOMMetadata(
    val viewPortWidth: Int = DEFAULT_VIEWPORT.width,
    val viewPortHeight: Int = DEFAULT_VIEWPORT.height,

    val scrollTop: Float = 0f,
    val scrollLeft: Float = 0f,

    val clientWidth: Float = DEFAULT_VIEWPORT.width.toFloat(),
    val clientHeight: Float = DEFAULT_VIEWPORT.height.toFloat(),

    // The screen number of the current scroll position, 0-based.
    // 0.00 means at the top of the first screen, 1.50 means halfway through the second screen.
    val screenNumber: Float = 0f,

    val dateTime: String? = null,
    val timestamp: String? = null,
)

data class ActiveDOMMessage(
    var trace: ActiveDOMStatTrace? = null,
    var urls: ActiveDOMUrls? = null,
    var metadata: ActiveDOMMetadata? = null,
) {
    companion object {
        val DEFAULT = ActiveDOMMessage()
    }
}

object Converters {
    fun convert(s: GActiveDOMStat): ActiveDOMStat {
        return ActiveDOMStat(s.ni, s.na, s.nnm, s.nst, s.w, s.h)
    }

    fun convert(s: ActiveDOMStat): GActiveDOMStat {
        return GActiveDOMStat().apply {
            ni = s.ni
            na = s.na
            nnm = s.nnm
            nst = s.nst
            w = s.w
            h = s.h
        }
    }

    fun convert(s: GActiveDOMStatus): ActiveDOMStatus {
        return ActiveDOMStatus(s.n, s.scroll, s.st.toString(), s.r.toString(), s.idl.toString(), s.ec.toString())
    }

    fun convert(s: ActiveDOMStatus): GActiveDOMStatus {
        return GActiveDOMStatus().apply {
            n = s.n
            st = s.st
            r = s.r
            idl = s.idl
            ec = s.ec
        }
    }
}
