# Mika 轻量架构评审

## 1. 架构一句话

用户的一句话从对话界面、定时触发或子任务进来，由 `TaskService`/`TaskCenter` 建成一个 Run（每个对话同时只跑一个）。`TaskRun` 按 `RunSpec` 装好能力，然后进入 `AgentLoop` 主循环。主循环里，`ModelCaller` 去问模型；模型给出工具调用，就经 `ToolDispatcher` 交给 **`ActionExecutor`（唯一执行入口）**，依次走守卫 → 锁屏 → 不可逆判定/审批 → 节奏间隔 → 执行 → 核对 → 记录。执行落到三种能力上：

- **phone**：虚拟屏 + 无障碍 + shell 身份的助手
- **web**：私有虚拟显示上的 WebView，用 CDP 驱动
- **sandbox**：PRoot 跑的 Alpine Linux

执行结果统一包成 `Observation` 回到上下文。模型某次回复不带工具调用，这条回复就是最终回答，经 `Outbox` 送达。

## 2. 三个最巧妙的设计

1. **编译期保证只有一个执行入口。** `ToolDispatcher.execute` 需要一个 `ActionPass` 参数，而它的构造函数是 `internal`，只有 `ActionExecutor` 能造出来，想绕过审批直接执行在编译时就过不了（architecture §4）。动作一旦开始，就放在 `NonCancellable` 里做完并记下，取消也不会把它掐在半中间（`ActionExecutor.kt:141-146`）。
2. **不要 root 就拿到 shell 权限，交接还校验得很严。** 作者自己实现了 ADB 协议，经手机的"无线调试"拉起一个 uid 2000 的助手进程，由它建虚拟屏、注入输入。交接 Binder 时要依次核对：调用方是不是 shell、它自报的 uid/pid 和 Binder 看到的是否一致、跑的是不是当前这份 APK、一次性口令对不对（`HelperHandoff.kt:72-81`，口令是 32 字节随机数，见 `HandoffNonces`）。入口 provider 还额外要求 `INTERACT_ACROSS_USERS_FULL` 权限（`AndroidManifest.xml:143-148`）。
3. **插话不打乱缓存，也不打乱节奏。** 用户在任务进行中插话时，只撤掉正在等的那次模型请求；历史只往后追加，请求前缀不变，提示词缓存照样能用。被打断的等待，没等够的时间下次动作前会补足，所以插话不会让下一次对外动作来得更快（`RunInbox`、architecture §2「收件箱与插话」）。再加上 `:core` 是纯 JVM 模块，由 `PackageDependencyTest` 管住包之间的依赖方向，主循环在电脑上用假模型就能跑测试。

## 3. 三个最值得警惕的风险

1. **审批门太松，而且碰上了后台操作。** `ApprovalPolicy.decide` 的第一条就是 `!risk.payment -> Allow`（`Approval.kt:48-52`）：只有花钱的那一步才问用户。发消息、删除、发帖、改账号或安全设置、往密码框里打字、读不出目标的点击，全部直接执行。README 还写明"成批做事直接开始，不用先跟你确认名单"。再加上它在用户看不见的后台屏上操作，模型一旦认错联系人或误读界面，**不可逆的错误会在没人看着的情况下成批发生**。而且"是不是花钱"本身也是靠屏上有没有带币种金额这类启发式规则来判断的。
2. **作者承认、但明确暂不修的安全网缺口**（roadmap「已知问题」1）：
   - `reviewFinalAnswer = false`：最终核查关着，回答一律记为"未核实"，学习和回放也因此不会生效。
   - 自主模式下，`ConfirmDecision.judge` 里 `autonomous` 的提前返回排在 `ownApp` 检查之前，所以"不操作 Mika 自己界面"这层保护失效，连付款也不拦。
   - roadmap 第 10 条自己也写了：评测时如果开着自主模式，下单题"会真的下单"。
3. **权限和调试面开得很大，加上脚本沙箱有已知的绕过点。** `WebView.setWebContentsDebuggingEnabled(true)` 无条件开启，没按 debug 构建区分（`WebDevtools.kt:288`）。它同时默认开着"自动打开无线调试"和"替我打开无障碍"，还声明了 `WRITE_SECURE_SETTINGS`（Manifest:24-26）。结果是：只要能 adb 连上这台手机，就能拿到内置浏览器里所有已登录站点的会话。只读脚本 `ReadScript` 的已知绕过点也都没修（roadmap 3）：
   - CSS 转义和 `:is()` 里拼出来的选择器能绕过 `[value` 拦截；
   - 秘密输入框的 `valueAsNumber`、`selectionStart` 等属性能间接读出内容；
   - `fetch` 的请求头全部放行。

## 4. 给 fork 者的建议

1. **先收紧审批门**（成本最低，收益最大）。`ApprovalPolicy` 是个纯函数，改起来很容易：把 `ActionRisk.Irreversible/External`（发送、删除、改安全设置、往密码框输入）也改成 `Ask`；无人在场的定时任务遇到这类动作一律 `Deny`；成批对外动作开工前先让用户确认一次名单和数量。
2. **修好安全网的顺序和开关。** 把 `ConfirmDecision.judge` 里 `ownApp` 的检查挪到 `autonomous` 提前返回之前。至少对做过对外动作的运行打开 `reviewFinalAnswer`，`RunEvent.Approval` 里已经记着"做过对外的事"，正好拿来判断。
3. **缩小攻击面。** WebView 调试 CDP 驱动确实要用，不能直接关，但"自动打开无线调试"和"重启后自动恢复"建议改成默认关、由用户主动开启。roadmap 第 3 条列出的 ReadScript 待办（先解析选择器再判断、秘密输入框只放行白名单属性、请求头白名单）建议在 fork 时优先补上。

---

以上只读了你指定的文件，而且只看了相关片段，没有改动仓库，也没有运行构建。按 AGENTS.md 的规定，发送前应先跑 reply-gate 打分，但本会话只能访问当前仓库目录，脚本路径不在其中，所以没有跑，回复末尾也就没有打分页脚。
