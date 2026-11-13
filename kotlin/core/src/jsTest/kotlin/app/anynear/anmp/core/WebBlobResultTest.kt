package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebBlobResultTest {
    @Test
    fun foundGoneAndLaterAreTold() {
        val found = WebBlobResult.of(BlobResult.Found(byteArrayOf(1, 2, 3)))
        assertEquals(WebBlobResult.FOUND, found.status)
        assertContentEquals(byteArrayOf(1, 2, 3), found.bytes)

        val gone = WebBlobResult.of(BlobResult.Gone)
        assertEquals(WebBlobResult.GONE, gone.status)
        assertNull(gone.bytes)

        assertEquals(WebBlobResult.LATER, WebBlobResult.of(BlobResult.Later).status)
    }
}
