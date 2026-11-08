package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns a spoken sentence into a phone action. Everything here is local; no network, no keys. */
class Commands(private val ctx: Context, private val say: (String) -> Unit) {

    private val needA11y = "Please enable the Varsha accessibility service first."

    private fun String.cmd(pattern: String): MatchResult? = Regex(pattern).matchEntire(this)

    fun handle(raw: String) {
        val t = raw.lowercase()
            .replace(Regex("[.,!?]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.isEmpty()) return
        Bus.log("You: $t")
        try {
            say(route(t))
        } catch (e: SecurityException) {
            say("I'm missing a permission for that.")
        } catch (e: ActivityNotFoundException) {
            say("I couldn't find an app for that.")
        } catch (e: Exception) {
            say("Sorry, that failed.")
        }
    }

    private fun route(t: String): String {
        // ---------- contacts ----------
        t.cmd("""what(?:'s| is) (.+?)(?:'s| s) (?:phone )?number|(?:number of|find number for|find contact) (.+)""")?.let {
            val who = it.groupValues[1].ifEmpty { it.groupValues[2] }
            val n = Contacts.findNumber(ctx, who) ?: return "I couldn't find $who in your contacts."
            return "$who's number is $n."
        }
        t.cmd("""(?:open|show) contact (.+)""")?.let {
            val uri = Contacts.findContactUri(ctx, it.groupValues[1]) ?: return "I couldn't find ${it.groupValues[1]} in your contacts."
            launch(Intent(Intent.ACTION_VIEW, uri)); return "Opening ${it.groupValues[1]}."
        }
        t.cmd("""(?:add|save|create) (?:a )?(?:new )?contact (.+?) (?:with )?(?:number |phone )?(\+?\d[\d ]{5,})""")?.let {
            launch(
                Intent(android.provider.ContactsContract.Intents.Insert.ACTION).apply {
                    type = android.provider.ContactsContract.RawContacts.CONTENT_TYPE
                    putExtra(android.provider.ContactsContract.Intents.Insert.NAME, it.groupValues[1])
                    putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, it.groupValues[2].replace(" ", ""))
                }
            )
            return "Review and save the new contact."
        }

        // ---------- Instagram ----------
        if ("instagram" in t) {
            val c = t.replace(" on instagram", "").replace(" in instagram", "").replace("instagram ", "")
            c.cmd("""(?:send|message|text|dm) (?:a )?(?:message |dm )?(?:to )?(.+?) (?:saying|say|that says) (.+)""")
                ?.let { return igMessage(it.groupValues[1], it.groupValues[2]) }
            t.cmd("""(?:open )?instagram profile (?:of |for )?(.+)""")?.let { return igProfile(it.groupValues[1]) }
            t.cmd("""(?:open )?instagram (home|reels|search|explore|profile|messages|dms?|create|new post)""")
                ?.let { return igTab(it.groupValues[1]) }
            t.cmd("""search (?:for )?(.+?) on instagram|(?:instagram search|search instagram)(?: for)? (.+)""")
                ?.let { return igSearch(it.groupValues[1].ifEmpty { it.groupValues[2] }) }
        }
        t.cmd("""(?:open )?(reels|messages|dms)""")?.let { if (JarvisAccessibilityService.instance?.inPackage(IG) == true) return igTab(it.groupValues[1]) }
        t.cmd("""like(?: this| it)?(?: reel| post| photo| video)?""")?.let { return igLike() }
        t.cmd("""follow(?: them| him| her| this account| this person)?""")?.let { return igTap("Follow", "Followed.") }
        t.cmd("""comment saying (.+)|add (?:a )?comment (.+)""")
            ?.let { return igComment(it.groupValues[1].ifEmpty { it.groupValues[2] }) }
        t.cmd("""next (?:reel|short|post|story|video)""")?.let { return swipeApp("up", "Next.") }
        t.cmd("""(?:previous|last) (?:reel|short|post|story|video)""")?.let { return swipeApp("down", "Previous.") }

        // ---------- messaging & calls ----------
        if ("whatsapp" in t) {
            val c = t.replace(" on whatsapp", "").replace("whatsapp ", "")
            c.cmd("""(?:send|message|text) (?:a )?(?:message |msg |text )?(?:to )?(.+?) (?:saying|say|that says|that) (.+)""")
                ?.let { return whatsapp(it.groupValues[1], it.groupValues[2]) }
        }
        t.cmd("""(?:send )?(?:an? )?(?:sms|text message|text)(?: message)? (?:to )?(.+?) (?:saying|say|that says|that) (.+)""")
            ?.let { return sms(it.groupValues[1], it.groupValues[2]) }
        t.cmd("""(?:end|cut|drop|disconnect) (?:the )?call|hang up""")?.let { return endCall() }
        t.cmd("""(?:answer|accept|pick up)(?: the)?(?: call| phone)?""")?.let { return answerCall() }
        t.cmd("""turn off speaker(?:phone)?|speaker(?:phone)? off""")?.let { return speaker(false) }
        t.cmd("""(?:turn )?(?:on )?speaker(?:phone)?(?: on)?""")?.let { return speaker(true) }
        t.cmd("""(?:call|dial|phone) (.+)""")?.let { return call(it.groupValues[1]) }

        // ---------- YouTube & Spotify ----------
        t.cmd("""(?:open )?youtube(?: and)? (?:play|search for|search|watch) (.+)""")
            ?.let { return youtube(it.groupValues[1]) }
        t.cmd("""(?:play|search for|search|watch) (.+?) (?:on|in) youtube""")
            ?.let { return youtube(it.groupValues[1]) }
        t.cmd("""(?:open )?spotify(?: and)? play (.+)""")?.let { return spotify(it.groupValues[1]) }
        t.cmd("""play (.+?) (?:on|in|using) spotify""")?.let { return spotify(it.groupValues[1]) }

        // ---------- media keys (work for Spotify, YouTube, any player) ----------
        t.cmd("""(?:pause|stop|stop playing)(?: the)?(?: music| song| video| playback)?""")
            ?.let { media(KeyEvent.KEYCODE_MEDIA_PAUSE); return "Paused." }
        t.cmd("""(?:resume|continue|play|play music|play the music|resume music)""")
            ?.let { media(KeyEvent.KEYCODE_MEDIA_PLAY); return "Playing." }
        t.cmd("""(?:next|skip|next song|next track|skip song|skip this song)""")
            ?.let { media(KeyEvent.KEYCODE_MEDIA_NEXT); return "Next." }
        t.cmd("""(?:previous|previous song|previous track|last song)""")
            ?.let { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return "Previous." }

        // ---------- sound, brightness, torch ----------
        t.cmd("""(?:volume up|increase (?:the )?volume|turn up (?:the )?volume|louder|turn it up)""")
            ?.let { vol(AudioManager.ADJUST_RAISE); return "Volume up." }
        t.cmd("""(?:volume down|decrease (?:the )?volume|lower (?:the )?volume|turn down (?:the )?volume|quieter|turn it down)""")
            ?.let { vol(AudioManager.ADJUST_LOWER); return "Volume down." }
        t.cmd("""unmute(?: the)?(?: phone| sound| volume)?""")
            ?.let { vol(AudioManager.ADJUST_UNMUTE); return "Unmuted." }
        t.cmd("""(?:mute|silence)(?: the)?(?: phone| sound| volume)?""")
            ?.let { vol(AudioManager.ADJUST_MUTE); return "Muted." }
        t.cmd("""(?:set )?volume (?:to )?(\d{1,3})(?: percent)?""")?.let {
            val am = ctx.getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max * it.groupValues[1].toInt().coerceIn(0, 100) / 100, AudioManager.FLAG_SHOW_UI)
            return "Volume set."
        }
        t.cmd("""(?:set )?brightness (?:to )?(\d{1,3})(?: percent)?""")
            ?.let { return brightness(it.groupValues[1].toInt().coerceIn(0, 100), 0) }
        t.cmd("""(?:increase|raise) brightness|brightness up""")?.let { return brightness(null, 40) }
        t.cmd("""(?:decrease|lower|reduce) brightness|brightness down""")?.let { return brightness(null, -40) }
        t.cmd("""(?:turn )?(on|off) (?:the )?(?:flashlight|torch)|(?:flashlight|torch) (on|off)""")?.let {
            val on = (it.groupValues[1] + it.groupValues[2]) == "on"
            return torch(on)
        }

        // ---------- alarms, timers, navigation ----------
        t.cmd("""set (?:an? )?alarm (?:for|at) (.+)""")?.let { return alarm(it.groupValues[1]) }
        t.cmd("""set (?:a )?timer (?:for )?(\d+) (second|minute|hour)s?""")
            ?.let { return timer(it.groupValues[1].toInt(), it.groupValues[2]) }
        t.cmd("""(?:navigate|directions|take me|drive me|route) (?:to )?(.+)""")?.let { return navigate(it.groupValues[1]) }

        // ---------- screen control (needs accessibility) ----------
        t.cmd("""(?:go )?home(?: screen)?|go to home screen""")?.let { return global(AccessibilityService.GLOBAL_ACTION_HOME, "Home.") }
        t.cmd("""(?:go )?back""")?.let { return global(AccessibilityService.GLOBAL_ACTION_BACK, "Back.") }
        t.cmd("""(?:show |open )?recent apps|recents""")?.let { return global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps.") }
        t.cmd("""(?:open |show |pull down )?(?:the )?notifications?""")?.let { return global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications.") }
        t.cmd("""(?:open |show )?(?:the )?quick settings""")?.let { return global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings.") }
        t.cmd("""lock (?:the )?(?:screen|phone)""")?.let {
            return if (Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locked.") else "Not supported on this Android version."
        }
        t.cmd("""(?:take (?:a )?)?screenshot""")?.let {
            return if (Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken.") else "Not supported on this Android version."
        }
        t.cmd("""scroll (up|down)""")?.let {
            val s = JarvisAccessibilityService.instance ?: return needA11y
            s.swipe(if (it.groupValues[1] == "down") "up" else "down"); return "Scrolling ${it.groupValues[1]}."
        }
        t.cmd("""swipe (up|down|left|right)""")?.let {
            val s = JarvisAccessibilityService.instance ?: return needA11y
            s.swipe(it.groupValues[1]); return "Swiped ${it.groupValues[1]}."
        }
        t.cmd("""(?:type|write) (.+)""")?.let {
            val s = JarvisAccessibilityService.instance ?: return needA11y
            return if (s.typeText(it.groupValues[1])) "Typed." else "I couldn't find a text box."
        }
        t.cmd("""(?:click|tap|press|select) (?:on )?(.+)""")?.let {
            val s = JarvisAccessibilityService.instance ?: return needA11y
            return if (s.clickByText(it.groupValues[1])) "Tapped ${it.groupValues[1]}." else "I can't see ${it.groupValues[1]} on screen."
        }

        // ---------- info ----------
        t.cmd("""what(?:'s| is) the time|what time is it|current time|time""")
            ?.let { return "It's " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) }
        t.cmd("""what(?:'s| is) the date|today's date|what day is it|date""")
            ?.let { return SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()) }
        t.cmd("""battery|battery level|what(?:'s| is) the battery|how much battery do i have""")?.let {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            return "Battery is at ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent."
        }

        // ---------- settings pages / toggles ----------
        t.cmd("""(open |turn on |turn off |toggle |go to )?(wifi|wi-fi|bluetooth|airplane mode|flight mode|location|mobile data|display|sound|battery saver|settings)(?: settings)?""")
            ?.let { return settingsPage(it.groupValues[1].trim(), it.groupValues[2]) }

        // ---------- camera, apps, search ----------
        t.cmd("""take (?:a )?(?:photo|picture|selfie)|open camera""")?.let {
            launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); return "Camera."
        }
        t.cmd("""(?:open|launch|start) (.+)""")?.let { return openApp(it.groupValues[1]) }
        t.cmd("""(?:search(?: for)?|google|look up) (.+)""")?.let {
            launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, it.groupValues[1]))
            return "Searching for ${it.groupValues[1]}."
        }
        t.cmd("""play (.+)""")?.let {
            return if (installed("com.spotify.music")) spotify(it.groupValues[1]) else youtube(it.groupValues[1])
        }

        return "I didn't catch a command for that."
    }

    // =============== Instagram (works by reading Instagram's on-screen labels, English UI) ===============

    private val IG = "com.instagram.android"

    private fun igPred(vararg names: String): (AccessibilityNodeInfo) -> Boolean = { n ->
        val d = n.contentDescription?.toString() ?: ""
        val x = n.text?.toString() ?: ""
        names.any { d.equals(it, true) || x.equals(it, true) }
    }

    private fun openIg(): String? {
        val i = ctx.packageManager.getLaunchIntentForPackage(IG) ?: return "Instagram isn't installed."
        launch(i)
        return null
    }

    private fun igProfile(spoken: String): String {
        val u = spoken.replace(" dot ", ".").replace(" underscore ", "_").replace(" ", "")
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/_u/$u")).setPackage(IG))
        return "Opening $u on Instagram."
    }

    private fun igTab(tab: String): String {
        val svc = JarvisAccessibilityService.instance ?: return needA11y
        val names = when (tab) {
            "home" -> arrayOf("Home")
            "reels" -> arrayOf("Reels")
            "search", "explore" -> arrayOf("Search and explore")
            "profile" -> arrayOf("Profile")
            "create", "new post" -> arrayOf("Create", "New post")
            else -> arrayOf("Messenger", "Direct", "Messages")
        }
        val already = svc.inPackage(IG)
        if (!already) openIg()?.let { return it }
        svc.runAsync {
            if (!already) Thread.sleep(2500)
            svc.tapWhen(8000, igPred(*names))
        }
        return "Opening Instagram $tab."
    }

    private fun igSearch(q: String): String {
        val svc = JarvisAccessibilityService.instance ?: return needA11y
        openIg()?.let { return it }
        svc.runAsync {
            Thread.sleep(2500)
            svc.tapWhen(8000, igPred("Search and explore"))
            Thread.sleep(1200)
            var box = svc.awaitNode(1500) { it.isEditable }
            if (box == null) {
                svc.tapWhen(4000, igPred("Search"))
                box = svc.awaitNode(5000) { it.isEditable }
            }
            box?.let { svc.setText(it, q) }
        }
        return "Searching Instagram for $q."
    }

    private fun igMessage(name: String, msg: String): String {
        if (!installed(IG)) return "Instagram isn't installed."
        val svc = JarvisAccessibilityService.instance ?: return needA11y
        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg).setPackage(IG))
        val who = name.lowercase()
        svc.runAsync {
            Thread.sleep(2500)
            val box = svc.awaitNode(8000) { it.isEditable } ?: return@runAsync
            svc.setText(box, name)
            Thread.sleep(1500)
            val hit = svc.awaitNode(6000) { !it.isEditable && svc.label(it).lowercase().contains(who) } ?: return@runAsync
            svc.click(hit)
            Thread.sleep(600)
            svc.tapWhen(5000, igPred("Send"))
        }
        return "Sending your Instagram message to $name."
    }

    private fun igLike(): String {
        val svc = JarvisAccessibilityService.instance ?: return needA11y
        if (!svc.inPackage(IG)) return "Open Instagram first."
        if (svc.awaitNode(300, igPred("Unlike")) != null) return "You've already liked it."
        svc.runAsync {
            if (!svc.tapWhen(1500, igPred("Like"))) svc.doubleTap()
        }
        return "Liked."
    }

    private fun igTap(label: String, reply: String): String {
        val svc = JarvisAccessibilityService.instance ?: return needA11y
        if (!svc.inPackage(IG)) return "Open Instagram first."
        svc.runAsync { svc.tapWhen(2000, igPred(label)) }
        return reply
    }

    private fun igComment(text: String): String {
        val svc = JarvisAccessibilityService.instance ?: return needA11y
        if (!svc.inPackage(IG)) return "Open Instagram first."
        svc.runAsync {
            svc.tapWhen(3000, igPred("Comment"))
            Thread.sleep(1200)
            val box = svc.awaitNode(5000) { it.isEditable } ?: return@runAsync
            svc.setText(box, text)
            Thread.sleep(600)
            svc.tapWhen(4000, igPred("Post"))
        }
        return "Commenting."
    }

    private fun swipeApp(dir: String, reply: String): String {
        val svc = JarvisAccessibilityService.instance ?: return needA11y
        svc.swipe(dir)
        return reply
    }

    // =============== helpers ===============

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = JarvisAccessibilityService.instance ?: ctx
        c.startActivity(i)
    }

    private fun installed(pkg: String): Boolean =
        try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun resolveRaw(who: String): String? =
        if (Regex("""[+\d][\d\s-]{4,}""").matches(who)) who.replace(Regex("[\\s-]"), "")
        else Contacts.findNumber(ctx, who)

    private fun e164Digits(raw: String): String {
        val cleaned = raw.replace(Regex("[^+\\d]"), "")
        if (cleaned.startsWith("+")) return cleaned.drop(1)
        val country = (ctx.getSystemService(TelephonyManager::class.java)?.networkCountryIso
            ?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country).uppercase()
        val e = PhoneNumberUtils.formatNumberToE164(cleaned, country)
        return (e ?: cleaned).replace("+", "")
    }

    private fun whatsapp(who: String, msg: String): String {
        val raw = resolveRaw(who) ?: return "I couldn't find $who in your contacts."
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(it) }
            ?: return "WhatsApp isn't installed."
        val uri = Uri.parse("https://wa.me/${e164Digits(raw)}?text=${Uri.encode(msg)}")
        launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))
        val svc = JarvisAccessibilityService.instance
            ?: return "Opened the chat. Enable accessibility so I can press send."
        svc.clickWhenReady(15000, 2000) { n ->
            n.viewIdResourceName == "$pkg:id/send" || n.contentDescription?.toString().equals("Send", true)
        }
        return "Sending your message to $who."
    }

    @Suppress("DEPRECATION")
    private fun sms(who: String, msg: String): String {
        val num = resolveRaw(who) ?: return "I couldn't find $who in your contacts."
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sm.sendMultipartTextMessage(num, null, sm.divideMessage(msg), null, null)
        return "Text sent to $who."
    }

    private fun call(who: String): String {
        val num = resolveRaw(who) ?: return "I couldn't find $who in your contacts."
        launch(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", num, null)))
        return "Calling $who."
    }

    private fun endCall(): String {
        if (Build.VERSION.SDK_INT < 28) return "Not supported on this Android version."
        val ok = ctx.getSystemService(TelecomManager::class.java).endCall()
        return if (ok) "Call ended." else "There's no call to end."
    }

    private fun answerCall(): String {
        ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
        return "Answering."
    }

    @Suppress("DEPRECATION")
    private fun speaker(on: Boolean): String {
        ctx.getSystemService(AudioManager::class.java).isSpeakerphoneOn = on
        return if (on) "Speaker on." else "Speaker off."
    }

    private fun youtube(q: String): String {
        try {
            launch(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
        } catch (e: ActivityNotFoundException) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")))
            return "Opened YouTube search in your browser."
        }
        val svc = JarvisAccessibilityService.instance
            ?: return "Opened YouTube search. Enable accessibility so I can press play."
        svc.clickWhenReady(15000, 3000) { n ->
            val d = n.contentDescription?.toString() ?: ""
            d.contains("play video", true) || d.contains("Go to channel", true)
        }
        return "Playing $q on YouTube."
    }

    private fun spotify(q: String): String {
        if (!installed("com.spotify.music")) return "Spotify isn't installed."
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage("com.spotify.music")
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, q)
        launch(i)
        return "Playing $q on Spotify."
    }

    private fun media(code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun vol(dir: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val times = if (dir == AudioManager.ADJUST_RAISE || dir == AudioManager.ADJUST_LOWER) 2 else 1
        repeat(times) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
    }

    private fun brightness(pct: Int?, delta: Int): String {
        if (!Settings.System.canWrite(ctx)) return "Please allow modify system settings in Varsha first."
        val cr = ctx.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val v = (if (pct != null) pct * 255 / 100 else cur + delta).coerceIn(10, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
        return "Brightness set."
    }

    private fun torch(on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "I couldn't find a flashlight."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun alarm(spoken: String): String {
        val m = Regex("""(\d{1,2})(?:[: ]?(\d{2}))?\s*(am|pm|a m|p m)?""").find(spoken) ?: return "I didn't catch the time."
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].replace(" ", "")
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h > 23 || min > 59) return "That time doesn't look right."
        launch(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Varsha")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Alarm set for %d:%02d.".format(h, min)
    }

    private fun timer(n: Int, unit: String): String {
        val secs = when (unit) { "hour" -> n * 3600; "minute" -> n * 60; else -> n }
        launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Timer started for $n $unit${if (n == 1) "" else "s"}."
    }

    private fun navigate(place: String): String {
        try {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}")).setPackage("com.google.android.apps.maps"))
        } catch (e: ActivityNotFoundException) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(place)}")))
        }
        return "Navigating to $place."
    }

    private fun global(action: Int, ok: String): String {
        val s = JarvisAccessibilityService.instance ?: return needA11y
        return if (s.performGlobalAction(action)) ok else "That didn't work."
    }

    private fun settingsPage(verb: String, what: String): String {
        val action = when (what) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane mode", "flight mode" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "mobile data" -> Settings.ACTION_WIRELESS_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery saver" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launch(Intent(action))
        // Android doesn't let apps flip these directly, so tap the switch like a finger would.
        val toggles = setOf("wifi", "wi-fi", "bluetooth", "airplane mode", "flight mode")
        if ((verb == "turn on" || verb == "turn off") && what in toggles) {
            val want = verb == "turn on"
            val svc = JarvisAccessibilityService.instance ?: return "Opened $what settings. Enable accessibility so I can flip the switch."
            svc.clickWhenReady(6000, 1500, thenBack = true) { n ->
                n.isCheckable && n.isChecked != want && (n.className?.toString()?.contains("Switch") == true)
            }
            return "Turning $what ${verb.removePrefix("turn ")}."
        }
        return "Opening $what."
    }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val q = name.replace(" ", "")
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var bestPkg: String? = null
        var bestLabel = ""
        var bestScore = 0
        for (ri in list) {
            val shown = ri.loadLabel(pm).toString()
            val label = shown.lowercase().replace(" ", "")
            val s = when {
                label == q -> 3
                label.startsWith(q) -> 2
                label.contains(q) || (label.length > 2 && q.contains(label)) -> 1
                else -> 0
            }
            if (s > bestScore) { bestScore = s; bestPkg = ri.activityInfo.packageName; bestLabel = shown }
        }
        val pkg = bestPkg ?: return "I couldn't find an app called $name."
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "I can't open $bestLabel."
        launch(i)
        return "Opening $bestLabel."
    }
}
