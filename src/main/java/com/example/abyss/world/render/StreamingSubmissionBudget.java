package com.example.abyss.world.render;

/**
 * Tracks how many items have been submitted in a single streaming sweep and
 * enforces a per-sweep cap.
 * <p>
 * Both {@link NearChunkStreamer} and {@code LodStreamer} share this class
 * so the submission-cap logic lives in one place. Each streamer creates its
 * own instance with its own cap.
 * </p>
 */
public final class StreamingSubmissionBudget {

    private final int maxSubmissionsPerSweep;
    private int submitted;

    public StreamingSubmissionBudget(int maxSubmissionsPerSweep) {
        this.maxSubmissionsPerSweep = maxSubmissionsPerSweep;
    }

    /** Resets the counter at the start of each sweep. */
    public void reset() {
        submitted = 0;
    }

    /** Returns {@code true} if the budget has been fully consumed. */
    public boolean isExhausted() {
        return submitted >= maxSubmissionsPerSweep;
    }

    /** Records one successful submission. */
    public void recordSubmission() {
        submitted++;
    }

    /** Number of items submitted in the current sweep. */
    public int getSubmitted() {
        return submitted;
    }
}