package com.example.proxycheker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.proxycheker.ui.theme.ProxyChekerTheme
import kotlinx.coroutines.launch

private val PingGreen = Color(0xFF57D66B)
private const val DefaultCheckTimeoutMs = 2500

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ProxyChekerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ProxyScreen()
                }
            }
        }
    }
}

private data class ProxyScreenState(
    val activeMode: ProxyMode? = null,
    val proxies: List<ProxyItem> = emptyList(),
    val isLoading: Boolean = false,
    val isChecking: Boolean = false,
    val statusMessage: String = "",
    val checkedCount: Int = 0,
    val totalToCheck: Int = 0,
)

private data class ProxySection(
    val group: ProxyGroup,
    val proxies: List<ProxyItem>,
)

@Composable
private fun ProxyScreen() {
    val context = LocalContext.current
    val repository = remember { ProxyRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(ProxyScreenState()) }
    var collapsedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }

    val sections = remember(uiState.proxies, uiState.activeMode) {
        ProxyGroup.entries
            .filter { it.mode == uiState.activeMode && it in ENABLED_PROXY_GROUPS }
            .mapNotNull { group ->
                val groupProxies = uiState.proxies.filter { it.group == group }
                if (groupProxies.isEmpty()) {
                    null
                } else {
                    ProxySection(
                        group = group,
                        proxies = groupProxies.sortedBy { it.latencyMs ?: Long.MAX_VALUE }
                    )
                }
            }
    }

    fun setIdleState(
        mode: ProxyMode?,
        proxies: List<ProxyItem>,
        message: String,
    ) {
        uiState = uiState.copy(
            activeMode = mode,
            proxies = proxies,
            isLoading = false,
            isChecking = false,
            statusMessage = message,
            checkedCount = 0,
            totalToCheck = 0,
        )
    }

    LaunchedEffect(Unit) {
        runCatching { repository.readCachedSelection() }
            .onSuccess { selection ->
                if (selection != null && selection.proxies.isNotEmpty()) {
                    setIdleState(
                        mode = selection.mode,
                        proxies = selection.proxies,
                        message = ""
                    )
                }
            }
            .onFailure { error ->
                uiState = uiState.copy(statusMessage = error.userFacingMessage())
            }
    }

    fun runCombinedCheck() {
        val mode = ProxyMode.TELEGRAM
        scope.launch {
            collapsedGroups = emptyList()
            uiState = uiState.copy(
                activeMode = mode,
                proxies = emptyList(),
                isLoading = true,
                isChecking = false,
                statusMessage = context.getString(R.string.loading_tg_status),
                checkedCount = 0,
                totalToCheck = 0,
            )

            val downloaded = runCatching { repository.downloadAndCacheProxies(mode) }
                .getOrElse { error ->
                    setIdleState(mode, emptyList(), error.userFacingMessage())
                    return@launch
                }

            Toast.makeText(
                context,
                context.resources.getQuantityString(
                    R.plurals.proxies_loaded,
                    downloaded.size,
                    downloaded.size
                ),
                Toast.LENGTH_SHORT
            ).show()

            uiState = uiState.copy(
                activeMode = mode,
                proxies = downloaded,
                isLoading = false,
                isChecking = true,
                checkedCount = 0,
                totalToCheck = downloaded.size,
                statusMessage = context.resources.getQuantityString(
                    R.plurals.checking_status,
                    downloaded.size,
                    0,
                    downloaded.size
                ),
            )

            runCatching {
                repository.keepReachableProxies(
                    source = downloaded,
                    mode = mode,
                    timeoutMs = DefaultCheckTimeoutMs
                ) { checked, total ->
                    uiState = uiState.copy(
                        checkedCount = checked,
                        totalToCheck = total,
                        statusMessage = context.resources.getQuantityString(
                            R.plurals.checking_status,
                            total,
                            checked,
                            total
                        )
                    )
                }
            }
                .onSuccess { working ->
                    val message = if (working.isEmpty()) {
                        context.getString(R.string.no_working_proxies)
                    } else {
                        context.resources.getQuantityString(
                            R.plurals.working_proxies_left,
                            working.size,
                            working.size,
                            downloaded.size
                        )
                    }
                    setIdleState(mode, working, message)
                }
                .onFailure { error ->
                    setIdleState(mode, downloaded, error.userFacingMessage())
                }
        }
    }

    fun clearAll() {
        scope.launch {
            runCatching {
                repository.clearAllCaches()
            }
                .onSuccess {
                    uiState = ProxyScreenState(
                        statusMessage = context.getString(R.string.clear_success)
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(
                        isLoading = false,
                        isChecking = false,
                        statusMessage = error.userFacingMessage(),
                        checkedCount = 0,
                        totalToCheck = 0,
                    )
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            HelpButton(onClick = { showAboutDialog = true })
        }

        Button(
            onClick = ::runCombinedCheck,
            enabled = !uiState.isLoading && !uiState.isChecking,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(text = stringResource(R.string.mtproto_check))
        }

        TextButton(
            onClick = ::clearAll,
            enabled = !uiState.isLoading && !uiState.isChecking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.clear))
        }

        StatusBlock(uiState = uiState)

        Text(
            text = context.resources.getQuantityString(
                R.plurals.proxies_in_list,
                uiState.proxies.size,
                uiState.proxies.size
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (uiState.proxies.isEmpty()) {
            EmptyListState(
                modifier = Modifier.weight(1f),
                message = stringResource(R.string.empty_state_hint)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                sections.forEach { section ->
                    item(key = "section_${section.group.name}") {
                        ProxySectionHeader(
                            section = section,
                            collapsed = section.group.name in collapsedGroups,
                            onToggle = {
                                collapsedGroups = if (section.group.name in collapsedGroups) {
                                    collapsedGroups - section.group.name
                                } else {
                                    collapsedGroups + section.group.name
                                }
                            }
                        )
                    }
                    if (section.group.name !in collapsedGroups) {
                        items(
                            items = section.proxies,
                            key = { it.uniqueKey }
                        ) { proxy ->
                            ProxyCard(
                                proxy = proxy,
                                onOpen = { openProxy(context, proxy) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text(text = stringResource(R.string.close))
                }
            },
            title = {
                Text(text = stringResource(R.string.app_about_title))
            },
            text = {
                Text(text = stringResource(R.string.app_about_body))
            }
        )
    }
}

@Composable
private fun HelpButton(
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.help_symbol),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ProxySectionHeader(
    section: ProxySection,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = section.group.sectionTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = section.proxies.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (collapsed) {
                    stringResource(R.string.expand)
                } else {
                    stringResource(R.string.collapse)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBlock(uiState: ProxyScreenState) {
    val hasProgress = uiState.isLoading || uiState.isChecking
    if (!hasProgress && uiState.statusMessage.isBlank()) {
        return
    }

    val progress = if (uiState.totalToCheck == 0) {
        0f
    } else {
        uiState.checkedCount.toFloat() / uiState.totalToCheck.toFloat()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyLarge
            )

            if (hasProgress) {
                if (uiState.isLoading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = stringResource(R.string.loading_wait),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                        )
                        Text(
                            text = stringResource(
                                R.string.checking_progress,
                                uiState.checkedCount,
                                uiState.totalToCheck
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyListState(
    modifier: Modifier = Modifier,
    message: String,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProxyCard(
    proxy: ProxyItem,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            proxy.latencyMs?.let { latency ->
                Text(
                    text = "${latency} ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = PingGreen
                )
            }
            Text(
                text = proxy.endpointLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(
                onClick = onOpen,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = stringResource(R.string.open_proxy))
            }
        }
    }
}

private fun openProxy(context: android.content.Context, proxy: ProxyItem) {
    val primaryIntent = Intent(Intent.ACTION_VIEW, Uri.parse(proxy.url))
    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(proxy.openUrl))

    try {
        context.startActivity(primaryIntent)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(fallbackIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_proxy_handler, Toast.LENGTH_SHORT).show()
        }
    }
}

private fun Throwable.userFacingMessage(): String {
    return message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить действие."
}
