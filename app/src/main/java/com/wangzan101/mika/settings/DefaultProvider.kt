package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelRole
import java.io.File

/**
 * 这个 fork 自带的测试服务商:NVIDIA build.nvidia.com 的免费端点(OpenAI 兼容,约 40 次请求/分钟)。
 * 首次启动时预先填好服务商、Key 和模型,装上 APK 就能用;Key 是公开的测试 Key,随时可能被撤销,
 * 失效后到「设置 → 模型」换成自己的即可。只在没有任何服务商、且从没预置过时做一次,用户删掉后不会再回来。
 */
object DefaultProvider {
    const val NAME = "NVIDIA"
    const val BASE_URL = "https://integrate.api.nvidia.com/v1"
    const val API_KEY = "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"

    /**
     * 主模型:Nemotron 3 Ultra 550B。在平板上用同一个任务(打开小红书 App、刷 5 条首页帖子并总结)实测:
     * 跑完、约 3.7 分钟、单次调用约 10 秒、没被限流,答案最细。纯文字模型,靠读界面文字做事。
     */
    const val MAIN_MODEL = "nvidia/nemotron-3-ultra-550b-a55b"

    /** 看图模型:Mistral Large 4(1T 参数),同一任务也跑完了,单次约 25 秒,能看图。 */
    const val VISION_MODEL = "mistralai/mistral-large-4-1t-a52b"

    private const val MARKER = "default-provider-seeded"

    private val mainCaps = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
    private val visionCaps = Capabilities(toolCalls = true, vision = true, contextTokens = 128_000, reasoning = false)

    /**
     * 预先列出的可选模型,都实测过带工具调用。MiniMax M3.1 Flash 在这个 Key 上几乎每次调用都被限流(429),
     * GLM 5.3 Flash、Step 5 单次 20 到 50 秒,都没放进来;设置里可以刷新成这个 Key 列出的全部模型。
     */
    private val models = listOf(
        ListedModel(MAIN_MODEL, toolCalls = true, vision = false, contextTokens = 128_000),
        ListedModel(VISION_MODEL, toolCalls = true, vision = true, contextTokens = 128_000),
        ListedModel("nvidia/nemotron-3.5-super-vl-120b-a12b", toolCalls = true, vision = true, contextTokens = 128_000),
        ListedModel("meta/muse-glimmer-30b", toolCalls = true, vision = true, contextTokens = 128_000),
        ListedModel("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning", toolCalls = true, contextTokens = 128_000),
        ListedModel("poolside/laguna-xs-2.1", toolCalls = true, vision = false, contextTokens = 128_000),
        ListedModel("openai/gpt-oss-20b", toolCalls = true, vision = false, contextTokens = 128_000),
        ListedModel("nvidia/nemotron-3.5-lightning-30b-a3b", toolCalls = true, vision = false, contextTokens = 128_000),
        ListedModel("nvidia/nemotron-3.5-bolt-e4b", toolCalls = true, vision = false, contextTokens = 128_000),
    )

    /** [settingsDir] 是 [ProviderStore] 用的目录(放一个标记文件,记下已预置过)。返回是否做了预置。 */
    fun seed(store: ProviderStore, settingsDir: File): Boolean {
        val marker = File(settingsDir, MARKER)
        if (marker.exists()) return false
        if (store.providers.value.isNotEmpty()) {
            marker.runCatching { parentFile?.mkdirs(); writeText("skipped") }
            return false
        }
        val provider = store.connect(NAME, ProviderConfig.Kind.OpenAiCompat, BASE_URL, API_KEY, models)
        // 主模型是纯文字的:看图交给 Mistral Large 4;轻量任务默认用主模型
        val ok = store.use(ModelRole.Main, provider.id, MAIN_MODEL, mainCaps) &&
            store.use(ModelRole.Vision, provider.id, VISION_MODEL, visionCaps)
        marker.runCatching { parentFile?.mkdirs(); writeText(if (ok) "seeded" else "failed") }
        return ok
    }
}
