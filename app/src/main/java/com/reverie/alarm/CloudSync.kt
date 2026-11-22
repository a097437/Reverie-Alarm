package com.reverie.alarm

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// ============================================================================
// 真正的共享帳號（Firebase 免費方案）
//
// Firestore 資料結構：
//   users/{uid}                       名稱、Email、邀請碼、公開連結開關（只有本人能讀寫）
//   users/{uid}/subscriptions/{owner} 我訂閱了誰（只有本人能讀寫）
//   inviteCodes/{8碼}                  → { uid, name }（登入者只能用完整代碼查詢，不能列出全部）
//   publicShifts/{uid}                公開班表：日期、班別、色系、上下班時間（只有本人能寫）
//   publicShifts/{uid}/readers/{讀者}  訂閱者名單：要知道「目前的」邀請碼才能把自己加進來，
//                                     只有名單上的人讀得到班表；主人可以移除任何人
//
// 存取控制：
//   - 邀請碼 8 碼英數（約 1 兆種組合），不能用猜的
//   - 「更換邀請碼」會清空訂閱者名單，舊的邀請碼和已訂閱的人都會失效
//   - 新帳號預設「不共享」，要自己打開
//
// 隱私：私人備註、圖片、附件、鬧鐘「完全不上傳」，留在手機裡。
// 安全規則見專案根目錄 firestore.rules。
// ============================================================================

data class CloudUser(val uid: String, val name: String, val email: String)
data class CloudProfile(val inviteCode: String, val publicLinkEnabled: Boolean)
/** 訂閱我班表的人 */
data class CloudReader(val uid: String, val name: String)

object CloudSync {
    var user by mutableStateOf<CloudUser?>(null)
        private set
    var profile by mutableStateOf<CloudProfile?>(null)
        private set
    /** 我訂閱的班表（即時同步） */
    val subscriptions = mutableStateListOf<PartnerSubscription>()
    var busy by mutableStateOf(false)
        private set
    /** 訂閱我班表的人（主人看得到、可以移除） */
    val readers = mutableStateListOf<CloudReader>()
    private var readersListener: ListenerRegistration? = null
    /** 已經試過自動補上訂閱者名單的對象（避免一直重試） */
    private val repairTried = mutableSetOf<String>()

    // ------------------------------------------------------------------ 邀請碼格式
    /** 不含容易看錯的 0/O、1/I：32 個字元 × 8 碼 ≈ 1 兆種組合 */
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val CODE_LENGTH = 8

    fun isCurrentCodeFormat(code: String) = code.length == CODE_LENGTH && code.all { it in CODE_ALPHABET }
    /** 舊版的 6 位數字邀請碼（過渡期還能查，新的安全規則生效後就讀不到班表了） */
    private fun isLegacyCode(code: String) = code.length == 6 && code.all { it.isDigit() }

    /** 使用者輸入的邀請碼 → 統一成大寫、去掉空白與連字號 */
    fun normalizeCode(raw: String): String = raw.uppercase().filter { it.isLetterOrDigit() }.take(CODE_LENGTH)

    /** 顯示用：ABCD-EFGH */
    fun displayCode(code: String): String = if (code.length == CODE_LENGTH) code.take(4) + "-" + code.drop(4) else code

    private fun randomCode(): String {
        val rnd = java.security.SecureRandom()
        return (1..CODE_LENGTH).map { CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)] }.joinToString("")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var subsListener: ListenerRegistration? = null
    private val shiftListeners = mutableMapOf<String, ListenerRegistration>()
    private var pushJob: Job? = null
    private var pendingEvents: List<ShiftEvent>? = null

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    /** Firebase 設定是否完整（google-services.json 已轉成資源） */
    fun isConfigured(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null }.getOrDefault(false)

    /** 直接引用 R.string，正式版的資源壓縮才不會把它當成沒用到而刪掉 */
    private fun webClientId(context: Context): String = context.getString(R.string.default_web_client_id)

    /** App 啟動時呼叫一次：監聽登入狀態 */
    fun start(context: Context) {
        if (started || !isConfigured(context)) return
        started = true
        auth.addAuthStateListener { a ->
            val u = a.currentUser
            if (u == null) {
                onSignedOut()
            } else if (user?.uid != u.uid) {
                user = CloudUser(u.uid, u.displayName ?: "Reverie 使用者", u.email ?: "")
                scope.launch {
                    runCatching { ensureProfile() }.onFailure { AppLog.w("Cloud", "讀取帳號資料失敗", it) }
                    listenSubscriptions()
                    pendingEvents?.let { schedulePush(it) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 登入／登出

    /** Google 登入：成功回傳 null，失敗回傳給使用者看的錯誤訊息 */
    suspend fun signIn(activity: Activity): String? {
        if (!isConfigured(activity)) return tr("Firebase 尚未設定（缺少 google-services.json）")
        val webId = webClientId(activity)
        if (webId.isBlank()) {
            return tr("Google 登入尚未設定完成：請在 Firebase 啟用 Google 登入並加入 SHA-1，重新下載 google-services.json")
        }
        busy = true
        try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(webId).build())
                .build()
            val result = CredentialManager.create(activity).getCredential(activity, request)
            val cred = result.credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return tr("登入失敗：收到不支援的憑證類型")
            }
            val google = GoogleIdTokenCredential.createFrom(cred.data)
            auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
            return null
        } catch (e: GetCredentialCancellationException) {
            android.util.Log.w("RACloud", "signIn cancelled", e)
            return tr("已取消登入")
        } catch (e: NoCredentialException) {
            android.util.Log.w("RACloud", "signIn no credential", e)
            return tr("這支手機上沒有可用的 Google 帳號，請先到系統設定新增帳號")
        } catch (e: Exception) {
            AppLog.w("Cloud", "登入失敗", e)
            return tr("登入失敗：") + (e.message ?: e.javaClass.simpleName)
        } finally {
            busy = false
        }
    }

    suspend fun signOut(context: Context) {
        runCatching { auth.signOut() }
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        onSignedOut()
    }

    private fun onSignedOut() {
        subsListener?.remove(); subsListener = null
        readersListener?.remove(); readersListener = null
        readers.clear()
        repairTried.clear()
        shiftListeners.values.forEach { it.remove() }; shiftListeners.clear()
        subscriptions.clear()
        profile = null
        user = null
    }

    // ------------------------------------------------------------------ 個人資料與邀請碼

    private suspend fun ensureProfile() {
        val u = user ?: return
        val ref = db.collection("users").document(u.uid)
        val snap = ref.get().await()
        val code = snap.getString("inviteCode")
        if (snap.exists() && code != null) {
            val enabled = snap.getBoolean("publicLinkEnabled") ?: true
            profile = CloudProfile(code, enabled)
            // 名稱若在 Google 帳號改過就順便更新
            ref.set(mapOf("name" to u.name, "email" to u.email), SetOptions.merge())
            // 舊版 6 位數邀請碼 → 換成 8 碼（新的安全規則還沒發布時會失敗，就先沿用舊的）
            if (!isCurrentCodeFormat(code)) runCatching { replaceCode(u, code, enabled, clearReaders = false) }
            else if (enabled) runCatching { claimCode(code, u) }
            listenReaders()
            return
        }
        // 新帳號：預設不共享，打開共享時才登記邀請碼
        val newCode = randomCode()
        ref.set(
            mapOf(
                "name" to u.name, "email" to u.email, "inviteCode" to newCode,
                "publicLinkEnabled" to false, "createdAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
        profile = CloudProfile(newCode, false)
        listenReaders()
    }

    /** 登記一組沒人用過的新邀請碼，回傳代碼 */
    private suspend fun reserveNewCode(u: CloudUser): String {
        repeat(5) {
            val code = randomCode()
            val ok = runCatching {
                db.runTransaction { tx ->
                    val doc = db.collection("inviteCodes").document(code)
                    if (tx.get(doc).exists()) false else {
                        tx.set(doc, mapOf("uid" to u.uid, "name" to u.name))
                        true
                    }
                }.await()
            }.getOrDefault(false)
            if (ok) return code
        }
        error(tr("無法產生邀請碼，請稍後再試"))
    }

    /** 確保邀請碼指向自己；被別人用掉（機率極低）就換一組新的。回傳實際的代碼 */
    private suspend fun claimCode(code: String, u: CloudUser): String {
        val doc = db.collection("inviteCodes").document(code)
        val snap = doc.get().await()
        if (!snap.exists()) { doc.set(mapOf("uid" to u.uid, "name" to u.name)).await(); return code }
        if (snap.getString("uid") == u.uid) return code
        val fresh = reserveNewCode(u)
        db.collection("users").document(u.uid).update("inviteCode", fresh).await()
        return fresh
    }

    /** 換一組新邀請碼：舊代碼作廢；clearReaders 時順便清空訂閱者名單（所有人都要用新代碼重新訂閱） */
    private suspend fun replaceCode(u: CloudUser, oldCode: String, enabled: Boolean, clearReaders: Boolean): String {
        val fresh = if (enabled) reserveNewCode(u) else randomCode()
        db.collection("users").document(u.uid).update("inviteCode", fresh).await()
        runCatching { db.collection("inviteCodes").document(oldCode).delete().await() }
        if (clearReaders) {
            val all = db.collection("publicShifts").document(u.uid).collection("readers").get().await()
            all.documents.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
        profile = CloudProfile(fresh, enabled)
        return fresh
    }

    /** 開關公開連結：關閉時邀請碼立刻失效、別人讀不到你的班表 */
    suspend fun setPublicLink(enabled: Boolean): String? {
        val u = user ?: return tr("請先登入")
        val p = profile ?: return tr("帳號資料尚未載入")
        return runCatching {
            db.collection("users").document(u.uid).update("publicLinkEnabled", enabled).await()
            val code = if (enabled) claimCode(p.inviteCode, u) else {
                db.collection("inviteCodes").document(p.inviteCode).delete().await(); p.inviteCode
            }
            profile = CloudProfile(code, enabled)
            if (enabled) pendingEvents?.let { schedulePush(it) }
            null
        }.getOrElse { tr("更新失敗：") + (it.message ?: "") }
    }

    /** 更換邀請碼：舊代碼失效、所有訂閱者都會被移除 */
    suspend fun rotateCode(): String? {
        val u = user ?: return tr("請先登入")
        val p = profile ?: return tr("帳號資料尚未載入")
        return runCatching { replaceCode(u, p.inviteCode, p.publicLinkEnabled, clearReaders = true); null }
            .getOrElse { tr("更換失敗：") + (it.message ?: "") }
    }

    /** 把某個人從訂閱者名單移除（對方立刻讀不到） */
    suspend fun removeReader(readerUid: String): String? {
        val u = user ?: return tr("請先登入")
        return runCatching {
            db.collection("publicShifts").document(u.uid).collection("readers").document(readerUid).delete().await(); null
        }.getOrElse { tr("移除失敗：") + (it.message ?: "") }
    }

    private fun listenReaders() {
        val u = user ?: return
        readersListener?.remove()
        readersListener = db.collection("publicShifts").document(u.uid).collection("readers")
            .addSnapshotListener { qs, _ ->
                // 新的安全規則還沒發布時會讀不到，名單就先空著
                val list = qs?.documents?.map { CloudReader(it.id, it.getString("name") ?: tr("親友")) }.orEmpty()
                readers.clear(); readers.addAll(list.sortedBy { it.name })
            }
    }

    // ------------------------------------------------------------------ 上傳我的公開班表

    /** 每次儲存排班時呼叫；1.5 秒內多次修改只上傳一次 */
    fun schedulePush(events: List<ShiftEvent>) {
        pendingEvents = events
        if (user == null || profile?.publicLinkEnabled != true) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(1500)
            runCatching { pushNow(events) }
        }
    }

    private suspend fun pushNow(events: List<ShiftEvent>) {
        val u = user ?: return
        // 只上傳公開欄位：日期、班別名稱、色系、上下班時間（不含備註、附件、鬧鐘）
        val days = events.associate { ev ->
            ev.date.toString() to mapOf(
                "t" to ev.title,
                "ty" to ev.type.name,
                "s" to ev.startTime.toString(),
                "e" to ev.endTime.toString()
            )
        }
        db.collection("publicShifts").document(u.uid).set(
            mapOf("name" to u.name, "days" to days, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
    }

    // ------------------------------------------------------------------ 訂閱別人的班表

    /** 輸入對方邀請碼訂閱：成功回傳 null，失敗回傳錯誤訊息 */
    suspend fun subscribe(codeRaw: String): String? {
        val u = user ?: return tr("請先登入 Google 帳號才能訂閱")
        val code = normalizeCode(codeRaw)
        if (!isCurrentCodeFormat(code) && !isLegacyCode(code)) return tr("請輸入 8 碼邀請碼（英文字母與數字）")
        if (code == profile?.inviteCode) return tr("這是你自己的邀請碼")
        return try {
            val snap = db.collection("inviteCodes").document(code).get().await()
            val ownerUid = snap.getString("uid") ?: return tr("找不到這組邀請碼，或對方已關閉共享")
            if (ownerUid == u.uid) return tr("這是你自己的邀請碼")
            val name = snap.getString("name") ?: tr("親友班表")
            addMeAsReader(ownerUid, code)
            db.collection("users").document(u.uid).collection("subscriptions").document(ownerUid).set(
                mapOf("name" to name, "code" to code, "addedAt" to FieldValue.serverTimestamp())
            ).await()
            repairTried.remove(ownerUid)
            null
        } catch (e: FirebaseFirestoreException) {
            AppLog.w("Cloud", "訂閱失敗：${e.code.name}")
            when {
                e.code == FirebaseFirestoreException.Code.UNAVAILABLE -> tr("目前沒有網路，請連線後再試")
                e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED && isLegacyCode(code) -> tr("這是舊版的 6 位數邀請碼，請對方更新 App，再把新的 8 碼邀請碼傳給你")
                e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> tr("找不到這組邀請碼，或對方已關閉共享")
                else -> tr("訂閱失敗：") + e.code.name
            }
        } catch (e: Exception) {
            tr("訂閱失敗：") + (e.message ?: "")
        }
    }

    /** 把自己加進對方的訂閱者名單（安全規則會檢查邀請碼是否正確、是否還有效） */
    private suspend fun addMeAsReader(ownerUid: String, code: String): Boolean {
        val u = user ?: return false
        return runCatching {
            db.collection("publicShifts").document(ownerUid).collection("readers").document(u.uid).set(
                mapOf("code" to code, "name" to u.name, "addedAt" to FieldValue.serverTimestamp())
            ).await()
            true
        }.getOrElse { false } // 新的安全規則還沒發布、或邀請碼已更換
    }

    suspend fun unsubscribe(ownerUid: String) {
        val u = user ?: return
        runCatching { db.collection("publicShifts").document(ownerUid).collection("readers").document(u.uid).delete().await() }
        runCatching { db.collection("users").document(u.uid).collection("subscriptions").document(ownerUid).delete().await() }
    }

    private fun listenSubscriptions() {
        val u = user ?: return
        subsListener?.remove()
        subsListener = db.collection("users").document(u.uid).collection("subscriptions")
            .addSnapshotListener { qs, _ ->
                if (qs == null) return@addSnapshotListener
                val wanted = qs.documents.associate { d ->
                    d.id to PartnerSubscription(
                        name = d.getString("name") ?: tr("親友班表"),
                        code = d.getString("code") ?: "",
                        shifts = subscriptions.firstOrNull { it.uid == d.id }?.shifts,
                        uid = d.id
                    )
                }
                // 移除取消訂閱的
                (shiftListeners.keys - wanted.keys).forEach { shiftListeners.remove(it)?.remove() }
                subscriptions.removeAll { it.uid !in wanted.keys }
                wanted.values.forEach { sub ->
                    val idx = subscriptions.indexOfFirst { it.uid == sub.uid }
                    if (idx == -1) subscriptions.add(sub) else subscriptions[idx] = subscriptions[idx].copy(name = sub.name, code = sub.code)
                    if (sub.uid !in shiftListeners) listenShifts(sub.uid)
                }
            }
    }

    private fun listenShifts(ownerUid: String) {
        shiftListeners[ownerUid] = db.collection("publicShifts").document(ownerUid)
            .addSnapshotListener { snap, err ->
                val idx = subscriptions.indexOfFirst { it.uid == ownerUid }
                if (idx == -1) return@addSnapshotListener
                if (err != null) {
                    // 讀不到：對方關閉共享、更換了邀請碼、把我移除，或是更新前訂閱的（還不在名單上）
                    subscriptions[idx] = subscriptions[idx].copy(shifts = emptyList(), unavailable = true)
                    val code = subscriptions[idx].code
                    if (ownerUid !in repairTried && code.isNotEmpty()) {
                        repairTried += ownerUid
                        scope.launch {
                            // 用當初的邀請碼補登記一次；成功就重新監聽
                            if (addMeAsReader(ownerUid, code)) { shiftListeners.remove(ownerUid)?.remove(); listenShifts(ownerUid) }
                        }
                    }
                    return@addSnapshotListener
                }
                @Suppress("UNCHECKED_CAST")
                val days = (snap?.get("days") as? Map<String, Map<String, Any?>>).orEmpty()
                val shifts = days.map { (date, v) ->
                    PartnerShift(
                        date = date,
                        title = v["t"] as? String ?: "",
                        type = v["ty"] as? String ?: "CUSTOM",
                        start = v["s"] as? String ?: "",
                        end = v["e"] as? String ?: ""
                    )
                }.sortedBy { it.date }
                subscriptions[idx] = subscriptions[idx].copy(
                    shifts = shifts,
                    unavailable = false,
                    name = snap?.getString("name") ?: subscriptions[idx].name
                )
            }
    }
}
