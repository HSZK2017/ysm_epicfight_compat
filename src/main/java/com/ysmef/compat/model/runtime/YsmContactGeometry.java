package com.ysmef.compat.model.runtime;

import org.joml.Vector3f;

import java.util.List;

/** Shared, model-space contact measurements used while classifying attached geometry. */
final class YsmContactGeometry {
    /**
     * One centimetre around a piece's closest approach to its support. This measures a contact
     * patch, not whether a whole piece is close enough to be held. On the reported scalp shell,
     * 0.005 and 0.010 blocks select the same 27 vertices; 0.030 swallows the inner shell.
     */
    static final float PATCH_TOLERANCE = 0.01F;
    /** Bound point comparisons while preparing even a very large converted mesh. */
    private static final int SEARCH_WORK = 262144;

    private YsmContactGeometry() {}

    static boolean isFinite(Vector3f point) {
        return point != null && Float.isFinite(point.x) && Float.isFinite(point.y)
                && Float.isFinite(point.z);
    }

    /** Smallest support-cloud stride that keeps the search within its work budget. */
    static int contactStride(int ownSize, int cloudSize) {
        long product = (long) ownSize * (long) cloudSize;
        if (product <= SEARCH_WORK || ownSize <= 0) {
            return 1;
        }
        return (int) Math.max(1L, Math.min(cloudSize, (product + SEARCH_WORK - 1) / SEARCH_WORK));
    }

    /** Distance to the closest finite support point sampled at the chosen stride. */
    static float distanceToCloud(Vector3f point, List<Vector3f> cloud, int stride) {
        float best = Float.MAX_VALUE;
        for (int at = 0; at < cloud.size(); at += stride) {
            Vector3f other = cloud.get(at);
            if (isFinite(other)) {
                best = Math.min(best, point.distance(other));
            }
        }
        return best;
    }
}
