package ai.platon.browser4.chrome

import ai.platon.browser4.chrome.IsolatedWorldManager
import ai.platon.browser4.api.BrowserProtocol
import ai.platon.cdt.kt.protocol.types.page.*
import ai.platon.browser4.api.model.BrowserSettings
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
        val bp = mock<BrowserProtocol>()
        val settings = mock<BrowserSettings>()
        val mgr = IsolatedWorldManager(bp, settings)

        val mainFrame = createFrame("main")
        wheneverBlocking { bp.mainFrame() }.thenReturn(mainFrame)
        wheneverBlocking {
            bp.createIsolatedWorld(
                frameId = eq("main"),
                worldName = eq(IsolatedWorldManager.RUNTIME_WORLD_NAME),
                grantUniveralAccess = eq(true),
            )
        }.thenReturn(101)

        val ctx = runBlocking { mgr.createIsolatedWorld(null) }
        assertEquals(101, ctx)
        assertEquals(101, mgr.getContextId("main"))

        runBlocking {
            verify(bp).createIsolatedWorld(any(), any(), any())
        }
    }

    @Test
    fun testCreateIsolatedWorldRejectsMissingFrameWhenTreeAvailable() {
        val bp = mock<BrowserProtocol>()
        val settings = mock<BrowserSettings>()
        val mgr = IsolatedWorldManager(bp, settings)

        val mainFrame = createFrame("main")
        wheneverBlocking { bp.mainFrame() }.thenReturn(mainFrame)
        // createIsolatedWorld returns 0 (invalid context ID) to trigger retry exhaustion
        wheneverBlocking {
            bp.createIsolatedWorld(any(), any(), any())
        }.thenReturn(0)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { mgr.createIsolatedWorld("missing") }
        }
    }
}
