package com.aiyifan.app.core.startup

import com.aiyifan.app.core.data.remote.HttpFetcher
import com.aiyifan.app.core.data.remote.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StartupModeResolverTest {
    @Test fun `only a successful plain one enables online mode`() = runBlocking {
        for (body in listOf("1", " 1\r\n")) {
            assertEquals(AppMode.ONLINE, resolver(200, body).resolve())
        }
        for (body in listOf("0", "", "b3Blbg==", "true", "01", "val enabled = 1", "<html>1</html>")) {
            assertEquals(AppMode.LOCAL, resolver(200, body).resolve())
        }
        assertEquals(AppMode.LOCAL, resolver(503, "1").resolve())
    }

    @Test fun `network failure and timeout choose local media`() = runBlocking {
        assertEquals(AppMode.LOCAL, StartupModeResolver(fetcher { throw IOException("offline") }).resolve())
        assertEquals(AppMode.LOCAL, StartupModeResolver(fetcher {
            delay(1_000)
            HttpResponse(200, "1")
        }, timeoutMs = 20).resolve())
    }

    @Test fun `parent cancellation is not converted into local navigation`() = runBlocking {
        try {
            StartupModeResolver(fetcher { throw CancellationException("closed") }).resolve()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `each launch fetches again and never reuses last online value`() = runBlocking {
        var requests = 0
        val resolver = StartupModeResolver(fetcher {
            requests++
            if (requests == 1) HttpResponse(200, "1") else throw IOException("offline")
        })
        assertEquals(AppMode.ONLINE, resolver.resolve())
        assertEquals(AppMode.LOCAL, resolver.resolve())
        assertEquals(2, requests)
    }

    private fun resolver(code: Int, body: String) = StartupModeResolver(fetcher { HttpResponse(code, body) })
    private fun fetcher(block: suspend () -> HttpResponse) = object : HttpFetcher {
        override suspend fun get(url: String): HttpResponse {
            assertEquals("https://raw.githubusercontent.com/xiaodaitaishuai1/aiyifan/main/app/daidai.kt", url)
            return block()
        }
    }
}
