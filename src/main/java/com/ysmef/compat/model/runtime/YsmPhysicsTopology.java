package com.ysmef.compat.model.runtime;

/** Model-derived chain sizes and the short coupling graph shared by the frame solver. */
final class YsmPhysicsTopology {
    private YsmPhysicsTopology() {}

    /** Per segment: how many joints its piece has, and how many of them are at or below it. */
    static final class PieceTable {
        final int[] jointsInPiece;
        final int[] jointsLeft;

        PieceTable(int[] jointsInPiece, int[] jointsLeft) {
            this.jointsInPiece = jointsInPiece;
            this.jointsLeft = jointsLeft;
        }
    }

    /**
     * The piece each segment belongs to, as counts rather than as a list.
     *
     * <p>Walked from the parent links rather than assumed from the list order, because the links are
     * model data and a piece's bones need not be listed root first - on the shipped maid the
     * right-hand panel is {@code RB3, RB2, RB} and the left-hand one {@code LM, LM2, LM3}. The walk
     * is bounded, because a damaged table can contain a cycle and this runs on the render thread's
     * path to a model's first frame.
     *
     * <p>The counts are of <b>segments</b>: the bones that are really simulated, which is what the
     * piece's allowance is shared among. A bone in the skeleton's subtree that produced no segment -
     * geometry that is not its own, a mapped body part, a bracket over two panels - must not raise
     * the allowance of the chain it hangs near.
     */
    static PieceTable piecesOf(YsmPhysicsParts.Segment[] segments) {
        int count = segments.length;
        int[] depth = new int[count];
        int[] root = new int[count];
        int[] size = new int[count];
        for (int i = 0; i < count; i++) {
            int top = i;
            int guard = 0;
            for (int parent = segments[i].parent();
                 parent >= 0 && parent < count && parent != top && guard++ <= count;
                 parent = segments[parent].parent()) {
                top = parent;
                depth[i]++;
            }
            root[i] = top;
        }
        for (int i = 0; i < count; i++) {
            size[root[i]]++;
        }
        int[] inPiece = new int[count];
        int[] left = new int[count];
        for (int i = 0; i < count; i++) {
            inPiece[i] = Math.max(1, size[root[i]]);
            left[i] = Math.max(1, size[root[i]] - depth[i]);
        }
        return new PieceTable(inPiece, left);
    }

    /**
     * Which segments are sewn to which.
     *
     * <p>A flat array rather than a list of lists because it is read once per segment per frame and
     * the frame allocates nothing: {@code partners} is a run {@code [start[i], start[i] + count[i])}
     * for each segment. Cross-panel links may be reciprocal; the parent link is stored only on
     * the child because the parent must be resolved first.
     */
    static final class Knits {
        final int[] partners;
        final int[] start;
        final int[] count;

        Knits(int[] partners, int[] start, int[] count) {
            this.partners = partners;
            this.start = start;
            this.count = count;
        }
    }

    /**
     * Build the coupling graph from the model's own geometry.
     *
     * <p>The sewn relation is {@link YsmPhysicsChains#sewnTogether}, in one place so the rule can be
     * read and tested without a frame: a parent and its child are always sewn (they are the same
     * piece of cloth), and two segments that are not related are sewn when their pivots are within
     * {@code KNIT_RADIUS} and their rest directions within {@code KNIT_MAX_ANGLE}.
     *
     * <p>The candidate set is the union of the parent/child links and the nearest few by pivot
     * distance, capped at {@code KNIT_COUNT} + 1 partners. A cap rather than a threshold alone,
     * because a skirt's waistband puts a dozen pivots inside the radius and a per-frame loop over
     * all of them is work the model did not ask for; the nearest few are the panels actually beside
     * each other.
     *
     * <p>Bounded by the segment count, because the parent links are model data and a damaged table
     * can contain a cycle; this runs on the render thread's path to a model's first frame.
     */
    static Knits knitsOf(YsmPhysicsParts.Segment[] segments) {
        int count = segments.length;
        int slotCount = YsmPhysicsChains.KNIT_COUNT;
        int[] parentOf = new int[count];
        for (int i = 0; i < count; i++) {
            parentOf[i] = parentOf(segments, i);
        }
        // One slot for the parent plus a bounded set of nearby panels.
        int[] partners = new int[count * (slotCount + 2)];
        int[] start = new int[count];
        int[] size = new int[count];
        int[] best = new int[slotCount];
        float[] bestDistance = new float[slotCount];
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            start[i] = cursor;
            java.util.Arrays.fill(best, -1);
            java.util.Arrays.fill(bestDistance, Float.MAX_VALUE);
            for (int j = 0; j < count; j++) {
                if (i == j) {
                    continue;
                }
                // A parent/child link is recorded on the CHILD's side only, and that is not a
                // detail of the data structure - it is what keeps the chain's composition honest.
                // A child is resolved after its parent and composes its delta under the parent's,
                // so its own swing IS its angle relative to the parent; pulling the parent toward
                // the child is what closes that angle, and the child has nothing to gain from a
                // pull in the other direction. Recording it on the child's side as well makes the
                // parent's relaxation depend on a partner that has not been resolved yet, and the
                // child then composes under a stale parent - which doubles the composed angle per
                // level, the exact "tail folds onto the lower body" this file was fixed for. The
                // measurement that caught it is in tmp_verify/T8_delta_probe.txt.
                boolean child = j == parentOf[i];
                if (child) {
                    partners[cursor++] = j;
                    size[i]++;
                    continue;
                }
                if (i == parentOf[j]) {
                    // This segment is the parent: the link is on the child's side, not here.
                    continue;
                }
                float distance = Float.MAX_VALUE;
                if (segments[i].bindPivot() != null && segments[j].bindPivot() != null) {
                    distance = segments[i].bindPivot().distance(segments[j].bindPivot());
                }
                if (distance > YsmPhysicsChains.KNIT_RADIUS
                        || segments[i].bindRest() == null || segments[j].bindRest() == null
                        || YsmDynamicBoneSolver.angleBetween(segments[i].bindRest(),
                                segments[j].bindRest()) > YsmPhysicsChains.KNIT_MAX_ANGLE) {
                    continue;
                }
                for (int slot = 0; slot < slotCount; slot++) {
                    if (distance < bestDistance[slot]) {
                        for (int shift = slotCount - 1; shift > slot; shift--) {
                            best[shift] = best[shift - 1];
                            bestDistance[shift] = bestDistance[shift - 1];
                        }
                        best[slot] = j;
                        bestDistance[slot] = distance;
                        break;
                    }
                }
            }
            for (int slot = 0; slot < slotCount; slot++) {
                if (best[slot] < 0) {
                    continue;
                }
                partners[cursor++] = best[slot];
                size[i]++;
            }
        }
        // Every segment of a coupled garment is relaxed toward its partners, the top of a piece
        // included: the hips are where a skirt's panels are sewn to each other, so the panel that
        // is a root is the one with the most to gain from being held by the ones beside it.
        return new Knits(java.util.Arrays.copyOf(partners, cursor), start, size);
    }

    /** A segment's parent index when it is a usable one, else -1. */
    private static int parentOf(YsmPhysicsParts.Segment[] segments, int index) {
        if (index < 0 || index >= segments.length) {
            return -1;
        }
        int parent = segments[index].parent();
        return parent >= 0 && parent < segments.length && parent != index ? parent : -1;
    }

}
