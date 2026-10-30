package com.aicode.feature.agent.domain.device

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ROOT「伪前台」：以 root 身份让系统一直把本应用当前台进程看待，从而不再
 * 挂起它的网络。仅在设备已取得 ROOT 时生效，无 ROOT 静默跳过。
 *
 * ## 为什么必须这么干
 *
 * 切后台后 SSE 静默挂起，实测存在**两层互相独立**的限制，只解一层不够：
 *
 * 1. **cgroup v2 freezer**：线程被整个挂起，停在 socket `read()` 上——不推进、
 *    也不抛异常（所以日志里没有 error）；解冻瞬间积压的 RST 一次性爆发成
 *    `SocketException: Software caused connection abort`，两个独立连接会同毫秒
 *    双双毙命，因为那是进程级事件而非对端超时。
 *
 *    注意 cgroup 冻结是**层级继承**的，UID 级 freeze=1 时下属进程全冻，
 *    进程级文件显示 0 也无效——只看进程级会得出「没被冻结」的错误结论。
 *    且冻结时 `/proc/<pid>/status` 仍是 `S (sleeping)`（`TASK_FROZEN` 只是
 *    `TASK_INTERRUPTIBLE` 加标志位），不能用进程状态判断冻结。
 *
 * 2. **ROM 私有后台网络管控**：ColorOS（OPPO/一加）有 `hans` 原生守护，
 *    把后台 UID 写进 netd 的 eBPF map（`map_oplus-netd_app_drop_wlan_socket_uid_limit_map`
 *    等），在 socket 层直接丢包。此时 iptables 里**一条规则都没有**，查防火墙
 *    白查；表现为 `UnknownHostException`（DNS 报文被丢），跟冻结是两码事。
 *
 * 两者都与前台服务、`PARTIAL_WAKE_LOCK`、`WifiLock`、`netpolicy` 白名单无关——
 * 那些保的是 CPU/优先级/后台数据配额，管不了「线程不执行」和「socket 被丢包」。
 *
 * ## 对策：持续维持而非一次性设置
 *
 * 系统会不断把状态改回去，所以一次性设置撑不过几秒。这里拉一个 root 常驻
 * 守护循环维持（以 marker 文件保证单实例，本应用进程消失后自行退出）：
 *
 * - 高频（每 2s）：解冻 UID 级 + 全部进程级 `cgroup.freeze`；
 * - 低频（每 10s）：`standby bucket = active`、后台运行 AppOps allow；
 * - 每次检查：`stop hans`（仅 ColorOS 存在该服务时）；
 * - 一次性：关闭全局 `app_standby_enabled`、`netpolicy` 后台数据白名单。
 *
 * 刻意**不申请 Doze 白名单**：息屏后本就不需要继续工作，只针对网络被挂起。
 *
 * 守护的 stdout/stderr 必须重定向到 `/dev/null`：调用方把 stderr 合并进了
 * stdout 并读到 EOF，后台进程持有管道会让 `waitFor()` 卡满超时。
 */
@Singleton
class RootNetworkGuard @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deviceRootEngine: DeviceRootEngine
) {
    private val mutex = Mutex()

    @Volatile
    private var lastAppliedAtMs = 0L

    /**
     * 应用后台网络豁免。必须在 IO 线程调用（内部经 `su` 子进程）。
     *
     * 同一会话内按 [APPLY_INTERVAL_MS] 节流：这些设置一旦生效就持续有效，
     * 没必要每条消息都 fork 一次 su。失败不抛，交由上层继续走常规保活路径。
     *
     * @return 是否已（或本次刚刚）生效；无 ROOT 返回 false。
     */
    suspend fun applyIfRooted(): Boolean {
        // ROOT 探测本身有缓存，未取得 ROOT 时静默跳过
        if (!deviceRootEngine.isProvisioned()) return false

        return mutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastAppliedAtMs < APPLY_INTERVAL_MS) return@withLock true

            val pkg = context.packageName
            // 一条脚本一次 su：先做一次性设置，再落守护脚本并后台拉起。
            // $pkg 由 Kotlin 注入；\$xxx 是设备 shell 的变量，需转义。
            val script = buildString {
                append("uid=\$(stat -c %u /data/data/$pkg 2>/dev/null); ")
                // ---- 一次性：关掉会把后台网络推后的 App Standby，加后台数据白名单 ----
                append("settings put global app_standby_enabled 0; ")
                append("[ -n \"\$uid\" ] && cmd netpolicy add restrict-background-whitelist \$uid; ")
                // ---- 立即解冻一次，不等守护首个 tick ----
                append("if [ -n \"\$uid\" ]; then for f in /sys/fs/cgroup/apps/uid_\$uid/cgroup.freeze ")
                append("/sys/fs/cgroup/uid_\$uid/cgroup.freeze ")
                append("/sys/fs/cgroup/apps/uid_\$uid/pid_*/cgroup.freeze ")
                append("/sys/fs/cgroup/uid_\$uid/pid_*/cgroup.freeze; ")
                append("do [ -f \"\$f\" ] && echo 0 > \"\$f\" 2>/dev/null; done; fi; ")
                append("am set-standby-bucket $pkg active; ")
                append("cmd appops set $pkg RUN_IN_BACKGROUND allow; ")
                append("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; ")
                // ---- 停掉 ROM 私有的后台网络管控守护（ColorOS 的 hans）----
                append("if [ \"\$(getprop init.svc.hans)\" = running ]; then stop hans; fi; ")
                // ---- 落守护脚本：heredoc 引用符避免 shell 提前展开 ----
                append("cat > $GUARD_PATH <<'AICODE_GUARD_EOF'\n")
                append("#!/system/bin/sh\n")
                append("u=\$(stat -c %u /data/data/$pkg 2>/dev/null)\n")
                append("op=\$(cat $GUARD_MARKER 2>/dev/null)\n")
                append("if [ -n \"\$op\" ] && [ -d /proc/\$op ]; then exit 0; fi\n")
                append("echo \$\$ > $GUARD_MARKER 2>/dev/null\n")
                append("i=0\n")
                append("while [ \$i -lt $GUARD_MAX_TICKS ]; do\n")
                append("  pidof $pkg >/dev/null 2>&1 || break\n")
                // 高频：解冻（纯 echo，开销可忽略）
                append("  for f in /sys/fs/cgroup/apps/uid_\$u/cgroup.freeze /sys/fs/cgroup/uid_\$u/cgroup.freeze ")
                append("/sys/fs/cgroup/apps/uid_\$u/pid_*/cgroup.freeze /sys/fs/cgroup/uid_\$u/pid_*/cgroup.freeze; do\n")
                append("    [ -f \"\$f\" ] && echo 0 > \"\$f\" 2>/dev/null\n")
                append("  done\n")
                // 每次：压住 ROM 私有管控守护（它会在 socket 层丢后台 UID 的包）
                append("  if [ \"\$(getprop init.svc.hans)\" = running ]; then stop hans; fi\n")
                // 低频：am/cmd 是 java 命令，fork 开销较大，降频执行
                append("  if [ \$((i % $STATE_EVERY_TICKS)) -eq 0 ]; then\n")
                append("    am set-standby-bucket $pkg active >/dev/null 2>&1\n")
                append("    cmd appops set $pkg RUN_IN_BACKGROUND allow >/dev/null 2>&1\n")
                append("    cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1\n")
                append("  fi\n")
                append("  sleep $GUARD_TICK_SECONDS\n")
                append("  i=\$((i+1))\n")
                append("done\n")
                append("rm -f $GUARD_MARKER 2>/dev/null\n")
                append("AICODE_GUARD_EOF\n")
                append("nohup sh $GUARD_PATH >/dev/null 2>&1 &\n")
                // ---- 回读关键状态，便于日志一眼确认是否真的生效 ----
                append("echo \"freeze=\$(cat /sys/fs/cgroup/apps/uid_\$uid/cgroup.freeze 2>/dev/null) ")
                append("hans=\$(getprop init.svc.hans) bucket=\$(am get-standby-bucket $pkg)\"")
            }

            val result = runCatching {
                deviceRootEngine.runCommandSyncWithExit(script, null, TIMEOUT_MS)
            }.getOrElse {
                FileLogger.w(TAG, "ROOT 网络豁免执行失败: ${it.message}")
                return@withLock false
            }

            if (result.exitCode == null) {
                FileLogger.w(TAG, "ROOT 网络豁免未完成: ${result.output.take(200)}")
                return@withLock false
            }
            lastAppliedAtMs = now
            val status = result.output.lineSequence()
                .firstOrNull { it.contains("freeze=") }
                ?.trim()
            FileLogger.i(
                TAG,
                "ROOT 伪前台已布置(exit=$result.exitCode) $status"
            )
            true
        }
    }

    private companion object {
        const val TAG = "RootNetworkGuard"

        /** 单条脚本超时：`cmd netpolicy` 偶发卡顿，给足但不至于拖住任务启动。 */
        const val TIMEOUT_MS = 15_000L

        /**
         * 节流间隔：只决定多久重新「校验并补拉」一次守护，守护自身在此期间持续维持。
         * 不能太长——守护有可能被系统清理，间隔过长会留出一个无人维持的空窗；
         * 脚本本身幂等（marker 保单实例）且耗时不足 1 秒，1 分钟校验一次足够廉价。
         */
        const val APPLY_INTERVAL_MS = 60 * 1000L

        /** 守护脚本落盘路径。 */
        const val GUARD_PATH = "/data/local/tmp/.aicode_guard.sh"

        /** 守护单实例 marker：存 pid，进程消失即视为失效。 */
        const val GUARD_MARKER = "/data/local/tmp/.aicode_guard"

        /** 守护每次循环间隔（秒）：系统重新冻结的节奏是秒级，2 秒足够压住。 */
        const val GUARD_TICK_SECONDS = 2

        /** `am`/`cmd` 是 java 命令、fork 开销较大，每 N 个 tick 才执行一次。 */
        const val STATE_EVERY_TICKS = 5

        /** 守护最多存活的 tick 数：2s × 1800 = 60 分钟，覆盖一次长任务的量级。 */
        const val GUARD_MAX_TICKS = 1800
    }
}
