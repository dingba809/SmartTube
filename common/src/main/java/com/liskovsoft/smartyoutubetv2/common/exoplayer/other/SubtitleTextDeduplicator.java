package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

/** Removes complete rolling-caption overlap while keeping repeated active-cue callbacks stable. */
public class SubtitleTextDeduplicator {
    private String mLastInput;
    private String mLastOutput;

    public String deduplicate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String input = text.trim();
        if (input.equals(mLastInput)) {
            return mLastOutput;
        }
        String output = input;
        if (mLastInput != null) {
            String[] previous = mLastInput.split("\n");
            String[] current = input.split("\n");
            // A rolling window may repeat several complete lines, not just its last line.
            // Leave at least one current line: a shrinking window is still the active cue.
            for (int overlap = Math.min(previous.length, current.length - 1); overlap > 0; overlap--) {
                boolean matches = true;
                for (int i = 0; i < overlap; i++) {
                    if (!previous[previous.length - overlap + i].equals(current[i])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    int start = 0;
                    for (int i = 0; i < overlap; i++) {
                        start += current[i].length() + 1;
                    }
                    output = input.substring(start);
                    break;
                }
            }
        }
        mLastInput = input;
        mLastOutput = output;
        return output;
    }
}
