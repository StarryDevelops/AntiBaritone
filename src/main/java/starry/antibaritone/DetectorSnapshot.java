package starry.antibaritone;

public record DetectorSnapshot(
        double score,
        int samples,
        int preciseHits,
        int sharpSnaps,
        int tunnelStreaks,
        String lastReason
) {
    public static DetectorSnapshot empty() {
        return new DetectorSnapshot(0.0D, 0, 0, 0, 0, "none");
    }
}
