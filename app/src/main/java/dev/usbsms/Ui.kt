package dev.usbsms

import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Screen(
    status: String,
    connected: Boolean,
    busy: Boolean,
    tele: Telemetry,
    netMode: Int?,
    modeLoading: Boolean,
    console: String,
    backup: ConfigBackup?,
    identity: Identity?,
    ifaces: List<IfaceInfo>,
    atIf: Int,
    sms: List<Sms>,
    storages: List<Storage>,
    supported: List<String>,
    current: String,
    snackbar: SnackbarHostState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onPickStorage: (String) -> Unit,
    onSend: (String, String) -> Unit,
    onDeleteOne: (Int) -> Unit,
    onDeleteBulk: (Int) -> Unit,
    onSetMode: (Int) -> Unit,
    onReadMode: () -> Unit,
    onRunAt: (String) -> Unit,
    onClearConsole: () -> Unit,
    onRefreshIfaces: () -> Unit,
    onCopied: (String) -> Unit,
    onRestore: () -> Unit,
    onSaveBackup: () -> Unit,
) {
    var number by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmFlag by remember { mutableStateOf<Int?>(null) }
    var modeSheet by remember { mutableStateOf(false) }
    var confirmMode by remember { mutableStateOf<NetMode?>(null) }
    var consoleOpen by remember { mutableStateOf(false) }
    var ifaceOpen by remember { mutableStateOf(false) }

    confirmFlag?.let { flag ->
        AlertDialog(
            containerColor = Panel,
            titleContentColor = TextHi,
            textContentColor = TextLo,
            onDismissRequest = { confirmFlag = null },
            title = { Text(if (flag == 4) "删除全部短信" else "删除已读短信") },
            text = {
                Text(
                    if (flag == 4) "模块存储区会被清空，无法恢复。"
                    else "已读短信会被删除，未读的保留。"
                )
            },
            confirmButton = {
                TextButton(onClick = { onDeleteBulk(flag); confirmFlag = null }) {
                    Text("删除", color = Alert)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFlag = null }) { Text("取消", color = TextLo) }
            },
        )
    }

    if (ifaceOpen) {
        IfaceDialog(ifaces, onRefreshIfaces) { ifaceOpen = false }
    }

    if (consoleOpen) {
        ConsoleDialog(console, busy, onRunAt, onClearConsole, onCopied) { consoleOpen = false }
    }

    if (modeSheet) {
        ModeDialog(
            current = netMode,
            loading = modeLoading,
            busy = busy,
            backup = backup,
            identity = identity,
            onReload = onReadMode,
            onRestore = onRestore,
            onSaveBackup = onSaveBackup,
            onDismiss = { modeSheet = false },
            onPick = { m -> modeSheet = false; confirmMode = m },
        )
    }

    confirmMode?.let { m ->
        AlertDialog(
            containerColor = Panel,
            titleContentColor = TextHi,
            textContentColor = TextLo,
            onDismissRequest = { confirmMode = null },
            title = { Text("切换到 ${m.name}？") },
            text = {
                Column {
                    Text("模块会立即重启，约 30 秒后重新连接。")
                    Spacer(Modifier.height(10.dp))
                    if (m.risky) {
                        Text(
                            "这个模式有风险：部分固件切过去后 AT 口会消失，" +
                                "届时本 App 无法再切回来，只能接电脑用 Linux 恢复。",
                            color = Alert,
                        )
                    } else {
                        Text(
                            "四种模式都保留 AT 串口，只是位置可能变化。" +
                                "本 App 连接时会自动遍历接口找回来，可以随时切回。",
                            color = TextLo,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSetMode(m.code); confirmMode = null }) {
                    Text("切换", color = if (m.risky) Alert else Amber)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMode = null }) { Text("取消", color = TextLo) }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)          // 平板/折叠屏上不要拉太宽
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {

            Header(connected, busy, onConnect, onRefresh, menuOpen,
                { menuOpen = it }, { confirmFlag = it }, { modeSheet = true; onReadMode() },
                { consoleOpen = true },
                { ifaceOpen = true; onRefreshIfaces() })

            TelemetryStrip(connected, tele, status, netMode, atIf)

            if (connected && supported.isNotEmpty()) {
                StorageRow(supported, storages, current, busy, onPickStorage)
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))

            if (!connected) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("未连接模块", color = TextLo, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "连接时会自动遍历所有接口探测 AT 口，" +
                            "切换过 USB 模式导致串口移位也能找回来。" +
                            "刚重启过模块的话可能要等一会儿。",
                        style = Readout, color = Hairline,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Pill("连接", Amber, Ink, onClick = onConnect)
                }
            } else if (sms.isEmpty()) {
                Empty(connected, current, Modifier.weight(1f))
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(sms, key = { it.index }) { m ->
                        MessageCard(m, busy, onDeleteOne, onCopied)
                    }
                }
            }

            Composer(
                number, { number = it }, text, { text = it },
                enabled = connected && !busy,
                onSend = { onSend(number, text); text = "" },
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

// ---------- 顶栏 ----------

@Composable
private fun Header(
    connected: Boolean,
    busy: Boolean,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    menuOpen: Boolean,
    setMenu: (Boolean) -> Unit,
    setConfirm: (Int) -> Unit,
    openModes: () -> Unit,
    openConsole: () -> Unit,
    openIfaces: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinkDot(connected)
        Spacer(Modifier.width(10.dp))
        Text(
            "MODEM SMS",
            color = TextHi,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            modifier = Modifier.weight(1f),
        )

        if (!connected) {
            Pill("连接", Amber, Ink, onClick = onConnect)
        } else {
            Pill("刷新", PanelHi, TextHi, enabled = !busy, onClick = onRefresh)
            Spacer(Modifier.width(6.dp))
            Box {
                Pill("···", PanelHi, TextHi, enabled = !busy) { setMenu(true) }
                DropdownMenu(
                    menuOpen,
                    onDismissRequest = { setMenu(false) },
                    modifier = Modifier.background(Panel),
                ) {
                    DropdownMenuItem(
                        text = { Text("USB 模式", color = TextHi) },
                        onClick = { setMenu(false); openModes() },
                    )
                    DropdownMenuItem(
                        text = { Text("AT 控制台", color = TextHi) },
                        onClick = { setMenu(false); openConsole() },
                    )
                    DropdownMenuItem(
                        text = { Text("USB 接口一览", color = TextHi) },
                        onClick = { setMenu(false); openIfaces() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除已读", color = TextHi) },
                        onClick = { setMenu(false); setConfirm(1) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除全部", color = Alert) },
                        onClick = { setMenu(false); setConfirm(4) },
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

/** 在线时缓慢呼吸的指示点 */
@Composable
private fun LinkDot(on: Boolean) {
    val a by rememberInfiniteTransition("dot").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier
            .size(9.dp)
            .background(
                if (on) Signal.copy(alpha = a) else Hairline,
                CircleShape,
            )
    )
}

// ---------- 遥测行 ----------

@Composable
private fun TelemetryStrip(
    connected: Boolean,
    t: Telemetry,
    status: String,
    netMode: Int?,
    atIf: Int,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (connected) {
            SignalBars(t.bars)
            Spacer(Modifier.width(8.dp))
            val parts = buildList {
                add(t.carrier.ifBlank { "未知运营商" })
                t.dbm?.let { add("${it}dBm") }
                if (!t.registered) add("未注册")
                if (t.smsc.isBlank()) add("无短信中心")
                NET_MODES.firstOrNull { it.code == netMode }?.let { add(it.name) }
                if (atIf >= 0) add("IF$atIf")
                add("v${BuildConfig.VERSION_NAME}")
            }
            Text(
                parts.joinToString("  ·  "),
                style = Readout,
                color = if (!t.registered || t.smsc.isBlank()) Alert else TextLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                "$status  ·  v${BuildConfig.VERSION_NAME}",
                style = Readout, color = TextLo, maxLines = 3,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SignalBars(level: Int) {
    Canvas(Modifier.size(width = 20.dp, height = 12.dp)) {
        val n = 5
        val unit = size.width / (n * 2 - 1)
        for (i in 0 until n) {
            val h = size.height * (0.3f + 0.175f * i)
            drawRect(
                color = if (i < level) Signal else Hairline,
                topLeft = Offset(i * unit * 2, size.height - h),
                size = Size(unit, h),
            )
        }
    }
}

// ---------- 存储区 ----------

@Composable
private fun StorageRow(
    supported: List<String>,
    storages: List<Storage>,
    current: String,
    busy: Boolean,
    onPick: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        supported.forEach { name ->
            val s = storages.firstOrNull { it.name == name }
            val on = name == current
            Box(
                Modifier
                    .background(if (on) Amber.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .border(1.dp, if (on) Amber else Hairline, RoundedCornerShape(8.dp))
                    .clickable(enabled = !busy) { onPick(name) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    s?.label ?: name,
                    style = Readout,
                    color = when {
                        s?.full == true -> Alert
                        on -> Amber
                        else -> TextLo
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

// ---------- 短信卡片 ----------

@Composable
private fun MessageCard(
    m: Sms,
    busy: Boolean,
    onDelete: (Int) -> Unit,
    onCopied: (String) -> Unit,
) {
    val clip = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(14.dp))
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                m.from.ifBlank { "未知号码" },
                color = if (m.unread) TextHi else TextLo,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text("#${m.index}", style = Readout, color = Hairline)
        }

        Spacer(Modifier.height(8.dp))
        Text(m.body, color = TextHi, fontSize = 15.sp, lineHeight = 22.sp)

            // 识别出验证码就给个可点复制的块
        m.code?.let { code ->
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .background(Amber.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .border(1.dp, Amber.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .clickable {
                        clip.setText(AnnotatedString(code))
                        // Android 13 起系统自带「已复制」提示，再弹 snackbar 是重复。
                        // 低版本没有，不补一个的话点下去毫无反馈。
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            onCopied(code)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    code,
                    color = Amber,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.width(12.dp))
                Text("点击复制", style = Readout, color = Amber.copy(alpha = 0.7f))
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.time, style = Readout, color = TextLo, modifier = Modifier.weight(1f))
            Text(
                "删除",
                style = Readout,
                color = if (busy) Hairline else TextLo,
                modifier = Modifier
                    .clickable(enabled = !busy) { onDelete(m.index) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// ---------- 空状态 ----------

@Composable
private fun Empty(connected: Boolean, current: String, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                !connected -> "插入模块后开始"
                current == "SM" -> "SIM 卡上没有短信"
                else -> "这个存储区里还没有短信"
            },
            color = TextLo,
            fontSize = 15.sp,
        )
        if (connected && current == "SM") {
            Spacer(Modifier.height(6.dp))
            Text("切到 ME 看看模块内存", style = Readout, color = Hairline)
        }
    }
}

// ---------- 发送栏 ----------

@Composable
private fun Composer(
    number: String,
    onNumber: (String) -> Unit,
    text: String,
    onText: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Column(Modifier.background(Panel).padding(14.dp)) {
        Field(number, onNumber, "对方号码", mono = true)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Field(text, onText, "短信内容", Modifier.weight(1f), singleLine = false)
            Spacer(Modifier.width(10.dp))
            val on = enabled && number.isNotBlank() && text.isNotBlank()
            Box(
                Modifier
                    .background(if (on) Amber else PanelHi, RoundedCornerShape(12.dp))
                    .clickable(enabled = on) { onSend() }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    "发送",
                    color = if (on) Ink else Hairline,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    mono: Boolean = false,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(PanelHi, RoundedCornerShape(12.dp))
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = TextLo.copy(alpha = 0.6f), fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            cursorBrush = SolidColor(Amber),
            textStyle = TextStyle(
                color = TextHi,
                fontSize = 15.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------- 通用小组件 ----------

@Composable
private fun Pill(
    label: String,
    bg: Color,
    fg: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(if (enabled) bg else PanelHi, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (enabled) fg else Hairline,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}


// ---------- USB 模式 ----------

@Composable
private fun ModeDialog(
    current: Int?,
    loading: Boolean,
    busy: Boolean,
    backup: ConfigBackup?,
    identity: Identity?,
    onReload: () -> Unit,
    onRestore: () -> Unit,
    onSaveBackup: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (NetMode) -> Unit,
) {
    AlertDialog(
        containerColor = Panel,
        titleContentColor = TextHi,
        onDismissRequest = onDismiss,
        title = { Text("USB 网络模式") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    loading -> {
                        Text("正在读取当前模式…", color = TextLo, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                    }
                    current == null -> {
                        Text(
                            "读不到当前模式。可以点下方「重新读取」再试一次；" +
                                "多次失败说明固件不支持 AT+QCFG=\"usbnet\"。",
                            color = Alert,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                identity?.let { id ->
                    Text(
                        "当前模块  ${id.short.ifBlank { "身份未知" }}",
                        style = Readout,
                        color = TextLo,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (backup != null) Signal.copy(alpha = 0.07f) else PanelHi,
                            RoundedCornerShape(10.dp),
                        )
                        .border(
                            1.dp,
                            if (backup != null) Signal.copy(alpha = 0.3f) else Hairline,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(10.dp)
                ) {
                    Column {
                        val b = backup
                        if (b == null) {
                            Text("尚无恢复点", style = Readout, color = TextLo)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "连接模块后会自动保存一份当前配置。",
                                color = TextLo, fontSize = 11.sp, lineHeight = 16.sp,
                            )
                        } else {
                            val sameModule = identity == null ||
                                b.imei.isBlank() || b.imei == identity.imei
                            val bkMode = qcfgValue(b.usbnet)
                            val bkName = NET_MODES.firstOrNull { it.code == bkMode }?.name

                            Text(
                                if (sameModule) "恢复点  ${bkName ?: b.usbnet}"
                                else "此恢复点属于另一颗模块",
                                style = Readout,
                                color = if (sameModule) Signal else Alert,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                listOfNotNull(
                                    b.imei.takeIf { it.isNotBlank() }?.let { "IMEI $it" },
                                    b.savedAt.takeIf { it > 0 }?.let { fmtTime(it) },
                                ).joinToString("  ·  "),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextLo,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "首次连接时自动保存，之后不会自动更新——" +
                                    "否则切换模式后就丢失了原始配置的记录。",
                                color = TextLo, fontSize = 11.sp, lineHeight = 16.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (sameModule && bkMode != null && bkMode != current) {
                                    MiniAction("恢复到此配置", Signal, !busy, onRestore)
                                }
                                MiniAction("以当前配置覆盖", Amber, !busy, onSaveBackup)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                NET_MODES.forEach { m ->
                    val on = m.code == current
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(
                                if (on) Amber.copy(alpha = 0.12f) else PanelHi,
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                1.dp,
                                if (on) Amber else Hairline,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(enabled = !busy && !on) { onPick(m) }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                m.name,
                                color = if (on) Amber else TextHi,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (on) {
                                Text("当前", style = Readout, color = Amber)
                            } else if (m.risky) {
                                Text("有风险", style = Readout, color = Alert)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(m.hosts, style = Readout, color = if (on) Amber else TextLo)
                        Spacer(Modifier.height(6.dp))
                        Text(m.note, color = TextLo, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = TextLo) }
        },
        dismissButton = {
            TextButton(onClick = onReload, enabled = !loading && !busy) {
                Text("重新读取", color = if (loading) Hairline else Amber)
            }
        },
    )
}


// ---------- AT 控制台 ----------

private val QUICK_CMDS = listOf(
    "AT+QCFG=\"usbnet\"",
    "AT+CSQ",
    "AT+CSCA?",
    "AT+CPMS?",
    "AT+CEREG?",
    "ATI",
)

/**
 * 会改写 USB 接口组合的**写入**指令。`usbcfg` 能把全部 USB 接口关掉、
 * `usbauto` 能让模块只枚举 RNDIS —— 两者都会让 AT 串口彻底消失，
 * 之后手机和电脑都连不上，只能进 EDL 模式用签名的 loader 刷机救。
 *
 * 靠参数名后面紧跟逗号来区分读写：`AT+QCFG="usbcfg"` 是查询，放行；
 * `AT+QCFG="usbcfg",0x2C7C,...` 是写入，拦下。
 */
private val DANGEROUS_AT = Regex("""usb(cfg|auto|mode)\s*"?\s*,""", RegexOption.IGNORE_CASE)

internal fun isDangerousAt(cmd: String): Boolean = DANGEROUS_AT.containsMatchIn(cmd)

@Composable
private fun ConsoleDialog(
    log: String,
    busy: Boolean,
    onRun: (String) -> Unit,
    onClear: () -> Unit,
    onCopied: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var cmd by remember { mutableStateOf("") }
    var danger by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val clip = LocalClipboardManager.current

    LaunchedEffect(log) { scroll.animateScrollTo(scroll.maxValue) }

    /** 危险指令先扣下来要二次确认，其余原样下发 */
    fun submit(c: String) {
        val t = c.trim()
        if (t.isBlank()) return
        if (isDangerousAt(t)) danger = t else { onRun(t); cmd = "" }
    }

    danger?.let { c ->
        AlertDialog(
            containerColor = Panel,
            titleContentColor = Alert,
            textContentColor = TextLo,
            onDismissRequest = { danger = null },
            title = { Text("这条指令可能让模块变砖") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Ink, RoundedCornerShape(8.dp))
                            .border(1.dp, Alert.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) { Text(c, style = Mono, color = Alert) }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "它会改写模块的 USB 接口组合，而不是网络模式。" +
                            "写错会关掉全部 USB 接口（包括 AT 串口）—— " +
                            "届时本 App 和电脑都再也连不上，只能进 EDL 模式" +
                            "用签名的 loader 刷机才能救回来。",
                        color = Alert, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "想换 4G 网络模式请用菜单里的「USB 模式」，" +
                            "那里走的是 usbnet 参数，串口一定保留，可逆。",
                        color = TextLo, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onRun(c); cmd = ""; danger = null }) {
                    Text("我清楚后果，执行", color = Alert)
                }
            },
            dismissButton = {
                TextButton(onClick = { danger = null }) { Text("取消", color = TextLo) }
            },
        )
    }

    AlertDialog(
        containerColor = Panel,
        titleContentColor = TextHi,
        onDismissRequest = onDismiss,
        title = { Text("AT 控制台") },
        text = {
            Column {
                Text(
                    "指令原样下发，响应原样回显。查询类指令随便试，" +
                        "写入类指令请确认自己知道在做什么。",
                    color = TextLo, fontSize = 12.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_CMDS.take(3).forEach { q ->
                        Box(
                            Modifier
                                .border(1.dp, Hairline, RoundedCornerShape(7.dp))
                                .clickable(enabled = !busy) { submit(q) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text(q.removePrefix("AT+").removeSuffix("?"), style = Readout, color = Amber) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_CMDS.drop(3).forEach { q ->
                        Box(
                            Modifier
                                .border(1.dp, Hairline, RoundedCornerShape(7.dp))
                                .clickable(enabled = !busy) { submit(q) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text(q.removePrefix("AT+").removeSuffix("?"), style = Readout, color = Amber) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Ink, RoundedCornerShape(10.dp))
                        .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                        .verticalScroll(scroll)
                        .padding(10.dp)
                ) {
                    // 包一层 SelectionContainer，长按就能选中任意一段复制；
                    // 整段复制走下面的「复制」按钮。
                    SelectionContainer {
                        Text(
                            log.ifBlank { "点上面的快捷指令，或在下方输入。" },
                            color = if (log.isBlank()) TextLo else TextHi,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(cmd, { cmd = it }, "AT 指令", Modifier.weight(1f), mono = true)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .background(
                                if (!busy && cmd.isNotBlank()) Amber else PanelHi,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable(enabled = !busy && cmd.isNotBlank()) {
                                submit(cmd)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            "发送",
                            color = if (!busy && cmd.isNotBlank()) Ink else Hairline,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = TextLo) } },
        dismissButton = {
            Row {
                TextButton(
                    enabled = log.isNotBlank(),
                    onClick = {
                        clip.setText(AnnotatedString(log))
                        // 同 MessageCard：Android 13 起系统自带「已复制」提示，
                        // 低版本没有，不补 snackbar 的话点下去毫无反馈。
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            onCopied("控制台全部输出")
                        }
                    },
                ) { Text("复制", color = if (log.isBlank()) Hairline else Amber) }
                TextButton(onClick = onClear) { Text("清空", color = TextLo) }
            }
        },
    )
}


// ---------- USB 接口一览 ----------

@Composable
private fun IfaceDialog(
    ifaces: List<IfaceInfo>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    AlertDialog(
        containerColor = Panel,
        titleContentColor = TextHi,
        onDismissRequest = onDismiss,
        title = { Text("USB 接口一览") },
        text = {
            Column(Modifier.verticalScroll(scroll)) {
                Text(
                    "当前 USB 组合下模块暴露的全部接口。" +
                        "带成对 bulk 端点的才可能是 AT 串口。",
                    color = TextLo, fontSize = 12.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))

                if (ifaces.isEmpty()) {
                    Text("读不到接口，模块可能未插好。", color = Alert, fontSize = 13.sp)
                }

                ifaces.forEach { f ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .background(
                                if (f.inUse) Amber.copy(alpha = 0.12f) else PanelHi,
                                RoundedCornerShape(10.dp),
                            )
                            .border(
                                1.dp,
                                if (f.inUse) Amber else Hairline,
                                RoundedCornerShape(10.dp),
                            )
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                f.descriptor,
                                style = Readout,
                                color = if (f.inUse) Amber else TextHi,
                                modifier = Modifier.weight(1f),
                            )
                            if (f.inUse) Text("使用中", style = Readout, color = Amber)
                            else if (f.candidate) Text("候选", style = Readout, color = Signal)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(f.role, color = TextLo, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = TextLo) } },
        dismissButton = { TextButton(onClick = onRefresh) { Text("刷新", color = Amber) } },
    )
}


private fun fmtTime(ms: Long): String = runCatching {
    java.text.SimpleDateFormat("yy/MM/dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
}.getOrDefault("")


@Composable
private fun MiniAction(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .border(1.dp, if (enabled) color.copy(alpha = 0.5f) else Hairline, RoundedCornerShape(7.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, style = Readout, color = if (enabled) color else Hairline)
    }
}

/** 从 +QCFG 响应行里取模式值 */
private fun qcfgValue(line: String): Int? =
    Regex("""(\d+)\s*$""").find(line.trim())?.groupValues?.get(1)?.toIntOrNull()
