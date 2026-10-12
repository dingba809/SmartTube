package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
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
    public void parseExtractsMessageContent() {
        assertEquals("你好，世界", SubtitleTranslator.parse(choicesOf("你好，世界")));
    }

    @Test
    public void parseStripsQuotesAndCodeFence() {
        assertEquals("你好", SubtitleTranslator.parse(choicesOf("\"你好\"")));
        assertEquals("你好", SubtitleTranslator.parse(choicesOf("```\n你好\n```")));
    }

    @Test
    public void parseRejectsMalformedResponse() {
        assertNull(SubtitleTranslator.parse(null));
        assertNull(SubtitleTranslator.parse("{ not json"));
        assertNull(SubtitleTranslator.parse("{\"choices\":[]}"));
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

    private static String choicesOf(String content) {
        return "{\"choices\":[{\"message\":{\"content\":" + JSONObject.quote(content) + "}}]}";
    }
}
