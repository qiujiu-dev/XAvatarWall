package app.xavatarwall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.xavatarwall.data.SettingsStore
import app.xavatarwall.data.WallSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    canRegenerate: Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onSaveAndRegenerate: () -> Unit
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }

    fun saveOnly() {
        SettingsStore.save(context, settings)
        onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个性化设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { saveOnly() }) { Text("保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OutlinedTextField(
                value = settings.title,
                onValueChange = { settings = settings.copy(title = it) },
                label = { Text("顶部标题") },
                supportingText = { Text("XX 会自动变成头像数量") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = settings.extraText,
                onValueChange = { settings = settings.copy(extraText = it) },
                label = { Text("图片下方文字") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            ChipRow(
                label = "背景风格",
                options = WallSettings.BACKGROUND_STYLES,
                selected = settings.bgStyle,
                onSelect = { settings = settings.copy(bgStyle = it) }
            )

            ColorRow(
                label = "背景颜色",
                value = settings.bgColor,
                onSelect = { settings = settings.copy(bgColor = it) }
            )

            ColorRow(
                label = "渐变起始色",
                value = settings.colorA,
                onSelect = { settings = settings.copy(colorA = it) }
            )

            ColorRow(
                label = "渐变结束色",
                value = settings.colorB,
                onSelect = { settings = settings.copy(colorB = it) }
            )

            ChipRow(
                label = "头像形状",
                options = WallSettings.SHAPES,
                selected = settings.shape,
                onSelect = { settings = settings.copy(shape = it) }
            )

            ColorRow(
                label = "标题颜色",
                value = settings.titleColor,
                onSelect = { settings = settings.copy(titleColor = it) }
            )

            ChipRow(
                label = "网格间距",
                options = WallSettings.GAPS,
                selected = settings.gap,
                onSelect = { settings = settings.copy(gap = it) }
            )

            if (canRegenerate) {
                Button(
                    onClick = {
                        SettingsStore.save(context, settings)
                        onSaveAndRegenerate()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存并重新生成")
                }
                OutlinedButton(
                    onClick = { saveOnly() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("只保存，先不生成")
                }
            } else {
                Button(
                    onClick = { saveOnly() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存设置")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) }
                )
            }
        }
    }
}

@Composable
private fun ColorRow(
    label: String,
    value: String,
    onSelect: (String) -> Unit
) {
    var hexText by remember(value) { mutableStateOf(value) }

    Column {
        Text(
            text = "$label · $value",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WallSettings.PALETTE.forEach { hex ->
                val color = try {
                    Color(android.graphics.Color.parseColor(hex))
                } catch (e: Exception) {
                    Color.Gray
                }
                val isSelected = hex.equals(value, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                        .clickable { onSelect(hex) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = hexText,
                onValueChange = { raw ->
                    hexText = raw
                    normalizeHex(raw)?.let { onSelect(it) }
                },
                label = { Text("自定义色值") },
                placeholder = { Text("#DBEAFE") },
                singleLine = true,
                isError = hexText.isNotBlank() && normalizeHex(hexText) == null,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        normalizeHex(hexText)?.let {
                            Color(android.graphics.Color.parseColor(it))
                        } ?: Color.Gray
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }
    }
}

/** 把用户输入的色值整理成 #RRGGBB，不合法返回 null */
private fun normalizeHex(raw: String): String? {
    var s = raw.trim().removePrefix("#")
    if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
    if (s.length != 6) return null
    if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return "#" + s.uppercase()
}
