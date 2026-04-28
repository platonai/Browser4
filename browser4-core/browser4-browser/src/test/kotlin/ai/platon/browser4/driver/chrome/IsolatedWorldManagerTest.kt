package ai.platon.browser4.driver.chrome

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.cdt.kt.protocol.types.page.*
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
        val page = mock<ai.platon.cdt.kt.protocol.commands.Page>()
        org.mockito.kotlin.whenever(devTools.page).thenReturn(page)

        val settings = mock<BrowserSettings>()
        val mgr = IsolatedWorldManager(devTools, settings)

        val mainFrame = createFrame("main")
        wheneverBlocking { page.getFrameTree() }.thenReturn(FrameTree(mainFrame, childFrames = null))
        wheneverBlocking {
            page.createIsolatedWorld(
                frameId = eq("main"),
                worldName = eq(IsolatedWorldManager.RUNTIME_WORLD_NAME),
                grantUniveralAccess = eq(true),
            )
        }.thenReturn(101)

        val ctx = runBlocking { mgr.createIsolatedWorld(null) }
        assertEquals(101, ctx)
        assertEquals(101, mgr.getContextId("main"))

        runBlocking {
            verify(page).createIsolatedWorld(any(), any(), any())
        }
    }

    @Test
    fun testCreateIsolatedWorldRejectsMissingFrameWhenTreeAvailable() {
        val devTools = mock<RemoteDevTools>()
        val page = mock<ai.platon.cdt.kt.protocol.commands.Page>()
        org.mockito.kotlin.whenever(devTools.page).thenReturn(page)

        val settings = mock<BrowserSettings>()
        val mgr = IsolatedWorldManager(devTools, settings)

        val mainFrame = createFrame("main")
        wheneverBlocking { page.getFrameTree() }.thenReturn(FrameTree(mainFrame, childFrames = null))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { mgr.createIsolatedWorld("missing") }
        }
    }
}
