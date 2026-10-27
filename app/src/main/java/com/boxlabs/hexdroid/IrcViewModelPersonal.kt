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

internal fun IrcViewModel.sendGreeting(netId: String, key: String, channel: String, args: String, aliasDepth: Int) {
    val st = _state.value
    val rt = runtimes[netId]
    if (!isChannelOnNet(netId, channel) || (args.isNotEmpty() && !args.equals("todos", true))) {
        append(key, from = null, text = appContext.getString(R.string.p2_greeting_usage), isLocal = true, doNotify = false)
        return
    }
    val members = scriptMembers(netId, channel)
    if (args.isNotEmpty() && members.isEmpty()) {
        append(key, from = null, text = appContext.getString(R.string.p2_members_pending), isLocal = true, doNotify = false)
        return
    }
    val text = if (args.isEmpty()) "Buenos días" else greeting(
        members, casefoldText(netId, st.connections[netId]?.myNick ?: st.myNick),
        st.settings.greetingBots.split(Regex("\\s+")).filter { it.isNotBlank() }.map { casefoldText(netId, bareMember(it)) }.toSet(),
        fold = { casefoldText(netId, it) },
    )
    val headerBytes = "PRIVMSG $channel :".toByteArray(Charsets.UTF_8).size + 2
    val wireBudget = ((rt?.support?.linelen ?: 512) - headerBytes).coerceAtLeast(0)
    val budget = minOf(wireBudget, if (ageOn(netId, channel)) minOf(120, outgoingByteBudget(netId, channel)) else outgoingByteBudget(netId, channel))
    if (text.toByteArray(Charsets.UTF_8).size > budget || outgoingChunks(netId, channel, text).size != 1) {
        append(key, from = null, text = appContext.getString(R.string.p2_greeting_long), isLocal = true, doNotify = false)
    } else sendInputInternal(text, aliasDepth + 1, key)
}
