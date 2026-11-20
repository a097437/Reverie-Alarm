package com.reverie.alarm

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** 匯入安全：惡意或壞掉的備份檔、檔案網址 */
class ImportGuardTest {
    private val pkg = "com.reverie.alarm"
    private val gson = Gson()
    private val today = LocalDate.of(2026, 11, 21)
    private val external = "content://com.android.providers.media.documents/document/audio%3A1000"
    private val ownFile = "content://com.reverie.alarm.fileprovider/camera/roster.jpg"

    @Test
    fun uriSafety() {
        assertTrue(UriSafety.isExternalContent(pkg, external))
        assertFalse("本 App 自己的檔案", UriSafety.isExternalContent(pkg, ownFile))
        assertFalse("大小寫混用也要擋", UriSafety.isExternalContent(pkg, "content://COM.Reverie.Alarm.fileprovider/x"))
        assertFalse("帳號密碼混淆", UriSafety.isExternalContent(pkg, "content://evil@com.reverie.alarm.fileprovider/x"))
        assertFalse(UriSafety.isExternalContent(pkg, "file:///data/data/com.reverie.alarm/shared_prefs/a.xml"))
        assertFalse(UriSafety.isExternalContent(pkg, "https://evil.example/a.mp3"))
        assertFalse(UriSafety.isExternalContent(pkg, "javascript:alert(1)"))
        assertFalse(UriSafety.isExternalContent(pkg, null))

        assertNotNull(UriSafety.safeRingtoneUri(pkg, "android.resource://com.reverie.alarm/raw/rt_morning_sky"))
        assertNotNull(UriSafety.safeRingtoneUri(pkg, external))
        assertNull("網路網址", UriSafety.safeRingtoneUri(pkg, "http://evil.example/track.mp3"))
        assertNull("別的 App 的資源", UriSafety.safeRingtoneUri(pkg, "android.resource://com.other.app/raw/rt_x"))
        assertNull("不是內建鈴聲", UriSafety.safeRingtoneUri(pkg, "android.resource://com.reverie.alarm/raw/../files/x"))
        assertNull(UriSafety.safeRingtoneUri(pkg, ownFile))
    }

    @Test
    fun brokenJsonIsRejectedWithoutTouchingAnything() {
        for (bad in listOf("", "not json", "[1,2,3]", "{\"events\": \"oops\"")) {
            try {
                ImportGuard.parseBackup(pkg, gson, bad, today)
                fail("應該拒絕：$bad")
            } catch (e: ImportException) {
                // 預期：格式不相容
            }
        }
    }

    @Test
    fun nullsAndWrongTypesNeverReachKotlinNonNullFields() {
        val json = """
            {"themeName": null, "events": null, "alarms": null, "templates": null, "ringtoneLibrary": null,
             "preferences": {"defaultWakeRingtoneName": null, "colorMode": 123, "fontScale": 99,
                             "maxSnoozeCount": -5, "defaultWakeOffset": 999999,
                             "defaultWakeRingtoneUri": "http://evil.example/x.mp3",
                             "defaultLeaveRingtoneUri": "$external", "customAccentHex": "red;}"},
             "partnerSync": {"partnerName": null, "subscriptions": [{"uid": "x"}]}}
        """.trimIndent()
        val c = ImportGuard.parseBackup(pkg, gson, json, today)
        assertEquals("晨空漫步", c.prefs.defaultWakeRingtoneName) // null 不會蓋掉預設值
        assertEquals("dark", c.prefs.colorMode)                    // 型別不對就不採用
        assertEquals(1.6f, c.prefs.fontScale, 0.001f)
        assertEquals(0, c.prefs.maxSnoozeCount)
        assertEquals(720, c.prefs.defaultWakeOffset)
        assertNull("網路鈴聲不收", c.prefs.defaultWakeRingtoneUri)
        assertEquals(external, c.prefs.defaultLeaveRingtoneUri)
        assertEquals("", c.prefs.customAccentHex)
        assertNotNull(c.partnerSync.partnerName)
        assertNull("訂閱清單不從備份讀", c.partnerSync.subscriptions)
        assertTrue(c.events.isEmpty() && c.alarms.isEmpty())
    }

    @Test
    fun maliciousEventsAlarmsAndRingtonesAreCleaned() {
        val longTitle = "A".repeat(5000)
        val events = buildList {
            add(mapOf("id" to "e1", "date" to "2026-11-22", "title" to "$longTitle\u0000\u0007", "type" to "EARLY", "start" to "08:00", "end" to "17:00", "note" to "x",
                "attachments" to listOf(
                    mapOf("id" to "a1", "name" to "steal.jpg", "uriString" to ownFile, "isImage" to true),
                    mapOf("id" to "a2", "name" to "web.jpg", "uriString" to "https://evil.example/a.jpg", "isImage" to true),
                    mapOf("id" to "a3", "name" to "ok.jpg", "uriString" to external, "isImage" to true)
                )))
            add(mapOf("id" to "e2", "date" to "1800-01-01", "title" to "old", "type" to "EARLY", "start" to "08:00", "end" to "17:00"))
            add(mapOf("id" to "e3", "date" to "not-a-date", "title" to "bad"))
            add(null)
            repeat(5000) { i -> add(mapOf("id" to "bulk$i", "date" to "2027-01-01", "title" to "t", "type" to "OFF", "start" to "00:00", "end" to "23:59")) }
        }
        val alarms = buildList {
            add(mapOf("id" to "al1", "requestCode" to 0, "date" to "2026-11-22", "time" to "07:00", "stage" to "WAKE", "label" to "wake", "ringtoneUri" to "http://evil.example/x.mp3", "maxSnoozeCount" to 999, "snoozeMinutes" to -3))
            add(mapOf("id" to "al2", "date" to "2026-11-22", "time" to "25:99", "stage" to "WAKE", "label" to "bad time"))
            repeat(1000) { i -> add(mapOf("id" to "b$i", "requestCode" to i + 1, "date" to "2026-12-01", "time" to "06:00", "stage" to "CUSTOM", "label" to "x")) }
        }
        val ringtones = listOf(
            mapOf("id" to "r1", "name" to "web", "uriString" to "https://evil.example/x.mp3", "isBuiltIn" to false),
            mapOf("id" to "r2", "name" to "mine", "uriString" to ownFile, "isBuiltIn" to false),
            mapOf("id" to "r3", "name" to "fake builtin", "uriString" to "android.resource://com.reverie.alarm/raw/rt_morning_sky", "isBuiltIn" to true),
            mapOf("id" to "r4", "name" to "B".repeat(500), "uriString" to external, "isBuiltIn" to false)
        )
        val json = gson.toJson(mapOf("version" to 2, "themeName" to "NOT_A_THEME", "events" to emptyList<Any>(), "alarms" to emptyList<Any>(),
            "templates" to listOf(mapOf("id" to "t1", "name" to "EARLY|早班|90|40", "startTime" to "08:00", "endTime" to "17:00", "isOvernight" to false),
                mapOf("id" to "t2", "name" to "broken", "startTime" to "x", "endTime" to "y")),
            "eventsV2" to events, "alarmsV2" to alarms, "ringtoneLibrary" to ringtones))

        val c = ImportGuard.parseBackup(pkg, gson, json, today)

        assertNull(c.themeName)
        assertEquals(1, c.templates.size)
        assertEquals("最多讀 ${ImportGuard.MAX_EVENTS} 筆，其中 3 筆不合格被丟掉", ImportGuard.MAX_EVENTS - 3, c.events.size)
        val e1 = c.events.first { it.id == "e1" }
        assertEquals(40, e1.title.length)
        assertFalse(e1.title.any { it.isISOControl() })
        assertEquals("只留手機裡的附件", listOf("a3"), e1.attachments.map { it.id })
        assertTrue(c.events.none { it.id == "e2" || it.id == "e3" })

        assertEquals("最多讀 ${ImportGuard.MAX_ALARMS} 個（Android 上限 500），其中 1 個時間不合格", ImportGuard.MAX_ALARMS - 1, c.alarms.size)
        val al1 = c.alarms.first { it.id == "al1" }
        assertNull("網路鈴聲不收", al1.ringtoneUri)
        assertEquals(10, al1.maxSnoozeCount)
        assertEquals(0, al1.snoozeMinutes)
        assertTrue(al1.requestCode > 0)
        assertTrue(c.alarms.none { it.id == "al2" })

        assertEquals("只留手機裡的音檔", listOf("r4"), c.ringtones.map { it.id })
        assertEquals(ImportGuard.MAX_AUDIO_NAME, c.ringtones[0].name.length)
        assertTrue(c.skipped > 0)
    }
}
