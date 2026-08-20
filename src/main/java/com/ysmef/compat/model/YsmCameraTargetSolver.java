package com.ysmef.compat.model;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Solves the RealCamera bind-target UVs of a converted YSM model from its
 * geometry (see EFMeshJsonWriter's runtime JSON "camera" section):
 *
 * - the target plane and the forward vector use the FRONT face of the head,
 *   identified by the plane the "eyes" element lies in: the eyes plate (or, for
 *   the many models whose "Eyes" bone is a bare locator, the largest flat plate
 *   among its descendants - eyelids/eyebrows sit coplanar with the head's front
 *   face), so that plate's texture-UV center lands RealCamera's primitive
 *   lookup on the face;
 * - the upward vector uses the adjacent side face of the head box ACROSS from
 *   the front face's right side (normal = modelUp x frontNormal - with the
 *   front face being the head's north face, that is the WEST face); an earlier
 *   revision used the right side itself and rendered the view upside-down and
 *   left-right flipped;
 * - the caller pins the roll offset at 90 degrees, which makes the resulting
 *   camera upright with that face pairing.
 *
 * RealCamera's probe resolves a configured UV to the FIRST captured triangle
 * containing it (buffer order). Model textures routinely reuse the same UV
 * region for unrelated parts (e.g. hair quads overlapping the head's side
 * face), and Epic Fight draws parts in HashMap order, so the face center can
 * resolve to a completely different part. Both probe points are therefore
 * nudged (within their intended face) to a spot no differently-facing quad
 * covers (findClearUv), so the probe lands on the intended face regardless of
 * draw order.
 *
 * All normals/centers are evaluated in model bind space (bone pivot/rotation
 * chains applied, exactly like EFMeshJsonWriter.walkBone); only the texture
 * UVs feed the bind target.
 */
public final class YsmCameraTargetSolver {

    /** posU, posV, forwardU, forwardV, upwardU, upwardV + model-space eyes position and face normals. */
    public static final class CameraUvs {
        public final float posU, posV, forwardU, forwardV, upwardU, upwardV;
        /** Bind-space position of the front plane (the eyes plate center). */
        public final float eyesX, eyesY, eyesZ;
        /** Bind-space normal of the front plane. */
        public final float normalX, normalY, normalZ;
        /** Bind-space normal of the upward side face (opposite of the front-right). */
        public final float upX, upY, upZ;

        CameraUvs(float posU, float posV, float forwardU, float forwardV, float upwardU, float upwardV,
                  Vector3f eyes, Vector3f normal, Vector3f up) {
            this.posU = posU;
            this.posV = posV;
            this.forwardU = forwardU;
            this.forwardV = forwardV;
            this.upwardU = upwardU;
            this.upwardV = upwardV;
            this.eyesX = eyes.x();
            this.eyesY = eyes.y();
            this.eyesZ = eyes.z();
            this.normalX = normal.x();
            this.normalY = normal.y();
            this.normalZ = normal.z();
            this.upX = up.x();
            this.upY = up.y();
            this.upZ = up.z();
        }
    }

    private static final Vector3f MODEL_UP = new Vector3f(0, 1, 0);
    /** Max distance (blocks) between the front plane and an acceptable side face. */
    private static final float MAX_SIDE_DISTANCE = 1.0f;
    /** Minimum |normal . right| for a quad to count as the right side face. */
    private static final float MIN_SIDE_DOT = 0.9f;
    /** The front plane must face roughly horizontally (vertical face). */
    private static final float MAX_FRONT_NORMAL_Y = 0.7f;

    private YsmCameraTargetSolver() {}

    /**
     * Solve the bind-target UVs, or null when the model has no usable
     * eyes/head-face geometry (no RealCamera target is emitted then).
     *
     * scaleW/scaleH are the YSM model package's width/height scales (default
     * 0.7): the converted mesh and the probe capture are scaled by them
     * (EFMeshJsonWriter.walkBone), so the bind-space eyes position reported
     * for the API bind function must be scaled too, or the camera hovers
     * above the rendered head.
     *
     * hiddenBones: the bones hidden in the model's default (battle-mode) form
     * (computed from the runtime animations at conversion time). Their quads
     * are excluded from every face pick - a hidden variant's face is never
     * captured by RealCamera's probe, so binding to it would fail.
     */
    public static CameraUvs solve(YSMGeoModel geoModel, float scaleW, float scaleH,
                                  java.util.Set<String> hiddenBones) {
        YSMGeoModel.Bone eyes = findEyesBone(geoModel);
        if (eyes == null) {
            return null;
        }

        YSMGeoModel.Bone head = geoModel.bonesByName.values().stream()
                .filter(bone -> normalize(bone.name).equals("head"))
                .findFirst().orElse(null);

        // The front plane: the largest flat quad of the eyes element or its
        // descendants (eyelid/eyebrow plates are coplanar with the head's
        // front face; the eyes bone itself is often a bare locator). Models
        // sometimes parent NON-face accessories under the eyes bone (a magic
        // circle hovering blocks ahead of the face, ...), whose quads dwarf
        // the face and would win the area contest, so the candidates are
        // first restricted to quads whose center is near the head box; the
        // unrestricted pick remains as a fallback for models with genuinely
        // detached faces.
        List<WorldQuad> eyeQuads = new ArrayList<>();
        java.util.Set<YSMGeoModel.Bone> eyeSubtree = new java.util.HashSet<>();
        collectQuads(eyes, eyeQuads, eyeSubtree, hiddenBones);
        float[] headBox = headBox(geoModel, head);
        WorldQuad front = pickFrontQuad(eyeQuads, headBox, true);
        if (front == null) {
            front = pickFrontQuad(eyeQuads, headBox, false);
        }
        if (front == null) {
            return null;
        }
        Vector3f frontNormal = front.normal;
        Vector3f frontCenter = front.center;

        // The upward direction: the OPPOSITE of the front face's right side
        // (verified in game: binding the head's right side renders the view
        // upside-down and left-right flipped, so the upward vector is taken
        // from the side face across from it - the WEST face when the front
        // face is the head's north face).
        Vector3f up = new Vector3f(MODEL_UP).cross(frontNormal);
        if (up.lengthSquared() < 1.0e-6f) {
            // face looking straight up/down: no meaningful side
            return null;
        }
        up.normalize();

        // The adjacent upward side face: prefer the head bone's quads, then
        // the eyes bone's parent chain, then any nearby quad with a matching
        // normal.
        YSMGeoModel.Quad upQuad = null;
        if (head != null && !hiddenBones.contains(head.name)) {
            upQuad = pickSideQuad(head, up);
        }
        for (YSMGeoModel.Bone current = eyes.parent; upQuad == null && current != null; current = current.parent) {
            if (hiddenBones.contains(current.name)) {
                continue;
            }
            upQuad = pickSideQuad(current, up);
        }
        if (upQuad == null) {
            upQuad = pickSideQuadGlobal(geoModel, up, frontCenter, hiddenBones);
        }
        if (upQuad == null) {
            return null;
        }

        // The target/forward UV: the eyes' position projected onto the head
        // box's front face quad (always rendered - expression plates can be
        // hidden by the default form), bilinearly interpolated in the quad's
        // UV space. Candidates are restricted to the eyes bone's own ancestor
        // chain first: variant head forms (e.g. an "AllHead2" sibling subtree)
        // are collapsed in the default form, and a quad picked from them would
        // never be rendered for RealCamera's probe to find. Falls back to a
        // global coplanar search, then to the eyes plate's own UV center.
        FrontProjection frontProj = projectOntoFrontFace(geoModel, eyes, eyeSubtree, frontNormal, frontCenter, hiddenBones);
        if (frontProj == null) {
            frontProj = projectOntoFrontFaceGlobal(geoModel, eyeSubtree, frontNormal, frontCenter, hiddenBones);
        }
        float[] frontUv;
        YSMGeoModel.Quad frontQuad;
        if (frontProj != null) {
            frontUv = new float[]{frontProj.u, frontProj.v};
            frontQuad = frontProj.quad;
        } else {
            frontUv = uvCenter(front.quad);
            frontQuad = front.quad;
        }

        // UV-collision avoidance: RealCamera's probe resolves a UV to the
        // FIRST captured triangle containing it, and unrelated parts often
        // reuse the same texture region (hair over the head's side face, ...).
        // Nudge both probe points - within their intended face - to a spot no
        // differently-facing quad covers, so the probe always lands on the
        // intended face no matter the part draw order.
        List<QuadN> allQuads = allQuads(geoModel, hiddenBones);
        frontUv = findClearUv(allQuads, frontQuad, frontNormal, frontUv[0], frontUv[1]);
        float[] upUv = uvCenter(upQuad);
        upUv = findClearUv(allQuads, upQuad, up, upUv[0], upUv[1]);
        // The bind-space eyes position feeds the API bind function, whose
        // camera is compared against the SCALED rendered model - apply the
        // model package's width/height scales (see EFMeshJsonWriter.walkBone:
        // x/z scaled by widthScale, y by heightScale). The internal projection
        // math above intentionally stayed in unscaled bind space.
        Vector3f scaledEyes = new Vector3f(
                frontCenter.x() * scaleW, frontCenter.y() * scaleH, frontCenter.z() * scaleW);
        return new CameraUvs(frontUv[0], frontUv[1], frontUv[0], frontUv[1], upUv[0], upUv[1],
                scaledEyes, frontNormal, up);
    }

    // ------------------------------------------------------------------
    // UV-collision avoidance
    //
    // RealCamera's probe resolves a configured UV to the FIRST captured
    // triangle containing it (buffer order, which follows Epic Fight's
    // HashMap part order - not something the conversion can control). Model
    // textures routinely reuse the same UV region for unrelated parts (hair
    // over the head's side face, ...), so the naive face-center UV can resolve
    // to a differently-facing quad and corrupt the bound orientation. Both
    // probe points are nudged - within their intended face's UV region - to a
    // spot no differently-facing quad covers (coplanar/same-facing covers are
    // harmless: they carry the same normal and nearly the same position).
    // ------------------------------------------------------------------

    /** A quad with its bind-space normal, for the collision checks. */
    private static final class QuadN {
        final YSMGeoModel.Quad quad;
        final Vector3f normal;

        QuadN(YSMGeoModel.Quad quad, Vector3f normal) {
            this.quad = quad;
            this.normal = normal;
        }
    }

    /** Cosine threshold above which a covering quad counts as same-facing (harmless). */
    private static final float SAME_FACING_DOT = 0.99f;
    /** Grid resolution of the clear-point search within the target quad's UV rect. */
    private static final int CLEAR_UV_GRID = 9;

    /** Every quad of the model with its bind-space normal (for the UV-collision checks). */
    private static List<QuadN> allQuads(YSMGeoModel geoModel, java.util.Set<String> hiddenBones) {
        List<QuadN> out = new ArrayList<>();
        for (YSMGeoModel.Bone bone : geoModel.bonesByName.values()) {
            // Hidden-in-battle quads are never captured by the probe, so they
            // are not colliders either.
            if (hiddenBones.contains(bone.name)) {
                continue;
            }
            Matrix4f world = boneWorld(bone);
            for (YSMGeoModel.Quad quad : bone.quads) {
                Vector3f normal = new Vector3f(quad.normal).mulDirection(world);
                if (normal.lengthSquared() < 1.0e-8f) {
                    continue;
                }
                out.add(new QuadN(quad, normal.normalize()));
            }
        }
        return out;
    }

    /**
     * A UV point inside the target quad that no differently-facing quad of the
     * model covers. Prefers the given center; searches a grid (nearest to the
     * center first) when it is covered. Falls back to the center when no clear
     * point exists (the collision then simply cannot be avoided for this model).
     */
    private static float[] findClearUv(List<QuadN> allQuads, YSMGeoModel.Quad target, Vector3f intendedNormal,
                                       float centerU, float centerV) {
        if (!coveredByOtherFace(allQuads, target, intendedNormal, centerU, centerV)) {
            return new float[]{centerU, centerV};
        }
        float[][] uvs = target.uvs;
        float uMin = Float.MAX_VALUE, uMax = -Float.MAX_VALUE, vMin = Float.MAX_VALUE, vMax = -Float.MAX_VALUE;
        for (float[] uv : uvs) {
            uMin = Math.min(uMin, uv[0]);
            uMax = Math.max(uMax, uv[0]);
            vMin = Math.min(vMin, uv[1]);
            vMax = Math.max(vMax, uv[1]);
        }
        List<float[]> candidates = new ArrayList<>();
        for (int i = 1; i < CLEAR_UV_GRID - 1; i++) {
            for (int j = 1; j < CLEAR_UV_GRID - 1; j++) {
                float u = uMin + (uMax - uMin) * i / (float) (CLEAR_UV_GRID - 1);
                float v = vMin + (vMax - vMin) * j / (float) (CLEAR_UV_GRID - 1);
                if (uvCovers(target, u, v)) {
                    candidates.add(new float[]{u, v});
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(
                c -> (c[0] - centerU) * (c[0] - centerU) + (c[1] - centerV) * (c[1] - centerV)));
        for (float[] candidate : candidates) {
            if (!coveredByOtherFace(allQuads, target, intendedNormal, candidate[0], candidate[1])) {
                return candidate;
            }
        }
        return new float[]{centerU, centerV};
    }

    /** Whether any quad facing differently from the intended normal covers the UV point. */
    private static boolean coveredByOtherFace(List<QuadN> allQuads, YSMGeoModel.Quad target, Vector3f intendedNormal,
                                              float u, float v) {
        for (QuadN qn : allQuads) {
            if (qn.quad == target || qn.normal.dot(intendedNormal) > SAME_FACING_DOT) {
                continue;
            }
            if (uvCovers(qn.quad, u, v)) {
                return true;
            }
        }
        return false;
    }

    /** UV containment in the quad's two fan triangles (same layout RealCamera's probe tests). */
    private static boolean uvCovers(YSMGeoModel.Quad quad, float u, float v) {
        float[][] uvs = quad.uvs;
        return pointInTriangle(u, v, uvs[0], uvs[1], uvs[2]) || pointInTriangle(u, v, uvs[2], uvs[3], uvs[0]);
    }

    /** Same math as RealCamera's MathUtil.pointInTriangle. */
    private static boolean pointInTriangle(float u, float v, float[] a, float[] b, float[] c) {
        float abX = b[0] - a[0], abY = b[1] - a[1], acX = c[0] - a[0], acY = c[1] - a[1];
        float alpha = abX * acY - abY * acX;
        if (alpha == 0) {
            return false;
        }
        float apX = u - a[0], apY = v - a[1];
        float beta = (apX * acY - apY * acX) / alpha;
        if (beta < 0) {
            return false;
        }
        float gamma = (abX * apY - abY * apX) / alpha;
        if (gamma < 0) {
            return false;
        }
        return beta + gamma <= 1;
    }

    /**
     * The head bone's bind-space AABB as {minX, minY, minZ, maxX, maxY, maxZ},
     * or null when the model has no head bone / the head has no quads (the
     * front-face pick then runs unconstrained).
     */
    private static float[] headBox(YSMGeoModel geoModel, YSMGeoModel.Bone head) {
        if (head == null || head.quads.isEmpty()) {
            return null;
        }
        Matrix4f world = boneWorld(head);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (YSMGeoModel.Quad quad : head.quads) {
            for (Vector3f position : quad.positions) {
                Vector3f p = new Vector3f(position).mulPosition(world);
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            }
        }
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Max distance (blocks) between the head box and an accepted face quad (~1.4 px at the typical 0.7 scale). */
    private static final float FACE_HEAD_DISTANCE = 0.5f;

    /**
     * The front plane: the largest flat quad of the eyes subtree. With
     * constrainToHead, quads whose center is farther than
     * {@link #FACE_HEAD_DISTANCE} from the head box are rejected first - that
     * filters out accessories parented under the eyes bone (a magic circle
     * hovering blocks ahead of the face, ...) whose huge quads would otherwise
     * win the area contest.
     */
    private static WorldQuad pickFrontQuad(List<WorldQuad> eyeQuads, float[] headBox, boolean constrainToHead) {
        WorldQuad front = null;
        float frontArea = 0.0f;
        for (WorldQuad candidate : eyeQuads) {
            if (Math.abs(candidate.normal.y()) > MAX_FRONT_NORMAL_Y) {
                continue;
            }
            if (constrainToHead && headBox != null && !nearHeadBox(candidate.center, headBox)) {
                continue;
            }
            float area = areaOf(candidate.quad);
            if (area > frontArea) {
                frontArea = area;
                front = candidate;
            }
        }
        return front;
    }

    /** Whether the point is within FACE_HEAD_DISTANCE of the head AABB (in any axis). */
    private static boolean nearHeadBox(Vector3f center, float[] headBox) {
        float dx = Math.max(Math.max(headBox[0] - center.x(), center.x() - headBox[3]), 0.0f);
        float dy = Math.max(Math.max(headBox[1] - center.y(), center.y() - headBox[4]), 0.0f);
        float dz = Math.max(Math.max(headBox[2] - center.z(), center.z() - headBox[5]), 0.0f);
        return dx * dx + dy * dy + dz * dz <= FACE_HEAD_DISTANCE * FACE_HEAD_DISTANCE;
    }

    private static YSMGeoModel.Bone findEyesBone(YSMGeoModel geoModel) {
        YSMGeoModel.Bone exact = geoModel.bonesByName.values().stream()
                .filter(bone -> normalize(bone.name).equals("eyes"))
                .findFirst().orElse(null);
        if (exact != null) {
            return exact;
        }
        // fallback: an eyes-prefixed bone (variant forms) with the deepest subtree
        YSMGeoModel.Bone best = null;
        for (YSMGeoModel.Bone bone : geoModel.bonesByName.values()) {
            if (normalize(bone.name).startsWith("eyes")
                    && (best == null || subtreeSize(bone) > subtreeSize(best))) {
                best = bone;
            }
        }
        return best;
    }

    private static int subtreeSize(YSMGeoModel.Bone bone) {
        int size = bone.quads.size();
        for (YSMGeoModel.Bone child : bone.children) {
            size += subtreeSize(child);
        }
        return size;
    }

    /** A quad with its model-space normal and center (bind pose). */
    private static final class WorldQuad {
        final YSMGeoModel.Quad quad;
        final Vector3f normal;
        final Vector3f center;

        WorldQuad(YSMGeoModel.Quad quad, Vector3f normal, Vector3f center) {
            this.quad = quad;
            this.normal = normal;
            this.center = center;
        }
    }

    /** Collect the quads of a bone and its whole descendant subtree (each with its own world transform). */
    private static void collectQuads(YSMGeoModel.Bone bone, List<WorldQuad> out, java.util.Set<YSMGeoModel.Bone> subtree,
                                     java.util.Set<String> hiddenBones) {
        subtree.add(bone);
        Matrix4f world = boneWorld(bone);
        // Bones hidden in the default (battle) form are never captured by the
        // probe: skip their quads (their children are hidden too - the default
        // form's effective scale multiplies down the chain - so this filters
        // the whole hidden subtree).
        if (!hiddenBones.contains(bone.name)) {
            for (YSMGeoModel.Quad quad : bone.quads) {
                Vector3f normal = new Vector3f(quad.normal).mulDirection(world);
                if (normal.lengthSquared() < 1.0e-8f) {
                    continue;
                }
                normal.normalize();
                out.add(new WorldQuad(quad, normal, centerOf(quad, world)));
            }
        }
        for (YSMGeoModel.Bone child : bone.children) {
            collectQuads(child, out, subtree, hiddenBones);
        }
    }

    /** Max coplanar offset (blocks) between the eyes plate and the accepted front face quad (~1.3 px). */
    private static final float MAX_FRONT_PLANE_OFFSET = 0.08f;
    /** Margin by which the front quad's rectangle is expanded for the projection containment test. */
    private static final float FRONT_PROJECTION_MARGIN = 0.15f;

    /** A projected front-face UV point plus the quad it lies on. */
    private static final class FrontProjection {
        final float u, v, offset, lateral;
        final YSMGeoModel.Quad quad;

        FrontProjection(float u, float v, float offset, float lateral, YSMGeoModel.Quad quad) {
            this.u = u;
            this.v = v;
            this.offset = offset;
            this.lateral = lateral;
            this.quad = quad;
        }
    }

    /**
     * Project the eyes plate's center onto the coplanar front face quad among
     * the eyes bone's ANCESTORS (its own head chain, guaranteed part of the
     * default form) and return the bilinearly interpolated texture UV at that
     * point, or null when no such quad exists.
     */
    private static FrontProjection projectOntoFrontFace(YSMGeoModel geoModel, YSMGeoModel.Bone eyes,
                                                        java.util.Set<YSMGeoModel.Bone> eyeSubtree,
                                                        Vector3f frontNormal, Vector3f frontCenter,
                                                        java.util.Set<String> hiddenBones) {
        for (YSMGeoModel.Bone current = eyes.parent; current != null; current = current.parent) {
            FrontProjection candidate = projectOntoFrontFaceOf(current, eyeSubtree, frontNormal, frontCenter, hiddenBones);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Global fallback of {@link #projectOntoFrontFace}: the best coplanar
     * front quad among all bones outside the eyes subtree (used when the head
     * chain carries no box geometry itself).
     */
    private static FrontProjection projectOntoFrontFaceGlobal(YSMGeoModel geoModel, java.util.Set<YSMGeoModel.Bone> eyeSubtree,
                                                              Vector3f frontNormal, Vector3f frontCenter,
                                                              java.util.Set<String> hiddenBones) {
        FrontProjection best = null;
        for (YSMGeoModel.Bone bone : geoModel.bonesByName.values()) {
            FrontProjection candidate = projectOntoFrontFaceOf(bone, eyeSubtree, frontNormal, frontCenter, hiddenBones);
            if (candidate != null && (best == null || candidate.offset < best.offset
                    || (candidate.offset == best.offset && candidate.lateral < best.lateral))) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The best coplanar front quad of ONE bone, as a FrontProjection,
     * or null when the bone has no acceptable front quad.
     */
    private static FrontProjection projectOntoFrontFaceOf(YSMGeoModel.Bone bone, java.util.Set<YSMGeoModel.Bone> eyeSubtree,
                                                          Vector3f frontNormal, Vector3f frontCenter,
                                                          java.util.Set<String> hiddenBones) {
        if (eyeSubtree.contains(bone) || hiddenBones.contains(bone.name)) {
            return null;
        }
        Matrix4f world = boneWorld(bone);
        YSMGeoModel.Quad bestQuad = null;
        float bestOffset = MAX_FRONT_PLANE_OFFSET;
        float bestLateral = Float.MAX_VALUE;
        for (YSMGeoModel.Quad quad : bone.quads) {
            Vector3f normal = new Vector3f(quad.normal).mulDirection(world);
            if (normal.lengthSquared() < 1.0e-8f) {
                continue;
            }
            normal.normalize();
            if (normal.dot(frontNormal) <= MIN_SIDE_DOT) {
                continue;
            }
            Vector3f center = centerOf(quad, world);
            float offset = new Vector3f(frontCenter).sub(center).dot(frontNormal);
            float absOffset = Math.abs(offset);
            if (absOffset > bestOffset) {
                continue;
            }
            Vector3f projected = new Vector3f(frontCenter).sub(new Vector3f(frontNormal).mul(offset));
            float[] ts = quadCoordinates(quad, world, projected);
            if (ts == null) {
                continue;
            }
            float lateral = Math.max(
                    Math.max(-ts[0], ts[0] - 1.0f),
                    Math.max(-ts[1], ts[1] - 1.0f));
            if (lateral > FRONT_PROJECTION_MARGIN) {
                continue;
            }
            if (absOffset < bestOffset || lateral < bestLateral) {
                bestOffset = Math.min(bestOffset, absOffset);
                bestLateral = lateral;
                bestQuad = quad;
            }
        }
        if (bestQuad == null) {
            return null;
        }
        Vector3f center = centerOf(bestQuad, world);
        float offset = new Vector3f(frontCenter).sub(center).dot(frontNormal);
        Vector3f projected = new Vector3f(frontCenter).sub(new Vector3f(frontNormal).mul(offset));
        float[] ts = quadCoordinates(bestQuad, world, projected);
        if (ts == null) {
            return null;
        }
        float[][] uvs = bestQuad.uvs;
        float u = uvs[0][0] + ts[0] * (uvs[1][0] - uvs[0][0]) + ts[1] * (uvs[3][0] - uvs[0][0]);
        float v = uvs[0][1] + ts[0] * (uvs[1][1] - uvs[0][1]) + ts[1] * (uvs[3][1] - uvs[0][1]);
        return new FrontProjection(u, v, Math.abs(offset), bestLateral, bestQuad);
    }

    /**
     * Bilinear coordinates (t along edge p0->p1, s along edge p0->p3) of a
     * point on a quad, in world space; null when the quad is degenerate.
     */
    private static float[] quadCoordinates(YSMGeoModel.Quad quad, Matrix4f world, Vector3f point) {
        Vector3f p0 = new Vector3f(quad.positions[0]).mulPosition(world);
        Vector3f e1 = new Vector3f(quad.positions[1]).mulPosition(world).sub(p0);
        Vector3f e2 = new Vector3f(quad.positions[3]).mulPosition(world).sub(p0);
        float len1 = e1.lengthSquared();
        float len2 = e2.lengthSquared();
        if (len1 < 1.0e-10f || len2 < 1.0e-10f) {
            return null;
        }
        Vector3f rel = new Vector3f(point).sub(p0);
        return new float[]{rel.dot(e1) / len1, rel.dot(e2) / len2};
    }

    /** The side-face quad of one bone with normal . right > threshold (largest area wins). */
    private static YSMGeoModel.Quad pickSideQuad(YSMGeoModel.Bone bone, Vector3f right) {
        Matrix4f world = boneWorld(bone);
        YSMGeoModel.Quad best = null;
        float bestArea = 0.0f;
        for (YSMGeoModel.Quad quad : bone.quads) {
            Vector3f normal = new Vector3f(quad.normal).mulDirection(world);
            if (normal.lengthSquared() < 1.0e-8f) {
                continue;
            }
            normal.normalize();
            if (normal.dot(right) <= MIN_SIDE_DOT) {
                continue;
            }
            float area = areaOf(quad);
            if (area > bestArea) {
                bestArea = area;
                best = quad;
            }
        }
        return best;
    }

    /** Global fallback: the nearest quad whose normal matches the right direction. */
    private static YSMGeoModel.Quad pickSideQuadGlobal(YSMGeoModel geoModel, Vector3f right, Vector3f frontCenter,
                                                       java.util.Set<String> hiddenBones) {
        YSMGeoModel.Quad best = null;
        float bestDist = MAX_SIDE_DISTANCE;
        for (YSMGeoModel.Bone bone : geoModel.bonesByName.values()) {
            if (hiddenBones.contains(bone.name)) {
                continue;
            }
            Matrix4f world = boneWorld(bone);
            for (YSMGeoModel.Quad quad : bone.quads) {
                Vector3f normal = new Vector3f(quad.normal).mulDirection(world);
                if (normal.lengthSquared() < 1.0e-8f) {
                    continue;
                }
                normal.normalize();
                if (normal.dot(right) <= MIN_SIDE_DOT) {
                    continue;
                }
                float dist = centerOf(quad, world).distance(frontCenter);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = quad;
                }
            }
        }
        return best;
    }

    /** Bone-chain bind transform, identical to EFMeshJsonWriter.walkBone's. */
    private static Matrix4f boneWorld(YSMGeoModel.Bone bone) {
        Matrix4f world = new Matrix4f();
        if (bone.parent != null) {
            world.set(boneWorld(bone.parent));
        }
        world.translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        world.rotateZ(bone.rotZ);
        world.rotateY(bone.rotY);
        world.rotateX(bone.rotX);
        world.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);
        return world;
    }

    private static float areaOf(YSMGeoModel.Quad quad) {
        Vector3f d0 = new Vector3f(quad.positions[2]).sub(quad.positions[0]);
        Vector3f d1 = new Vector3f(quad.positions[3]).sub(quad.positions[1]);
        return d0.cross(d1).length() * 0.5f;
    }

    private static Vector3f centerOf(YSMGeoModel.Quad quad, Matrix4f world) {
        Vector3f center = new Vector3f();
        for (Vector3f position : quad.positions) {
            center.add(position);
        }
        center.mul(0.25f);
        return center.mulPosition(world);
    }

    private static float[] uvCenter(YSMGeoModel.Quad quad) {
        float u = 0.0f;
        float v = 0.0f;
        for (float[] uv : quad.uvs) {
            u += uv[0];
            v += uv[1];
        }
        return new float[]{u * 0.25f, v * 0.25f};
    }

    private static String normalize(String boneName) {
        String normalized = boneName.toLowerCase().replace("_", "").replace(" ", "");
        // YSM's default-form bones may carry a "_Default" form suffix (e.g.
        // "Head_Default"); strip it so the head/eyes lookup still matches.
        if (normalized.endsWith("default")) {
            normalized = normalized.substring(0, normalized.length() - "default".length());
        }
        return normalized;
    }
}
