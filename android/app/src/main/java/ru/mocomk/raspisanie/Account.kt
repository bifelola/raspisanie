package ru.mocomk.raspisanie

import android.app.Activity
import android.app.Application
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

/** Роли: user — пишет комментарии; admin — правит и удаляет любые, без рекламы; owner — ещё и раздаёт админки. */
data class Profile(
    val uid: String,
    val name: String,
    val email: String,
    val role: String,
    val noAds: Boolean,
    val group: String = "",
) {
    val isAdmin get() = role == ROLE_ADMIN || role == ROLE_OWNER
    val isOwner get() = role == ROLE_OWNER
    /** Рекламу не видят админы и те, у кого подписка «без рекламы». */
    val adFree get() = isAdmin || noAds
}

data class Comment(
    val id: String,
    val date: String,
    val text: String,
    val uid: String,
    val name: String,
    val created: Date?,
    val edited: Boolean,
)

/** Настройки рекламного места (документ config/ads, меняют админы прямо из приложения). */
data class AdConfig(
    val yandexUnitId: String = "",
    val bannerEnabled: Boolean = false,
    val bannerText: String = "",
    val bannerImage: String = "",
    val bannerUrl: String = "",
    val noAdsInfo: String = "",
)

sealed interface AuthState {
    /** Firebase не подключён (нет google-services.json) — приложение работает как раньше, без входа. */
    data object Off : AuthState
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val profile: Profile) : AuthState
}

const val ROLE_USER = "user"
const val ROLE_ADMIN = "admin"
const val ROLE_OWNER = "owner"
const val COMMENT_MAX = 1000

class AccountViewModel(app: Application) : AndroidViewModel(app) {
    val enabled = FirebaseApp.getApps(app).isNotEmpty()

    private val _auth = MutableStateFlow<AuthState>(if (enabled) AuthState.Loading else AuthState.Off)
    val auth: StateFlow<AuthState> = _auth
    private val _ads = MutableStateFlow(AdConfig())
    val ads: StateFlow<AdConfig> = _ads
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val info = MutableStateFlow<String?>(null)

    private val fa get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private var profileReg: ListenerRegistration? = null
    private var adsReg: ListenerRegistration? = null
    private var pendingName: String? = null

    private val listener = FirebaseAuth.AuthStateListener { onUser(it.currentUser) }

    init {
        if (enabled) {
            fa.addAuthStateListener(listener)
            adsReg = db.collection("config").document("ads").addSnapshotListener { s, _ ->
                if (s != null) _ads.value = parseAds(s)
            }
        }
    }

    override fun onCleared() {
        if (enabled) fa.removeAuthStateListener(listener)
        profileReg?.remove()
        adsReg?.remove()
    }

    val profile: Profile? get() = (auth.value as? AuthState.SignedIn)?.profile

    private fun isOwnerEmail(u: FirebaseUser) =
        BuildConfig.OWNER_EMAIL.isNotBlank() && u.isEmailVerified &&
            u.email.equals(BuildConfig.OWNER_EMAIL, ignoreCase = true)

    private fun basic(u: FirebaseUser) = Profile(
        u.uid, displayName(u), u.email.orEmpty(),
        if (isOwnerEmail(u)) ROLE_OWNER else ROLE_USER, false,
    )

    private fun displayName(u: FirebaseUser) =
        pendingName?.takeIf { it.isNotBlank() }
            ?: u.displayName?.takeIf { it.isNotBlank() }
            ?: u.email?.substringBefore("@")
            ?: "Студент"

    private fun onUser(u: FirebaseUser?) {
        profileReg?.remove()
        profileReg = null
        if (u == null) {
            _auth.value = AuthState.SignedOut
            return
        }
        val doc = db.collection("users").document(u.uid)
        profileReg = doc.addSnapshotListener { s, _ ->
            if (s == null) { _auth.value = AuthState.SignedIn(basic(u)); return@addSnapshotListener }
            if (!s.exists()) {
                if (!s.metadata.isFromCache) {
                    val b = basic(u)
                    doc.set(
                        mapOf(
                            "name" to b.name, "email" to b.email, "role" to b.role,
                            "noAds" to false, "group" to "", "created" to FieldValue.serverTimestamp(),
                        )
                    )
                }
                // анкеты ещё нет в памяти телефона — ждём ответа сервера
                _auth.value = if (s.metadata.isFromCache) AuthState.Loading else AuthState.SignedIn(basic(u))
                return@addSnapshotListener
            }
            val p = Profile(
                u.uid,
                s.getString("name") ?: displayName(u),
                s.getString("email") ?: u.email.orEmpty(),
                s.getString("role") ?: ROLE_USER,
                s.getBoolean("noAds") ?: false,
                s.getString("group").orEmpty(),
            )
            // владелец (почта из настроек сборки) получает свою роль автоматически
            if (!p.isOwner && isOwnerEmail(u)) doc.update("role", ROLE_OWNER)
            _auth.value = AuthState.SignedIn(p)
        }
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                block()
            } catch (e: Exception) {
                error.value = humanError(e)
            } finally {
                busy.value = false
            }
        }
    }

    fun signIn(email: String, password: String) = run {
        pendingName = null
        fa.signInWithEmailAndPassword(email.trim(), password).await()
    }

    fun register(name: String, email: String, password: String) = run {
        if (name.isBlank()) throw IllegalArgumentException("Введите имя — его увидят рядом с вашими комментариями")
        pendingName = name.trim()
        val res = fa.createUserWithEmailAndPassword(email.trim(), password).await()
        res.user?.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build())?.await()
        runCatching { res.user?.sendEmailVerification()?.await() }
    }

    fun resetPassword(email: String) = run {
        if (email.isBlank()) throw IllegalArgumentException("Введите почту, на неё придёт ссылка для сброса пароля")
        fa.sendPasswordResetEmail(email.trim()).await()
        info.value = "Письмо для сброса пароля отправлено на ${email.trim()}"
    }

    fun signInGoogle(activity: Activity) = run {
        pendingName = null
        val res = activity.resources
        val id = res.getIdentifier("default_web_client_id", "string", activity.packageName)
        if (id == 0) throw IllegalStateException("Вход через Google не включён в Firebase")
        val option = GetSignInWithGoogleOption.Builder(res.getString(id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val cred = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            return@run
        } catch (e: NoCredentialException) {
            throw IllegalStateException("На телефоне нет Google-аккаунта. Добавьте его в настройках или войдите по почте.")
        }
        if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalStateException("Google вернул неподходящие данные для входа")
        }
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        fa.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
    }

    fun signOut() {
        if (!enabled) return
        fa.signOut()
        viewModelScope.launch {
            runCatching { CredentialManager.create(getApplication()).clearCredentialState(ClearCredentialStateRequest()) }
        }
    }

    fun rename(name: String) = run {
        val p = profile ?: return@run
        if (name.isBlank()) return@run
        db.collection("users").document(p.uid).update("name", name.trim()).await()
    }

    /** Группа студента — выбирается сразу после регистрации, видна админам в списке пользователей. */
    fun setGroup(group: String) = run {
        val p = profile ?: return@run
        db.collection("users").document(p.uid).update("group", group).await()
    }

    // ---------- админка ----------

    fun saveAds(cfg: AdConfig) = run {
        db.collection("config").document("ads").set(
            mapOf(
                "yandexUnitId" to cfg.yandexUnitId.trim(),
                "bannerEnabled" to cfg.bannerEnabled,
                "bannerText" to cfg.bannerText.trim(),
                "bannerImage" to cfg.bannerImage.trim(),
                "bannerUrl" to cfg.bannerUrl.trim(),
                "noAdsInfo" to cfg.noAdsInfo.trim(),
            ),
            SetOptions.merge(),
        ).await()
        info.value = "Настройки рекламы сохранены"
    }

    suspend fun loadUsers(): List<Profile> =
        db.collection("users").get().await().documents.map {
            Profile(
                it.id, it.getString("name").orEmpty(), it.getString("email").orEmpty(),
                it.getString("role") ?: ROLE_USER, it.getBoolean("noAds") ?: false,
                it.getString("group").orEmpty(),
            )
        }.sortedWith(compareBy({ roleOrder(it.role) }, { it.group.ifBlank { "яяя" } }, { it.name.lowercase() }))

    suspend fun setRole(uid: String, role: String) {
        db.collection("users").document(uid).update("role", role).await()
    }

    suspend fun setNoAds(uid: String, value: Boolean) {
        db.collection("users").document(uid).update("noAds", value).await()
    }

    // ---------- комментарии ----------

    /** Комментарии группы за неделю: дата -> список (старые сверху). */
    fun watchComments(group: String, week: String, onChange: (Map<String, List<Comment>>) -> Unit): ListenerRegistration =
        db.collection("comments")
            .whereEqualTo("group", group)
            .whereEqualTo("week", week)
            .addSnapshotListener { s, _ ->
                if (s == null) return@addSnapshotListener
                val list = s.documents.map { d ->
                    Comment(
                        d.id, d.getString("date").orEmpty(), d.getString("text").orEmpty(),
                        d.getString("uid").orEmpty(), d.getString("name").orEmpty(),
                        d.getTimestamp("created", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate(),
                        d.getBoolean("edited") ?: false,
                    )
                }
                onChange(list.sortedBy { it.created ?: Date() }.groupBy { it.date })
            }

    fun addComment(group: String, week: String, date: String, text: String) = run {
        val p = profile ?: return@run
        val t = text.trim().take(COMMENT_MAX)
        if (t.isEmpty()) return@run
        db.collection("comments").add(
            mapOf(
                "group" to group, "week" to week, "date" to date, "text" to t,
                "uid" to p.uid, "name" to p.name, "created" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    fun editComment(id: String, text: String) = run {
        val p = profile ?: return@run
        val t = text.trim().take(COMMENT_MAX)
        if (t.isEmpty()) return@run
        db.collection("comments").document(id)
            .update(mapOf("text" to t, "edited" to true, "editedBy" to p.uid)).await()
    }

    fun deleteComment(id: String) = run {
        db.collection("comments").document(id).delete().await()
    }

    companion object {
        fun roleOrder(r: String) = when (r) { ROLE_OWNER -> 0; ROLE_ADMIN -> 1; else -> 2 }

        fun parseAds(s: DocumentSnapshot) = AdConfig(
            yandexUnitId = s.getString("yandexUnitId").orEmpty(),
            bannerEnabled = s.getBoolean("bannerEnabled") ?: false,
            bannerText = s.getString("bannerText").orEmpty(),
            bannerImage = s.getString("bannerImage").orEmpty(),
            bannerUrl = s.getString("bannerUrl").orEmpty(),
            noAdsInfo = s.getString("noAdsInfo").orEmpty(),
        )

        fun humanError(e: Exception): String = when (e) {
            is FirebaseAuthWeakPasswordException -> "Слишком простой пароль — нужно минимум 6 символов"
            is FirebaseAuthUserCollisionException -> "Эта почта уже зарегистрирована — войдите или сбросьте пароль"
            is FirebaseAuthInvalidUserException -> "Аккаунт с такой почтой не найден"
            is FirebaseAuthInvalidCredentialsException -> "Неверная почта или пароль"
            is FirebaseNetworkException -> "Нет интернета"
            is FirebaseFirestoreException ->
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "Недостаточно прав"
                else "Ошибка сервера: ${e.code}"
            is IllegalArgumentException, is IllegalStateException -> e.message ?: "Ошибка"
            else -> e.localizedMessage ?: "Ошибка"
        }
    }
}
