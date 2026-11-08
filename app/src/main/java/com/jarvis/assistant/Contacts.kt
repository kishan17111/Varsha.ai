package com.jarvis.assistant

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object Contacts {
    /** Best-matching phone number for a spoken name, or null. */
    fun findNumber(ctx: Context, spoken: String): String? {
        val q = spoken.lowercase().trim()
        if (q.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.lowercase() ?: continue
                val number = c.getString(1) ?: continue
                val score = when {
                    name == q -> 4
                    name.startsWith(q) -> 3
                    q.split(" ").all { it in name } -> 2
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = number
                }
            }
        }
        return best
    }

    /** Content URI of the contact page for a spoken name, or null. */
    fun findContactUri(ctx: Context, spoken: String): Uri? {
        val q = spoken.lowercase().trim()
        if (q.isEmpty()) return null
        var bestId = -1L
        var bestScore = 0
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        )
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.lowercase() ?: continue
                val score = when {
                    name == q -> 4
                    name.startsWith(q) -> 3
                    q.split(" ").all { it in name } -> 2
                    else -> 0
                }
                if (score > bestScore) { bestScore = score; bestId = c.getLong(1) }
            }
        }
        return if (bestId >= 0) ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, bestId) else null
    }
}
