package dev.koga.hopi.urlsession

import dev.koga.hopi.urlsession.fixtures.HopiFxCompletion
import dev.koga.hopi.urlsession.fixtures.HopiFxPartialDelegate
import dev.koga.hopi.urlsession.fixtures.HopiFxProxy
import dev.koga.hopi.urlsession.fixtures.HopiFxRecorder
import dev.koga.hopi.urlsession.fixtures.HopiFxSubDelegate
import dev.koga.hopi.urlsession.fixtures.hopi_fx_foreign_calls
import dev.koga.hopi.urlsession.fixtures.hopi_fx_install_foreign_swizzlers
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataTaskWithURL
import platform.Foundation.downloadTaskWithURL
import platform.Foundation.uploadTaskWithRequest
import platform.Foundation.NSURLSessionTask

/**
 * Everything observed or created before start(), run once by the first test that needs capture:
 * the control runs whose app-visible behavior the tests compare after start(), and the sessions
 * and tasks of "created before start()".
 */
@OptIn(ExperimentalForeignApi::class)
internal object BeforeStart {
    lateinit var controlEvents: List<String>
    lateinit var controlMainEvents: List<String>
    lateinit var responds: Map<String, String>
    lateinit var protocolClasses: String
    lateinit var dataTaskClass: String
    lateinit var foreignPerRequest: List<Long>

    /** Built before start, used after it. */
    lateinit var preSession: NSURLSession
    lateinit var preDelegate: HopiFxRecorder
    lateinit var preDelegateSession: NSURLSession
    lateinit var createdNotResumed: NSURLSessionTask
    lateinit var createdNotResumedUrl: String
    val createdNotResumedHandler = HopiFxCompletion()
    lateinit var uploadCreatedBefore: NSURLSessionTask
    lateinit var uploadCreatedBeforeUrl: String
    val uploadCreatedBeforeHandler = HopiFxCompletion()
    lateinit var resumedBeforeUrl: String
    val resumedBeforeHandler = HopiFxCompletion()
    lateinit var suspendedBefore: NSURLSessionTask
    lateinit var suspendedBeforeUrl: String
    val suspendedBeforeHandler = HopiFxCompletion()

    private var prepared = false

    fun prepare() {
        if (prepared) return
        prepared = true
        // Foreign swizzlers installed before Hopi, as an SDK initialised first would be.
        hopi_fx_install_foreign_swizzlers()
        val foreignBefore = foreignCalls()
        controlEvents = delegateRun(NSOperationQueue())
        foreignPerRequest = foreignCalls().zip(foreignBefore) { after, before -> after - before }
        controlMainEvents = delegateRun(NSOperationQueue.mainQueue)
        responds = delegates().mapValues { appView(it.value) }
        protocolClasses = NSURLSessionConfiguration.defaultSessionConfiguration.protocolClasses.toString()
        dataTaskClass = classOf(session(SessionKind.Ephemeral).dataTaskWithURL(nsUrl(Capture.url("/bytes?n=1"))))

        preSession = session(SessionKind.Default)
        preDelegate = HopiFxRecorder()
        preDelegateSession = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration, preDelegate, NSOperationQueue())
        createdNotResumedUrl = Capture.url("/bytes?n=4000")
        createdNotResumed = preSession.dataTaskWithURL(nsUrl(createdNotResumedUrl), createdNotResumedHandler.dataHandler()!!)
        uploadCreatedBeforeUrl = Capture.url("/echo")
        uploadCreatedBefore = preSession.uploadTaskWithRequest(post(uploadCreatedBeforeUrl), expectedBytes(300).toNSData(), uploadCreatedBeforeHandler.dataHandler()!!)
        // Resumed before start, still running when start() installs the hooks.
        resumedBeforeUrl = Capture.url("/slow?n=3000&pieces=6&ms=100")
        preSession.dataTaskWithURL(nsUrl(resumedBeforeUrl), resumedBeforeHandler.dataHandler()!!).resume()
        // Resumed before start and suspended partway (the first pieces arrived): resumed after it.
        suspendedBeforeUrl = Capture.url("/slow?n=3000&pieces=6&ms=100")
        suspendedBefore = preSession.dataTaskWithURL(nsUrl(suspendedBeforeUrl), suspendedBeforeHandler.dataHandler()!!)
        suspendedBefore.resume()
        platform.posix.usleep(250_000u)
        suspendedBefore.suspend()
    }

    /** A delegate-driven data task to the fixture server; returns the delegate's event sequence. */
    fun delegateRun(queue: NSOperationQueue): List<String> {
        val recorder = HopiFxRecorder()
        val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration, recorder, queue)
        session.dataTaskWithURL(nsUrl(Capture.url("/bytes?n=50000"))).resume()
        recorder.awaitDone()
        session.finishTasksAndInvalidate()
        return recorder.events!!.map { it.toString() }
    }

    fun delegates(): Map<String, Any> {
        val recorder = HopiFxRecorder()
        return mapOf(
            "recorder" to recorder,
            "partial" to HopiFxPartialDelegate(),
            "subclass" to HopiFxSubDelegate(),
            "proxy" to HopiFxProxy(target = recorder),
        )
    }

    fun foreignCalls(): List<Long> = hopi_fx_foreign_calls()!!.map { (it as Number).toLong() }
}
