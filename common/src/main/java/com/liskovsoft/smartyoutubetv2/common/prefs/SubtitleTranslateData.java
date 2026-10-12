package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataSaverBase;

/**
 * Settings for LLM based subtitle translation (OpenAI compatible /chat/completions API).
 */
public class SubtitleTranslateData extends DataSaverBase {
    /**
     * Each language is written the way it names itself, so it can be passed to the model as-is
     * without extra string resources.
     */
    public static final String[] TARGET_LANGUAGES = {
            "简体中文", "繁體中文", "English", "日本語", "한국어",
            "Español", "Français", "Deutsch", "Português", "Русский",
            "العربية", "हिन्दी", "ไทย", "Tiếng Việt", "Bahasa Indonesia"
    };
    private static final int ENABLED = 0;
    private static final int API_URL = 1;
    private static final int API_KEY = 2;
    private static final int MODEL = 3;
    private static final int TARGET_LANGUAGE = 4;
    private static final int BILINGUAL = 5;
    @SuppressLint("StaticFieldLeak")
    private static SubtitleTranslateData sInstance;

    private SubtitleTranslateData(Context context) {
        super(context);
    }

    public static SubtitleTranslateData instance(Context context) {
        if (sInstance == null) {
            sInstance = new SubtitleTranslateData(context.getApplicationContext());
        }

        return sInstance;
    }

    public boolean isEnabled() {
        return getBoolean(ENABLED, false);
    }

    public void setEnabled(boolean enable) {
        setBoolean(ENABLED, enable);
    }

    public String getApiUrl() {
        return getString(API_URL, null);
    }

    public void setApiUrl(String apiUrl) {
        setString(API_URL, apiUrl);
    }

    public String getApiKey() {
        return getString(API_KEY, null);
    }

    public void setApiKey(String apiKey) {
        setString(API_KEY, apiKey);
    }

    public String getModel() {
        return getString(MODEL, null);
    }

    public void setModel(String model) {
        setString(MODEL, model);
    }

    public String getTargetLanguage() {
        return getString(TARGET_LANGUAGE, TARGET_LANGUAGES[0]);
    }

    public void setTargetLanguage(String language) {
        setString(TARGET_LANGUAGE, language);
    }

    public boolean isBilingual() {
        return getBoolean(BILINGUAL, true);
    }

    public void setBilingual(boolean bilingual) {
        setBoolean(BILINGUAL, bilingual);
    }

    public boolean isConfigured() {
        return getApiUrl() != null && !getApiUrl().trim().isEmpty()
                && getModel() != null && !getModel().trim().isEmpty();
    }
}
