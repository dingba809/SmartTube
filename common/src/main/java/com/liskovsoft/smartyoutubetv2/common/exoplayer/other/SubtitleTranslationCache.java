package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps translated subtitle lines in memory so repeated cues (seek back, repeated lines)
 * and re-renders don't hit the API again.
 */
public class SubtitleTranslationCache {
    private static final int MAX_ENTRIES = 1000;
    private final Runnable mOnChange;

    public SubtitleTranslationCache() {
        this(null);
    }

    public SubtitleTranslationCache(Runnable onChange) {
        mOnChange = onChange;
    }

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
        if (source != null && translation != null && !translation.trim().isEmpty()) {
            String previous = mCache.put(source, translation);
            if (!translation.equals(previous) && mOnChange != null) {
                mOnChange.run();
            }
        }
    }

    public void clear() {
        mCache.clear();
    }
}
