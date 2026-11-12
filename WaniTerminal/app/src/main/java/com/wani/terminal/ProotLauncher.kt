package com.wani.terminal

import android.content.Context
import java.io.File

/** Builds the command line + environment that starts Alpine under proot. */
class ProotLauncher(ctx: Context) {
    private val nativeDir = ctx.applicationInfo.nativeLibraryDir
    private val rootfs = RootfsInstaller.rootfs(ctx)

    val shell: String = "$nativeDir/libproot.so"
    val cwd: String = ctx.filesDir.absolutePath

    private val guestShell =
        if (File(rootfs, "bin/bash").exists()) "/bin/bash" else "/bin/sh"

    val args: Array<String> = buildList {
        add("proot")                    // argv[0]
        add("--kill-on-exit")
        add("--link2symlink")
        add("-0")                       // fake root
        add("-r"); add(rootfs.absolutePath)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        if (File("/sdcard").exists()) { add("-b"); add("/sdcard") }
        add("-w"); add("/root")
        add(guestShell); add("-l")
    }.toTypedArray()

    val env: Array<String> = arrayOf(
        "HOME=/root",
        "USER=root",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "TMPDIR=/tmp",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "PROOT_TMP_DIR=${File(cwd, "tmp").absolutePath}",
        "PROOT_LOADER=$nativeDir/libproot-loader.so"
        // Add "PROOT_NO_SECCOMP=1" here if proot crashes on your device.
    )

    fun isReady() = File(shell).exists()
}
