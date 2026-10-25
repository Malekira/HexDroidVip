/*
* HexDroidIRC - An IRC Client for Android
* Copyright (C) 2026 boxlabs
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.boxlabs.hexdroid

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.boxlabs.hexdroid.BuildConfig
import com.boxlabs.hexdroid.IrcViewModel.Companion.MAX_DEBUG_ERRORS
import com.boxlabs.hexdroid.IrcViewModel.DebugError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal fun IrcViewModel.syncActiveNetworkSummary(st: UiState): UiState {
    val id = st.activeNetworkId ?: return st.copy(connected = false, connecting = false, status = appContext.getString(R.string.vm_status_disconnected), myNick = "me")
    val conn = st.connections[id] ?: NetConnState()
    return st.copy(connected = conn.connected, connecting = conn.connecting, status = conn.status, myNick = conn.myNick)
}

// Network

/**
 * Update the nick (and altNick) on all default server profiles that still have
 * the factory-default "HexDroidUser" / "HexDroid" nick. Called from the welcome screen
 * so the user's chosen nickname is applied everywhere before they even connect.
 */
fun IrcViewModel.updateAllDefaultNetworkNicks(nick: String) {
    viewModelScope.launch {
        val st = _state.value
        val defaultNicks = setOf("HexDroidUser", "HexDroid", "HexDroidUser_")
        for (net in st.networks) {
            if (net.nick in defaultNicks) {
                val updated = net.copy(
                    nick = nick,
                    altNick = "${nick}_",
                    username = nick.lowercase()
                )
                repo.upsertNetwork(updated)
            }
        }
    }
}

internal suspend fun IrcViewModel.logsToast(res: Int) = withContext(Dispatchers.Main) {
    android.widget.Toast.makeText(appContext, appContext.getString(res), android.widget.Toast.LENGTH_SHORT).show()
}

internal fun IrcViewModel.toastShareError() {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching {
            android.widget.Toast.makeText(
                appContext, appContext.getString(R.string.vm_file_cannot_open), android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}

// /SYSINFO

internal fun IrcViewModel.readGpuRendererBestEffort(): String {
    return try {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return "Unknown"

        val vers = IntArray(2)
        if (!EGL14.eglInitialize(display, vers, 0, vers, 1)) return "Unknown"

        // From here on, eglTerminate must be called in the finally block.
        var ctx: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
        var surf: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, num, 0)) {
                return "Unknown"
            }
            val config = configs[0] ?: return "Unknown"

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return "Unknown"

            val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surf = EGL14.eglCreatePbufferSurface(display, config, surfAttribs, 0)
            if (surf == EGL14.EGL_NO_SURFACE) return "Unknown"

            EGL14.eglMakeCurrent(display, surf, surf, ctx)

            val vendor = GLES20.glGetString(GLES20.GL_VENDOR)?.trim().orEmpty()
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)?.trim().orEmpty()

            val joined = listOf(vendor, renderer).filter { it.isNotBlank() }.joinToString(" ")
            if (joined.isBlank()) "Unknown" else joined
        } finally {
            // Always detach context and release resources even on early returns above.
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
            EGL14.eglTerminate(display)
        }
    } catch (_: Throwable) {
        "Unknown"
    }
}

internal fun IrcViewModel.buildSysInfoLine(): String {
    val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
    val api = android.os.Build.VERSION.SDK_INT
    val release = android.os.Build.VERSION.RELEASE ?: "?"
    val codename = android.os.Build.VERSION.CODENAME ?: "?"
    val cpuCores = Runtime.getRuntime().availableProcessors()
    val cpuModel = readCpuModel().ifBlank { "Unknown" }

    val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val totalMem = mi.totalMem
    val availMem = mi.availMem
    val usedMem = (totalMem - availMem).coerceAtLeast(0L)

    val stat = StatFs(android.os.Environment.getDataDirectory().absolutePath)
    val totalStorage = stat.blockCountLong * stat.blockSizeLong
    val freeStorage = stat.availableBlocksLong * stat.blockSizeLong
    val usedStorage = (totalStorage - freeStorage).coerceAtLeast(0L)

    val usedMemPct = if (totalMem > 0) usedMem.toDouble() / totalMem.toDouble() else 0.0
    val usedStoPct = if (totalStorage > 0) usedStorage.toDouble() / totalStorage.toDouble() else 0.0

    val uptimeMs = SystemClock.elapsedRealtime()
    val uptime = fmtUptime(uptimeMs)

    val gpu = cachedGpu ?: readGpuRendererBestEffort().also { cachedGpu = it }

    return "HexDroid v${BuildConfig.VERSION_NAME} | " +
        "Device: $device running Android $release $codename (API $api), CPU: ${cpuCores}-core $cpuModel, " +
        "Memory: ${fmtBytes(totalMem)} total, ${fmtBytes(usedMem)} (${fmtPct(usedMemPct)}) used, ${fmtBytes(availMem)} (${fmtPct(1.0 - usedMemPct)}) free, " +
        "Storage: ${fmtBytes(totalStorage)} total, ${fmtBytes(usedStorage)} (${fmtPct(usedStoPct)}) used, ${fmtBytes(freeStorage)} (${fmtPct(1.0 - usedStoPct)}) free, " +
        "Graphics: $gpu, Uptime: $uptime"
}

internal fun IrcViewModel.noteDebugError(netId: String, text: String) {
    synchronized(recentErrors) {
        recentErrors.addLast(DebugError(System.currentTimeMillis(), netId, text.take(400)))
        while (recentErrors.size > MAX_DEBUG_ERRORS) recentErrors.removeFirst()
    }
}

internal fun IrcViewModel.readCpuModel(): String {
    return runCatching {
        val txt = File("/proc/cpuinfo").readText()
        // Try common keys
        val keys = listOf("Hardware", "Model", "model name", "Processor", "CPU implementer")
        for (k in keys) {
            val m = Regex("^\\s*${Regex.escape(k)}\\s*:\\s*(.+)$", RegexOption.MULTILINE).find(txt)
            if (m != null) return m.groupValues[1].trim()
        }
        ""
    }.getOrDefault("")
}

internal fun IrcViewModel.fmtBytes(b: Long): String {
    val gb = 1024.0 * 1024.0 * 1024.0
    val mb = 1024.0 * 1024.0
    return when {
        b >= gb -> String.format(Locale.US, "%.1fGB", b / gb)
        b >= mb -> String.format(Locale.US, "%.0fMB", b / mb)
        else -> "${b}B"
    }
}

internal fun IrcViewModel.fmtPct(v: Double): String =
    String.format(Locale.US, "%.1f%%", (v * 100.0).coerceIn(0.0, 100.0))

internal fun IrcViewModel.fmtUptime(ms: Long): String {
    val s = ms / 1000
    val days = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (days > 0) "${days}d ${h}h ${m}m ${sec}s" else "${h}h ${m}m ${sec}s"
}
