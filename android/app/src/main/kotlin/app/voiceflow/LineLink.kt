package app.voiceflow

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.shell.AdbShellPacket
import java.io.File
import java.io.OutputStream
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.runBlocking

/** Talks to the phone's own wireless debugging. Connected only for a pairing or a call, then closed. */
object LineLink {
    private var folder: File? = null
    private var loaded = false
    private var identitySaved = false

    /** Loads the key that survived the last update. Without this, every update looks unpaired. */
    @Synchronized fun install(context: Context) {
        val dir = File(context.applicationContext.filesDir, "line")
        if (loaded && folder == dir) return
        if (!dir.isDirectory) dir.mkdirs()
        folder = dir
        loaded = true
        identitySaved = false
        val identity = AtomicFile(File(dir, "identity"))
        try {
            DataInputStream(identity.openRead()).use { input ->
                val certSize = input.readInt()
                require(certSize in 1..65536)
                val certBytes = ByteArray(certSize).also { input.readFully(it) }
                val keySize = input.readInt()
                require(keySize in 1..65536)
                val keyBytes = ByteArray(keySize).also { input.readFully(it) }
                KadbCert.set(certBytes, keyBytes)
                identitySaved = true
            }
            return
        } catch (_: Exception) { /* Older installations keep the same identity in two files. */ }
        val cert = File(dir, "cert")
        val key = File(dir, "key")
        if (cert.length() <= 0 || key.length() <= 0) return
        try {
            KadbCert.set(cert.readBytes(), key.readBytes())
            identitySaved = true
            keep() // Import, retaining the original files for recovery.
        } catch (_: Exception) {}
    }

    @Synchronized fun saved(): Boolean = identitySaved

    fun pair(host: String, port: Int, code: String) {
        runBlocking { Kadb.pair(host, port, code, "Vocading") }
        keep()
    }

    /** ok when this phone accepts us, auth when the key was rejected, down when the port just did not answer. */
    fun reachable(host: String, port: Int): String {
        if (!saved()) return "auth"
        return try {
            connect(host, port).close()
            "ok"
        } catch (error: Exception) {
            if (auth(error)) "auth" else "down"
        }
    }

    /** Starts the shell recorder and copies its audio to [sink]. Close the result to hang up the connection. */
    fun record(host: String, port: Int, sink: OutputStream): AutoCloseable {
        val kadb = connect(host, port)
        val shell = try {
            val listed = kadb.shell("pm path app.voiceflow").allOutput
            val apk = listed.lineSequence().map { it.removePrefix("package:").trim() }.firstOrNull { it.endsWith(".apk") }
                ?: throw IllegalStateException("Could not find Vocading on this phone.")
            kadb.openShell("CLASSPATH='$apk' exec app_process /system/bin app.voiceflow.CallShell")
        } catch (error: Exception) {
            try { kadb.close() } catch (_: Exception) {}
            throw error
        }
        val thread = Thread({
            try {
                while (true) {
                    when (val packet = shell.read()) {
                        is AdbShellPacket.StdOut -> if (packet.payload.isNotEmpty()) sink.write(packet.payload)
                        is AdbShellPacket.Exit -> break
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { sink.flush() } catch (_: Exception) {}
                try { sink.close() } catch (_: Exception) {}
            }
        }, "line-shell")
        thread.start()
        return AutoCloseable {
            try { shell.close() } catch (_: Exception) {}
            try { thread.join(2500) } catch (_: Exception) {}
            try { kadb.close() } catch (_: Exception) {}
        }
    }

    /** The address wireless debugging advertised, then the phone talking to itself. */
    private fun connect(host: String, port: Int): Kadb {
        val hosts = LinkedHashSet<String>()
        hosts.add("127.0.0.1")
        if (host.isNotBlank()) hosts.add(host)
        var last: Exception? = null
        for (candidate in hosts) {
            var kadb: Kadb? = null
            try {
                kadb = Kadb.create(candidate, port, 5000, 0)
                val out = kadb.shell("echo voiceflow").allOutput
                if (out.contains("voiceflow")) return kadb
                kadb.close()
                kadb = null
            } catch (error: Exception) {
                try { kadb?.close() } catch (_: Exception) {}
                last = error
                if (auth(error)) throw error
            }
        }
        throw last ?: IllegalStateException("Wireless debugging did not answer.")
    }

    @Synchronized private fun keep() {
        val dir = folder ?: throw IllegalStateException("Pairing storage is not ready. Open Vocading and try again.")
        val identity = AtomicFile(File(dir, "identity"))
        var output: java.io.FileOutputStream? = null
        try {
            val pair = KadbCert.getOrError()
            require(pair.first.isNotEmpty() && pair.second.isNotEmpty())
            output = identity.startWrite()
            val data = DataOutputStream(output)
            data.writeInt(pair.first.size); data.write(pair.first)
            data.writeInt(pair.second.size); data.write(pair.second)
            data.flush()
            identity.finishWrite(output)
            identitySaved = true
        } catch (error: Exception) {
            identity.failWrite(output)
            throw IllegalStateException("Pairing connected, but its key could not be saved. Free some storage and pair again.", error)
        }
    }

    private fun auth(error: Exception): Boolean {
        if (error.javaClass.name.contains("AdbAuth")) return true
        val message = (error.message ?: "").lowercase()
        return message.contains("unauthorized") || message.contains("auth") && message.contains("fail")
    }
}
