package com.baidu.carlife.xiaomi.carplay

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import java.io.InterruptedIOException
import java.net.ProtocolException

/** Tracks screen ownership independently of the control connection and audio streams. */
internal class VehicleScreenResources(info: NSDictionary, private val protocol: ReceiverProtocol) {
    data class Snapshot(val revision: Long, val grant: Long, val owned: Boolean)

    private data class Grant(
        val current: Int = 1, val permanent: Int = 1, val take: Int = 100,
        val borrow: Int = 100, val unborrow: Int = 100, val borrows: Int = 0,
    )

    private val lock = java.lang.Object()
    private val modes = info.objectForKey("modes") as? NSDictionary
    private var screen = initialGrant(1)
    private var audio = initialGrant(2)
    private var revision = 0L
    private var grant = 0L
    private var screenCommandReceived = false
    private var video: java.net.Socket? = null

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(revision, grant, screen.current == 1) }

    fun allows(snapshot: Snapshot): Boolean = synchronized(lock) { screen.current == 1 && grant == snapshot.grant }

    fun install(snapshot: Snapshot, socket: java.net.Socket): Boolean = synchronized(lock) {
        if (!allows(snapshot)) return false
        video = socket
        true
    }

    fun detach(socket: java.net.Socket) = synchronized(lock) {
        if (video === socket) video = null
    }

    fun waitForChange(previous: Snapshot) = synchronized(lock) {
        if (revision != previous.revision) return@synchronized
        try {
            lock.wait(250)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("已停止等待主屏")
        }
    }

    fun claimInitial() = synchronized(lock) {
        if (!screenCommandReceived) update(claim(screen, if (protocol.isBmw) 500 else 100), audio)
    }

    data class ModeReply(val body: NSDictionary, val afterReply: () -> Unit = {})

    fun onCommand(command: NSDictionary): ModeReply? = synchronized(lock) {
        val params = command.objectForKey("params") as? NSDictionary
        when ((command.objectForKey("type") as? NSString)?.content) {
            "requestUI" -> {
                screenCommandReceived = true
                update(claim(screen, 500), audio, force = true)
                null
            }
            "changeModes" -> prepareModeReply(params)
            else -> null
        }
    }

    private fun prepareModeReply(params: NSDictionary?): ModeReply? {
        fun rejected() = ModeReply(NSDictionary().apply { put("status", 1) })
        val value = params?.objectForKey("resources") ?: return null
        val raw = value as? NSArray ?: return rejected()
        if (raw.array.size > 8) return rejected()
        var nextScreen = screen
        var hasScreenTransfer = false
        var nextAudio = audio
        var hasAudioTransfer = false
        for (entry in raw.array) {
            val item = entry as? NSDictionary ?: return rejected()
            when (item.number("resourceID")) {
                1 -> {
                    nextScreen = transfer(nextScreen, item) ?: return rejected()
                    hasScreenTransfer = true
                }
                2 -> {
                    nextAudio = transfer(nextAudio, item) ?: return rejected()
                    hasAudioTransfer = true
                }
                else -> return rejected()
            }
        }
        if (hasScreenTransfer) screenCommandReceived = true
        val response = NSDictionary().apply {
            put("status", 0)
            put("params", snapshotBody(nextScreen, nextAudio))
        }
        return ModeReply(response) {
            synchronized(lock) {
                // Acknowledge the receiver's release first, then reclaim the available screen.
                val releasedScreen = hasScreenTransfer && nextScreen.current == 0
                val resumed = when {
                    !hasScreenTransfer -> screen
                    releasedScreen -> claim(nextScreen, 100)
                    else -> nextScreen
                }
                update(resumed, if (hasAudioTransfer) nextAudio else audio, force = releasedScreen)
            }
        }
    }

    private fun update(nextScreen: Grant, nextAudio: Grant, force: Boolean = false) {
        if (!force && screen == nextScreen && audio == nextAudio) return
        if (screen.current != nextScreen.current) {
            grant++
            if (nextScreen.current != 1) {
                video?.let { runCatching { it.close() } }
                video = null
            }
        }
        screen = nextScreen
        audio = nextAudio
        revision++
        lock.notifyAll()
    }

    private fun allowed(priority: Int, constraint: Int) =
        constraint == 100 || constraint == 500 && priority == 500

    private fun claim(value: Grant, priority: Int): Grant {
        if (value.current == 1) return value
        val constraint = if (value.permanent == 1 && value.current == 2) value.unborrow else value.take
        return if (allowed(priority, constraint)) Grant() else value
    }

    private fun transfer(value: Grant, item: NSDictionary): Grant? {
        val priority = item.number("transferPriority")?.takeIf { it == 100 || it == 500 }
        fun constraint(key: String) = item.number(key)?.takeIf { it == 100 || it == 500 || it == 1000 }
        val hasReleaseFields = listOf("transferPriority", "takeConstraint", "borrowConstraint", "unborrowConstraint")
            .any { item.objectForKey(it) != null }
        return when (item.number("transferType")) {
            1 -> {
                val take = constraint("takeConstraint") ?: return null
                val borrow = constraint("borrowConstraint") ?: return null
                val rule = if (value.permanent == 2 && value.current == 1) value.unborrow else value.take
                if (priority == null || (value.permanent != 2 || value.current != 2) && !allowed(priority, rule)) null
                else Grant(2, 2, take, borrow)
            }
            2 -> if (hasReleaseFields) null else if (value.permanent == 2) Grant(current = 0, permanent = 0) else value
            3 -> {
                val unborrow = constraint("unborrowConstraint") ?: return null
                if (priority == null || value.permanent == 2 || item.objectForKey("takeConstraint") != null ||
                    item.objectForKey("borrowConstraint") != null
                ) return null
                if (value.current == 2) value.copy(borrows = (value.borrows + 1).coerceAtMost(1024))
                else if (allowed(priority, value.borrow)) value.copy(current = 2, permanent = 1, unborrow = unborrow, borrows = 1)
                else null
            }
            4 -> if (hasReleaseFields || value.current == 1 && value.permanent == 2) null
                else if (value.current == 2 && value.permanent == 1) {
                    if (value.borrows <= 1) value.copy(current = 1, unborrow = 100, borrows = 0)
                    else value.copy(borrows = value.borrows - 1)
                } else value
            else -> null
        }
    }

    private fun initialGrant(id: Int): Grant {
        val initial = resources(modes, "initialPermanentEntity", id).lastOrNull()
        val transfer = resources(modes, "resources", id).lastOrNull()
        val permanent = if (transfer?.number("transferType") == 1) 2
            else initial?.number("permanentEntity")?.takeIf { it in 1..2 } ?: 1
        val borrowed = permanent == 1 && transfer?.number("transferType") == 3
        return Grant(
            current = if (borrowed) 2 else permanent, permanent = permanent,
            take = transfer?.number("takeConstraint") ?: initial?.number("takeConstraint") ?: 100,
            borrow = transfer?.number("borrowConstraint") ?: initial?.number("borrowConstraint") ?: 100,
            unborrow = transfer?.number("unborrowConstraint") ?: 100, borrows = if (borrowed) 1 else 0,
        )
    }

    private fun snapshotBody(screenState: Grant, audioState: Grant) = NSDictionary().apply {
        put("resources", NSArray(resource(1, screenState.current, screenState.permanent),
            resource(2, audioState.current, audioState.permanent)))
        val states = (1..3).map { id ->
            NSDictionary().apply {
                put("appStateID", id)
                put("entity", 0)
                if (id == 1) put("speechMode", -1)
            }
        }
        put("appStates", NSArray(*states.toTypedArray()))
    }

    fun publish(control: RtspControl) {
        val snapshot = synchronized(lock) { snapshotBody(screen, audio) }
        val declaration = NSDictionary().apply {
            put("type", "modesChanged")
            put("params", snapshot)
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
