package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

/** Removes complete repeated rolling-caption lines without erasing a repeated cue callback. */
public class SubtitleTextDeduplicator {
    private String mPreviousLine;
    private String mLastInput;
    private String mLastOutput;

    public String deduplicate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String input = text.trim();
        // ExoPlayer can submit the same active cue again; it must retain its display text.
        if (input.equals(mLastInput)) {
            return mLastOutput;
        }
        String output = input;
        if (mPreviousLine != null && input.startsWith(mPreviousLine + "\n")) {
            output = input.substring(mPreviousLine.length() + 1);
        }
        int lastNewline = input.lastIndexOf('\n');
        mPreviousLine = input.substring(lastNewline + 1);
        mLastInput = input;
        mLastOutput = output;
        return output;
    }
}
