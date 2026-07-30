package dev.usbsms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 大疆增强图传模块（EG25G-QDC507）。改过 ID 就换这两个值。 */
const val VENDOR_ID = 0x2CA3
const val PRODUCT_ID = 0x4006

/** 移远标准组合里 AT 在接口 2，但切换 usbnet 后会变，所以只作为优先尝试项 */
const val AT_INTERFACE_HINT = 2

const val ACTION_USB_PERMISSION = "dev.usbsms.USB_PERMISSION"

private const val BUF_LIMIT = 64 * 1024
private const val URC_TAIL = 512

data class Sms(
    val index: Int,
    val stat: String,
    val from: String,
    val time: String,
    val body: String,
) {
    val unread: Boolean get() = stat.contains("UNREAD", ignoreCase = true)
    val code: String? get() = extractCode(body)
}

data class Storage(val name: String, val used: Int, val total: Int) {
    val label: String get() = "$name $used/$total"
    val full: Boolean get() = total > 0 && used >= total
}

/** USB 网络模式，对应 AT+QCFG="usbnet",<code> */
data class NetMode(
    val code: Int,
    val name: String,
    val hosts: String,
    val note: String,
    val risky: Boolean = false,
)

val NET_MODES = listOf(
    NetMode(
        0, "QMI / RMNET", "Linux 免驱 · Windows 需驱动",
        "出厂默认。高通私有协议，性能和可控性最好，软路由/树莓派做 4G 网关都用它。" +
            "Linux 靠 qmi_wwan 内置支持；Windows 要装移远 NDIS 驱动。" +
            "macOS 和 iPad 没有也不可能有驱动。",
    ),
    NetMode(
        1, "ECM", "macOS · iPad · Linux 免驱",
        "标准 CDC 以太网，覆盖面最广的选择。模块内部自动建立数据呼叫，" +
            "主机跑 DHCP 就能上网（网段 192.168.225.x）。" +
            "macOS / iPadOS / Linux 全部免驱；Windows 装移远官方 ECM 驱动同样可用。",
    ),
    NetMode(
        2, "MBIM", "Windows 免驱 · Linux 免驱",
        "微软移动宽带标准，也是 USB-IF 标准类。Windows 8 以上内置驱动，" +
            "会当成真正的蜂窝网卡在系统设置里管理，是 Windows 下最省事的选择。" +
            "Linux 靠 cdc_mbim 支持。macOS 不支持。",
    ),
    NetMode(
        3, "RNDIS", "仅 Windows XP/7 等老系统",
        "微软的以太网over USB，属于遗留兼容模式。Linux 的 rndis_host 已被标记弃用。" +
            "切换后 AT 串口位置会变、数量可能减少——社区里大量「模块变砖」的报告" +
            "其实是主机写死了原来的串口号。本 App 遍历接口探测，通常能自己找回来，但不保证。" +
            "ECM 与 MBIM 已覆盖所有现代平台，除非要接 XP/Win7 老设备，否则没有理由选它。",
        risky = true,
    ),
)

/** 单个 USB 接口的描述，用于诊断当前组合。 */
data class IfaceInfo(
    val index: Int,
    val cls: Int,
    val subCls: Int,
    val proto: Int,
    val bulkIn: Boolean,
    val bulkOut: Boolean,
    val epCount: Int,
    val inUse: Boolean = false,
) {
    /** 有成对 bulk 端点才可能是 AT 串口 */
    val candidate: Boolean get() = bulkIn && bulkOut

    val role: String
        get() = when {
            cls == 0xFF && candidate -> "厂商自定义 · 可能是串口"
            cls == 0xFF -> "厂商自定义"
            cls == 0x02 && subCls == 0x0E -> "CDC 控制 · MBIM"
            cls == 0x02 && subCls == 0x06 -> "CDC 控制 · ECM"
            cls == 0x02 && proto == 0xFF -> "CDC 控制 · RNDIS"
            cls == 0x02 -> "CDC 控制"
            cls == 0x0A -> "CDC 数据"
            cls == 0xE0 -> "无线控制 · RNDIS"
            cls == 0x01 -> "音频 · UAC"
            cls == 0x08 -> "大容量存储"
            else -> "未知类"
        }

    val descriptor: String
        get() = "IF%d  %02X/%02X/%02X  %d 端点%s".format(
            index, cls, subCls, proto, epCount,
            if (bulkIn && bulkOut) "  bulk in+out" else if (bulkIn) "  仅 bulk in"
            else if (bulkOut) "  仅 bulk out" else "",
        )
}

/** 切换模式前保存的原始配置，用于恢复。 */
data class ConfigBackup(val usbnet: String, val usbcfg: String, val savedAt: Long)

/** 顶部状态行的实时读数。 */
data class Telemetry(
    val carrier: String = "",
    val bars: Int = 0,
    val dbm: Int? = null,
    val smsc: String = "",
    val registered: Boolean = false,
)

class Modem(private val ctx: Context) {

    private var conn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var readerJob: Job? = null
    private var atInterface = -1
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioLock = Mutex()

    private val buf = StringBuilder()
    private val urc = StringBuilder()
    private var prepared = false

    var onNewSms: (() -> Unit)? = null

    @Volatile
    var storage: String = "ME"
        private set

    val isOpen: Boolean get() = conn != null

    /** 实际找到 AT 口的接口号，界面上显示用 */
    val atIfIndex: Int get() = atInterface

    // ---------- 连接 ----------

    fun findDevice(): UsbDevice? {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return mgr.deviceList.values.firstOrNull {
            it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID
        }
    }

    fun hasPermission(dev: UsbDevice): Boolean {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return mgr.hasPermission(dev)
    }

    fun requestPermission(dev: UsbDevice) {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        mgr.requestPermission(dev, pi)
    }

    /**
     * 打开并自动探测 AT 口。切换 usbnet 模式后接口布局会变，
     * 写死接口号会导致再也连不上，所以逐个试到谁回 OK 为止。
     * 返回 null 表示成功。
     */
    suspend fun open(dev: UsbDevice): String? {
        close()
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val c = mgr.openDevice(dev) ?: return "打开设备失败，通常是没有授权。"
        conn = c

        // 先试标准位置，再试其余接口
        val order = buildList {
            if (dev.interfaceCount > AT_INTERFACE_HINT) add(AT_INTERFACE_HINT)
            for (i in 0 until dev.interfaceCount) if (i != AT_INTERFACE_HINT) add(i)
        }

        for (idx in order) {
            val intf = dev.getInterface(idx)
            var i: UsbEndpoint? = null
            var o: UsbEndpoint? = null
            for (k in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(k)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) i = ep else o = ep
            }
            if (i == null || o == null) continue
            if (!c.claimInterface(intf, true)) continue

            // CDC SET_CONTROL_LINE_STATE，拉高 DTR/RTS。
            // 少了这步模块收得到指令但不回应。
            c.controlTransfer(0x21, 0x22, 0x03, idx, null, 0, 1000)
            epIn = i; epOut = o
            startReader()

            if (at("AT", timeoutMs = 1500).contains("OK")) {
                atInterface = idx
                prepared = false
                return null
            }

            readerJob?.cancel(); readerJob = null
            runCatching { c.releaseInterface(intf) }
            epIn = null; epOut = null
        }

        c.close(); conn = null
        return "所有接口都不响应 AT 指令。模块可能切到了不带串口的 USB 组合，" +
            "需要接电脑用 Linux 发 AT+QCFG=\"usbnet\",0 恢复。"
    }

    /** 不需要连接，只读 USB 描述符。诊断用。 */
    fun describe(dev: UsbDevice): List<IfaceInfo> =
        (0 until dev.interfaceCount).map { i ->
            val intf = dev.getInterface(i)
            var bin = false
            var bout = false
            for (k in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(k)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) bin = true else bout = true
            }
            IfaceInfo(
                index = i,
                cls = intf.interfaceClass,
                subCls = intf.interfaceSubclass,
                proto = intf.interfaceProtocol,
                bulkIn = bin, bulkOut = bout,
                epCount = intf.endpointCount,
                inUse = i == atInterface,
            )
        }

    /**
     * 救援模式：加长超时、多轮重试、多种指令变体、DTR 开关都试。
     * 专治「切换 usbnet 后 AT 口移位 / 模块刚重启还没稳定」。
     */
    suspend fun openDeep(dev: UsbDevice, log: (String) -> Unit): String? {
        close()
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val c = mgr.openDevice(dev) ?: return "打开设备失败，通常是没有授权。"
        conn = c

        val cands = (0 until dev.interfaceCount).filter { i ->
            val intf = dev.getInterface(i)
            var bin = false; var bout = false
            for (k in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(k)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) bin = true else bout = true
            }
            bin && bout
        }

        log("共 ${dev.interfaceCount} 个接口，其中 ${cands.size} 个带成对 bulk 端点")
        if (cands.isEmpty()) {
            c.close(); conn = null
            return "没有任何接口带成对 bulk 端点。模块的 USB 组合里已不含串口，" +
                "手机端无法恢复，需要接电脑处理。"
        }

        val variants = listOf("AT\r\n", "AT\r", "ATE0\r\n", "ATI\r\n")

        for (round in 1..3) {
            log("── 第 $round / 3 轮")
            for (idx in cands) {
                val intf = dev.getInterface(idx)
                if (!c.claimInterface(intf, true)) {
                    log("IF$idx 无法占用，跳过")
                    continue
                }

                var i: UsbEndpoint? = null
                var o: UsbEndpoint? = null
                for (k in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(k)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) i = ep else o = ep
                }
                epIn = i; epOut = o

                for (dtr in listOf(true, false)) {
                    if (dtr) c.controlTransfer(0x21, 0x22, 0x03, idx, null, 0, 1000)
                    startReader()
                    for (v in variants) {
                        clear(); delay(60)
                        write(v)
                        if (await("OK", "Quectel", timeoutMs = 2500)) {
                            atInterface = idx
                            prepared = false
                            log("✓ IF$idx 响应了（${v.trim()}，DTR ${if (dtr) "开" else "关"}）")
                            return null
                        }
                    }
                    readerJob?.cancel(); readerJob = null
                }
                log("IF$idx 无响应")
                runCatching { c.releaseInterface(intf) }
                epIn = null; epOut = null
            }
            if (round < 3) {
                log("等 8 秒后重试，模块可能仍在启动")
                delay(8000)
            }
        }

        c.close(); conn = null
        return "三轮探测都没有接口响应 AT。若刚切换过 usbnet，再等一分钟重试；" +
            "仍然不行则需要接电脑用 Linux 恢复。"
    }

    fun close() {
        readerJob?.cancel(); readerJob = null
        conn?.close(); conn = null
        epIn = null; epOut = null
        atInterface = -1
        prepared = false
        synchronized(buf) { buf.setLength(0) }
        synchronized(urc) { urc.setLength(0) }
    }

    fun release() {
        close()
        scope.cancel()
    }

    // ---------- 底层读写 ----------

    private fun startReader() {
        readerJob = scope.launch {
            val b = ByteArray(4096)
            while (isActive) {
                val c = conn ?: break
                val n = try {
                    c.bulkTransfer(epIn, b, b.size, 300)
                } catch (e: Exception) {
                    -1
                }
                if (n <= 0) continue

                val s = String(b, 0, n, Charsets.ISO_8859_1)

                synchronized(buf) {
                    buf.append(s)
                    if (buf.length > BUF_LIMIT) buf.delete(0, buf.length - BUF_LIMIT / 2)
                }

                // URC 必须在累积缓冲上匹配。USB 可能把 "+CMTI" 拆到两个包里，
                // 只看单个数据块会漏掉。
                val hit = synchronized(urc) {
                    urc.append(s)
                    if (urc.length > URC_TAIL) urc.delete(0, urc.length - URC_TAIL)
                    if (urc.contains("+CMTI")) {
                        urc.setLength(0); true
                    } else false
                }
                if (hit) onNewSms?.invoke()
            }
        }
    }

    private fun write(s: String) {
        val d = s.toByteArray(Charsets.ISO_8859_1)
        conn?.bulkTransfer(epOut, d, d.size, 3000)
    }

    private fun writeByte(b: Byte) {
        conn?.bulkTransfer(epOut, byteArrayOf(b), 1, 3000)
    }

    private fun snapshot(): String = synchronized(buf) { buf.toString() }
    private fun clear() = synchronized(buf) { buf.setLength(0) }

    private suspend fun await(vararg marks: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (marks.any { snapshot().contains(it) }) return true
            delay(40)
        }
        return false
    }

    /**
     * @param expect 期望的响应前缀。只等 OK 有个坑：上一条命令的尾巴可能
     *   在 clear() 之后才到达，"OK" 会被误当成本条的结束标志，导致拿到空响应。
     */
    private suspend fun at(
        cmd: String,
        timeoutMs: Long = 5000,
        expect: String? = null,
    ): String {
        clear(); delay(50); clear()      // 排空在途残留
        write(cmd + "\r\n")
        if (expect != null) {
            await(expect, "ERROR", timeoutMs = timeoutMs)
            delay(80)                    // 让后面的 OK 一起收进来
        } else {
            await("OK\r\n", "ERROR", timeoutMs = timeoutMs)
        }
        return snapshot()
    }

    /** 从 +QCFG 响应里取模式值。UCS2 字符集下参数名会被十六进制回显，
     *  所以不匹配字面量，直接取该行最后一个整数。 */
    private fun parseQcfgValue(raw: String): Int? =
        raw.lines().firstOrNull { it.contains("+QCFG:") }
            ?.let { Regex("""(\d+)\s*$""").find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }

    // ---------- 初始化 ----------

    /** 只在连接后跑一次。之前每次操作都重设一遍纯属浪费。 */
    private suspend fun prepare() {
        if (prepared) return
        at("ATE0")
        at("AT+CMGF=1")
        if (at("AT+CPMS=\"$storage\",\"ME\",\"ME\"").contains("ERROR")) {
            at("AT+CPMS=\"ME\",\"ME\",\"ME\"")
        }
        at("AT+CSCS=\"UCS2\"")
        at("AT+CNMI=2,1,0,0,0")
        prepared = true
    }

    suspend fun init(): String = ioLock.withLock {
        prepare()
        at("ATI")
    }

    // ---------- 遥测 ----------

    suspend fun telemetry(): Telemetry = ioLock.withLock {
        prepare()

        val csq = Regex("""\+CSQ:\s*(\d+)""").find(at("AT+CSQ"))
            ?.groupValues?.get(1)?.toIntOrNull() ?: 99
        val bars = when {
            csq >= 99 -> 0
            csq <= 5 -> 1
            csq <= 11 -> 2
            csq <= 17 -> 3
            csq <= 23 -> 4
            else -> 5
        }
        val dbm = if (csq >= 99) null else -113 + 2 * csq

        val carrier = Regex("""\+COPS:[^"]*"([^"]*)"""").find(at("AT+COPS?"))
            ?.groupValues?.get(1)?.let { maybeUcs2(it) } ?: ""

        val smsc = Regex("""\+CSCA:\s*"([^"]*)"""").find(at("AT+CSCA?"))
            ?.groupValues?.get(1)?.let { maybeUcs2(it) } ?: ""

        val reg = Regex("""\+CEREG:\s*\d+,(\d+)""").find(at("AT+CEREG?"))
            ?.groupValues?.get(1)?.toIntOrNull()

        Telemetry(carrier, bars, dbm, smsc, reg == 1 || reg == 5)
    }

    // ---------- 短信 ----------

    suspend fun listSms(): List<Sms> = ioLock.withLock {
        prepare()
        at("AT+CSCS=\"UCS2\"")
        var raw = at("AT+CMGL=\"ALL\"", timeoutMs = 20000)
        if (raw.contains("ERROR")) {
            // UCS2 字符集下部分固件要求参数也是 UCS2，"ALL" 即 0041004C004C
            raw = at("AT+CMGL=\"0041004C004C\"", timeoutMs = 20000)
        }
        parseCmgl(raw)
    }

    /** 返回 null 表示成功。 */
    suspend fun sendSms(number: String, text: String): String? = ioLock.withLock {
        if (number.isBlank() || text.isBlank()) return@withLock "号码和内容都不能为空。"
        prepare()

        // 纯 ASCII 用 GSM 编码，单条上限 160；含中文才用 UCS2，上限 70。
        val ascii = text.all { it.code < 128 }
        val addr: String
        if (ascii) {
            at("AT+CSCS=\"GSM\"")
            at("AT+CSMP=17,167,0,0")
            addr = number
        } else {
            at("AT+CSCS=\"UCS2\"")
            at("AT+CSMP=17,167,0,8")
            addr = ucs2Encode(number)
        }

        clear()
        write("AT+CMGS=\"$addr\"\r")
        if (!await(">", timeoutMs = 8000)) {
            writeByte(0x1B)   // ESC，退出可能半开的输入态
            at("AT+CSCS=\"UCS2\"")
            return@withLock "模块没有返回 > 提示符。多半是短信中心号码为空。"
        }

        clear()
        write(if (ascii) text else ucs2Encode(text))
        writeByte(0x1A)       // Ctrl+Z
        await("+CMGS:", "ERROR", timeoutMs = 60000)
        val r = snapshot()

        at("AT+CSCS=\"UCS2\"")   // 还原，读取时要用

        when {
            r.contains("+CMGS:") -> null
            r.contains("ERROR") -> "模块返回错误：${r.trim().lines().lastOrNull()}"
            else -> "发送超时，未收到网络确认。"
        }
    }

    // ---------- 存储区 ----------

    suspend fun supportedStorages(): List<String> = ioLock.withLock {
        val r = at("AT+CPMS=?")
        val g = Regex("""\(([^)]*)\)""").find(r)?.groupValues?.get(1)
            ?: return@withLock listOf("ME")
        g.split(",").map { it.trim().trim('"') }.filter { it.isNotBlank() }
    }

    suspend fun storageInfo(): List<Storage> = ioLock.withLock {
        val line = at("AT+CPMS?").lines().firstOrNull { it.contains("+CPMS:") }
            ?: return@withLock emptyList()
        val p = splitCsv(line.substringAfter("+CPMS:").trim())
        val out = mutableListOf<Storage>()
        var i = 0
        while (i + 2 < p.size) {
            val name = p[i].trim().trim('"')
            if (name.isNotBlank()) {
                out.add(
                    Storage(
                        name,
                        p[i + 1].trim().toIntOrNull() ?: 0,
                        p[i + 2].trim().toIntOrNull() ?: 0,
                    )
                )
            }
            i += 3
        }
        out
    }

    suspend fun setStorage(name: String): String? = ioLock.withLock {
        val r = at("AT+CPMS=\"$name\",\"ME\",\"ME\"")
        if (r.contains("ERROR")) "模块不支持存储区 $name" else { storage = name; null }
    }

    // ---------- 删除 ----------

    suspend fun deleteOne(index: Int): String? = ioLock.withLock {
        if (at("AT+CMGD=$index", timeoutMs = 10000).contains("ERROR"))
            "删除第 $index 条失败" else null
    }

    /** 1=已读 2=已读+已发 3=+未发 4=全部 */
    suspend fun deleteBulk(flag: Int): String? = ioLock.withLock {
        if (at("AT+CMGD=1,$flag", timeoutMs = 30000).contains("ERROR"))
            "批量删除失败，部分固件只支持逐条删" else null
    }

    // ---------- USB 网络模式 ----------

    /** 读出 usbnet 与 usbcfg 的原始返回，切换前备份用。 */
    suspend fun readBackup(): ConfigBackup? = ioLock.withLock {
        prepare()
        at("AT+CSCS=\"GSM\"")
        try {
            val net = at("AT+QCFG=\"usbnet\"", expect = "+QCFG:")
                .lines().firstOrNull { it.contains("+QCFG:") }?.trim() ?: return@withLock null
            val cfg = at("AT+QCFG=\"usbcfg\"", expect = "+QCFG:")
                .lines().firstOrNull { it.contains("+QCFG:") }?.trim() ?: ""
            ConfigBackup(net, cfg, System.currentTimeMillis())
        } finally {
            at("AT+CSCS=\"UCS2\"")
        }
    }

    /** 读当前 usbnet 值。返回 null 表示固件不支持这个参数。 */
    suspend fun usbnetMode(): Int? = ioLock.withLock {
        prepare()
        // UCS2 字符集会让模块把 "usbnet" 也按 UCS2 回显，查询前先切回 GSM
        at("AT+CSCS=\"GSM\"")
        try {
            var v = parseQcfgValue(at("AT+QCFG=\"usbnet\"", expect = "+QCFG:"))
            if (v == null) {
                delay(150)
                v = parseQcfgValue(at("AT+QCFG=\"usbnet\"", expect = "+QCFG:"))
            }
            v
        } finally {
            at("AT+CSCS=\"UCS2\"")
        }
    }

    /**
     * 切换 usbnet 并重启模块。返回 null 表示已下发。
     * 写入后会立即读回校验——部分定制固件返回 OK 但根本不存。
     */
    suspend fun setUsbnetMode(mode: Int): String? = ioLock.withLock {
        prepare()
        at("AT+CSCS=\"GSM\"")
        if (at("AT+QCFG=\"usbnet\",$mode").contains("ERROR")) {
            at("AT+CSCS=\"UCS2\"")
            return@withLock "模块拒绝了这条指令，固件可能不支持切换。"
        }
        val back = parseQcfgValue(at("AT+QCFG=\"usbnet\"", expect = "+QCFG:"))
        if (back != mode) {
            at("AT+CSCS=\"UCS2\"")
            return@withLock "写入没有生效，读回仍是 $back。大疆固件可能锁了 NV 写入。"
        }
        at("AT+CFUN=1,1", timeoutMs = 3000)   // 重启后新组合才生效
        null
    }

    suspend fun raw(cmd: String): String = ioLock.withLock { at(cmd, timeoutMs = 15000) }
}

// ---------- UCS2 ----------

fun ucs2Encode(s: String): String = s.map { "%04X".format(it.code) }.joinToString("")

fun maybeUcs2(s: String): String {
    val t = s.trim()
    if (t.length < 4 || t.length % 4 != 0) return s
    if (!t.all { it in "0123456789ABCDEFabcdef" }) return s
    return try {
        val out = t.chunked(4).map { it.toInt(16).toChar() }.joinToString("")
        if (out.any { it.code < 0x20 && it != '\n' && it != '\t' }) s else out
    } catch (e: Exception) {
        s
    }
}

// ---------- 验证码提取 ----------

private val CODE_HINTS = listOf(
    "验证码", "校验码", "动态码", "识别码", "确认码", "口令", "密码",
    "code", "otp", "verification", "passcode",
)
private val DIGITS = Regex("""(?<![0-9])([0-9]{4,8})(?![0-9])""")

/** 只在短信像验证码时提取，优先 6 位，其次 4 位。 */
fun extractCode(body: String): String? {
    val low = body.lowercase()
    if (CODE_HINTS.none { low.contains(it) }) return null
    val all = DIGITS.findAll(body).map { it.groupValues[1] }.toList()
    if (all.isEmpty()) return null
    return all.firstOrNull { it.length == 6 }
        ?: all.firstOrNull { it.length == 4 }
        ?: all.first()
}

// ---------- +CMGL 解析 ----------

/** 逗号分割，引号内的逗号不算（时间戳里有逗号）。 */
private fun splitCsv(line: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var q = false
    for (c in line) {
        when {
            c == '"' -> { q = !q; sb.append(c) }
            c == ',' && !q -> { out.add(sb.toString()); sb.setLength(0) }
            else -> sb.append(c)
        }
    }
    out.add(sb.toString())
    return out
}

fun parseCmgl(raw: String): List<Sms> {
    val out = mutableListOf<Sms>()
    val lines = raw.split("\r\n", "\n")
    var i = 0
    while (i < lines.size) {
        val l = lines[i].trim()
        if (l.startsWith("+CMGL:")) {
            val p = splitCsv(l.removePrefix("+CMGL:").trim())
            out.add(
                Sms(
                    index = p.getOrNull(0)?.trim()?.toIntOrNull() ?: -1,
                    stat = maybeUcs2(p.getOrNull(1)?.trim()?.trim('"') ?: ""),
                    from = maybeUcs2(p.getOrNull(2)?.trim()?.trim('"') ?: ""),
                    time = p.getOrNull(4)?.trim()?.trim('"') ?: "",
                    body = maybeUcs2(lines.getOrNull(i + 1)?.trim() ?: ""),
                )
            )
            i += 2
        } else i++
    }
    return out.sortedByDescending { it.index }
}
