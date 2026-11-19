package com.baidu.carlife.xiaomi.carplay

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Tracks the initial screen grant for one BMW control session. */
internal class BmwScreenResources(
    info: NSDictionary,
    protocol: ReceiverProtocol,
    private val onScreenRevoked: () -> Unit,
) {
    private val uiRequest = CountDownLatch(1)
    private val lock = Any()
    private val modes = info.objectForKey("modes") as? NSDictionary
    private val initialScreen = resources(modes, "initialPermanentEntity", id = 1).lastOrNull()
    private val initialTransfer = resources(modes, "resources", id = 1).lastOrNull()
    private val defaultOwner = if (protocol == ReceiverProtocol.BMW_ID9) 2 else 1
    private val takeConstraint = if (initialTransfer?.number("transferType") == 3) {
        initialTransfer.number("unborrowConstraint") ?: 100
    } else {
        initialTransfer?.number("takeConstraint") ?: initialScreen?.number("takeConstraint") ?: 100
    }
    private var permanent = if (initialTransfer?.number("transferType") == 1) 2
        else initialScreen?.number("permanentEntity")?.takeIf { it in 1..2 } ?: defaultOwner
    private var owner = if (initialTransfer?.number("transferType") == 3) 2 else permanent
    private val initialAudio = resources(modes, "initialPermanentEntity", id = 2).lastOrNull()
    private val audioTransfer = resources(modes, "resources", id = 2).lastOrNull()
    private val audioPermanent = if (audioTransfer?.number("transferType") == 1) 2
        else initialAudio?.number("permanentEntity")?.takeIf { it in 1..2 } ?: defaultOwner
    private val audioOwner = if (audioTransfer?.number("transferType") == 3) 2 else audioPermanent
    private var revoked = false
    private var streaming = false

    fun onCommand(command: NSDictionary) {
        val params = command.objectForKey("params") as? NSDictionary
        when ((command.objectForKey("type") as? NSString)?.content) {
            "requestUI" -> uiRequest.countDown()
            "changeModes" -> {
                val changes = resources(params, "resources", id = 1)
                if (changes.any { it.number("transferType") == 1 || it.number("transferType") == 3 }) {
                    val stop = synchronized(lock) {
                        revoked = true
                        owner = 2
                        uiRequest.countDown()
                        streaming
                    }
                    if (stop) onScreenRevoked()
                }
            }
        }
    }

    fun declareAndClaim(control: RtspControl) {
        declare(control)
        val needsClaim = synchronized(lock) { owner != 1 && !revoked }
        if (needsClaim) {
            // MGU starts with a user-initiated screen request. Respect stronger constraints.
            if (takeConstraint != 100 && takeConstraint != 500) {
                throw ProtocolException("车机保留主屏资源")
            }
            synchronized(lock) {
                if (revoked) throw ProtocolException("车机已收回主屏资源")
                owner = 1
                permanent = 1
            }
            declare(control)
        }
    }

    fun awaitUiRequest(): Boolean {
        val requested = try {
            uiRequest.await(8, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("已取消等待车机主屏")
        }
        return requested && synchronized(lock) { !revoked }
    }

    fun requireScreen() = synchronized(lock) {
        if (owner != 1 || revoked) throw ProtocolException("车机未授予主屏资源")
    }

    fun startScreen() = synchronized(lock) {
        requireScreen()
        streaming = true
    }

    private fun declare(control: RtspControl) {
        val screen = synchronized(lock) { resource(1, owner, permanent) }
        val declaration = NSDictionary().apply {
            put("type", "modesChanged")
            put("params", NSDictionary().apply {
                put("resources", NSArray(screen, resource(2, audioOwner, audioPermanent)))
                val states = (1..3).map { id ->
                    NSDictionary().apply {
                        put("appStateID", id)
                        put("entity", 0)
                        if (id == 1) put("speechMode", -1)
                    }
                }
                put("appStates", NSArray(*states.toTypedArray()))
            })
        }
        val response = control.request(
            "POST", "/command", mapOf("Content-Type" to "application/x-apple-binary-plist"),
            BinaryPropertyListWriter.writeToArray(declaration),
        )
        requireAcknowledgement("屏幕资源声明", response)
    }

    private fun resource(id: Int, owner: Int, permanent: Int) = NSDictionary().apply {
        put("resourceID", id)
        put("entity", owner)
        put("permanentEntity", permanent)
    }

    private fun resources(value: NSDictionary?, key: String, id: Int): List<NSDictionary> =
        (value?.objectForKey(key) as? NSArray)?.array.orEmpty()
            .filterIsInstance<NSDictionary>().filter { it.number("resourceID") == id }

    private fun NSDictionary.number(key: String): Int? =
        (objectForKey(key) as? NSNumber)?.takeIf { it.isInteger }?.longValue()
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

    companion object {
        fun requireAcknowledgement(stage: String, response: RtspResponse) {
            if (response.status != 200) throw ProtocolException("$stage 失败（RTSP ${response.status}）")
            if (response.body.isEmpty()) return
            val status = CarPlayPlist.readDictionary(response.body).objectForKey("status") ?: return
            if (status !is NSNumber || !status.isInteger || status.longValue() != 0L) {
                throw ProtocolException("车机拒绝 $stage")
            }
        }
    }
}
