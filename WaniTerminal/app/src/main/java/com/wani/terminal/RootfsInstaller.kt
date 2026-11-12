package com.wani.terminal

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Downloads the Alpine minirootfs and unpacks it into filesDir/rootfs/alpine. */
object RootfsInstaller {
    private const val URL_ARM64 =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"

    // Paste the value from the .sha256 file next to the tarball to enable verification.
    private const val SHA256 = ""

    fun rootfs(ctx: Context) = File(ctx.filesDir, "rootfs/alpine")
    fun isInstalled(ctx: Context) = File(rootfs(ctx), ".wani-installed").exists()

    fun install(ctx: Context, progress: (String, Int) -> Unit) {
        val tar = File(ctx.cacheDir, "alpine.tar.gz")
        download(tar, progress)
        verify(tar)

        val root = rootfs(ctx)
        root.deleteRecursively()
        root.mkdirs()
        extract(tar, root, progress)

        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        listOf("proc", "sys", "dev", "sdcard", "tmp", "root").forEach { File(root, it).mkdirs() }
        File(ctx.filesDir, "tmp").mkdirs()
        File(root, ".wani-installed").writeText("ok")
        tar.delete()
        progress("Done", 100)
    }

    private fun download(out: File, progress: (String, Int) -> Unit) {
        val conn = URL(URL_ARM64).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        val total = conn.contentLengthLong
        var done = 0L
        conn.inputStream.use { input ->
            FileOutputStream(out).use { o ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) progress("Downloading Alpine… ${done / 1024} KB", (done * 50 / total).toInt())
                }
            }
        }
    }

    private fun verify(f: File) {
        if (SHA256.isBlank()) return
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { i ->
            val b = ByteArray(32 * 1024)
            while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        check(hex.equals(SHA256, ignoreCase = true)) { "Checksum mismatch, download corrupted" }
    }

    private fun extract(tar: File, root: File, progress: (String, Int) -> Unit) {
        val rootPath = root.toPath().normalize()
        var count = 0
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(FileInputStream(tar)))).use { tin ->
            var e = tin.nextTarEntry
            while (e != null) {
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isNotEmpty()) {
                    val out = File(root, name)
                    check(out.toPath().normalize().startsWith(rootPath)) { "Bad path in archive: $name" }
                    out.parentFile?.mkdirs()
                    when {
                        e.isDirectory -> {
                            out.mkdirs()
                            Os.chmod(out.path, (e.mode and 0x1ff) or 0x1c0)
                        }
                        e.isSymbolicLink -> {
                            out.delete()
                            Os.symlink(e.linkName, out.path)
                        }
                        e.isLink -> {
                            File(root, e.linkName.removePrefix("./")).copyTo(out, overwrite = true)
                            Os.chmod(out.path, (e.mode and 0x1ff) or 0x180)
                        }
                        else -> {
                            FileOutputStream(out).use { tin.copyTo(it) }
                            Os.chmod(out.path, (e.mode and 0x1ff) or 0x180)
                        }
                    }
                }
                count++
                if (count % 20 == 0) progress("Extracting… $count files", 50 + (count * 50 / 550).coerceAtMost(49))
                e = tin.nextTarEntry
            }
        }
    }
}
