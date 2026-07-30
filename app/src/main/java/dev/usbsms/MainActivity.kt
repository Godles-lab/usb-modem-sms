package dev.usbsms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var modem: Modem

    private val smsList = mutableStateListOf<Sms>()
    private val storages = mutableStateListOf<Storage>()
    private val supported = mutableStateListOf<String>()

    private var status by mutableStateOf("未连接")
    private var connected by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var connecting = false
    private var current by mutableStateOf("ME")
    private var tele by mutableStateOf(Telemetry())
    private var netMode by mutableStateOf<Int?>(null)
    private var modeLoading by mutableStateOf(false)
    private var console by mutableStateOf("")
    private var backup by mutableStateOf<ConfigBackup?>(null)
    private var identity by mutableStateOf<Identity?>(null)
    private val ifaces = mutableStateListOf<IfaceInfo>()

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("modemsms", MODE_PRIVATE)
    }
    private var atIf by mutableStateOf(-1)

    private val snackbar = SnackbarHostState()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else status = "已拒绝 USB 授权"

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect()

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    connecting = false
                    modem.close()
                    connected = false
                    smsList.clear(); storages.clear(); supported.clear()
                    tele = Telemetry(); atIf = -1
                    identity = null; backup = null
                    if (!status.startsWith("已切换到")) {
                        status = "模块已断开。若是突然掉线，多半是 OTG 供电不足。"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // enableEdgeToEdge() 默认按系统的浅色/深色模式决定系统栏图标颜色，
        // 而本应用界面恒为深色。系统处于浅色模式时会被设成黑色图标，
        // 压在深靛蓝背景上完全看不见。这里显式声明「深色背景」，
        // 强制系统栏使用浅色图标，与系统主题无关。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        modem = Modem(this)
        modem.onNewSms = { lifecycleScope.launch { refresh() } }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        setContent {
            DjiSmsTheme {
                Screen(
                    status = status,
                    connected = connected,
                    busy = busy,
                    tele = tele,
                    netMode = netMode,
                    modeLoading = modeLoading,
                    console = console,
                    backup = backup,
                    identity = identity,
                    ifaces = ifaces,
                    atIf = atIf,
                    sms = smsList,
                    storages = storages,
                    supported = supported,
                    current = current,
                    snackbar = snackbar,
                    onConnect = ::connect,
                    onRefresh = { lifecycleScope.launch { refresh() } },
                    onPickStorage = { s -> lifecycleScope.launch { pickStorage(s) } },
                    onSend = { n, t -> lifecycleScope.launch { send(n, t) } },
                    onDeleteOne = { i -> lifecycleScope.launch { deleteOne(i) } },
                    onDeleteBulk = { f -> lifecycleScope.launch { deleteBulk(f) } },
                    onSetMode = { m -> lifecycleScope.launch { setMode(m) } },
                    onReadMode = { lifecycleScope.launch { readMode() } },
                    onRunAt = { c -> lifecycleScope.launch { runAt(c) } },
                    onClearConsole = { console = "" },
                    onRefreshIfaces = ::refreshIfaces,
                    onCopied = { c ->
                        lifecycleScope.launch { snackbar.showSnackbar("已复制 $c") }
                    },
                    onRestore = { lifecycleScope.launch { restore() } },
                    onSaveBackup = { lifecycleScope.launch { saveBackupManual() } },
                )
            }
        }

        connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(usbReceiver) }
        modem.release()
    }

    // ---------- 动作 ----------

    private fun connect() {
        // onCreate 与 USB 广播都会调这里。不加锁会让两个协程在
        // supported / storages 列表上竞争，界面出现重复的存储区按钮。
        if (connecting) return
        connecting = true

        lifecycleScope.launch {
            try {
                val dev = modem.findDevice()
                if (dev == null) {
                    status = "没找到模块。确认已插好，并使用带外部供电的 OTG 转接头。"
                    connected = false
                    return@launch
                }
                if (!modem.hasPermission(dev)) {
                    status = "正在请求 USB 授权…"
                    modem.requestPermission(dev)
                    return@launch
                }

                status = "正在探测 AT 口…"
                val err = modem.open(dev) { p -> runOnUiThread { status = p } }
                if (err != null) {
                    status = err
                    connected = false
                    return@launch
                }

                connected = true
                atIf = modem.atIfIndex
                status = "初始化中…"
                modem.init()

                // 先确认这颗模块的身份，备份才能按模块区分
                val id = modem.identity()
                identity = id
                loadBackup(id.key)
                if (backup == null) saveBackup()

                // 先取到完整结果再一次性替换，避免中间态被界面读到
                val st = modem.supportedStorages()
                supported.clear()
                supported.addAll(st)

                current = modem.storage
                netMode = modem.usbnetMode()
                refreshIfaces()
                refresh()
            } finally {
                connecting = false
            }
        }
    }

    // ---------- 诊断 / 备份 ----------

    private fun refreshIfaces() {
        ifaces.clear()
        modem.findDevice()?.let { ifaces.addAll(modem.describe(it)) }
    }

    // 备份按 IMEI 分键存储。两颗同型号模块的 VID/PID 完全相同，
    // 不按 IMEI 区分就会把 A 的配置恢复到 B 上。
    private fun bk(key: String, field: String) = "bk.$key.$field"

    private fun loadBackup(key: String) {
        val net = prefs.getString(bk(key, "usbnet"), null)
        if (net == null) {
            backup = null
            return
        }
        backup = ConfigBackup(
            imei = prefs.getString(bk(key, "imei"), "") ?: "",
            model = prefs.getString(bk(key, "model"), "") ?: "",
            usbnet = net,
            usbcfg = prefs.getString(bk(key, "usbcfg"), "") ?: "",
            savedAt = prefs.getLong(bk(key, "at"), 0L),
        )
    }

    /** 用户主动把当前配置设为新的恢复点。 */
    private suspend fun saveBackupManual() {
        if (!connected || busy) return
        busy = true
        val ok = runCatching { saveBackup(); true }.getOrDefault(false)
        busy = false
        snackbar.showSnackbar(if (ok) "已把当前配置存为恢复点" else "读取配置失败")
    }

    private suspend fun saveBackup() {
        val b = runCatching { modem.readBackup() }.getOrNull() ?: return
        val key = b.imei.ifBlank { "unknown" }
        prefs.edit()
            .putString(bk(key, "imei"), b.imei)
            .putString(bk(key, "model"), b.model)
            .putString(bk(key, "usbnet"), b.usbnet)
            .putString(bk(key, "usbcfg"), b.usbcfg)
            .putLong(bk(key, "at"), b.savedAt)
            .apply()
        backup = b
    }

    /** 按备份里的 usbnet 值恢复。 */
    private suspend fun restore() {
        val b = backup ?: return
        val m = Regex("""(\d+)\s*$""").find(b.usbnet.trim())
            ?.groupValues?.get(1)?.toIntOrNull()
        if (m == null) {
            snackbar.showSnackbar("备份里解析不出模式值：${b.usbnet}")
            return
        }
        if (m == netMode) {
            snackbar.showSnackbar("当前已是备份中的模式（$m），无需恢复")
            return
        }
        setMode(m)
    }

    /** 直接下发任意 AT 指令，原样回显。排查用。 */
    private suspend fun runAt(cmd: String) {
        if (!connected || busy) return
        busy = true
        val r = runCatching { modem.raw(cmd) }.getOrElse { "异常：${it.message}" }
        busy = false
        val shown = r.replace("\r\n", "\n").trim().ifBlank { "（无响应）" }
        console = buildString {
            append(console)
            if (isNotEmpty()) append("\n\n")
            append("> ").append(cmd).append('\n').append(shown)
        }.takeLast(8000)
    }

    /** 单独重读一次 USB 模式，不用重新插拔 */
    private suspend fun readMode() {
        if (!connected || modeLoading) return
        modeLoading = true
        netMode = runCatching { modem.usbnetMode() }.getOrNull()
        modeLoading = false
    }

    private suspend fun setMode(mode: Int) {
        if (!connected || busy) return
        busy = true
        val err = modem.setUsbnetMode(mode)
        busy = false
        if (err != null) {
            snackbar.showSnackbar(err)
        } else {
            connected = false
            smsList.clear(); storages.clear(); supported.clear()
            status = "已切换到 ${NET_MODES.first { it.code == mode }.name}，模块重启中，约 30 秒后自动重连。"
            snackbar.showSnackbar("模块正在重启")
        }
    }

    private suspend fun refresh() {
        if (!connected || busy) return
        busy = true
        tele = runCatching { modem.telemetry() }.getOrDefault(Telemetry())
        val list = runCatching { modem.listSms() }.getOrDefault(emptyList())
        smsList.clear(); smsList.addAll(list)
        storages.clear()
        storages.addAll(runCatching { modem.storageInfo() }.getOrDefault(emptyList()))
        current = modem.storage
        busy = false
    }

    private suspend fun pickStorage(name: String) {
        if (!connected || busy) return
        busy = true
        val err = modem.setStorage(name)
        busy = false
        if (err != null) snackbar.showSnackbar(err) else refresh()
    }

    private suspend fun send(number: String, text: String) {
        if (!connected) return
        busy = true
        val err = runCatching { modem.sendSms(number, text) }
            .getOrElse { it.message ?: "发送异常" }
        busy = false
        snackbar.showSnackbar(err ?: "已发送")
        if (err == null) refresh()
    }

    private suspend fun deleteOne(index: Int) {
        if (!connected || busy) return
        busy = true
        val err = modem.deleteOne(index)
        busy = false
        if (err != null) snackbar.showSnackbar(err) else refresh()
    }

    private suspend fun deleteBulk(flag: Int) {
        if (!connected || busy) return
        busy = true
        val err = modem.deleteBulk(flag)
        busy = false
        if (err != null) snackbar.showSnackbar(err) else refresh()
    }
}
