package io.nous.andagent.shell

import android.content.Context
import java.io.File

/*
 * Adapted from termux-app/termux-shared/.../TermuxConstants.java (MIT).
 * Copyright (c) Termux contributors. The app-private root replaces com.termux.
 */
internal class AndagentTermuxPaths(context: Context) {
    val prefix = File(context.filesDir, "usr")
    val home = File(context.filesDir, "home")
    val dash = File(prefix, "bin/dash")
    val bash = File(prefix, "bin/bash")
    val pkg = File(prefix, "bin/pkg")
    val path = "${prefix.path}/bin:/system/bin"
    val environment = mapOf(
        "PREFIX" to prefix.path, "HOME" to home.path, "PATH" to path,
        "LD_LIBRARY_PATH" to "${prefix.path}/lib", "TMPDIR" to context.cacheDir.path,
        "TERMUX__PREFIX" to prefix.path, "TERMUX__HOME" to home.path,
    )
}
