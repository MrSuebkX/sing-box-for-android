package io.nekohasekai.sfa.mihomo

import org.junit.Assert.*
import org.junit.Test

class CoreConfigTest {
    @Test fun leavesSingBoxJsonWithItsOwnParser() {
        assertNull(CoreConfig.clashMap("{\"inbounds\":[],\"outbounds\":[]}"))
        assertNull(CoreConfig.clashMap("// comment\n{}"))
    }

    @Test fun recognizesYamlAndClashJson() {
        assertNotNull(CoreConfig.clashMap("proxies: []\nproxy-groups: []\nrules: [MATCH,DIRECT]"))
        assertNotNull(CoreConfig.clashMap("{\"proxy-providers\":{}}"))
    }

    @Test fun rejectsDuplicateKeysAndYamlObjectTags() {
        assertThrows(Exception::class.java) { CoreConfig.clashMap("proxies: []\nproxies: []") }
        assertThrows(Exception::class.java) { CoreConfig.clashMap("!!java.net.URL ['https://example.com']") }
    }
}
