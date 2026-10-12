package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps translated subtitle lines in memory so repeated cues (seek back, repeated lines)
 * and re-renders don't hit the API again.
 */
public class SubtitleTranslationCache {
    private static final int MAX_ENTRIES = 1000;
    private final Map<String, String> mCache = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public String get(String source) {
        return source == null ? null : mCache.get(source);
    }

    public void put(String source, String translation) {
        if (source != null && translation != null) {
            mCache.put(source, translation);
        }
    }

    public void clear() {
        mCache.clear();
    }
}
