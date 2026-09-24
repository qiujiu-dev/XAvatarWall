package app.xavatarwall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.xavatarwall.ui.CollectScreen
import app.xavatarwall.ui.HomeScreen
import app.xavatarwall.ui.LoginScreen
import app.xavatarwall.ui.SettingsScreen
import app.xavatarwall.ui.theme.XAWTheme
import app.xavatarwall.vm.MainViewModel
import app.xavatarwall.vm.Stage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XAWTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsState()
                var showSettings by remember { mutableStateOf(false) }

                // 当前所在界面（用枚举区分层级，方便做"前进 / 返回"方向性过渡动画）
                val currentScreen =
                    if (showSettings) Screen.SETTINGS else Screen.fromStage(state.stage)

                // 单处 BackHandler：开启 enableOnBackInvokedCallback 后，
                // 系统会在手指滑动时实时预览"预测性返回"动画，松手才真正触发下面的逻辑。
                BackHandler(enabled = currentScreen != Screen.HOME) {
                    when (currentScreen) {
                        Screen.SETTINGS -> showSettings = false
                        Screen.LOGIN -> vm.goHome()
                        Screen.COLLECT -> vm.goHome()
                        else -> { /* HOME 不拦截，交给系统（退出 App） */ }
                    }
                }

                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        val forward = targetState.order > initialState.order
                        if (forward) {
                            // 进入更深一层：新界面从右滑入，旧界面向左轻微视差滑出
                            (slideInHorizontally(
                                initialOffsetX = { it },
                                animationSpec = tween(320, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(320)))
                                .togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { -it / 3 },
                                        animationSpec = tween(320)
                                    ) + fadeOut(animationSpec = tween(320)))
                        } else {
                            // 返回上一层：旧界面向右滑出，新界面从左侧视差滑入
                            (slideInHorizontally(
                                initialOffsetX = { -it / 3 },
                                animationSpec = tween(320)
                            ) + fadeIn(animationSpec = tween(320)))
                                .togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = tween(320, easing = FastOutSlowInEasing)
                                    ) + fadeOut(animationSpec = tween(320)))
                        }
                    },
                    label = "screenTransition"
                ) { screen ->
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            notice = state.notice,
                            onStart = { vm.goToLogin() }
                        )

                        Screen.LOGIN -> LoginScreen(
                            statusText = state.message,
                            error = state.error,
                            busy = false,
                            onLoggedIn = { vm.onCookieReady() }
                        )

                        Screen.COLLECT -> CollectScreen(
                            username = state.me?.username.orEmpty(),
                            fans = state.fans,
                            running = state.running,
                            generating = state.generating,
                            genDone = state.genDone,
                            genTotal = state.genTotal,
                            preview = state.preview,
                            savedMessage = state.savedMessage,
                            message = state.message,
                            error = state.error,
                            settings = state.settings,
                            cachedCount = state.cachedCount,
                            hasGenerated = state.hasGenerated,
                            onStop = { vm.stopCollect() },
                            onStart = { vm.startCollect() },
                            onGenerate = { vm.generateWall() },
                            onOpenSettings = { showSettings = true },
                            onClearCache = { vm.clearAvatarCache() },
                            onLogout = { vm.backToLogin() }
                        )

                        Screen.SETTINGS -> SettingsScreen(
                            canRegenerate = state.fans.isNotEmpty() && !state.generating,
                            onBack = { showSettings = false },
                            onSaved = {
                                vm.refreshSettings()
                                showSettings = false
                            },
                            onSaveAndRegenerate = {
                                vm.refreshSettings()
                                showSettings = false
                                vm.generateWall()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 界面层级，order 越大代表越"深"，用于决定过渡方向 */
private enum class Screen(val order: Int) {
    HOME(0), LOGIN(1), COLLECT(2), SETTINGS(3);

    companion object {
        fun fromStage(stage: Stage): Screen = when (stage) {
            Stage.HOME -> HOME
            Stage.LOGIN -> LOGIN
            Stage.COLLECT -> COLLECT
        }
    }
}
