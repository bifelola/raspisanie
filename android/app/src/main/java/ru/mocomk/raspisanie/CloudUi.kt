@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.mocomk.raspisanie

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.MobileAds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------- комментарии под днём ----------------

@Composable
fun CommentsSection(
    comments: List<Comment>,
    me: Profile,
    onAdd: () -> Unit,
    onEdit: (Comment) -> Unit,
    onDelete: (Comment) -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.fillMaxWidth().padding(14.dp, 8.dp, 8.dp, 4.dp)) {
        for (c in comments) {
            CommentRow(c, canManage = me.isAdmin || c.uid == me.uid, mine = c.uid == me.uid, onEdit, onDelete)
        }
        TextButton(onClick = onAdd, modifier = Modifier.padding(start = 0.dp)) {
            Icon(Icons.AutoMirrored.Filled.Comment, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (comments.isEmpty()) "Написать комментарий" else "Ответить")
        }
    }
}

@Composable
private fun CommentRow(c: Comment, canManage: Boolean, mine: Boolean, onEdit: (Comment) -> Unit, onDelete: (Comment) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.name + if (mine) " (вы)" else "", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "  " + formatWhen(c.created) + if (c.edited) " · изменено" else "",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            Text(c.text, fontSize = 14.sp)
        }
        if (canManage) Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, "Действия", Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Изменить") }, onClick = { menu = false; onEdit(c) })
                DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete(c) })
            }
        }
    }
}

private fun formatWhen(d: Date?): String {
    if (d == null) return "сейчас"
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { time = d }
    val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    val ru = Locale.forLanguageTag("ru")
    return if (sameDay) SimpleDateFormat("HH:mm", ru).format(d) else SimpleDateFormat("d MMM, HH:mm", ru).format(d)
}

@Composable
fun CommentDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { if (it.length <= COMMENT_MAX) text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = { Text("Что было на парах, перенос, домашка…") },
                supportingText = { Text("${text.length} / $COMMENT_MAX") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ConfirmDialog(text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ---------------- аккаунт ----------------

@Composable
fun AccountDialog(acc: AccountViewModel, me: Profile, ads: AdConfig, onChangeGroup: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(me.name) }
    var showPolicy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Аккаунт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(me.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Группа: " + me.group.ifBlank { "не указана" }, Modifier.weight(1f))
                    TextButton(onClick = onChangeGroup) { Text("Сменить") }
                }
                Text(
                    "Роль: " + when (me.role) {
                        ROLE_OWNER -> "владелец"
                        ROLE_ADMIN -> "администратор"
                        else -> "пользователь"
                    } + if (me.adFree) " · без рекламы" else "",
                )
                OutlinedTextField(name, { name = it }, label = { Text("Имя в комментариях") }, singleLine = true)
                if (!me.adFree) Text(
                    ads.noAdsInfo.ifBlank { "Отключить рекламу можно по подписке — напишите администратору." },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showPolicy = true }) { Text("Конфиденциальность") }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.trim() != me.name) acc.rename(name); onDismiss() }) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(); acc.signOut() }) {
                Text("Выйти", color = MaterialTheme.colorScheme.error)
            }
        },
    )
    if (showPolicy) PolicyDialog { showPolicy = false }
}

// ---------------- админка: пользователи ----------------

@Composable
fun UsersSheet(acc: AccountViewModel, me: Profile, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val users = remember { mutableStateListOf<Profile>() }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            users.clear(); users.addAll(acc.loadUsers())
        } catch (e: Exception) {
            err = AccountViewModel.humanError(e)
        }
        loading = false
    }

    fun change(p: Profile, block: suspend () -> Unit, updated: Profile) {
        scope.launch {
            try {
                block()
                val i = users.indexOfFirst { it.uid == p.uid }
                if (i >= 0) users[i] = updated
            } catch (e: Exception) {
                err = AccountViewModel.humanError(e)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text("Пользователи", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (me.isOwner) "Назначайте админов (правят и удаляют любые комментарии, не видят рекламу) " +
                    "и включайте «без рекламы» тем, кто оформил подписку."
                else "Включайте «без рекламы» тем, кто оформил подписку. Админов назначает владелец.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            OutlinedTextField(
                q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск: имя, почта или группа") },
            )
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            val groups = users.map { it.group.ifBlank { "без группы" } }.distinct().sorted()
            if (groups.size > 1) androidx.compose.foundation.lazy.LazyRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(listOf<String?>(null) + groups) { g ->
                    androidx.compose.material3.FilterChip(
                        selected = groupFilter == g, onClick = { groupFilter = g },
                        label = { Text(g ?: "Все") },
                    )
                }
            }
            val shown = users.filter {
                (groupFilter == null || it.group.ifBlank { "без группы" } == groupFilter) &&
                    (q.isBlank() || it.name.contains(q.trim(), true) || it.email.contains(q.trim(), true) ||
                        it.group.replace(" ", "").contains(q.replace(" ", ""), true))
            }
            if (!loading) Text(
                "Показано: ${shown.size} из ${users.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            for (p in shown) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name.ifBlank { "Без имени" }, fontWeight = FontWeight.SemiBold)
                        Text(p.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "группа " + p.group.ifBlank { "не указана" } + " · " +
                                when (p.role) { ROLE_OWNER -> "владелец"; ROLE_ADMIN -> "админ"; else -> "пользователь" },
                            fontSize = 12.sp,
                            color = if (p.isAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!p.isAdmin) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(
                            checked = p.noAds,
                            onCheckedChange = { v -> change(p, { acc.setNoAds(p.uid, v) }, p.copy(noAds = v)) },
                        )
                        Text("без рекламы", fontSize = 10.sp)
                    }
                }
                if (me.isOwner && !p.isOwner) {
                    val makeAdmin = p.role != ROLE_ADMIN
                    OutlinedButton(
                        onClick = {
                            val r = if (makeAdmin) ROLE_ADMIN else ROLE_USER
                            change(p, { acc.setRole(p.uid, r) }, p.copy(role = r))
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text(if (makeAdmin) "Сделать админом" else "Снять админку") }
                }
            }
            Spacer(Modifier.navigationBarsPadding().padding(bottom = 32.dp))
        }
    }
}

// ---------------- админка: реклама ----------------

@Composable
fun AdsDialog(acc: AccountViewModel, current: AdConfig, onDismiss: () -> Unit) {
    var unit by remember { mutableStateOf(current.yandexUnitId) }
    var enabled by remember { mutableStateOf(current.bannerEnabled) }
    var text by remember { mutableStateOf(current.bannerText) }
    var image by remember { mutableStateOf(current.bannerImage) }
    var url by remember { mutableStateOf(current.bannerUrl) }
    var noAds by remember { mutableStateOf(current.noAdsInfo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Реклама") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Рекламная сеть Яндекса: вставьте ID рекламного блока (вида R-M-1234567-1) из кабинета partner.yandex.ru. " +
                        "Для проверки можно ввести demo-banner-yandex. Пусто — Яндекс выключен.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(unit, { unit = it }, label = { Text("ID блока Яндекса") }, singleLine = true)
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Свой баннер", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Switch(enabled, { enabled = it })
                }
                Text(
                    "Показывается, если Яндекс выключен или не нашёл рекламу.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(text, { text = it }, label = { Text("Текст баннера") })
                OutlinedTextField(image, { image = it }, label = { Text("Ссылка на картинку (необязательно)") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("Куда ведёт нажатие") }, singleLine = true)
                HorizontalDivider()
                OutlinedTextField(
                    noAds, { noAds = it },
                    label = { Text("Как отключить рекламу (текст для пользователей)") },
                    placeholder = { Text("Например: подписка 50 ₽/мес, пишите @nick") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                acc.saveAds(AdConfig(unit, enabled, text, image, url, noAds)); onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ---------------- рекламное место внизу экрана ----------------

@Composable
fun AdBanner(cfg: AdConfig, hidden: Boolean) {
    if (hidden) return
    var yandexFailed by remember(cfg.yandexUnitId) { mutableStateOf(false) }
    val own = cfg.bannerEnabled && (cfg.bannerText.isNotBlank() || cfg.bannerImage.isNotBlank())
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        when {
            cfg.yandexUnitId.isNotBlank() && !yandexFailed -> {
                HorizontalDivider()
                YandexBanner(cfg.yandexUnitId) { yandexFailed = true }
            }
            own -> {
                HorizontalDivider()
                OwnBanner(cfg)
            }
        }
    }
}

private var yandexInitDone = false

@Composable
private fun YandexBanner(unitId: String, onFail: () -> Unit) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            if (!yandexInitDone) {
                MobileAds.initialize(ctx) {}
                yandexInitDone = true
            }
            BannerAdView(ctx).apply {
                setAdUnitId(unitId)
                setAdSize(BannerAdSize.stickySize(ctx, widthDp))
                setBannerAdEventListener(object : BannerAdEventListener {
                    override fun onAdLoaded() {}
                    override fun onAdFailedToLoad(error: AdRequestError) = onFail()
                    override fun onAdClicked() {}
                    override fun onLeftApplication() {}
                    override fun onReturnedToApplication() {}
                    override fun onImpression(impressionData: ImpressionData?) {}
                })
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() },
    )
}

@Composable
private fun OwnBanner(cfg: AdConfig) {
    val uri = LocalUriHandler.current
    val bmp by produceState<Bitmap?>(null, cfg.bannerImage) {
        value = if (cfg.bannerImage.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(cfg.bannerImage).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    Surface(
        Modifier.fillMaxWidth().clickable(enabled = cfg.bannerUrl.isNotBlank()) {
            runCatching { uri.openUri(cfg.bannerUrl) }
        },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Реклама", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(8.dp))
            bmp?.let {
                Image(
                    it.asImageBitmap(), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = 56.dp).then(
                        if (cfg.bannerText.isBlank()) Modifier.weight(1f) else Modifier.width(96.dp)
                    ),
                )
                if (cfg.bannerText.isNotBlank()) Spacer(Modifier.width(10.dp))
            }
            if (cfg.bannerText.isNotBlank()) Text(
                cfg.bannerText, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
