package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class DefaultProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key })

    @Test fun seedsProviderKeyAndModelsOnce() {
        val s = store()
        assertTrue(DefaultProvider.seed(s, tmp.root))
        val p = s.providers.value.single()
        assertEquals(DefaultProvider.BASE_URL, p.baseUrl)
        assertEquals(DefaultProvider.API_KEY, s.providerKey(p.id))
        assertEquals(DefaultProvider.MAIN_MODEL, s.configFor(ModelRole.Main)?.model)
        assertEquals(DefaultProvider.VISION_MODEL, s.configFor(ModelRole.Vision)?.model)
        assertTrue(p.models.orEmpty().size > 1)
        // 用户删掉后不会再回来
        s.removeProvider(p.id)
        assertFalse(DefaultProvider.seed(s, tmp.root))
        assertTrue(s.providers.value.isEmpty())
    }

    @Test fun skipsWhenAProviderAlreadyExists() {
        val s = store()
        s.connect("Mine", ProviderConfig.Kind.OpenAiCompat, "https://mine.test/v1", "k", emptyList())
        assertFalse(DefaultProvider.seed(s, tmp.root))
        assertEquals(1, s.providers.value.size)
    }
}
