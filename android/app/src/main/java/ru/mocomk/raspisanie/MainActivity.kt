package ru.mocomk.raspisanie

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    private val vm: ScheduleViewModel by viewModels()
    private val account: AccountViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        UpdateWorker.schedule(this)
        Reminders.schedule(this)
        TodayWidget.refresh(this)
        setContent {
            AppTheme {
                val auth by account.auth.collectAsState()
                val a = auth
                when {
                    a == AuthState.Loading -> LoadingScreen()
                    a == AuthState.SignedOut -> AuthScreen(account)
                    // при регистрации обязательно выбрать свою группу
                    a is AuthState.SignedIn && a.profile.group.isBlank() -> GroupPickScreen(vm, account)
                    else -> {
                        // на новом телефоне сразу открываем расписание своей группы
                        val my = (a as? AuthState.SignedIn)?.profile?.group
                        LaunchedEffect(my) {
                            if (!my.isNullOrBlank() && vm.repo.prefs.group == null) vm.selectGroup(my)
                        }
                        ScheduleScreen(vm, account) // вошёл, или Firebase ещё не подключён
                    }
                }
            }
        }
    }
}
