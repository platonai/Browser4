package ai.platon.browser4.driver.chrome

import ai.platon.browser4.chrome.IsolatedWorldManager
import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.browser4.chrome.handler.RemoteChromeProtocol
import ai.platon.cdt.kt.protocol.types.page.*
import ai.platon.pulsar.browser.common.BrowserSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class IsolatedWorldManagerTest {

    private fun createFrame(id: String, parentId: String? = null): Frame {
        return Frame(
            id = id,
            parentId = parentId,
            loaderId = "l-$id",
            name = null,
            url = "https://example.com/$id",
            urlFragment = null,
            domainAndRegistry = "example.com",
            securityOrigin = "https://example.com",
            mimeType = "text/html",
            secureContextType = SecureContextType.SECURE,
            crossOriginIsolatedContextType = CrossOriginIsolatedContextType.NOT_ISOLATED,
            gatedAPIFeatures = emptyList<GatedAPIFeatures>(),
        )
    }

    @Test
    fun testCreateIsolatedWorldUsesResolvedMainFrameId() {
        val devTools = mock<RemoteDevTools>()
        val browserProtocol = RemoteChromeProtocol(devTools)

        val settings = mock<BrowserSettings>()
        val mgr = IsolatedWorldManager(browserProtocol, settings)

        val mainFrame = createFrame("main")
        wheneverBlocking {
            devTools.execute(
                eq("Page.getFrameTree"), isNull(), eq(FrameTree::class), isNull(), isNull()
            )
        }.thenReturn(FrameTree(mainFrame, childFrames = null))
        wheneverBlocking {
            devTools.execute(
                eq("Page.createIsolatedWorld"), any(), eq(Int::class), eq("executionContextId"), isNull()
            )
        }.thenReturn(101)

        val ctx = runBlocking { mgr.createIsolatedWorld(null) }
        assertEquals(101, ctx)
        assertEquals(101, mgr.getContextId("main"))

        runBlocking {
            verify(devTools).execute(
                eq("Page.createIsolatedWorld"), any(), eq(Int::class), eq("executionContextId"), isNull()
            )
        }
    }

    @Test
    fun testCreateIsolatedWorldRejectsMissingFrameWhenTreeAvailable() {
        val devTools = mock<RemoteDevTools>()
        val browserProtocol = RemoteChromeProtocol(devTools)

        val settings = mock<BrowserSettings>()
        val mgr = IsolatedWorldManager(browserProtocol, settings)

        val mainFrame = createFrame("main")
        wheneverBlocking {
            devTools.execute(
                eq("Page.getFrameTree"), isNull(), eq(FrameTree::class), isNull(), isNull()
            )
        }.thenReturn(FrameTree(mainFrame, childFrames = null))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { mgr.createIsolatedWorld("missing") }
        }
    }
}
