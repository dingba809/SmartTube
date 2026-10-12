package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.util.Log;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Translates subtitle lines through any OpenAI compatible {@code /chat/completions} endpoint.
 */
public class SubtitleTranslator {
    public interface OnTranslate {
        /** Called on the main thread. {@code translation} is null when the request failed. */
        void onTranslate(String translation);
    }

    public interface OnTranslateBatch {
        /**
         * Called on the main thread. Same size as the request, an entry is null when that
         * particular line could not be translated.
         */
        void onTranslate(List<String> translations);
    }

    private static final String TAG = SubtitleTranslator.class.getSimpleName();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long TIMEOUT_SEC = 20;
    /** Numbered answers like {@code [1] 你好}. */
    private static final Pattern BATCH_LINE = Pattern.compile("^\\[(\\d+)]\\s*(.+)$");
    // Serial execution keeps the line order and stays clear of provider rate limits.
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private OkHttpClient mClient;

    public void translate(String apiUrl, String apiKey, String model, String targetLanguage, String text, OnTranslate callback) {
        if (isBlank(apiUrl) || isBlank(model) || isBlank(text)) {
            Utils.post(() -> callback.onTranslate(null));
            return;
        }

        String body = buildBody(model, targetLanguage, text);

        mExecutor.execute(() -> {
            String translation = null;

            try {
                translation = send(apiUrl, apiKey, body);
            } catch (Exception e) {
                Log.e(TAG, "translate failed: " + e.getMessage());
            }

            String result = translation;
            Utils.post(() -> callback.onTranslate(result));
        });
    }

    /**
     * Translates many lines in a single request. Far fewer round trips than one call per line,
     * and the system prompt is paid for once instead of once per line.
     */
    public void translateBatch(String apiUrl, String apiKey, String model, String targetLanguage,
                               List<String> texts, OnTranslateBatch callback) {
        if (isBlank(apiUrl) || isBlank(model) || texts == null || texts.isEmpty()) {
            Utils.post(() -> callback.onTranslate(null));
            return;
        }

        String body = buildBatchBody(model, targetLanguage, texts);
        int size = texts.size();

        mExecutor.execute(() -> {
            List<String> translations = null;

            try {
                translations = parseBatch(send(apiUrl, apiKey, body), size);
            } catch (Exception e) {
                Log.e(TAG, "translateBatch failed: " + e.getMessage());
            }

            List<String> result = translations;
            Utils.post(() -> callback.onTranslate(result));
        });
    }

    private String send(String apiUrl, String apiKey, String body) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(buildEndpoint(apiUrl))
                .post(RequestBody.create(JSON, body));

        if (!isBlank(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }

        Call call = getClient().newCall(builder.build());

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                Log.e(TAG, "request failed: HTTP " + response.code());
                return null;
            }

            return parse(response.body() != null ? response.body().string() : null);
        }
    }

    private OkHttpClient getClient() {
        if (mClient == null) {
            // Derived from the app client so the user's proxy and custom DNS settings apply.
            mClient = OkHttpManager.instance().getClient().newBuilder()
                    .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                    .build();
        }

        return mClient;
    }

    /**
     * Accepts {@code https://host}, {@code https://host/v1} and the full endpoint alike.
     */
    static String buildEndpoint(String apiUrl) {
        String url = apiUrl.trim();

        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        if (url.endsWith("/chat/completions")) {
            return url;
        }

        return url.endsWith("/v1") ? url + "/chat/completions" : url + "/v1/chat/completions";
    }

    static String buildBody(String model, String targetLanguage, String text) {
        try {
            JSONObject system = new JSONObject()
                    .put("role", "system")
                    .put("content", "You translate subtitle lines into " + targetLanguage
                            + ". Reply with the translation only. No quotes, no notes, no original text.");
            JSONObject user = new JSONObject()
                    .put("role", "user")
                    .put("content", text);

            return new JSONObject()
                    .put("model", model)
                    .put("temperature", 0.2)
                    .put("messages", new JSONArray().put(system).put(user))
                    .toString();
        } catch (JSONException e) {
            Log.e(TAG, "buildBody failed: " + e.getMessage());
            return null;
        }
    }

    static String buildBatchBody(String model, String targetLanguage, List<String> texts) {
        try {
            JSONObject system = new JSONObject()
                    .put("role", "system")
                    .put("content", "You translate subtitle lines into " + targetLanguage
                            + ". The user sends numbered lines. Reply with the same numbers, one"
                            + " translation per line, nothing else. Keep the line count identical.");
            JSONObject message = new JSONObject()
                    .put("role", "user")
                    .put("content", buildBatchUserMessage(texts));

            return new JSONObject()
                    .put("model", model)
                    .put("temperature", 0.2)
                    .put("messages", new JSONArray().put(system).put(message))
                    .toString();
        } catch (JSONException e) {
            Log.e(TAG, "buildBatchBody failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Plain text on purpose: the JSON wrapper around it cannot be unit tested, since
     * {@code org.json} is an Android class with no real implementation on the JVM.
     */
    static String buildBatchUserMessage(List<String> texts) {
        StringBuilder user = new StringBuilder();

        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                user.append('\n');
            }

            // The numbered format is one line per cue, so flatten any multi line cue.
            user.append('[').append(i + 1).append("] ").append(texts.get(i).replace('\n', ' '));
        }

        return user.toString();
    }

    /**
     * One entry per requested line. Unparsable answers simply stay null.
     */
    static List<String> parseBatch(String responseBody, int size) {
        List<String> result = new ArrayList<>(Collections.nCopies(size, (String) null));

        if (responseBody == null) {
            return result;
        }

        for (String line : responseBody.replace("```", "").split("\n")) {
            Matcher matcher = BATCH_LINE.matcher(line.trim());

            if (!matcher.matches()) {
                continue;
            }

            int index = Integer.parseInt(matcher.group(1)) - 1;

            if (index >= 0 && index < size) {
                result.set(index, sanitize(matcher.group(2)));
            }
        }

        return result;
    }

    static String parse(String responseBody) {
        if (responseBody == null) {
            return null;
        }

        try {
            JSONArray choices = new JSONObject(responseBody).optJSONArray("choices");

            if (choices == null || choices.length() == 0) {
                return null;
            }

            JSONObject message = choices.getJSONObject(0).optJSONObject("message");

            return message == null ? null : sanitize(message.optString("content", null));
        } catch (JSONException e) {
            Log.e(TAG, "parse failed: " + e.getMessage());
            return null;
        }
    }

    static String sanitize(String text) {
        if (text == null) {
            return null;
        }

        String result = text.trim();

        if (result.contains("```")) { // some models wrap the answer in a code block
            result = result.replace("```", "").trim();
        }

        if (result.length() > 1 && result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length() - 1).trim();
        }

        return result.isEmpty() ? null : result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
