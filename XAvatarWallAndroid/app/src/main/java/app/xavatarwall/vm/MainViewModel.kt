package app.xavatarwall.vm

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.xavatarwall.data.Fan
import app.xavatarwall.data.Me
import app.xavatarwall.data.Artwork
import app.xavatarwall.data.Notice
import app.xavatarwall.data.NoticeRepository
import app.xavatarwall.data.SettingsStore
import app.xavatarwall.data.WallSettings
import app.xavatarwall.data.XApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Stage { HOME, LOGIN, COLLECT }

data class UiState(
    val stage: Stage = Stage.HOME,
    val cookie: String = "",
    val me: Me? = null,
    val fans: List<Fan> = emptyList(),
    val running: Boolean = false,
    val message: String = "请在下方登录 X，登录完成后点右上角「我登录好了」",
    val error: String? = null,
    val pages: Int = 0,
    val generating: Boolean = false,
    val genDone: Int = 0,
    val genTotal: Int = 0,
    val preview: Bitmap? = null,
    val savedMessage: String? = null,
    val reportedCount: Int = -1,

    /** 当前个性化设置（界面上用来显示摘要） */
    val settings: WallSettings = WallSettings(),
    /** 已经缓存到本地的头像数量 */
    val cachedCount: Int = 0,
    /** 本次会话是否已经生成过一次（决定按钮显示「生成」还是「重新生成」） */
    val hasGenerated: Boolean = false,
    /** 从服务器拉来的公告 */
    val notice: Notice? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val api = XApi()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var collectJob: Job? = null
    private var generateJob: Job? = null
    private val seenIds = HashSet<String>()

    init {
        _state.update { it.copy(settings = SettingsStore.load(getApplication())) }
        loadNotice()
    }

    /** 拉一次远程公告，失败就静默留空 */
    fun loadNotice() {
        viewModelScope.launch {
            _state.update { it.copy(notice = NoticeRepository.fetch()) }
        }
    }

    /** 主界面点「开始制作」→ 进入登录 */
    fun goToLogin() {
        _state.update { it.copy(stage = Stage.LOGIN) }
    }

    /** 系统返回键：从登录 / 采集页退回主界面 */
    fun goHome() {
        stopCollect()
        _state.update {
            it.copy(
                stage = Stage.HOME,
                error = null,
                message = "已返回主界面"
            )
        }
    }

    /** 重新读取个性化设置（设置页保存后调用） */
    fun refreshSettings() {
        _state.update { it.copy(settings = SettingsStore.load(getApplication())) }
    }

    /** 统计已缓存头像数，用于提示「重新生成不会重新下载」 */
    fun refreshCacheInfo() {
        viewModelScope.launch {
            val fans = _state.value.fans
            val count = if (fans.isEmpty()) 0 else Artwork.cachedCount(getApplication(), fans)
            _state.update { it.copy(cachedCount = count) }
        }
    }

    /** 清掉头像缓存，下次生成才会真正重新下载 */
    fun clearAvatarCache() {
        viewModelScope.launch(Dispatchers.IO) {
            Artwork.clearCache(getApplication())
            _state.update {
                it.copy(cachedCount = 0, hasGenerated = false, message = "已清空头像缓存，下次生成会重新下载")
            }
        }
    }

    /** 从 WebView 读到 cookie 后调用 */
    fun onCookieReady() {
        viewModelScope.launch {
            val cookie = api.readCookie()
            if (!api.hasAuth(cookie)) {
                _state.update {
                    it.copy(error = "还没检测到登录状态，请先在页面里完成登录")
                }
                return@launch
            }
            _state.update {
                it.copy(cookie = cookie, error = null, message = "正在读取账号信息…")
            }
            try {
                val me = api.fetchMe(cookie)
                _state.update {
                    it.copy(
                        me = me,
                        stage = Stage.COLLECT,
                        message = "登录成功，开始抓取粉丝头像",
                        error = null
                    )
                }
                startCollect()
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = e.message ?: "读取账号信息失败", message = "请重试或重新登录")
                }
            }
        }
    }

    fun startCollect() {
        if (collectJob?.isActive == true) return
        val cookie = _state.value.cookie
        val me = _state.value.me ?: return
        if (cookie.isEmpty()) return

        collectJob = viewModelScope.launch {
            _state.update { it.copy(running = true, error = null, message = "开始抓取粉丝…") }
            var cursor: String? = null
            var page = 0
            var useGraphQl = true

            // 先刷新 GraphQL 的 queryId（X 会定期更换）
            _state.update { it.copy(message = "正在准备采集通道…") }
            api.refreshQueryIds()

            try {
                while (isActive) {
                    page++
                    val result: app.xavatarwall.data.FollowersPage = try {
                        if (useGraphQl) {
                            try {
                                api.fetchFollowersGql(me.id, cookie, cursor, _state.value.fans.size + 1)
                            } catch (e: XApi.RateLimitException) {
                                throw e
                            } catch (e: Exception) {
                                // GraphQL 不通就退回 v1.1 列表接口
                                Log.w("XAW", "GraphQL followers failed, fallback to v1.1: ${e.message}")
                                useGraphQl = false
                                api.fetchFollowers(me.id, cookie, cursor, _state.value.fans.size + 1)
                            }
                        } else {
                            api.fetchFollowers(me.id, cookie, cursor, _state.value.fans.size + 1)
                        }
                    } catch (e: XApi.RateLimitException) {
                        val until = e.resetAtMillis
                        if (until == null) throw e
                        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        val waitMs = (until - System.currentTimeMillis()).coerceAtLeast(30_000L)
                        _state.update {
                            it.copy(message = "触发接口限速，等待到 ${fmt.format(Date(until))} 后继续")
                        }
                        delay(waitMs)
                        continue
                    }

                    val fresh = result.users.filter { seenIds.add(it.id) }
                    if (fresh.isNotEmpty()) {
                        _state.update {
                            it.copy(
                                fans = it.fans + fresh,
                                pages = page,
                                message = "已获取 ${it.fans.size + fresh.size} 人"
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(pages = page, message = "已获取 ${it.fans.size} 人（本页无新增）")
                        }
                    }

                    cursor = result.nextCursor
                    if (cursor.isNullOrEmpty() || cursor == "0") {
                        _state.update { it.copy(message = "正在核对粉丝总数…") }
                        reconcile(me, cookie)
                        break
                    }
                    // 礼貌间隔，避免触发风控（GraphQL 通道稍快一点）
                    delay(if (useGraphQl) 800 else 1200)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(running = false, error = e.message ?: "抓取失败")
                }
            }
        }
    }

    /**
     * 采集完成后核对总数：
     * 1. 读 X 报告的粉丝总数
     * 2. 用 ids 接口拉全部粉丝 ID（包含资料不可见的账号）
     * 3. 缺失的 ID 尝试批量补资料，补不到的保留占位，保证墙上仍有它的位置
     */
    private suspend fun reconcile(me: Me, cookie: String) {
        var reported = -1
        try {
            reported = api.fetchFollowersCount(me.username, cookie)
        } catch (e: Exception) {
            Log.w("XAW", "followers count failed: ${e.message}")
        }

        var allIds = emptyList<String>()
        try {
            allIds = api.fetchFollowerIds(me.id, cookie)
        } catch (e: Exception) {
            Log.w("XAW", "ids failed: ${e.message}")
        }

        val missing = allIds.filter { !seenIds.contains(it) }
        if (missing.isNotEmpty()) {
            _state.update {
                it.copy(message = "发现 ${missing.size} 个账号资料缺失，正在尝试补全…")
            }
            val extra = try {
                api.fetchUsersByRestIds(missing, cookie)
            } catch (e: Exception) {
                Log.w("XAW", "restIds failed: ${e.message}")
                emptyList()
            }
            val freshExtra = extra.filter { seenIds.add(it.id) }
            if (freshExtra.isNotEmpty()) {
                _state.update { state ->
                    val base = state.fans.size
                    state.copy(
                        fans = state.fans + freshExtra.mapIndexed { i, f ->
                            f.copy(index = base + i + 1)
                        }
                    )
                }
            }

            val stillMissing = missing.filter { !seenIds.contains(it) }
            if (stillMissing.isNotEmpty()) {
                _state.update { state ->
                    val base = state.fans.size
                    state.copy(
                        fans = state.fans + stillMissing.mapIndexed { i, id ->
                            Fan(
                                id = id,
                                username = "",
                                name = "账号已不可见（冻结或停用）",
                                avatar = "",
                                index = base + i + 1
                            )
                        }
                    )
                }
            }
        }

        _state.update { state ->
            val withAvatar = state.fans.count { it.avatar.isNotEmpty() }
            val invisible = state.fans.size - withAvatar
            val text = buildString {
                if (reported > 0) append("X 报告 $reported 人 · ")
                append("获取到 $withAvatar 个头像")
                if (invisible > 0) append("，$invisible 个账号已不可见")
            }
            state.copy(running = false, reportedCount = reported, message = text)
        }
        // 抓取名单结束后，看看本地还留着多少张已经下载过的头像
        refreshCacheInfo()
    }

    fun stopCollect() {
        collectJob?.cancel()
        collectJob = null
        _state.update {
            it.copy(running = false, message = "已停止，共获取 ${it.fans.size} 人")
        }
    }

    /** 点击「生成头像墙」后才开始下载头像并绘制图片 */
    fun generateWall() {
        if (generateJob?.isActive == true) return
        val fans = _state.value.fans
        if (fans.isEmpty()) {
            _state.update { it.copy(error = "还没有粉丝数据，先完成抓取") }
            return
        }

        generateJob = viewModelScope.launch {
            // 先看有多少张已经缓存好了，据此决定提示文案
            val cachedBefore = Artwork.cachedCount(getApplication(), fans)
            val reuseHint = cachedBefore > 0

            _state.update {
                it.copy(
                    generating = true,
                    error = null,
                    savedMessage = null,
                    preview = null,
                    genDone = 0,
                    genTotal = fans.size,
                    message = if (reuseHint) "正在生成 0/${fans.size}"
                    else "正在下载头像 0/${fans.size}"
                )
            }

            val settings = SettingsStore.load(getApplication())
            val invisible = fans.count { it.avatar.isEmpty() }

            val result = try {
                Artwork.generate(
                    context = getApplication(),
                    fans = fans,
                    settings = settings,
                    invisibleCount = invisible
                ) { done, total ->
                    _state.update {
                        it.copy(
                            genDone = done,
                            genTotal = total,
                            message = if (reuseHint) "正在生成 $done/$total"
                            else "正在下载头像 $done/$total"
                        )
                    }
                }
            } catch (e: Exception) {
                Artwork.GenerateResult(null, null, e.message ?: "生成失败")
            }

            val cachedTotal = Artwork.cachedCount(getApplication(), fans)
            _state.update {
                it.copy(
                    generating = false,
                    preview = result.preview,
                    savedMessage = result.savedMessage,
                    error = result.error,
                    cachedCount = cachedTotal,
                    hasGenerated = result.error == null && result.preview != null,
                    message = result.error ?: (result.savedMessage ?: "生成完成")
                )
            }
        }
    }

    fun backToLogin() {
        stopCollect()
        _state.update {
            UiState(
                stage = Stage.LOGIN,
                message = "请重新登录 X",
                settings = SettingsStore.load(getApplication())
            )
        }
        seenIds.clear()
    }
}
