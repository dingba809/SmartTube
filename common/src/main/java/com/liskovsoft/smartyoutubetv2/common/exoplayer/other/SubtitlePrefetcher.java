package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.util.Log;

import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads the whole subtitle file of the track being watched and translates it in batches
 * ahead of playback, so {@link SubtitleManager} already finds the translations cached when
 * ExoPlayer hands it a cue. ExoPlayer stays in charge of the timeline, which is what keeps the
 * subtitles aligned.<br/>
 * The first batches cover the beginning of the video, the rest follow as the loop runs, which
 * is why the opening minutes are translated almost immediately.
 */
public class SubtitlePrefetcher {
    private static final String TAG = SubtitlePrefetcher.class.getSimpleName();
    private static final int BATCH_SIZE = 40;
    private static final long DOWNLOAD_TIMEOUT_SEC = 30;
    private static SubtitlePrefetcher sInstance;
    private final SubtitleTranslator mTranslator = new SubtitleTranslator();
    private volatile boolean mStopped = true;
    private String mTrackId;
    private String mTrackName;
    private long mGeneration;
    private List<MediaSubtitle> mSubtitles;

    public static SubtitlePrefetcher instance() {
        if (sInstance == null) {
            sInstance = new SubtitlePrefetcher();
        }

        return sInstance;
    }

    /**
     * Called when the format info of a new video arrives. Deliberately does not cancel a running
     * job: this is also called when the player is merely reconfigured.
     */
    public void setSubtitles(List<MediaSubtitle> subtitles) {
        mSubtitles = subtitles;
    }

    public MediaSubtitle findSubtitle(String trackId, String trackName) {
        return SubtitleTrackPreference.findSubtitle(mSubtitles, trackId, trackName);
    }

    /**
     * @param trackId     {@code FormatItem.getFormatId()}, which is the caption track vssId
     * @param onReady     called once the first batch is translated, or right away when the
     *                    prefetch cannot run. Playback waits on this.
     */
    public void start(String apiUrl, String apiKey, String model, String targetLanguage,
                      String trackId, String trackName, SubtitleTranslationCache cache, Runnable onReady) {
        if (trackId == null || mSubtitles == null) {
            runOnReady(onReady);
            return;
        }

        if (trackId.equals(mTrackId) && (trackName == null ? mTrackName == null : trackName.equals(mTrackName)) && !mStopped) {
            return; // this track is already being fetched
        }

        stop();
        mTrackId = trackId;
        mTrackName = trackName;
        mStopped = false;
        long generation = mGeneration;

        MediaSubtitle subtitle = findSubtitle(trackId, trackName);
        String url = subtitle != null ? subtitle.getBaseUrl() : null;

        if (url == null) {
            Log.e(TAG, "no subtitle url for track: " + trackId);
            mStopped = true;
            runOnReady(onReady);
            return;
        }

        new Thread(() -> {
            String vtt = download(url);
            List<String> texts = SubtitleVttParser.parse(vtt);
            Log.i(TAG, "prefetch started, " + texts.size() + " lines");
            Log.i(TAG, "prefetch sample: " + sample(texts));
            Log.i(TAG, "raw vtt sample: " + sample(Arrays.asList(vtt != null ? vtt.split("\n") : new String[0])));

            if (texts.isEmpty()) {
                runOnReady(onReady);
                return;
            }

            Utils.post(() -> {
                if (generation == mGeneration && !mStopped) {
                    translateNext(apiUrl, apiKey, model, targetLanguage, texts, 0, cache,
                            onReady, generation, 0);
                }
            });
        }).start();
    }

    private static void runOnReady(Runnable onReady) {
        if (onReady != null) {
            Utils.post(onReady);
        }
    }

    /** First few entries of a list, for comparing what the two sides see. */
    private static List<String> sample(List<String> items) {
        return items.subList(0, Math.min(3, items.size()));
    }

    public void stop() {
        mStopped = true;
        mGeneration++;
    }

    private void translateNext(String apiUrl, String apiKey, String model, String targetLanguage,
                              List<String> texts, int from, SubtitleTranslationCache cache,
                              Runnable onFirstBatch, long generation, int attempt) {
        if (mStopped || generation != mGeneration || from >= texts.size()) {
            return;
        }

        int to = Math.min(from + BATCH_SIZE, texts.size());
        final List<String> batch = new ArrayList<>();

        // Skip what is already translated, so a restarted job does not pay for it twice.
        for (String text : texts.subList(from, to)) {
            if (cache.get(text) == null) {
                batch.add(text);
            }
        }

        if (batch.isEmpty()) {
            if (from == 0 && onFirstBatch != null) {
                runOnReady(onFirstBatch);
            }

            translateNext(apiUrl, apiKey, model, targetLanguage, texts, to, cache, null, generation, 0);
            return;
        }

        mTranslator.translateBatch(apiUrl, apiKey, model, targetLanguage, batch, translations -> {
            if (mStopped || generation != mGeneration) {
                return;
            }

            if (translations != null) {
                for (int i = 0; i < batch.size() && i < translations.size(); i++) {
                    cache.put(batch.get(i), translations.get(i));
                }
            }

            int missing = 0;
            for (String text : batch) {
                if (cache.get(text) == null) {
                    missing++;
                }
            }
            if (missing > 0 && attempt == 0) {
                Log.w(TAG, "retrying " + missing + " missing translations at " + from);
                translateNext(apiUrl, apiKey, model, targetLanguage, texts, from, cache,
                        onFirstBatch, generation, 1);
                return;
            }
            Log.i(TAG, "prefetch processed: " + to + "/" + texts.size() + ", missing=" + missing);

            if (onFirstBatch != null) {
                runOnReady(onFirstBatch);
            }

            translateNext(apiUrl, apiKey, model, targetLanguage, texts, to, cache, null, generation, 0);
        });
    }

    private String download(String url) {
        OkHttpClient client = OkHttpManager.instance().getClient().newBuilder()
                .callTimeout(DOWNLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.e(TAG, "download failed: HTTP " + response.code());
                return null;
            }

            return response.body() != null ? response.body().string() : null;
        } catch (Exception e) {
            Log.e(TAG, "download failed: " + e.getMessage());
            return null;
        }
    }
}
