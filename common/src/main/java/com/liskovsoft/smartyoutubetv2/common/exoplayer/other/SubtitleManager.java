package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build.VERSION;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.CaptioningManager;
import android.view.accessibility.CaptioningManager.CaptionStyle;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SubtitleTranslateData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SubtitleManager implements TextOutput, OnDataChange {
    private static final String TAG = SubtitleManager.class.getSimpleName();
    private final SubtitleView mSubtitleView;
    private final Context mContext;
    private final List<SubtitleStyle> mSubtitleStyles = new ArrayList<>();
    private final AppPrefs mPrefs;
    private final PlayerData mPlayerData;
    private final SubtitleTranslateData mTranslateData;
    private final SubtitleTranslationCache mTranslationCache = new SubtitleTranslationCache();
    private final Set<String> mPendingTranslations = new HashSet<>();
    private final SubtitleTranslator mTranslator = new SubtitleTranslator();
    private final SubtitleTextDeduplicator mDeduplicator = new SubtitleTextDeduplicator();
    private List<Cue> mLastCues = new ArrayList<>();
    private long mCuesVersion;
    private boolean mPrefetchStarted;
    private String mTranslateConfig;

    public static class SubtitleStyle {
        public final int nameResId;
        public final int subsColorResId;
        public final int backgroundColorResId;
        public final int captionStyle;

        public SubtitleStyle(int nameResId) {
            this(nameResId, -1, -1, -1);
        }

        public SubtitleStyle(int nameResId, int subsColorResId, int backgroundColorResId, int captionStyle) {
            this.nameResId = nameResId;
            this.subsColorResId = subsColorResId;
            this.backgroundColorResId = backgroundColorResId;
            this.captionStyle = captionStyle;
        }

        public boolean isSystem() {
            return subsColorResId == -1 && backgroundColorResId == -1 && captionStyle == -1;
        }
    }

    public SubtitleManager(SubtitleView subtitleView) {
        mContext = subtitleView.getContext();
        mSubtitleView = subtitleView;
        mPrefs = AppPrefs.instance(mContext);
        mPlayerData = PlayerData.instance(mContext);
        mTranslateData = SubtitleTranslateData.instance(mContext);
        mPlayerData.setOnChange(this);
        mTranslateData.setOnChange(this);
        mTranslateConfig = buildTranslateConfig();
        configureSubtitleView();
    }

    @Override
    public void onDataChange() {
        configureSubtitleView();

        // PlayerData fires on every playback tweak, so only restart when the translation
        // settings themselves changed. Clearing the cache on every callback threw away work
        // that was still in flight and kept the translations behind the audio.
        String config = buildTranslateConfig();

        if (config.equals(mTranslateConfig)) {
            return;
        }

        mTranslateConfig = config;
        mTranslationCache.clear();
        SubtitlePrefetcher.instance().stop();
        mPrefetchStarted = false;
        renderSubtitles();
    }

    @Override
    public void onCues(List<Cue> cues) {
        if (mSubtitleView != null) {
            mLastCues = forceCenterAlignment(cues);
            startPrefetchIfNeeded();
            renderSubtitles();
        }
    }

    /**
     * ExoPlayer is already loading this track, so its url is known and the whole file can be
     * fetched and translated in batches before the cues reach us. Everything it produces lands
     * in the cache, which is what makes playback appear instant.
     */
    private void startPrefetchIfNeeded() {
        if (mPrefetchStarted || !isTranslationEnabled()) {
            return;
        }

        mPrefetchStarted = true;

        SubtitlePrefetcher.instance().start(mTranslateData.getApiUrl(), mTranslateData.getApiKey(),
                mTranslateData.getModel(), mTranslateData.getTargetLanguage(),
                getSelectedTrackId(), mTranslationCache);
    }

    /**
     * @return {@code FormatItem.getFormatId()} of the subtitle track being played, which is the
     * caption track vssId and therefore matches {@code MediaSubtitle.getVssId()}
     */
    private String getSelectedTrackId() {
        PlaybackView player = PlaybackPresenter.instance(mContext).getPlayer();

        if (player == null) {
            return null;
        }

        for (FormatItem format : player.getSubtitleFormats()) {
            if (format.isSelected() && !format.isDefault()) {
                return format.getFormatId();
            }
        }

        return null;
    }

    private void renderSubtitles() {
        if (mSubtitleView == null) {
            return;
        }

        if (!isTranslationEnabled()) {
            mSubtitleView.setCues(mLastCues);
            return;
        }

        // Bumping the version invalidates translations that are still in flight for a previous cue.
        long version = ++mCuesVersion;
        List<Cue> rendered = new ArrayList<>();

        for (Cue cue : mLastCues) {
            String source = cue.text.toString();
            String translation = mTranslationCache.get(source);

            if (translation != null) {
                rendered.add(new Cue(join(source, translation)));
            } else {
                requestTranslation(source, version);

                // Keep the original on screen until the translation lands, but only when the
                // user asked for both lines: in translation only mode showing the original
                // first looks like the subtitle flickering between two languages.
                if (mTranslateData.isBilingual()) {
                    rendered.add(cue);
                }
            }
        }

        mSubtitleView.setCues(rendered);
    }

    private void requestTranslation(String source, long version) {
        if (!isTranslatable(source) || !mPendingTranslations.add(source)) {
            return;
        }

        mTranslator.translate(mTranslateData.getApiUrl(), mTranslateData.getApiKey(), mTranslateData.getModel(),
                mTranslateData.getTargetLanguage(), source, translation -> {
                    mPendingTranslations.remove(source);

                    if (translation == null) {
                        return;
                    }

                    mTranslationCache.put(source, translation);

                    if (version == mCuesVersion) {
                        renderSubtitles();
                    }
                });
    }

    private String buildTranslateConfig() {
        return mTranslateData.isEnabled() + "|" + mTranslateData.getTargetLanguage()
                + "|" + mTranslateData.getModel() + "|" + mTranslateData.getApiUrl();
    }

    private boolean isTranslationEnabled() {
        return mTranslateData.isEnabled() && mTranslateData.isConfigured();
    }

    private CharSequence join(String source, String translation) {
        return mTranslateData.isBilingual() ? source + "\n" + translation : translation;
    }

    /** Skip lines that hold no words (music notes, timestamps) to save API calls. */
    private static boolean isTranslatable(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }

        return false;
    }

    public void show(boolean show) {
        if (mSubtitleView != null) {
            mSubtitleView.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private List<SubtitleStyle> getSubtitleStyles() {
        return mSubtitleStyles;
    }

    private SubtitleStyle getSubtitleStyle() {
        return mPlayerData.getSubtitleStyle();
    }

    private void setSubtitleStyle(SubtitleStyle subtitleStyle) {
        mPlayerData.setSubtitleStyle(subtitleStyle);
        configureSubtitleView();
    }

    private List<Cue> forceCenterAlignment(List<Cue> cues) {
        List<Cue> result = new ArrayList<>();

        for (Cue cue : cues) {
            // Autogenerated subs repeated lines fix. Shared with the prefetcher, so both sides
            // derive the exact same cache keys.
            String text = mDeduplicator.deduplicate(cue.text.toString());

            if (text != null) {
                result.add(new Cue(text)); // sub centered by default
            }
        }

        return result;
    }

    private void configureSubtitleView() {
        if (mSubtitleView != null) {
            // disable default style
            mSubtitleView.setApplyEmbeddedStyles(false);

            SubtitleStyle subtitleStyle = getSubtitleStyle();

            if (subtitleStyle.isSystem()) {
                if (VERSION.SDK_INT >= 19) {
                    applySystemStyle();
                }
            } else {
                applyStyle(subtitleStyle);
            }

            mSubtitleView.setBottomPaddingFraction(mPlayerData.getSubtitlePosition());
        }
    }

    private void applyStyle(SubtitleStyle subtitleStyle) {
        int textColor = ContextCompat.getColor(mContext, subtitleStyle.subsColorResId);
        int outlineColor = ContextCompat.getColor(mContext, R.color.black);
        int backgroundColor = ContextCompat.getColor(mContext, subtitleStyle.backgroundColorResId);

        CaptionStyleCompat style =
                new CaptionStyleCompat(textColor,
                        backgroundColor, Color.TRANSPARENT,
                        subtitleStyle.captionStyle,
                        outlineColor, Typeface.DEFAULT_BOLD);
        mSubtitleView.setStyle(style);

        float textSize = getTextSizePx();
        mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
    }

    @RequiresApi(19)
    private void applySystemStyle() {
        CaptioningManager captioningManager =
                (CaptioningManager) mContext.getSystemService(Context.CAPTIONING_SERVICE);

        if (captioningManager != null) {
            CaptionStyle userStyle = captioningManager.getUserStyle();

            CaptionStyleCompat style =
                    new CaptionStyleCompat(userStyle.foregroundColor,
                            userStyle.backgroundColor, VERSION.SDK_INT >= 21 ? userStyle.windowColor : Color.TRANSPARENT,
                            userStyle.edgeType,
                            userStyle.edgeColor, userStyle.getTypeface());
            mSubtitleView.setStyle(style);

            float textSizePx = getTextSizePx();
            mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * captioningManager.getFontScale());
        }
    }

    private float getTextSizePx() {
        float textSizePx = mSubtitleView.getContext().getResources().getDimension(R.dimen.subtitle_text_size);
        return textSizePx * mPlayerData.getSubtitleScale();
    }
}
