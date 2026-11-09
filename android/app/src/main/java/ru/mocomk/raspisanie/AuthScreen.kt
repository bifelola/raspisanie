package ru.mocomk.raspisanie

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun AuthScreen(acc: AccountViewModel) {
    val busy by acc.busy.collectAsState()
    val error by acc.error.collectAsState()
    val info by acc.info.collectAsState()
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPolicy by remember { mutableStateOf(false) }
    val activity = LocalContext.current as Activity

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .systemBarsPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)),
                Alignment.Center,
            ) { Icon(Icons.Default.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
            Text(androidx.compose.ui.res.stringResource(R.string.app_name), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                if (register) "Создайте аккаунт, чтобы смотреть расписание и писать комментарии"
                else "Войдите, чтобы смотреть расписание и писать комментарии",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            OutlinedButton(
                onClick = { acc.signInGoogle(activity) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text("G", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color(0xFF4285F4))
                Spacer(Modifier.size(10.dp))
                Text("Войти через Google")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f))
                Text("  или по почте  ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.weight(1f))
            }

            if (register) OutlinedTextField(
                name, { name = it }, label = { Text("Имя (видно в комментариях)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                email, { email = it }, label = { Text("Почта") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("Пароль" + if (register) " (от 6 символов)" else "") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center) }

            Button(
                onClick = {
                    acc.info.value = null
                    if (register) acc.register(name, email, password) else acc.signIn(email, password)
                },
                enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(if (register) "Зарегистрироваться" else "Войти")
            }
            TextButton(onClick = { register = !register; acc.error.value = null }) {
                Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")
            }
            if (!register) TextButton(onClick = { acc.resetPassword(email) }, enabled = !busy) {
                Text("Забыли пароль?")
            }
            TextButton(onClick = { showPolicy = true }) {
                Text("Конфиденциальность", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showPolicy) PolicyDialog { showPolicy = false }
}

@Composable
fun PolicyDialog(onDismiss: () -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Конфиденциальность") },
        text = {
            Text(
                "Приложение хранит только то, что нужно для работы: почту и имя для входа, " +
                    "ваши комментарии и роль (пользователь или администратор). Данные хранятся в Google Firebase. " +
                    "Имя видно рядом с вашими комментариями, почту видят только администраторы. " +
                    "Пароль приложению не виден — его проверяет Google. " +
                    "Чтобы удалить аккаунт и данные, напишите администратору.\n\n" +
                    "В приложении может показываться реклама (Рекламная сеть Яндекса); " +
                    "рекламная сеть может собирать обезличенные данные об устройстве."
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
        dismissButton = {
            TextButton(onClick = { runCatching { uri.openUri(BuildConfig.BASE_URL + "privacy.html") } }) {
                Text("Полная политика")
            }
        },
    )
}

/** Шаг регистрации: выбор своей группы (для Google-входа и для входа по почте). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GroupPickScreen(vm: ScheduleViewModel, acc: AccountViewModel) {
    val s by vm.state.collectAsState()
    val busy by acc.busy.collectAsState()
    val error by acc.error.collectAsState()
    var q by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()
            .imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Text("Ваша группа", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Выберите группу, в которой учитесь. Её будет видно администраторам; расписание можно смотреть и для других групп.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val groups = s.index?.groups
        if (groups == null) {
            if (s.loading) Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
            else {
                Text(s.message ?: "Не удалось загрузить список групп", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.refresh(initial = true) }) { Text("Повторить") }
            }
        } else {
            OutlinedTextField(
                q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск, например 22 фо") },
            )
            val norm = q.replace(" ", "").lowercase()
            val byCourse = groups.filter { norm.isEmpty() || it.replace(" ", "").contains(norm) }
                .groupBy { if (it.firstOrNull()?.isDigit() == true) "${it[0]} курс" else "Другие" }
                .toSortedMap()
            for ((course, list) in byCourse) {
                Text(
                    course, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (g in list) {
                        androidx.compose.material3.Surface(
                            onClick = { if (!busy) { acc.setGroup(g); vm.selectGroup(g) } },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) { Text(g, Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { acc.signOut() }) { Text("Выйти из аккаунта") }
    }
}
