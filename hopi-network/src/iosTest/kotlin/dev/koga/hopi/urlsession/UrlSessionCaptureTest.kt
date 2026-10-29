package dev.koga.hopi.urlsession

import dev.koga.hopi.api.HopiRuntimes
import dev.koga.hopi.core.body.ResponseSkip
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.start.HopiStart
import dev.koga.hopi.core.start.HopiToolContext
import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.network.internal.NetworkTool
import dev.koga.hopi.network.internal.UrlSessionCapture
import dev.koga.hopi.network.internal.urlsession.hopi_us_self_check
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_captures_mime
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_count_mismatches
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_force_metadata
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_install_count
import dev.koga.hopi.network.internal.urlsession.hopi_us_test_retained_high_water
import dev.koga.hopi.urlsession.fixtures.HopiFxCompletion
import dev.koga.hopi.urlsession.fixtures.HopiFxPartialDelegate
import dev.koga.hopi.urlsession.fixtures.HopiFxProxy
import dev.koga.hopi.urlsession.fixtures.HopiFxRecorder
import dev.koga.hopi.urlsession.fixtures.HopiFxSubDelegate
import dev.koga.hopi.urlsession.fixtures.hopi_fx_concurrently
import dev.koga.hopi.urlsession.fixtures.hopi_fx_install_late_swizzlers
import dev.koga.hopi.urlsession.fixtures.hopi_fx_server_requests
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSInputStream
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMutableData
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDelegateProtocol
import platform.Foundation.appendData
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataTaskWithURL
import platform.Foundation.downloadTaskWithURL
import platform.Foundation.resetBytesInRange
import platform.Foundation.uploadTaskWithRequest
import platform.Foundation.setHTTPBodyStream
import platform.Foundation.setHTTPMethod
import platform.Foundation.writeToURL

/**
 * Automatic URLSession capture against a loopback server on the simulator: every API shape and
 * delegate shape records exactly one transaction, with the app's bytes unchanged.
 */
@OptIn(ExperimentalForeignApi::class)
class UrlSessionCaptureTest {
    init {
        Capture.ensureStarted()
    }

    @Test
    fun startsOnTheBytePathOnce() {
        assertNotNull(HopiStart.config, "the start sequence started")
        assertEquals(UrlSessionCapture.Mode.Bytes, UrlSessionCapture.mode)
        assertTrue(hopi_us_self_check(), "this OS passes the self-check")
        // Eight threads racing to start the network tool install once.
        hopi_fx_concurrently(8) { NetworkTool(rules = true).start(HopiToolContext(StartConfig(summaryLog = false), null)) }
        assertEquals(1L, hopi_us_test_install_count())
    }

    @Test
    fun theStartSequencesNetworkToolStartsTheSameCaptureAndNamesItsMode() {
        // hopi-start's Hopi.start lists this tool. Capture runs already, started by the start
        // sequence with the same settings, so the tool installs nothing again.
        val tool = NetworkTool(rules = true)
        assertEquals("network", tool.name)
        assertEquals("urlsession bytes", tool.start(HopiToolContext(StartConfig(summaryLog = false), platform = null)))
        assertEquals(1L, hopi_us_test_install_count())
        // The network status the tool wrote into hopi-api's registry: capture runs, and OkHttp is
        // Android's.
        assertTrue(HopiRuntimes.networkStarted)
        assertFalse(HopiRuntimes.okHttpInstrumented)
    }

    @Test
    fun completionHandlerApisOnEverySessionKind() {
        for (kind in SessionKind.entries) {
            val session = session(kind)
            // dataTaskWithURL:completionHandler:
            run {
                val url = Capture.url("/bytes?n=20000")
                val watch = Watch(url)
                val done = HopiFxCompletion()
                session.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
                done.awaitDone()
                assertContentEquals(expectedBytes(20000), done.data!!.toBytes(), "$kind: the app's bytes")
                assertComplete(watch.single(), "GET", 200, response = expectedBytes(20000))
            }
            // dataTaskWithRequest:completionHandler: with an HTTPBody
            run {
                val url = Capture.url("/echo")
                val watch = Watch(url)
                val done = HopiFxCompletion()
                val body = "hello from $kind".encodeToByteArray()
                session.dataTaskWithRequest(post(url, body), done.dataHandler()!!).resume()
                done.awaitDone()
                assertContentEquals(body, done.data!!.toBytes())
                val tx = assertComplete(watch.single(), "POST", 200, response = body)
                assertContentEquals(body, tx.requestBody!!.bytes)
                assertEquals("text/plain", tx.requestBody!!.contentType)
                assertEquals("text/plain", tx.requestHeaders.single { it.first == "Content-Type" }.second)
            }
            // uploadTaskWithRequest:fromData:completionHandler:
            run {
                val url = Capture.url("/echo")
                val watch = Watch(url)
                val done = HopiFxCompletion()
                val body = expectedBytes(70_000)
                session.uploadTaskWithRequest(post(url), body.toNSData(), done.dataHandler()!!).resume()
                done.awaitDone()
                assertContentEquals(body, done.data!!.toBytes())
                val tx = assertComplete(watch.single(), "POST", 200, response = body)
                assertContentEquals(body, tx.requestBody!!.bytes, "$kind: upload body from hook 4")
                assertEquals(70_000L, tx.requestBody!!.totalBytes)
            }
            // downloadTaskWithURL:completionHandler: — metadata and size, no body
            run {
                val url = Capture.url("/bytes?n=90000")
                val watch = Watch(url)
                val done = HopiFxCompletion()
                session.downloadTaskWithURL(nsUrl(url), done.downloadHandler()!!).resume()
                done.awaitDone()
                assertContentEquals(expectedBytes(90000), done.data!!.toBytes(), "$kind: the downloaded file")
                val tx = assertComplete(watch.single(), "GET", 200)
                val body = tx.responseBody!!
                assertEquals(UrlSessionCapture.SKIPPED_DOWNLOAD, body.skippedReason)
                assertEquals(0, body.bytes.size)
                assertEquals(90000L, body.totalBytes)
            }
            if (kind != SessionKind.Shared) session.finishTasksAndInvalidate()
        }
    }

    @Test
    fun tasksWithoutAHandlerOnASessionWithoutADelegate() {
        val session = session(SessionKind.Default)
        val url = Capture.url("/bytes?n=1234")
        val watch = Watch(url)
        session.dataTaskWithURL(nsUrl(url)).resume()
        assertComplete(watch.single(), "GET", 200, response = expectedBytes(1234))
        val upload = Capture.url("/echo")
        val uploadWatch = Watch(upload)
        session.uploadTaskWithRequest(post(upload), "fire and forget".encodeToByteArray().toNSData()).resume()
        val tx = assertComplete(uploadWatch.single(), "POST", 200, response = "fire and forget".encodeToByteArray())
        assertContentEquals("fire and forget".encodeToByteArray(), tx.requestBody!!.bytes)
        session.finishTasksAndInvalidate()
    }

    @Test
    fun everyDelegateShape() {
        val recorder = HopiFxRecorder()
        val sub = HopiFxSubDelegate()
        val proxied = HopiFxRecorder()
        val shapes: List<Pair<String, Any?>> = listOf(
            "full delegate" to recorder,
            "subclass calling super" to sub,
            "NSProxy" to HopiFxProxy(target = proxied),
            "partial delegate" to HopiFxPartialDelegate(),
        )
        for ((name, delegate) in shapes) {
            for (queue in listOf(NSOperationQueue.mainQueue, NSOperationQueue())) {
                val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration, delegate as NSURLSessionDelegateProtocol?, queue)
                val url = Capture.url("/bytes?n=60000&chunked=1&chunk=7000")
                val watch = Watch(url)
                val receiver = when (delegate) {
                    is HopiFxRecorder -> delegate
                    is HopiFxProxy -> delegate.target!!
                    else -> null
                }
                val before = receiver?.received?.length?.toInt() ?: 0
                if (receiver == null) {
                    // A delegate with no data methods: the bytes go to the completion handler.
                    val done = HopiFxCompletion()
                    session.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
                    done.awaitDone()
                    assertContentEquals(expectedBytes(60000), done.data!!.toBytes(), name)
                } else {
                    session.dataTaskWithURL(nsUrl(url)).resume()
                    assertTrue(receiver.waitFor(15.0), "$name: no completion")
                    val received = receiver.received!!.toBytes()
                    assertContentEquals(expectedBytes(60000), received.copyOfRange(before, received.size), name)
                }
                assertComplete(watch.single(), "GET", 200, response = expectedBytes(60000))
                session.finishTasksAndInvalidate()
            }
        }
        assertTrue(sub.subCalls > 0, "the subclass's override ran")
    }

    @Test
    fun theAppSeesNothingChange() {
        // The same delegate run gives the same callback sequence as before start().
        assertEquals(BeforeStart.controlEvents, BeforeStart.delegateRun(NSOperationQueue()))
        assertEquals(BeforeStart.controlMainEvents, BeforeStart.delegateRun(NSOperationQueue.mainQueue))
        // respondsToSelector: of every delegate shape over the 25 delegate selectors.
        assertEquals(BeforeStart.responds, BeforeStart.delegates().mapValues { appView(it.value) })
        assertEquals(BeforeStart.protocolClasses, NSURLSessionConfiguration.defaultSessionConfiguration.protocolClasses.toString())
        // Delegate identity, the task's class (no isa swizzling on the byte path), the queue of the handler.
        val delegate = HopiFxRecorder()
        val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration, delegate, NSOperationQueue())
        // NSObject's isEqual: is identity: the session still holds the app's own delegate object.
        assertTrue(delegate.isEqual(session.delegate), "the session's delegate is the app's object")
        val url = Capture.url("/bytes?n=10")
        val watch = Watch(url)
        val task = session.dataTaskWithURL(nsUrl(url))
        assertEquals(BeforeStart.dataTaskClass, classOf(task))
        task.resume()
        assertEquals(BeforeStart.dataTaskClass, classOf(task))
        delegate.awaitDone()
        watch.single()
        assertEquals(BeforeStart.dataTaskClass, classOf(task))
        assertEquals(1L, delegate.completions)
        assertTrue(delegate.threads!!.none { it == "main" }, "a background delegate queue stays off the main thread")
        session.finishTasksAndInvalidate()
        // A completion handler runs once, on the session's queue.
        val done = HopiFxCompletion()
        val shared = Capture.url("/bytes?n=10")
        val sharedWatch = Watch(shared)
        NSURLSession.sharedSession.dataTaskWithURL(nsUrl(shared), done.dataHandler()!!).resume()
        done.awaitDone()
        sharedWatch.single()
        assertEquals(1L, done.calls)
        assertFalse(done.onMainThread)
    }

    @Test
    fun nothingIsSentTwice() {
        val path = "/status"
        val before = hopi_fx_server_requests(path)
        repeat(5) {
            val url = Capture.url("$path?code=204")
            val watch = Watch(url)
            val done = HopiFxCompletion()
            NSURLSession.sharedSession.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
            done.awaitDone()
            watch.single()
        }
        assertEquals(before + 5, hopi_fx_server_requests(path), "every request reached the server exactly once")
    }

    @Test
    fun sessionsAndTasksCreatedBeforeStart() {
        // A task resumed before start() is not recorded; its app still gets everything.
        BeforeStart.resumedBeforeHandler.awaitDone()
        assertContentEquals(expectedBytes(3000), BeforeStart.resumedBeforeHandler.data!!.toBytes())
        Watch(BeforeStart.resumedBeforeUrl).none(200)
        assertNull(Capture.store.transactions.value.firstOrNull { it.url == BeforeStart.resumedBeforeUrl })
        // Resumed before start(), suspended, resumed after: not recorded either (it would show part
        // of the body as all of it); its app still gets everything.
        val suspended = Watch(BeforeStart.suspendedBeforeUrl)
        BeforeStart.suspendedBefore.resume()
        BeforeStart.suspendedBeforeHandler.awaitDone()
        assertContentEquals(expectedBytes(3000), BeforeStart.suspendedBeforeHandler.data!!.toBytes())
        suspended.none(300)
        // Created before, resumed after: recorded with its body.
        val watch = Watch(BeforeStart.createdNotResumedUrl)
        BeforeStart.createdNotResumed.resume()
        BeforeStart.createdNotResumedHandler.awaitDone()
        assertComplete(watch.single(), "GET", 200, response = expectedBytes(4000))
        // An upload created before start(): its data was handed over before Hopi looked.
        val upload = Watch(BeforeStart.uploadCreatedBeforeUrl)
        BeforeStart.uploadCreatedBefore.resume()
        BeforeStart.uploadCreatedBeforeHandler.awaitDone()
        val tx = assertComplete(upload.single(), "POST", 200, response = expectedBytes(300))
        assertEquals(UrlSessionCapture.SKIPPED_NOT_OBSERVED, tx.requestBody!!.skippedReason)
        assertEquals(300L, tx.requestBody!!.totalBytes)
        // Sessions created before start(): new tasks on them are recorded, delegate or not.
        val url = Capture.url("/bytes?n=777")
        val preWatch = Watch(url)
        BeforeStart.preSession.dataTaskWithURL(nsUrl(url)).resume()
        assertComplete(preWatch.single(), "GET", 200, response = expectedBytes(777))
        val delegated = Capture.url("/bytes?n=778")
        val delegatedWatch = Watch(delegated)
        BeforeStart.preDelegateSession.dataTaskWithURL(nsUrl(delegated)).resume()
        assertComplete(delegatedWatch.single(), "GET", 200, response = expectedBytes(778))
    }

    @Test
    fun parallelTasksKeepTheirOwnBodies() {
        val session = session(SessionKind.Ephemeral)
        val urls = List(64) { Capture.url("/echo") }
        val watches = urls.map { Watch(it) }
        val handlers = urls.mapIndexed { i, url ->
            HopiFxCompletion().also { done ->
                session.uploadTaskWithRequest(post(url), "body number $i ".repeat(i + 1).encodeToByteArray().toNSData(), done.dataHandler()!!).resume()
            }
        }
        handlers.forEach { it.awaitDone() }
        watches.forEachIndexed { i, watch ->
            val body = "body number $i ".repeat(i + 1).encodeToByteArray()
            val tx = assertComplete(watch.single(graceMs = 0), "POST", 200, response = body)
            assertContentEquals(body, tx.requestBody!!.bytes)
        }
        session.finishTasksAndInvalidate()
    }

    @Test
    fun aLargeBodyIsCutAtTheCapAndCountedWhole() {
        val size = 8 * 1024 * 1024
        val cap = 256 * 1024
        for (chunked in listOf(false, true)) {
            val url = Capture.url("/bytes?n=$size&chunked=${if (chunked) 1 else 0}")
            val watch = Watch(url)
            val done = HopiFxCompletion()
            NSURLSession.sharedSession.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
            done.awaitDone()
            assertEquals(size.toULong(), done.data!!.length, "the app gets the whole body")
            val body = watch.single().responseBody!!
            assertEquals(cap, body.bytes.size)
            assertTrue(body.truncated)
            assertEquals(size.toLong(), body.totalBytes)
            assertContentEquals(expectedBytes(cap), body.bytes)
        }
        // Nothing past the cap is retained: the replay ring holds weak references only.
        val highWater = hopi_us_test_retained_high_water()
        println("retained high water: $highWater bytes for an 8 MB body with a 256 KB cap")
        assertTrue(highWater <= cap, "retained $highWater")
        assertEquals(0L, hopi_us_test_count_mismatches(), "Hopi's count never fell short of countOfBytesReceived")
    }

    @Test
    fun failuresCancelsAndTimeouts() {
        // A reset mid-body.
        val reset = Capture.url("/reset?n=5000")
        val resetWatch = Watch(reset)
        val resetDone = HopiFxCompletion()
        NSURLSession.sharedSession.dataTaskWithURL(nsUrl(reset), resetDone.dataHandler()!!).resume()
        resetDone.awaitDone()
        assertNotNull(resetDone.error)
        val failed = resetWatch.single()
        assertEquals(TransactionState.Failed, failed.state)
        assertEquals("NSURLErrorDomain -1005: network connection lost", failed.error)
        // Connection refused.
        val refused = "http://127.0.0.1:9/refused"
        val refusedWatch = Watch(refused)
        val refusedDone = HopiFxCompletion()
        NSURLSession.sharedSession.dataTaskWithURL(nsUrl(refused), refusedDone.dataHandler()!!).resume()
        refusedDone.awaitDone()
        assertTrue(refusedWatch.single().error!!.startsWith("NSURLErrorDomain -1004"))
        // Cancelled right after resume, before any byte, and mid-body.
        for (path in listOf("/stall?ms=2000", "/slow?n=60000&pieces=6&ms=200")) {
            val url = Capture.url(path)
            val watch = Watch(url)
            val done = HopiFxCompletion()
            val task = NSURLSession.sharedSession.dataTaskWithURL(nsUrl(url), done.dataHandler()!!)
            task.resume()
            if (path.startsWith("/slow")) platform.posix.usleep(300_000u)
            task.cancel()
            done.awaitDone()
            val tx = watch.single()
            assertEquals(TransactionState.Failed, tx.state, path)
            assertEquals("cancelled", tx.error, path)
        }
        // A timeout.
        val slow = Capture.url("/stall?ms=3000")
        val slowWatch = Watch(slow)
        val slowDone = HopiFxCompletion()
        val request = NSURLRequest.requestWithURL(nsUrl(slow), NSURLRequestUseProtocolCachePolicy, timeoutInterval = 1.0)
        NSURLSession.sharedSession.dataTaskWithRequest(request, slowDone.dataHandler()!!).resume()
        slowDone.awaitDone()
        assertTrue(slowWatch.single().error!!.startsWith("NSURLErrorDomain -1001"))
    }

    @Test
    fun statusesRedirectsAndEncodings() {
        for (code in listOf(204, 304)) {
            val url = Capture.url("/status?code=$code")
            val watch = Watch(url)
            val done = HopiFxCompletion()
            NSURLSession.sharedSession.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
            done.awaitDone()
            val tx = assertComplete(watch.single(), "GET", code)
            assertNull(tx.responseBody, "$code has no body")
        }
        // HEAD
        val head = Capture.url("/bytes?n=500")
        val headWatch = Watch(head)
        val headDone = HopiFxCompletion()
        NSURLSession.sharedSession.dataTaskWithRequest(post(head).apply { setHTTPMethod("HEAD") }, headDone.dataHandler()!!).resume()
        headDone.awaitDone()
        assertNull(assertComplete(headWatch.single(), "HEAD", 200).responseBody)
        // A redirect URLSession follows natively: one record, the final hop's response, both URLs.
        val redirect = Capture.url("/redirect?to=/bytes%3Fn%3D10")
        val redirectWatch = Watch(redirect)
        val redirectDone = HopiFxCompletion()
        NSURLSession.sharedSession.dataTaskWithURL(nsUrl(redirect), redirectDone.dataHandler()!!).resume()
        redirectDone.awaitDone()
        val redirected = assertComplete(redirectWatch.single(), "GET", 200, response = expectedBytes(10))
        assertEquals("http://127.0.0.1:${Capture.port}/bytes?n=10", redirected.finalUrl)
        // No redirect: CFNetwork's canonical form of the URL (an empty path as "/", the scheme and
        // host lowercased) is not a final URL.
        for (url in listOf(Capture.url(""), Capture.url("/bytes?n=5").replaceFirst("http://", "HTTP://"))) {
            val watch = Watch(url)
            val done = HopiFxCompletion()
            NSURLSession.sharedSession.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
            done.awaitDone()
            val tx = watch.single()
            assertEquals(TransactionState.Complete, tx.state, url)
            assertNull(tx.finalUrl, url)
        }
        // gzip: the body as the app receives it, decoded.
        val gzip = Capture.url("/gzip?n=40000")
        val gzipWatch = Watch(gzip)
        val gzipDone = HopiFxCompletion()
        NSURLSession.sharedSession.dataTaskWithURL(nsUrl(gzip), gzipDone.dataHandler()!!).resume()
        gzipDone.awaitDone()
        assertContentEquals(expectedBytes(40000), gzipDone.data!!.toBytes())
        assertComplete(gzipWatch.single(), "GET", 200, response = expectedBytes(40000))
        // A binary content type: counted, not kept.
        val image = Capture.url("/bytes?n=30000&type=image/png")
        val imageWatch = Watch(image)
        val imageDone = HopiFxCompletion()
        NSURLSession.sharedSession.dataTaskWithURL(nsUrl(image), imageDone.dataHandler()!!).resume()
        imageDone.awaitDone()
        val skipped = assertComplete(imageWatch.single(), "GET", 200).responseBody!!
        assertEquals(ResponseSkip.BINARY, skipped.skippedReason)
        assertEquals(30000L, skipped.totalBytes)
        assertEquals("image/png", skipped.contentType)
    }

    @Test
    fun streamAndFileRequestBodiesAreNotRead() {
        // HTTPBodyStream: single-use, so it is never read by Hopi.
        val body = expectedBytes(5000)
        val url = Capture.url("/echo")
        val watch = Watch(url)
        val done = HopiFxCompletion()
        val request = post(url).apply { setHTTPBodyStream(NSInputStream(data = body.toNSData())) }
        NSURLSession.sharedSession.dataTaskWithRequest(request, done.dataHandler()!!).resume()
        done.awaitDone()
        assertContentEquals(body, done.data!!.toBytes(), "the app's stream reached the server whole")
        val tx = assertComplete(watch.single(), "POST", 200, response = body)
        assertEquals(UrlSessionCapture.SKIPPED_STREAM, tx.requestBody!!.skippedReason)
        // uploadTaskWithRequest:fromFile:
        val file = NSURL.fileURLWithPath(NSTemporaryDirectory() + "hopi-upload.bin")
        body.toNSData().writeToURL(file, atomically = true)
        val fileUrl = Capture.url("/echo")
        val fileWatch = Watch(fileUrl)
        val fileDone = HopiFxCompletion()
        NSURLSession.sharedSession.uploadTaskWithRequest(post(fileUrl), file, fileDone.dataHandler()!!).resume()
        fileDone.awaitDone()
        val fileTx = assertComplete(fileWatch.single(), "POST", 200, response = body)
        assertEquals(UrlSessionCapture.SKIPPED_FILE, fileTx.requestBody!!.skippedReason)
        NSFileManager.defaultManager.removeItemAtURL(file, null)
    }

    @Test
    fun aLargeMutableUploadBodyIsCopiedOnlyUpToTheCap() {
        val cap = 256 * 1024
        val original = expectedBytes(600_000)
        val mutable = NSMutableData().apply { appendData(original.toNSData()) }
        val url = Capture.url("/echo")
        val watch = Watch(url)
        val done = HopiFxCompletion()
        val task = NSURLSession.sharedSession.uploadTaskWithRequest(post(url), mutable, done.dataHandler()!!)
        // A change after the factory returned does not reach the record.
        mutable.resetBytesInRange(NSMakeRange(0u, 16u))
        task.resume()
        done.awaitDone()
        val tx = assertComplete(watch.single(), "POST", 200)
        val body = tx.requestBody!!
        assertContentEquals(original.copyOf(cap), body.bytes)
        assertEquals(600_000L, body.totalBytes)
        assertTrue(body.truncated)
    }

    @Test
    fun concurrentResumesOfOneTaskRecordItOnce() {
        val url = Capture.url("/bytes?n=40000&chunked=1&chunk=4000")
        val watch = Watch(url)
        val done = HopiFxCompletion()
        val task = NSURLSession.sharedSession.dataTaskWithURL(nsUrl(url), done.dataHandler()!!)
        hopi_fx_concurrently(8) { task.resume() }
        done.awaitDone()
        assertComplete(watch.single(), "GET", 200, response = expectedBytes(40000))
    }

    @Test
    fun nonHttpLoadsAreNotRecorded() {
        val data = "data:text/plain,hello"
        val file = NSURL.fileURLWithPath(NSTemporaryDirectory() + "hopi-local.txt")
        expectedBytes(213).toNSData().writeToURL(file, atomically = true)
        for ((url, size) in listOf(data to 5, file.absoluteString!! to 213)) {
            val watch = Watch(url)
            val done = HopiFxCompletion()
            NSURLSession.sharedSession.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
            done.awaitDone()
            assertEquals(size.toULong(), done.data!!.length, url)
            watch.none(300)
            assertNull(Capture.store.transactions.value.firstOrNull { it.url == url }, url)
        }
        NSFileManager.defaultManager.removeItemAtURL(file, null)
    }

    @Test
    fun foreignSwizzlersInBothOrdersStillSeeEveryCall() {
        // Installed before start() (BeforeStart): each still sees what it saw per request then.
        val before = BeforeStart.foreignCalls()
        val url = Capture.url("/bytes?n=50000")
        val watch = Watch(url)
        val recorder = HopiFxRecorder()
        val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration, recorder, NSOperationQueue())
        session.dataTaskWithURL(nsUrl(url)).resume()
        recorder.awaitDone()
        assertComplete(watch.single(), "GET", 200, response = expectedBytes(50000))
        val delta = BeforeStart.foreignCalls().zip(before) { a, b -> a - b }
        for (i in 0 until 2) assertEquals(BeforeStart.foreignPerRequest[i], delta[i], "resume swizzler $i")
        for (i in 2 until 4) assertEquals(BeforeStart.foreignPerRequest[i] > 0, delta[i] > 0, "data swizzler $i")
        // Installed after start(): it wraps Hopi's hooks, and both see the request.
        hopi_fx_install_late_swizzlers()
        val lateBefore = BeforeStart.foreignCalls()
        val late = Capture.url("/bytes?n=50000")
        val lateWatch = Watch(late)
        val lateRecorder = HopiFxRecorder()
        val lateSession = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration, lateRecorder, NSOperationQueue())
        lateSession.dataTaskWithURL(nsUrl(late)).resume()
        lateRecorder.awaitDone()
        assertContentEquals(expectedBytes(50000), lateRecorder.received!!.toBytes())
        assertComplete(lateWatch.single(), "GET", 200, response = expectedBytes(50000))
        val lateDelta = BeforeStart.foreignCalls().zip(lateBefore) { a, b -> a - b }
        assertTrue(lateDelta[5] > 0, "the late data swizzler ran: $lateDelta")
        session.finishTasksAndInvalidate()
        lateSession.finishTasksAndInvalidate()
    }

    @Test
    fun theMetadataPathRecordsWithoutBodies() {
        hopi_us_test_force_metadata(true)
        try {
            assertFalse(hopi_us_self_check(), "the switch stands for an OS that fails the self-check")
            val url = Capture.url("/bytes?n=2000")
            val watch = Watch(url)
            val done = HopiFxCompletion()
            NSURLSession.sharedSession.dataTaskWithURL(nsUrl(url), done.dataHandler()!!).resume()
            done.awaitDone()
            assertContentEquals(expectedBytes(2000), done.data!!.toBytes())
            // The metadata path reads no private metrics either.
            val tx = assertComplete(watch.single(), "GET", 200, protocol = null)
            assertEquals(UrlSessionCapture.SKIPPED_NOT_OBSERVABLE, tx.responseBody!!.skippedReason)
            assertEquals(2000L, tx.responseBody!!.totalBytes)
        } finally {
            hopi_us_test_force_metadata(false)
        }
    }

    @Test
    fun theNativeContentTypeRuleMirrorsCore() {
        val types = listOf(
            null, "text/plain", "application/json; charset=utf-8", "image/png", "IMAGE/JPEG", "image/svg+xml",
            "video/mp4", "audio/mpeg", "font/woff2", "text/event-stream", "application/grpc+proto", "text/html",
        ) + ResponseSkip.binaryApplicationTypes
        for (type in types) {
            assertEquals(ResponseSkip.reason(type) == null, hopi_us_test_captures_mime(type), "$type")
        }
    }

    @Test
    fun protocolNamesAreWrittenAsKtorWritesThem() {
        assertEquals("HTTP/1.1", UrlSessionCapture.protocolName("http/1.1"))
        assertEquals("HTTP/2.0", UrlSessionCapture.protocolName("h2"))
        assertEquals("HTTP/3.0", UrlSessionCapture.protocolName("h3"))
        assertNull(UrlSessionCapture.protocolName(null))
    }

    private fun assertComplete(
        tx: HttpTransaction,
        method: String,
        code: Int,
        response: ByteArray? = null,
        protocol: String? = "HTTP/1.1",
    ): HttpTransaction {
        assertEquals(TransactionState.Complete, tx.state, "${tx.url}: ${tx.error}")
        assertEquals(method, tx.method)
        assertEquals(code, tx.responseCode)
        assertEquals(TransactionSource.URL_SESSION, tx.source)
        assertEquals(protocol, tx.protocol)
        assertNotNull(tx.durationMs)
        if (response != null) {
            val body = assertNotNull(tx.responseBody, tx.url)
            assertContentEquals(response, body.bytes, tx.url)
            assertEquals(response.size.toLong(), body.totalBytes, tx.url)
            assertFalse(body.truncated)
        }
        return tx
    }
}
