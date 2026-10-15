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
    private List<MediaSubtitle> mSubtitles;

    public static SubtitlePrefetcher instance() {
        if (sInstance == null) {
            sInstance = new SubtitlePrefetcher();
        }

        return sInstance;
    }

    /**
     * The caption tracks of the video being played. Null until its format info arrives.
     */
    public SubtitleTrackPreference.Pick pickPreferredTrack() {
        return SubtitleTrackPreference.pick(mSubtitles);
    }

    /**
     * Called when the format info of a new video arrives. Deliberately does not cancel a running
     * job: this is also called when the player is merely reconfigured.
     */
    public void setSubtitles(List<MediaSubtitle> subtitles) {
        mSubtitles = subtitles;
    }

    /**
     * @param trackId     {@code FormatItem.getFormatId()}, which is the caption track vssId
     * @param onReady     called once the first batch is translated, or right away when the
     *                    prefetch cannot run. Playback waits on this.
     */
    public void start(String apiUrl, String apiKey, String model, String targetLanguage,
                      String trackId, SubtitleTranslationCache cache, Runnable onReady) {
        if (trackId == null || mSubtitles == null) {
            runOnReady(onReady);
            return;
        }

        if (trackId.equals(mTrackId) && !mStopped) {
            return; // this track is already being fetched
        }

        stop();
        mTrackId = trackId;
        mStopped = false;

        String url = findBaseUrl(trackId);

        if (url == null) {
            Log.e(TAG, "no subtitle url for track: " + trackId);
            mStopped = true;
            runOnReady(onReady);
            return;
        }

        Log.i(TAG, "prefetch url: " + url);

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

            translateNext(apiUrl, apiKey, model, targetLanguage, texts, 0, cache,
                    () -> runOnReady(onReady));
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
    }

    private void translateNext(String apiUrl, String apiKey, String model, String targetLanguage,
                              List<String> texts, int from, SubtitleTranslationCache cache,
                              Runnable onFirstBatch) {
        if (mStopped || from >= texts.size()) {
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

            translateNext(apiUrl, apiKey, model, targetLanguage, texts, to, cache, null);
            return;
        }

        mTranslator.translateBatch(apiUrl, apiKey, model, targetLanguage, batch, translations -> {
            if (mStopped) {
                return;
            }

            if (translations != null) {
                for (int i = 0; i < batch.size(); i++) {
                    cache.put(batch.get(i), translations.get(i));
                }
            }

            Log.i(TAG, "prefetch progress: " + to + "/" + texts.size());

            if (onFirstBatch != null) {
                runOnReady(onFirstBatch);
            }

            translateNext(apiUrl, apiKey, model, targetLanguage, texts, to, cache, null);
        });
    }

    private String findBaseUrl(String trackId) {
        for (MediaSubtitle subtitle : mSubtitles) {
            if (trackId.equals(subtitle.getVssId())) {
                return subtitle.getBaseUrl();
            }
        }

        return null;
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
