package dev.usbsms

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
    rescueLog: String,
    rescuing: Boolean,
    backup: ConfigBackup?,
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
    onRescue: () -> Unit,
    onRefreshIfaces: () -> Unit,
    onRestore: () -> Unit,
) {
    var number by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmFlag by remember { mutableStateOf<Int?>(null) }
    var modeSheet by remember { mutableStateOf(false) }
    var confirmMode by remember { mutableStateOf<NetMode?>(null) }
    var consoleOpen by remember { mutableStateOf(false) }
    var rescueOpen by remember { mutableStateOf(false) }
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

    if (rescueOpen) {
        RescueDialog(rescueLog, rescuing, onRescue) { rescueOpen = false }
    }

    if (ifaceOpen) {
        IfaceDialog(ifaces, onRefreshIfaces) { ifaceOpen = false }
    }

    if (consoleOpen) {
        ConsoleDialog(console, busy, onRunAt, onClearConsole) { consoleOpen = false }
    }

    if (modeSheet) {
        ModeDialog(
            current = netMode,
            loading = modeLoading,
            busy = busy,
            backup = backup,
            onReload = onReadMode,
            onRestore = onRestore,
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
                        Text("ECM / MBIM / QMI 都保留 AT 串口，随时可以切回来。", color = TextLo)
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
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {

            Header(connected, busy, onConnect, onRefresh, menuOpen,
                { menuOpen = it }, { confirmFlag = it }, { modeSheet = true; onReadMode() },
                { consoleOpen = true },
                { rescueOpen = true },
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
                        "如果刚切换过 USB 模式，AT 口可能已经移位。" +
                            "救援模式会加长超时、多轮遍历所有接口。",
                        style = Readout, color = Hairline,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("重新连接", PanelHi, TextHi, onClick = onConnect)
                        Pill("救援模式", Signal, Ink) { rescueOpen = true }
                    }
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
                        MessageCard(m, busy, onDeleteOne)
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
    openRescue: () -> Unit,
    openIfaces: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
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
                        text = { Text("救援模式", color = Signal) },
                        onClick = { setMenu(false); openRescue() },
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
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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
private fun MessageCard(m: Sms, busy: Boolean, onDelete: (Int) -> Unit) {
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
                    .clickable { clip.setText(AnnotatedString(code)) }
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
    onReload: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (NetMode) -> Unit,
) {
    AlertDialog(
        containerColor = Panel,
        titleContentColor = TextHi,
        onDismissRequest = onDismiss,
        title = { Text("USB 网络模式") },
        text = {
            Column {
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
                backup?.let { b ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Signal.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .border(1.dp, Signal.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text("已备份原始配置", style = Readout, color = Signal)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                b.usbnet,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextLo,
                            )
                            if (b.usbcfg.isNotBlank()) {
                                Text(
                                    b.usbcfg,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextLo,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

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
                        Text(m.note, color = TextLo, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = TextLo) }
        },
        dismissButton = {
            Row {
                if (backup != null) {
                    TextButton(onClick = onRestore, enabled = !busy) {
                        Text("恢复备份", color = Signal)
                    }
                }
                TextButton(onClick = onReload, enabled = !loading && !busy) {
                    Text("重新读取", color = if (loading) Hairline else Amber)
                }
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

@Composable
private fun ConsoleDialog(
    log: String,
    busy: Boolean,
    onRun: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var cmd by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(log) { scroll.animateScrollTo(scroll.maxValue) }

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
                                .clickable(enabled = !busy) { onRun(q) }
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
                                .clickable(enabled = !busy) { onRun(q) }
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
                    Text(
                        log.ifBlank { "点上面的快捷指令，或在下方输入。" },
                        color = if (log.isBlank()) TextLo else TextHi,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                    )
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
                                onRun(cmd.trim()); cmd = ""
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
        dismissButton = { TextButton(onClick = onClear) { Text("清空", color = TextLo) } },
    )
}


// ---------- 救援模式 ----------

@Composable
private fun RescueDialog(
    log: String,
    running: Boolean,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(log) { scroll.animateScrollTo(scroll.maxValue) }

    AlertDialog(
        containerColor = Panel,
        titleContentColor = TextHi,
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("救援模式") },
        text = {
            Column {
                Text(
                    "切换 usbnet 后 AT 串口的位置会变，很多人以为模块坏了，" +
                        "其实只是不在原来那个接口上了。\n\n" +
                        "救援模式会遍历所有带 bulk 端点的接口，每个都试 " +
                        "AT / ATI 等多种指令、DTR 开关两种状态，共三轮，" +
                        "轮间等待 8 秒以应对模块尚未启动完成。",
                    color = TextLo, fontSize = 12.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Ink, RoundedCornerShape(10.dp))
                        .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                        .verticalScroll(scroll)
                        .padding(10.dp)
                ) {
                    Text(
                        log.ifBlank { "点「开始探测」。整个过程最长约 1 分钟。" },
                        color = if (log.isBlank()) TextLo else TextHi,
                        fontSize = 11.sp, lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onStart, enabled = !running) {
                Text(if (running) "探测中…" else "开始探测", color = if (running) Hairline else Signal)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) {
                Text("关闭", color = TextLo)
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
