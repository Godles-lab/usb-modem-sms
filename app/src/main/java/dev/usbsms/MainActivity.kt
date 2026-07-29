package dev.usbsms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    private var current by mutableStateOf("ME")
    private var tele by mutableStateOf(Telemetry())
    private var netMode by mutableStateOf<Int?>(null)
    private var modeLoading by mutableStateOf(false)
    private var console by mutableStateOf("")
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
                    modem.close()
                    connected = false
                    smsList.clear(); storages.clear(); supported.clear()
                    tele = Telemetry(); atIf = -1
                    if (!status.startsWith("已切换到")) {
                        status = "模块已断开。若是突然掉线，多半是 OTG 供电不足。"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
        lifecycleScope.launch {
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
            val err = modem.open(dev)
            if (err != null) {
                status = err
                connected = false
                return@launch
            }

            connected = true
            atIf = modem.atIfIndex
            status = "初始化中…"
            modem.init()
            supported.clear()
            supported.addAll(modem.supportedStorages())
            current = modem.storage
            netMode = modem.usbnetMode()
            refresh()
        }
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
