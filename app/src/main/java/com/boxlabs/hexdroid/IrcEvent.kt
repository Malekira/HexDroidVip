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


sealed class IrcEvent {
    data class Status(val text: String) : IrcEvent()
    data class Connected(val server: String) : IrcEvent()
    data class Registered(val nick: String) : IrcEvent()
    /**
     * @param reason human-readable, translated, for display only. Never match on it.
     * @param code machine-readable cause. Classify on this.
     */
    data class Disconnected(
        val reason: String?,
        val code: DisconnectCode = DisconnectCode.UNKNOWN,
    ) : IrcEvent()
    /**
     * The server's certificate fingerprint differs from the stored TOFU pin; the connection is
     * refused. Could be a legitimate rotation or an interception.
     */
    data class TlsFingerprintChanged(val stored: String, val actual: String) : IrcEvent()
    /**
     * Emitted on the first TLS connection when no TOFU fingerprint was stored yet.
     * The caller should persist [fingerprint] in the network profile for future verification.
     */
    data class TlsFingerprintLearned(val fingerprint: String) : IrcEvent()
    /**
     * The host doesn't match the certificate's identities and the profile hasn't accepted them; the
     * connection is refused. [sans] lists the SAN names, SAN IPs and subject CN.
     */
    data class TlsHostnameMismatch(val expected: String, val sans: List<String>) : IrcEvent()
    /**
     * @param transient set by the emitter when the failure is a routine connectivity blip
     *   that should not raise a notification. Null means "not stated", and the consumer
     *   falls back to its own heuristic.
     */
    data class Error(val message: String, val transient: Boolean? = null) : IrcEvent()

    /**
     * The server rejected an outbound multiline BATCH (FAIL BATCH MULTILINE_*), so it never
     * reached [target]. [sendIds] holds the rejected batch's send id; without echo-message it is
     * empty and [target] is that of the latest multiline send.
     */
    data class MultilineSendFailed(
        val target: String?,
        val code: String,
        val reason: String,
        val sendIds: List<Long> = emptyList(),
    ) : IrcEvent()

    /**
     * Sends that got an answer other than their echo: [failed] for a refusal or error, otherwise an
     * ACK, another labeled reply, or a later echo to the same target showing the server has dealt
     * with them.
     */
    data class SendResolved(val ids: List<Long>, val failed: Boolean) : IrcEvent()

    // get latency from PING/PONG (milliseconds)
    data class LagUpdated(val lagMs: Long?) : IrcEvent()

    // Raw server line (for logging/debug) */
    data class ServerLine(val line: String) : IrcEvent()

    // server output (MOTD/WHOIS/etc)
    /** One line of raw protocol traffic, emitted only while the raw log is enabled. */
    data class RawLine(val outgoing: Boolean, val line: String) : IrcEvent()

    data class ServerText(
        val text: String,
        val code: String? = null,
        val bufferName: String? = null,
        /** True when the line was matched to an outstanding WHOIS. */
        val isWhoisReply: Boolean = false,
        val whoisNick: String? = null,
        val whoisRequestId: Long? = null,
        val whoisParams: List<String> = emptyList(),
        val whoisTrailing: String? = null,
    ) : IrcEvent()

    // CTCP replies
    data class CtcpReply(
        val from: String,
        val command: String,
        val args: String,
        val timeMs: Long? = null
    ) : IrcEvent()


    // ISUPPORT (005) tokens
    data class ISupport(
        val chantypes: String,
        val caseMapping: String,
        val prefixModes: String,
        val prefixSymbols: String,
        val statusMsg: String? = null,
        /** Raw CHANMODES token value (e.g. "b,e,I,k,l,imnpst"). */
        val chanModes: String? = null,
        /**
         * LINELEN ISUPPORT token: maximum bytes per IRC line including the trailing CRLF.
         * RFC 1459 = 512; IRCv3 / Ergo / InspIRCd = typically 4096.
         * Null when the server did not advertise LINELEN (assume 512).
         */
        val linelen: Int? = null,
        /**
         * ELIST ISUPPORT token (uppercased): supported server-side LIST search extensions.
         * Contains 'U' when the server can filter LIST by user count ("LIST >N"). Null when
         * not advertised.
         */
        val elist: String? = null,
        /**
         * soju.im/FILEHOST (or draft/FILEHOST) upload endpoint URL, null when the
         * server does not offer HTTP file uploads.
         */
        val filehostUrl: String? = null,
        /** ICON / draft/ICON ISUPPORT token: server-supplied icon URL. */
        val networkIconUrl: String? = null,
        /** EXTBAN prefix (e.g. "~"), or empty string when the ircd uses no prefix; null if unsupported. */
        val extbanPrefix: String? = null,
        /** EXTBAN type letters (e.g. "acfijmnpqrtACFGOST"); null when the server has no EXTBAN token. */
        val extbanTypes: String? = null,
        /** draft/account-extban letter from ACCOUNTEXTBAN (e.g. "a"); null when unsupported. */
        val accountExtban: String? = null,
        /** BOUNCER_NETID: the soju network this connection is bound to; null when unbound. */
        val bouncerNetId: String? = null,
        /** soju.im/SAFERATE: the server delays rather than disconnects a fast client. */
        val safeRate: Boolean = false,
    ) : IrcEvent()

    // Join failure numerics (e.g. 471-477) with the channel extracted
    data class JoinError(val channel: String, val message: String, val code: String) : IrcEvent()

    // Channel modes as reported by RPL_CHANNELMODEIS (324)
    /**
     * RPL_CHANNELMODEIS. [modes] keeps its parameters ("+ntl 59"). [silent] is set when the
     * query came from the UI rather than the user, in which case the modes are stored but
     * no line is printed.
     */
    data class ChannelModeIs(
        val channel: String,
        val modes: String,
        val code: String = "324",
        val silent: Boolean = false,
    ) : IrcEvent()

    // Channel ban list entry (RPL_BANLIST / 367)
    data class BanListItem(
        val channel: String,
        val mask: String,
        val setBy: String? = null,
        val setAtMs: Long? = null,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // End of channel ban list (RPL_ENDOFBANLIST / 368)
    data class BanListEnd(
        val channel: String,
        val code: String = "368",
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // Channel quiet list entry (common: RPL_QUIETLIST / 728)
    data class QuietListItem(
        val channel: String,
        val mask: String,
        val setBy: String? = null,
        val setAtMs: Long? = null,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // End of channel quiet list (common: RPL_ENDOFQUIETLIST / 729)
    data class QuietListEnd(
        val channel: String,
        val code: String = "729",
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // Channel exception list entry (+e) (RPL_EXCEPTLIST / 348)
    data class ExceptListItem(
        val channel: String,
        val mask: String,
        val setBy: String? = null,
        val setAtMs: Long? = null,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // End of channel exception list (+e) (RPL_ENDOFEXCEPTLIST / 349)
    data class ExceptListEnd(
        val channel: String,
        val code: String = "349",
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // Channel invite-exemption list (+I) (RPL_INVEXLIST / 346)
    data class InvexListItem(
        val channel: String,
        val mask: String,
        val setBy: String? = null,
        val setAtMs: Long? = null,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // End of invite-exemption list (+I) (RPL_ENDOFINVEXLIST / 347)
    data class InvexListEnd(
        val channel: String,
        val code: String = "347",
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    data class ChatMessage(
        val from: String,
        val target: String,
        val text: String,
        val isPrivate: Boolean,
        val isAction: Boolean = false,
        /**
         * True when this was assembled from a draft/multiline BATCH, i.e. the sender meant
         * it as ONE message with embedded newlines. The UI folds long ones behind a "show
         * more"; a MOTD, an ASCII-art paste sent as separate PRIVMSGs, or any other
         * naturally multi-line rendering is not this and must not be folded.
         */
        val multiline: Boolean = false,
        val timeMs: Long? = null,
        val isHistory: Boolean = false,
        /**
         * draft/chathistory-context: a related message the server volunteered alongside the
         * history that was asked for. The spec says these "MUST NOT be counted towards the
         * message limit", so they take no part in deciding whether a page was empty or in
         * anchoring the next request.
         */
        val isChathistoryContext: Boolean = false,
        /** draft/oper-tag: true when the server marked the sender as an IRC operator. */
        val fromOper: Boolean = false,
        /** bot message tag (Bot Mode spec): true when the sender is flagged as a bot. */
        val fromBot: Boolean = false,
        /**
         * +draft/channel-context client tag: for a PM, the channel this message was sent
         * in the context of (e.g. a reply to something said in that channel). Null for
         * channel messages or when the tag is absent/invalid.
         */
        val channelContext: String? = null,
        /** IRCv3 msgid tag — used for deduplication when echo-message and chathistory overlap. */
        val msgId: String? = null,
        /**
         * IRCv3 +draft/reply / +reply tag: the msgid of the message this is a reply to.
         * Non-null when the sender used a reply feature
         */
        val replyToMsgId: String? = null,
        /**
         * IRCv3 account-tag: services account name of the sender, when available.
         * Requires the account-tag CAP to be negotiated.
         */
        val senderAccount: String? = null,
        /**
         * End-to-end encryption scheme this message arrived under. Non-null when
         * the wire payload was prefixed with a scheme indicator AND decryption
         * succeeded; the UI renders a per-scheme padlock annotation in this case.
         * Null for cleartext messages and for failed-decrypt attempts (the wire
         * text is shown verbatim in the latter case so the user can investigate).
         */
        val encryption: com.boxlabs.hexdroid.crypto.E2eScheme? = null,
        /**
         * For the echo of our own message, the id [IrcClient.privmsg] returned when it was sent.
         * Null for everyone else's messages and for ours sent from elsewhere.
         */
        val ownSendId: Long? = null,
    ) : IrcEvent()

data class Notice(
        /** draft/oper-tag: true when the server marked the sender as an IRC operator. */
        val fromOper: Boolean = false,
        /** bot message tag (Bot Mode spec): true when the sender is flagged as a bot. */
        val fromBot: Boolean = false,
        val from: String,
        // IRC target param (channel, our nick, etc.)
        val target: String,
        val text: String,
        val isPrivate: Boolean,
        /** True when the NOTICE prefix looks like a server prefix (no '!'). */
        val isServer: Boolean = false,
        val timeMs: Long? = null,
        val isHistory: Boolean = false,
        /** IRCv3 msgid tag — used for deduplication. */
        val msgId: String? = null,
        /**
         * IRCv3 +draft/reply / +reply tag: the msgid of the message this NOTICE replies to.
         * Non-null when the sender attached a reply tag.
         */
        val replyToMsgId: String? = null,
        /** E2E scheme; see [ChatMessage.encryption]. */
        val encryption: com.boxlabs.hexdroid.crypto.E2eScheme? = null,
    ) : IrcEvent()

    data class DccOfferEvent(val offer: DccOffer) : IrcEvent()

    // CTCP DCC CHAT offer
    data class DccChatOfferEvent(val offer: DccChatOffer) : IrcEvent()

    /**
     * Incoming CTCP `DCC RESUME` from [from]. Carries the request we'd need to honour
     * if [resume] matches one of our outgoing SEND offers (we're the sender).
     */
    data class DccResumeRequest(val from: String, val resume: DccResume) : IrcEvent()

    /**
     * Incoming CTCP `DCC ACCEPT` from [from]. The sender confirmed our RESUME request:
     * the original SEND can now be answered (active: we connect; passive: we listen)
     * with the agreed start offset.
     */
    data class DccAcceptResponse(val from: String, val accept: DccAccept) : IrcEvent()

    // Numeric 442 (ERR_NOTONCHANNEL)
    data class NotOnChannel(val channel: String, val message: String, val code: String = "442") : IrcEvent()
    /** 381 RPL_YOUREOPER — user successfully authenticated as IRC operator */
    data class YoureOper(val message: String) : IrcEvent()
    /** User MODE -o/-O received on our own nick — de-opered */
    object YoureDeOpered : IrcEvent()
    /** ChannelModeChanged — live MODE change on a channel (not 324 snapshot) */
    data class ChannelModeChanged(val channel: String, val modes: String) : IrcEvent()

    /** A channel MODE line from [byNick]: as written ([modes], with arguments) and as single changes. */
    data class ChannelModeChanges(
        val channel: String,
        val byNick: String?,
        val modes: String,
        val changes: List<ChannelModeChange>,
        val isHistory: Boolean = false,
    ) : IrcEvent()

    /** A CTCP request (anything but ACTION) from [from]: [command] uppercased, then its arguments. */
    data class CtcpRequest(
        val from: String,
        val target: String,
        val command: String,
        val args: String,
        val isHistory: Boolean = false,
    ) : IrcEvent()

    data class Joined(val channel: String, val nick: String, val userHost: String? = null, val timeMs: Long? = null, val isHistory: Boolean = false,
        /** IRCv3 extended-join: services account name sent in JOIN params[1], or null if not logged in ("*"). */
        val account: String? = null,
        /** IRCv3 extended-join: realname (gecos) sent as trailing in JOIN. */
        val realname: String? = null,
        /** For our own live join: the key we sent with the JOIN, or null if none. */
        val key: String? = null,
    ) : IrcEvent()
    data class Parted(val channel: String, val nick: String, val userHost: String? = null, val reason: String?, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()
    /**
     * [historyChannel] is set only for a QUIT replayed inside a CHATHISTORY batch, naming the
     * channel that batch was for.
     */
    data class Quit(
        val nick: String,
        val userHost: String? = null,
        val reason: String?,
        val timeMs: Long? = null,
        val isHistory: Boolean = false,
        val historyChannel: String? = null,
    ) : IrcEvent()

    data class Kicked(
        val channel: String,
        val victim: String,
        val byNick: String?,
        val byHost: String? = null,
        val reason: String? = null,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // Names list items may include prefixes (@,+,%,&,~)
    data class Names(val channel: String, val names: List<String>) : IrcEvent()
    data class NamesEnd(val channel: String) : IrcEvent()

    data class Topic(val channel: String, val topic: String?, val setter: String? = null, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()

    // Topic text reurned by server (RPL_TOPIC / 332) sent after JOIN or /TOPIC.
    data class TopicReply(val channel: String, val topic: String?, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()

    // Topic setter + time (RPL_TOPICWHOTIME / 333)
    data class TopicWhoTime(val channel: String, val setter: String, val setAtMs: Long?, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()

	/**
     * Channel user mode change (e.g. MODE #chan +o Nick).
     * @prefix is one of '~','&','@','%','+' depending on mode, or null if not a rank mode.
     */
    data class ChannelUserMode(
        val channel: String, val nick: String, val prefix: Char?, val adding: Boolean,
        val timeMs: Long? = null, val isHistory: Boolean = false,
        /** Who set the mode, when the MODE line names them. */
        val byNick: String? = null,
    ) : IrcEvent()

    // MODE line for a channel (includes channel modes and user rank mode changes)
    data class ChannelModeLine(val channel: String, val line: String, val timeMs: Long? = null, val isHistory: Boolean = false) : IrcEvent()
    data object ChannelListStart : IrcEvent()
    data class ChannelListItem(val channel: String, val users: Int, val topic: String) : IrcEvent()
    data object ChannelListEnd : IrcEvent()
    /**
     * RPL_TRYAGAIN (263): the server rate-limited or temporarily refused [command]
     * (commonly LIST under SECURELIST). [message] is the server's explanation, if any.
     */
    data class TryAgain(val command: String, val message: String?) : IrcEvent()

    data class NickChanged(
        val oldNick: String,
        val newNick: String,
        val timeMs: Long? = null,
        val isHistory: Boolean = false,
        /**
         * Channel this was replayed for, set only inside a CHATHISTORY batch.
         *
         * A nick change is broadcast to every channel the person shares, so a replayed one
         * has to be told which room it came from: the member lists say where they are now,
         * not where they were then.
         */
        val historyChannel: String? = null,
    ) : IrcEvent()

    // LagUpdated is defined above with a nullable value so callers can clear lag on disconnect.

    // IRCv3 CHGHOST: user changed their ident/host (requires chghost CAP)
    data class Chghost(
        val nick: String,
        val newUser: String,
        val newHost: String,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // IRCv3 ACCOUNT: user's services account name changed (requires account-notify CAP)
    data class AccountChanged(
        val nick: String,
        /** New account name, or "*" if logged out. */
        val account: String,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // IRCv3 SETNAME: user changed their realname (requires setname CAP)
    data class Setname(
        val nick: String,
        val newRealname: String,
        val timeMs: Long? = null,
        val isHistory: Boolean = false
    ) : IrcEvent()

    // Incoming INVITE
    data class InviteReceived(
        val from: String,
        val channel: String,
        val timeMs: Long? = null
    ) : IrcEvent()

    // ERROR :message - server-sent fatal error (usually precedes disconnect)
    data class ServerError(val message: String) : IrcEvent()

    // AWAY status change for another user in a shared channel (requires away-notify CAP)
    data class AwayChanged(
        val nick: String,
        /** null = no longer away; non-null = new away message */
        val awayMessage: String?,
        val timeMs: Long? = null
    ) : IrcEvent()

    // IRCv3 CAP NEW: server advertised a new capability after registration
    data class CapNew(val caps: List<String>) : IrcEvent()

    // IRCv3 CAP DEL: server withdrew a previously negotiated capability
    data class CapDel(val caps: List<String>) : IrcEvent()
    /**
     * IRCv3 STS `sts` cap observed. [host] is the configured server host, [secure]
     * whether this connection uses TLS. The port drives the insecure-connection
     * upgrade; the duration drives policy persistence on secure connections (0 =
     * delete the stored policy).
     */
    data class StsReceived(val host: String, val secure: Boolean, val port: Int?, val durationSec: Long?) : IrcEvent()
    /**
     * IRCv3 draft/message-redaction: [fromNick] deleted the message [msgId] in [target].
     * Applied on receipt only - the server echoes our own REDACTs back, so a rejected
     * redact (FAIL REDACT ...) never leaves the local buffer disagreeing with the channel.
     */
    data class MessageRedacted(
        val fromNick: String,
        val target: String,
        val msgId: String,
        val reason: String?,
        val timeMs: Long?,
        /** True when replayed from history rather than happening now. */
        val isHistory: Boolean = false,
    ) : IrcEvent()
    /**
     * draft/metadata-2: a metadata key changed on [target] (a nick or channel).
     * [value] is null when the key was removed / is not set. [visibility] is "*" for
     * keys everyone can see, or an implementation-defined string.
     */
    data class MetadataChanged(val target: String, val key: String, val visibility: String?, val value: String?) : IrcEvent()

    /**
     * draft/account-registration progress, surfaced structurally so a guided
     * registration dialog can drive its own state machine. [command] is REGISTER
     * or VERIFY; [stage] is the server-sent stage (SUCCESS/VERIFICATION_REQUIRED) or "FAIL:<code>" for a
     * standard-reply failure. [account] is the account name when known; [message] is the detail.
     */
    data class AccountRegUpdate(
        val command: String,
        val stage: String,
        val account: String?,
        val message: String?,
    ) : IrcEvent()

    /**
     * A bouncer upstream network from `BOUNCER NETWORK` (soju.im/bouncer-networks). [attrs] holds
     * the attributes this message set, keyed by lower-cased name with values unescaped; one it
     * leaves out keeps its previous value unless its key is in [clearedKeys]. [removed] is set for
     * `BOUNCER NETWORK <id> *`, where only [networkId] is meaningful.
     */
    data class BouncerNetwork(
        val networkId: String,
        val attrs: Map<String, String> = emptyMap(),
        val removed: Boolean = false,
        /** Lower-cased attribute keys that the message explicitly cleared (key= with empty value). */
        val clearedKeys: Set<String> = emptySet()
    ) : IrcEvent() {
        val name: String? get() = attrs["name"]
        val host: String? get() = attrs["host"]
        /** "connected", "connecting" or "disconnected". */
        val state: String? get() = attrs["state"]
    }

    /**
     * The bouncer answered a `BOUNCER ADDNETWORK`, `CHANGENETWORK` or `DELNETWORK`, either with
     * the reply naming [networkId] or with `FAIL BOUNCER`, which sets [failed], [code] and
     * [description]. [attribute] names the offending attribute for the *_ATTRIBUTE codes.
     * Replies come in the order the commands went out.
     */
    data class BouncerCommandResult(
        val subcommand: String,
        val networkId: String?,
        val failed: Boolean,
        val code: String? = null,
        val attribute: String? = null,
        val description: String? = null,
    ) : IrcEvent()

    /**
     * The reply to the soju.im/search request [requestId] from [IrcClient.searchMessages], oldest
     * first. [failed] is set when the server refused it, with [code] and [description] from its
     * FAIL SEARCH.
     */
    data class SearchResults(
        val requestId: String,
        val hits: List<SearchHit>,
        val failed: Boolean = false,
        val code: String? = null,
        val description: String? = null,
    ) : IrcEvent()

    /** The server confirmed a draft/metadata-2 subscription to [key] (770). */
    data class MetadataSubscribed(val key: String) : IrcEvent()

    /**
     * IRCv3 MONITOR: online/offline status notification for a watched nick.
     * Emitted when MONONLINE or MONOFFLINE is received, or on MONLIST reply (731/732).
     *
     * With draft/extended-monitor, MONONLINE entries are nick!user@host [account],
     * so ident, host, and account are populated when available.
     */
    data class MonitorStatus(
        val nick: String,
        val online: Boolean,
        val timeMs: Long? = null,
        /** ident from extended-monitor MONONLINE nick!user@host (null when not present). */
        val ident: String? = null,
        /** host from extended-monitor MONONLINE nick!user@host (null when not present). */
        val host: String? = null,
        /** services account from extended-monitor MONONLINE (null when not logged in or not present). */
        val account: String? = null
    ) : IrcEvent()

    /**
     * IRCv3 draft/read-marker: server sent an updated read marker (last-read message ID)
     * for a buffer. The client can use this to show unread-message indicators.
     *
     * @param target  Channel or nick buffer name.
     * @param timestamp  ISO 8601 timestamp of the last-read message.
     */
    data class ReadMarker(
        val target: String,
        val timestamp: String
    ) : IrcEvent()

    /**
     * IRCv3 draft/typing (+typing tag on TAGMSG): another user is typing (or stopped).
     *
     * @param target  Channel or nick that received the TAGMSG.
     * @param nick    Nick of the user whose typing state changed.
     * @param state   "active" | "paused" | "done"
     */
    data class TypingStatus(
        val target: String,
        val nick: String,
        val state: String,        // "active" | "paused" | "done"
        val timeMs: Long? = null
    ) : IrcEvent()

    /** [nick] has read our private messages up to and including [msgId]. */
    data class ReadReceipt(val nick: String, val msgId: String) : IrcEvent()

    /**
     * WHOX reply (354) for a nick: provides enriched ident/host/account data.
     * Emitted after a WHO #chan %tuhsnfar,42 query sent on channel join (when WHOX is
     * advertised in ISUPPORT 005).  The UI can use this to enrich the nicklist with
     * full hostname and services account information.
     */
    data class WhoxReply(
        val nick: String,
        val ident: String,
        val host: String,
        /** Services account name, or null if the user is not identified. */
        val account: String? = null,
        /**
         * True when the WHOX flags field starts with 'G' (Gone/away).
         * 'H' means Here (present), 'G' means Gone (away).
         * Null when flags were not included in the reply.
         */
        val isAway: Boolean? = null,
        /** Bot Mode: true when the WHOX flags field carries the BOT ISUPPORT mode letter. */
        val isBot: Boolean = false
    ) : IrcEvent()

    /**
     * draft/channel-rename: the server renamed [oldName] to [newName].
     * The client should update its buffer key, display name, and re-advertise the join.
     */
    data class ChannelRenamed(
        val oldName: String,
        val newName: String,
        val timeMs: Long? = null
    ) : IrcEvent()

    /**
     * draft/message-reactions: a TAGMSG with +draft/react was received.
     * [adding] = true means the reaction was added, false means it was removed.
     */
    data class MessageReaction(
        val fromNick: String,
        val target: String,
        val reaction: String,
        val msgId: String?,
        val adding: Boolean,
        val timeMs: Long? = null,
        /** True when replayed from history rather than happening now. */
        val isHistory: Boolean = false,
    ) : IrcEvent()

    /**
     * Emitted by /query to ask the ViewModel to open/focus a PM buffer without
     * necessarily sending a message. The ViewModel handles ensureBuffer + selectBuffer.
     */
    data class OpenQueryBuffer(val nick: String) : IrcEvent()

    /**
     * The server rejected our credentials (464, or SASL 904/905/906), so the ViewModel halts
     * auto-reconnect until the user acts. Not sent for 907, 908 or connection failures, which are
     * worth retrying. [source] is "PASS" or "SASL".
     */
    data class AuthFailed(val reason: String, val source: String) : IrcEvent()

    /**
     * A CHATHISTORY reply batch opened for [target]. Its messages arrive as ordinary events with
     * isHistory set; the open/close pair tells the ViewModel whether they fill a backfill request
     * or are catch-up.
     */
    /**
     * A CHATHISTORY request was rejected with FAIL. [target] is present for the codes that carry
     * one (INVALID_TARGET, MESSAGE_ERROR, INVALID_MSGREFTYPE); [label] names the request exactly
     * when present.
     */
    data class HistoryRequestFailed(
        val target: String?,
        val code: String,
        val description: String,
        val label: String? = null,
    ) : IrcEvent()

    /**
     * Our own away state changed because we asked for it.
     *
     * [message] is null when returning from away.
     */
    data class SelfAwayChanged(val message: String?) : IrcEvent()

    data class HistoryBatchStart(
        val target: String,
        val label: String? = null,
        /**
         * True when the batch opener carried draft/chathistory-end, meaning the server has
         * nothing older for this target. Null when it said nothing either way.
         */
        val complete: Boolean? = null,
    ) : IrcEvent()

    /**
     * The CHATHISTORY reply batch for [target] closed. See [HistoryBatchStart].
     *
     * @param lines how many lines the batch carried, whatever their type. This is what "the
     *   server returned an empty batch" means: counting only the lines that become visible
     *   messages reads a batch of nothing but JOIN and PART events as empty.
     */
    data class HistoryBatchEnd(
        val target: String,
        val label: String? = null,
        val lines: Int = 0,
    ) : IrcEvent()

    /**
     * One entry of a CHATHISTORY TARGETS reply: a buffer the server holds history for.
     *
     * @param target     Channel or nick.
     * @param timestamp  ISO 8601 time of the most recent stored message for that target.
     */
    data class HistoryTarget(val target: String, val timestamp: String) : IrcEvent()

    /**
     * We joined [channel] and its history should be fetched, up to [limit] messages when
     * nothing is held to anchor on. The request is left to the caller, which knows what the
     * buffer already holds and can wait for its saved log to load.
     */
    data class JoinHistoryWanted(val channel: String, val limit: Int) : IrcEvent()

    /**
     * An outgoing `/msg` or `/notice`, to be shown in the buffer the user typed it in.
     */
    data class OutgoingEcho(val originBuffer: String, val target: String, val text: String) : IrcEvent()

    /**
     * A CTCP request and the automatic reply we sent, as a pair.
     *
     * Emitted together so the UI can show both sides in one place.
     * [reply] is null for a request we recognised but did not answer, so the request is
     * still visible without implying a reply went out.
     */
    data class CtcpExchange(val fromNick: String, val request: String, val reply: String?) : IrcEvent()

    /**
     * The server confirmed a WEBPUSH REGISTER for [endpoint]. Registration is only
     * durable once this arrives, so the ViewModel records the endpoint here.
     */
    data class WebPushRegistered(val endpoint: String) : IrcEvent()

    /** The server confirmed a WEBPUSH UNREGISTER for [endpoint]. */
    data class WebPushUnregistered(val endpoint: String) : IrcEvent()

    /**
     * The server rejected a WEBPUSH command with a FAIL.
     *
     * [subcommand] is REGISTER or UNREGISTER, [code] the standard-replies error code.
     */
    data class WebPushFailed(val subcommand: String, val code: String, val message: String) : IrcEvent()

    /**
     * The server advertised a VAPID public key it had not advertised before.
     */
    data class WebPushVapidReady(val key: String) : IrcEvent()
}

/** One message from a soju.im/search reply. [target] is the channel or nick it was sent to. */
data class SearchHit(
    val from: String,
    val target: String,
    val text: String,
    val timeMs: Long?,
    val msgId: String?,
    val isNotice: Boolean,
    val isAction: Boolean,
)
