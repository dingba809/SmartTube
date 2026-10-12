package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Plain JUnit on purpose. Robolectric 4.6.1 cannot run on the JDK 17 CI runner: its ASM
 * version chokes on the class files. So only code that touches no Android API is covered
 * here: URL normalization, answer sanitizing and the translation cache.
 */
public class SubtitleTranslationTest {
    @Test
    public void buildEndpointAcceptsHostV1AndFullEndpoint() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                SubtitleTranslator.buildEndpoint("https://api.deepseek.com"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                SubtitleTranslator.buildEndpoint("https://api.deepseek.com/"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                SubtitleTranslator.buildEndpoint("https://api.deepseek.com/v1"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                SubtitleTranslator.buildEndpoint("https://api.deepseek.com/v1/chat/completions"));
    }

    @Test
    public void sanitizeStripsQuotesAndCodeFence() {
        assertEquals("你好", SubtitleTranslator.sanitize("  你好  "));
        assertEquals("你好", SubtitleTranslator.sanitize("\"你好\""));
        assertEquals("你好", SubtitleTranslator.sanitize("```\n你好\n```"));
    }

    @Test
    public void sanitizeRejectsEmpty() {
        assertNull(SubtitleTranslator.sanitize(null));
        assertNull(SubtitleTranslator.sanitize(""));
        assertNull(SubtitleTranslator.sanitize("   "));
    }

    @Test
    public void cacheKeepsTranslationUntilCleared() {
        SubtitleTranslationCache cache = new SubtitleTranslationCache();

        assertNull(cache.get("hello"));
        cache.put("hello", "你好");
        assertEquals("你好", cache.get("hello"));

        cache.clear();
        assertNull(cache.get("hello"));
    }

    @Test
    public void cacheIgnoresNullsAndEvictsOldest() {
        SubtitleTranslationCache cache = new SubtitleTranslationCache();

        cache.put(null, "x");
        cache.put("x", null);
        assertNull(cache.get(null));
        assertNull(cache.get("x"));

        for (int i = 0; i < 2000; i++) {
            cache.put("line " + i, "译 " + i);
        }

        assertEquals("译 1999", cache.get("line 1999"));
        assertNull(cache.get("line 0"));
    }
}
