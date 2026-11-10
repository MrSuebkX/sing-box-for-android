package io.nekohasekai.sfa.mihomo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubscriptionLinkTest {
    @Test fun preservesEncodedTokensInDirectUrls() {
        val url = "https://example.com/sub?token=a%2Bb%26c&target=clash"
        assertEquals(url, SubscriptionLink.parse(url).url)
    }

    @Test fun decodesOnlyTheOuterLink() {
        val link = "clash://install-config?url=https%3A%2F%2Fexample.com%2Fsub%3Ftoken%3Da%252Bb%2526c&name=%E6%B5%8B%E8%AF%95"
        assertEquals(SubscriptionLink.Subscription("https://example.com/sub?token=a%2Bb%26c", "测试"), SubscriptionLink.parse(link))
    }

    @Test fun supportsClashMeta() {
        assertEquals("https://example.com/sub", SubscriptionLink.parse("clashmeta://install-config?url=https%3A%2F%2Fexample.com%2Fsub").url)
    }

    @Test fun rejectsAmbiguousOrUnsafeLinks() {
        for (link in listOf("clash://install-config", "clash://install-config?url=file%3A%2F%2Fetc%2Fpasswd", "clash://install-config?url=https://a.com&url=https://b.com", "https://user:password@example.com/sub", "clash://wrong?url=https://example.com")) {
            assertThrows(IllegalArgumentException::class.java) { SubscriptionLink.parse(link) }
        }
    }
}
