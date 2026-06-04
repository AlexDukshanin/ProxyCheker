package com.example.proxycheker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TELEGRAM_ACCEPTABLE_PING_MS = 2_500L
private const val TELEGRAM_RESULT_TIMEOUT_MS = 4_500L
private const val TELEGRAM_CLICK_COOLDOWN_MS = 500L
private const val TELEGRAM_POST_CONNECT_SETTLE_MS = 900L

private val TELEGRAM_CHECK_ACTION_TEXTS = listOf(
    "Check",
    "CHECK",
    "Retry",
    "RETRY",
    "Проверить",
    "Повторить"
)

private val TELEGRAM_CONNECT_ACTION_TEXTS = listOf(
    "Connect Proxy",
    "CONNECT PROXY",
    "Use Proxy",
    "USE PROXY",
    "Enable Proxy",
    "ENABLE PROXY",
    "Подключить прокси",
    "Использовать прокси",
    "Включить прокси",
    "Connect",
    "CONNECT"
)

private val TELEGRAM_SUCCESS_TEXTS = listOf(
    "Connected",
    "CONNECTED",
    "Available",
    "AVAILABLE",
    "Done",
    "Подключено",
    "Доступно",
    "Готово"
)

private val TELEGRAM_FAILURE_TEXTS = listOf(
    "Failed",
    "FAILED",
    "Unavailable",
    "UNAVAILABLE",
    "Error",
    "ERROR",
    "Timed out",
    "TIMEOUT",
    "Не удалось",
    "Недоступно",
    "Ошибка",
    "Таймаут"
)

data class TelegramAutomationState(
    val serviceReady: Boolean = false,
    val isRunning: Boolean = false,
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
    val message: String = "",
    val remainingProxies: List<ProxyItem> = emptyList(),
    val completedRunId: Long = 0L,
)

class TelegramAutomationService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: ProxyRepository

    private var queue = ArrayDeque<ProxyItem>()
    private var survivors = mutableListOf<ProxyItem>()
    private var currentProxy: ProxyItem? = null
    private var processedCount = 0
    private var totalCount = 0
    private var lastClickAtMs = 0L
    private var timeoutJob: Job? = null
    private var connectRequestedAtMs = 0L
    private var lastSuccessfulLatencyMs: Long? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = ProxyRepository(applicationContext)
        instance = this
        _state.value = _state.value.copy(serviceReady = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!_state.value.isRunning) {
            return
        }

        val packageName = event?.packageName?.toString().orEmpty()
        if (!packageName.isTelegramPackage()) {
            return
        }

        handleTelegramScreen()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        timeoutJob?.cancel()
        instance = null
        _state.value = _state.value.copy(
            serviceReady = false,
            isRunning = false
        )
        super.onDestroy()
    }

    private fun startSession(proxies: List<ProxyItem>): Boolean {
        if (_state.value.isRunning || proxies.isEmpty()) {
            return false
        }

        queue = ArrayDeque(
            proxies.filter { it.mode == ProxyMode.TELEGRAM }
                .sortedWith(compareBy<ProxyItem>({ it.group.sortOrder }, { it.latencyMs ?: Long.MAX_VALUE }))
        )
        survivors = mutableListOf()
        currentProxy = null
        processedCount = 0
        totalCount = queue.size
        lastClickAtMs = 0L
        connectRequestedAtMs = 0L
        lastSuccessfulLatencyMs = null

        if (totalCount == 0) {
            return false
        }

        _state.value = TelegramAutomationState(
            serviceReady = true,
            isRunning = true,
            checkedCount = 0,
            totalCount = totalCount,
            message = "Проверка в Telegram: 0 из $totalCount",
            remainingProxies = queue.toList()
        )

        launchNextProxy()
        return true
    }

    private fun launchNextProxy() {
        timeoutJob?.cancel()

        if (queue.isEmpty()) {
            finishSession()
            return
        }

        val nextProxy = queue.removeFirst()
        currentProxy = nextProxy
        connectRequestedAtMs = 0L
        lastSuccessfulLatencyMs = null
        _state.value = _state.value.copy(
            isRunning = true,
            checkedCount = processedCount,
            totalCount = totalCount,
            message = "Открываю в Telegram: ${processedCount + 1} из $totalCount"
        )

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(nextProxy.url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)

        timeoutJob = serviceScope.launch {
            delay(TELEGRAM_RESULT_TIMEOUT_MS)
            if (_state.value.isRunning && currentProxy?.uniqueKey == nextProxy.uniqueKey) {
                completeCurrentProxy(success = false, telegramLatencyMs = null)
            }
        }
    }

    private fun handleTelegramScreen() {
        val root = rootInActiveWindow ?: return
        val proxy = currentProxy ?: return
        val now = SystemClock.uptimeMillis()

        val telegramLatencyMs = root.extractLatencyMs()
        if (telegramLatencyMs != null) {
            lastSuccessfulLatencyMs = telegramLatencyMs

            if (telegramLatencyMs > TELEGRAM_ACCEPTABLE_PING_MS) {
                completeCurrentProxy(success = false, telegramLatencyMs = telegramLatencyMs)
                return
            }

            if (connectRequestedAtMs == 0L) {
                if (now - lastClickAtMs < TELEGRAM_CLICK_COOLDOWN_MS) {
                    return
                }

                val clickedConnect = root.clickFirstMatchingNode(TELEGRAM_CONNECT_ACTION_TEXTS)
                if (clickedConnect) {
                    lastClickAtMs = now
                    connectRequestedAtMs = now
                    _state.value = _state.value.copy(
                        message = "Подключаю прокси в Telegram: ${processedCount + 1} из $totalCount"
                    )
                    return
                }

                completeCurrentProxy(
                    success = true,
                    telegramLatencyMs = telegramLatencyMs
                )
                return
            }

            if (now - connectRequestedAtMs >= TELEGRAM_POST_CONNECT_SETTLE_MS) {
                completeCurrentProxy(
                    success = true,
                    telegramLatencyMs = telegramLatencyMs
                )
            }
            return
        }

        if (root.containsAnyText(TELEGRAM_FAILURE_TEXTS)) {
            completeCurrentProxy(success = false, telegramLatencyMs = null)
            return
        }

        if (root.containsAnyText(TELEGRAM_SUCCESS_TEXTS)) {
            completeCurrentProxy(
                success = true,
                telegramLatencyMs = lastSuccessfulLatencyMs ?: proxy.latencyMs
            )
            return
        }

        if (now - lastClickAtMs < TELEGRAM_CLICK_COOLDOWN_MS) {
            return
        }

        if (connectRequestedAtMs == 0L && root.clickFirstMatchingNode(TELEGRAM_CHECK_ACTION_TEXTS)) {
            lastClickAtMs = now
            _state.value = _state.value.copy(
                message = "Жду результат в Telegram: ${processedCount + 1} из $totalCount"
            )
        }
    }

    private fun completeCurrentProxy(success: Boolean, telegramLatencyMs: Long?) {
        timeoutJob?.cancel()

        val proxy = currentProxy ?: return
        currentProxy = null
        processedCount += 1
        connectRequestedAtMs = 0L

        if (success) {
            survivors += proxy.copy(latencyMs = telegramLatencyMs ?: proxy.latencyMs)
        }

        _state.value = _state.value.copy(
            isRunning = true,
            checkedCount = processedCount,
            totalCount = totalCount,
            message = "Проверка в Telegram: $processedCount из $totalCount"
        )

        serviceScope.launch {
            returnToHostApp()
            delay(450)
            launchNextProxy()
        }
    }

    private fun finishSession() {
        val sortedSurvivors = survivors.sortedWith(
            compareBy<ProxyItem>({ it.group.sortOrder }, { it.latencyMs ?: Long.MAX_VALUE })
        )

        serviceScope.launch(Dispatchers.IO) {
            repository.persistProxies(ProxyMode.TELEGRAM, sortedSurvivors)
        }

        _state.value = TelegramAutomationState(
            serviceReady = true,
            isRunning = false,
            checkedCount = processedCount,
            totalCount = totalCount,
            message = if (sortedSurvivors.isEmpty()) {
                "Telegram не подтвердил ни один прокси."
            } else {
                "Telegram подтвердил ${sortedSurvivors.size} из $totalCount."
            },
            remainingProxies = sortedSurvivors,
            completedRunId = SystemClock.elapsedRealtime()
        )

        serviceScope.launch {
            returnToHostApp()
        }
    }

    private fun returnToHostApp() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(launchIntent)
    }

    companion object {
        private val _state = MutableStateFlow(TelegramAutomationState())
        val state = _state.asStateFlow()

        private var instance: TelegramAutomationService? = null

        fun isReady(): Boolean = instance != null

        fun startAutomation(proxies: List<ProxyItem>): Boolean {
            return instance?.startSession(proxies) ?: false
        }

        fun accessibilitySettingsIntent(): Intent {
            return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}

private fun AccessibilityNodeInfo.containsAnyText(candidates: List<String>): Boolean {
    return collectTexts().any { text ->
        candidates.any { candidate ->
            text.contains(candidate, ignoreCase = true)
        }
    }
}

private fun AccessibilityNodeInfo.extractLatencyMs(): Long? {
    return collectTexts()
        .flatMap { text -> LATENCY_REGEX.findAll(text).map { it.groupValues[1] }.toList() }
        .mapNotNull { it.toLongOrNull() }
        .minOrNull()
}

private fun AccessibilityNodeInfo.clickFirstMatchingNode(candidates: List<String>): Boolean {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue += this

    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        val nodeText = buildString {
            append(node.text?.toString().orEmpty())
            append(' ')
            append(node.contentDescription?.toString().orEmpty())
        }.trim()

        if (nodeText.isNotBlank() && candidates.any { candidate -> nodeText.contains(candidate, ignoreCase = true) }) {
            var clickableNode: AccessibilityNodeInfo? = node
            while (clickableNode != null) {
                if (clickableNode.isClickable && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                clickableNode = clickableNode.parent
            }
        }

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let(queue::addLast)
        }
    }

    return false
}

private fun AccessibilityNodeInfo.collectTexts(): List<String> {
    val texts = mutableListOf<String>()
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue += this

    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(texts::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(texts::add)

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let(queue::addLast)
        }
    }

    return texts
}

private fun String.isTelegramPackage(): Boolean {
    return contains("telegram", ignoreCase = true) || this == "org.telegram.messenger"
}

private val LATENCY_REGEX = Regex("""\b(\d{1,4})\s*(?:ms|мс)\b""", RegexOption.IGNORE_CASE)
