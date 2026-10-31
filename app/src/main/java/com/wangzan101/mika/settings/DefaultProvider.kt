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

    const val MAIN_MODEL = "minimaxai/minimax-m3.1-flash"
    const val VISION_MODEL = "z-ai/glm-5.3-flash"

    private const val MARKER = "default-provider-seeded"

    private val mainCaps = Capabilities(toolCalls = true, vision = true, contextTokens = 262_144, reasoning = false)
    private val visionCaps = Capabilities(toolCalls = true, vision = true, contextTokens = 128_000, reasoning = false)

    /** [settingsDir] 是 [ProviderStore] 用的目录(放一个标记文件,记下已预置过)。返回是否做了预置。 */
    fun seed(store: ProviderStore, settingsDir: File): Boolean {
        val marker = File(settingsDir, MARKER)
        if (marker.exists()) return false
        if (store.providers.value.isNotEmpty()) {
            marker.runCatching { parentFile?.mkdirs(); writeText("skipped") }
            return false
        }
        val provider = store.connect(
            NAME, ProviderConfig.Kind.OpenAiCompat, BASE_URL, API_KEY,
            models = listOf(
                ListedModel(MAIN_MODEL, toolCalls = true, vision = true, contextTokens = 262_144),
                ListedModel(VISION_MODEL, toolCalls = true, vision = true, contextTokens = 128_000),
            ),
        )
        val ok = store.use(ModelRole.Main, provider.id, MAIN_MODEL, mainCaps) &&
            store.use(ModelRole.Vision, provider.id, VISION_MODEL, visionCaps)
        marker.runCatching { parentFile?.mkdirs(); writeText(if (ok) "seeded" else "failed") }
        return ok
    }
}
