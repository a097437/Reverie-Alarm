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
//   inviteCodes/{6位數}                → { uid, name }（登入者只能用完整代碼查詢，不能列出全部）
//   publicShifts/{uid}                公開班表：日期、班別、色系、上下班時間
//                                     （只有本人能寫；對方開著公開連結時，登入者才讀得到）
//
// 隱私：私人備註、圖片、附件、鬧鐘「完全不上傳」，留在手機裡。
// 安全規則見專案根目錄 firestore.rules。
// ============================================================================

data class CloudUser(val uid: String, val name: String, val email: String)
data class CloudProfile(val inviteCode: String, val publicLinkEnabled: Boolean)

object CloudSync {
    var user by mutableStateOf<CloudUser?>(null)
        private set
    var profile by mutableStateOf<CloudProfile?>(null)
        private set
    /** 我訂閱的班表（即時同步） */
    val subscriptions = mutableStateListOf<PartnerSubscription>()
    var busy by mutableStateOf(false)
        private set

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
                    runCatching { ensureProfile() }
                    listenSubscriptions()
                    pendingEvents?.let { schedulePush(it) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 登入／登出

    /** Google 登入：成功回傳 null，失敗回傳給使用者看的錯誤訊息 */
    suspend fun signIn(activity: Activity): String? {
        if (!isConfigured(activity)) return "Firebase 尚未設定（缺少 google-services.json）"
        val webId = webClientId(activity)
        if (webId.isBlank()) {
            return "Google 登入尚未設定完成：請在 Firebase 啟用 Google 登入並加入 SHA-1，重新下載 google-services.json"
        }
        busy = true
        try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(webId).build())
                .build()
            val result = CredentialManager.create(activity).getCredential(activity, request)
            val cred = result.credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return "登入失敗：收到不支援的憑證類型"
            }
            val google = GoogleIdTokenCredential.createFrom(cred.data)
            auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
            return null
        } catch (e: GetCredentialCancellationException) {
            android.util.Log.w("RACloud", "signIn cancelled", e)
            return "已取消登入"
        } catch (e: NoCredentialException) {
            android.util.Log.w("RACloud", "signIn no credential", e)
            return "這支手機上沒有可用的 Google 帳號，請先到系統設定新增帳號"
        } catch (e: Exception) {
            android.util.Log.w("RACloud", "signIn failed", e)
            return "登入失敗：" + (e.message ?: e.javaClass.simpleName)
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
        val enabled = snap.getBoolean("publicLinkEnabled") ?: true
        if (snap.exists() && code != null) {
            profile = CloudProfile(code, enabled)
            // 名稱若在 Google 帳號改過就順便更新
            ref.set(mapOf("name" to u.name, "email" to u.email), SetOptions.merge())
            if (enabled) claimCode(code, u)
            return
        }
        val newCode = createUniqueCode(u)
        ref.set(
            mapOf(
                "name" to u.name, "email" to u.email, "inviteCode" to newCode,
                "publicLinkEnabled" to true, "createdAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
        profile = CloudProfile(newCode, true)
    }

    /** 產生一組沒人用過的 6 位數邀請碼 */
    private suspend fun createUniqueCode(u: CloudUser): String {
        repeat(8) {
            val code = (100000..999999).random().toString()
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
        error("無法產生邀請碼，請稍後再試")
    }

    /** 確保邀請碼還指向自己（重新開啟公開連結時用） */
    private suspend fun claimCode(code: String, u: CloudUser) {
        runCatching {
            val doc = db.collection("inviteCodes").document(code)
            val snap = doc.get().await()
            if (!snap.exists()) doc.set(mapOf("uid" to u.uid, "name" to u.name)).await()
        }
    }

    /** 開關公開連結：關閉時邀請碼立刻失效、別人讀不到你的班表 */
    suspend fun setPublicLink(enabled: Boolean): String? {
        val u = user ?: return "請先登入"
        val p = profile ?: return "帳號資料尚未載入"
        return runCatching {
            db.collection("users").document(u.uid).update("publicLinkEnabled", enabled).await()
            val codeDoc = db.collection("inviteCodes").document(p.inviteCode)
            if (enabled) claimCode(p.inviteCode, u) else codeDoc.delete().await()
            profile = p.copy(publicLinkEnabled = enabled)
            if (enabled) pendingEvents?.let { schedulePush(it) }
            null
        }.getOrElse { "更新失敗：" + (it.message ?: "") }
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
        val u = user ?: return "請先登入 Google 帳號才能訂閱"
        val code = codeRaw.filter { it.isDigit() }
        if (code.length != 6) return "請輸入 6 位數字邀請碼"
        if (code == profile?.inviteCode) return "這是你自己的邀請碼"
        return try {
            val snap = db.collection("inviteCodes").document(code).get().await()
            val ownerUid = snap.getString("uid") ?: return "找不到這組邀請碼，或對方已關閉共享"
            if (ownerUid == u.uid) return "這是你自己的邀請碼"
            val name = snap.getString("name") ?: "親友班表"
            db.collection("users").document(u.uid).collection("subscriptions").document(ownerUid).set(
                mapOf("name" to name, "code" to code, "addedAt" to FieldValue.serverTimestamp())
            ).await()
            null
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) "目前沒有網路，請連線後再試" else "訂閱失敗：" + e.code.name
        } catch (e: Exception) {
            "訂閱失敗：" + (e.message ?: "")
        }
    }

    suspend fun unsubscribe(ownerUid: String) {
        val u = user ?: return
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
                        name = d.getString("name") ?: "親友班表",
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
                    // 對方關閉公開連結 → 讀不到
                    subscriptions[idx] = subscriptions[idx].copy(shifts = emptyList(), unavailable = true)
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
