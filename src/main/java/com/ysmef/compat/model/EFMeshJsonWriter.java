package com.ysmef.compat.model;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates Epic Fight animmodels mesh JSON files from YSM model packages.
 *
 * The output follows the exact format consumed by Epic Fight's JsonAssetLoader
 * (see assets/epicfight/animmodels/entity/biped.json in the Epic Fight jar):
 * - vertex arrays (positions/normals/uvs/vcounts/vindices/weights)
 * - named parts with pre-triangulated index triplets (position, uv, normal);
 *   Epic Fight groups every three consecutive VertexBuilders into one triangle,
 *   so each quad is fanned as (0,1,2) + (2,3,0), six triplets per quad
 * - render_properties with the mesh texture
 *
 * Every YSM bone that has geometry becomes its own Epic Fight part ("y/<boneName>").
 * YSM models change shape at runtime through molang-driven bone animations (variant
 * subtrees scaled to zero, secondary bones like tails/ears/magic circles animated by
 * parallel scripts), so per-bone parts let the runtime hide and transform bones
 * individually, replicating YSM's model-changing behavior (see YSMRuntimeModel).
 * The twelve vanilla humanoid parts are also emitted (empty) for Epic Fight's
 * humanoid mesh/layers compatibility.
 *
 * Positions and normals are written in Epic Fight's Blender-style coordinate
 * convention (the loader applies a -90deg X rotation: (x, y, z)_mc -> (x, -z, y)),
 * and the YSM model's width/height scale is baked into the vertex positions.
 *
 * Each vertex is rigidly bound to one Epic Fight joint (weight 1.0), so Epic
 * Fight's combat animations deform the mesh through joint skinning.
 */
public class EFMeshJsonWriter {

    private static final String[] HUMANOID_PARTS = {
            "head", "torso", "leftArm", "rightArm", "leftLeg", "rightLeg",
            "hat", "jacket", "leftSleeve", "rightSleeve", "leftPants", "rightPants"
    };

    /** Prefix of the Epic Fight part generated for a YSM bone. */
    public static final String BONE_PART_PREFIX = "y/";

    /**
     * The edge length a quad is resampled down to, in blocks.
     *
     * <p>Small enough that a limb bend has several rows of vertices to spread over, large
     * enough that the vertex count stays in the tens of thousands rather than the hundreds.
     */
    private static final float TARGET_EDGE_BLOCKS = 0.05F;

    /** A quad is never split finer than this, however large it is. */
    private static final int MAX_CELLS_PER_EDGE = 4;

    /**
     * How far a child bone's pivot must sit from its parent's to count as a joint worth
     * blending across, in blocks. Below this the two are the same point and the surface does
     * not bend there.
     */
    private static final float MIN_JOINT_OFFSET = 0.02F;

    /**
     * The identity of a vertex for deduplication: position, normal, UV, and which joints it is
     * blended between. The blend is part of the key because two vertices can share a position
     * and still belong to different joints - the same point on a seam is a different vertex on
     * either side of it.
     */
    private record VertexKey(int px, int py, int pz, int nx, int ny, int nz, int u, int v, int jointId,
                             int secondJointId, int blend) {}

    /**
     * Convert a YSM model package into an Epic Fight mesh JSON file.
     *
     * @param pkg         the parsed YSM model package
     * @param outFile     the target mesh JSON file
     * @param runtimeFile the target runtime script JSON file (bone table + animations)
     * @param textureRL   the resource location of the model's default texture
     * @return the number of quads converted, or -1 if the model has no geometry
     */
    public static int write(YsmModelPackage pkg, Path outFile, Path runtimeFile, String textureRL) throws IOException {
        YSMGeoModel geoModel = pkg.geometry;
        if (geoModel == null) {
            return -1;
        }
        int quads = writeMeshJson(geoModel, pkg.widthScale, pkg.heightScale, outFile, textureRL);
        if (quads < 0) {
            return -1;
        }
        writeRuntimeJson(pkg, geoModel, runtimeFile);
        return quads;
    }

    /**
     * Shared mesh-writing core: walks the bone hierarchy (bone pivots/rotations
     * and quads are pre-converted to the conventions of the source model) and
     * emits the Epic Fight animmodels JSON.
     */
    private static int writeMeshJson(YSMGeoModel geoModel, float scaleW, float scaleH, Path outFile, String textureRL) throws IOException {
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Integer> vcounts = new ArrayList<>();
        List<Integer> vindices = new ArrayList<>();
        List<Float> weights = new ArrayList<>();

        Map<VertexKey, Integer> dedup = new HashMap<>();
        Map<String, List<Integer>> partIndices = new LinkedHashMap<>();

        int[] quadCount = {0};
        for (YSMGeoModel.Bone rootBone : geoModel.topLevelBones) {
            walkBone(rootBone, new Matrix4f(), scaleW, scaleH, dedup,
                    positions, normals, uvs, vcounts, vindices, weights, partIndices, quadCount, 0);
        }

        if (positions.isEmpty()) {
            return -1;
        }

        JsonObject root = new JsonObject();

        JsonObject renderProperties = new JsonObject();
        renderProperties.addProperty("texture_path", textureRL);
        renderProperties.addProperty("transparent", false);
        root.add("render_properties", renderProperties);

        JsonObject vertices = new JsonObject();
        vertices.add("positions", floatArray(positions, 3));
        vertices.add("normals", floatArray(normals, 3));
        vertices.add("uvs", floatArray(uvs, 2));
        vertices.add("vcounts", intArray(vcounts, 1));
        vertices.add("vindices", intArray(vindices, 2));
        vertices.add("weights", weightArray(weights));

        JsonObject parts = new JsonObject();
        for (String partName : HUMANOID_PARTS) {
            parts.add(partName, partArray(List.of()));
        }
        for (Map.Entry<String, List<Integer>> entry : partIndices.entrySet()) {
            parts.add(entry.getKey(), partArray(entry.getValue()));
        }
        vertices.add("parts", parts);

        root.add("vertices", vertices);

        writeFileAtomic(outFile, new GsonBuilder().create().toJson(root).getBytes(StandardCharsets.UTF_8));
        // What the resampling and the joint blending actually produced, so the cost and the
        // coverage of both are measured rather than assumed. A model whose geometry turns out
        // to have no joint boundaries to blend across - or one whose quads are large enough to
        // multiply its vertex count - is visible here rather than only in a frame time.
        if (com.ysmef.compat.YSMEpicFightCompat.LOGGER.isDebugEnabled()) {
            int blended = 0;
            for (int i = 0; i < weights.size(); i++) {
                if (weights.get(i) < 0.999F) {
                    blended++;
                }
            }
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.debug(
                    "YSM-EF Compat: [mesh] {} quads -> {} vertices ({} influence slots, {} blended)",
                    quadCount[0], positions.size() / 3, weights.size(), blended);
        }
        return quadCount[0];
    }

    /**
     * Writes the runtime script JSON consumed by YSMRuntimeModel: the bone table
     * (hierarchy, bind transforms, EF joint binding) plus the molang animations that
     * drive YSM's model-changing behavior.
     */
    private static void writeRuntimeJson(YsmModelPackage pkg, YSMGeoModel geoModel, Path runtimeFile) throws IOException {
        JsonObject root = new JsonObject();

        JsonArray bones = new JsonArray();
        for (YSMGeoModel.Bone bone : geoModel.bonesByName.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", bone.name);
            obj.addProperty("parent", bone.parent != null ? bone.parent.name : "");
            JsonArray pivot = new JsonArray();
            pivot.add(bone.pivotX);
            pivot.add(bone.pivotY);
            pivot.add(bone.pivotZ);
            obj.add("pivot", pivot);
            JsonArray rot = new JsonArray();
            rot.add(bone.rotX);
            rot.add(bone.rotY);
            rot.add(bone.rotZ);
            obj.add("rot", rot);
            obj.addProperty("joint", YSMJointMapper.resolveJointId(bone));
            obj.addProperty("mapped", YSMJointMapper.isDirectlyMapped(bone));
            bones.add(obj);
        }
        root.add("bones", bones);

        root.add("animations", com.ysmef.compat.ysm.script.ScriptJson.animationsToJson(pkg.scriptAnims));

        // RealCamera bind target: front face (the plane the "Eyes" element lies
        // in) for the target plane + forward vector, the head's west side face
        // for the upward vector, roll 90 degrees. Consumed by
        // YsmRealCameraBridge when the Real Camera mod is present. The model
        // package's width/height scales are passed so the reported bind-space
        // eyes position matches the scaled rendered mesh, and bones hidden in
        // the model's default (battle-mode) form are excluded from the face
        // picks (a hidden variant's face is never captured by the probe).
        // Computed from the bones+animations written above (no camera section
        // yet, so the conversion-time compile has no RealCamera side effects).
        java.util.Set<String> hiddenBones = com.ysmef.compat.model.runtime.YSMRuntimeModel
                .computeDefaultHiddenBoneNames(root);
        YsmCameraTargetSolver.CameraUvs cameraUvs = YsmCameraTargetSolver.solve(geoModel, pkg.widthScale, pkg.heightScale, hiddenBones);
        if (cameraUvs != null) {
            JsonObject camera = new JsonObject();
            camera.addProperty("posU", cameraUvs.posU);
            camera.addProperty("posV", cameraUvs.posV);
            camera.addProperty("forwardU", cameraUvs.forwardU);
            camera.addProperty("forwardV", cameraUvs.forwardV);
            camera.addProperty("upwardU", cameraUvs.upwardU);
            camera.addProperty("upwardV", cameraUvs.upwardV);
            camera.addProperty("roll", 90.0f);
            // bind-space eyes position + face normals, used by the RealCamera
            // API-function path (non-battle mode, where the probe/texture
            // matching is unavailable)
            camera.addProperty("eyesX", cameraUvs.eyesX);
            camera.addProperty("eyesY", cameraUvs.eyesY);
            camera.addProperty("eyesZ", cameraUvs.eyesZ);
            camera.addProperty("normalX", cameraUvs.normalX);
            camera.addProperty("normalY", cameraUvs.normalY);
            camera.addProperty("normalZ", cameraUvs.normalZ);
            camera.addProperty("upX", cameraUvs.upX);
            camera.addProperty("upY", cameraUvs.upY);
            camera.addProperty("upZ", cameraUvs.upZ);
            root.add("camera", camera);
        }

        writeFileAtomic(runtimeFile, new GsonBuilder().create().toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Write a file atomically (temp file + rename) so a concurrently reading
     * resource reload or cache scan can never observe a half-written file.
     */
    static void writeFileAtomic(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Recursively walks a bone, applying the bind-pose transform chain and
     * emitting all quad vertices in Epic Fight's Blender-space convention.
     */
    private static void walkBone(YSMGeoModel.Bone bone, Matrix4f parentTransform, float scaleW, float scaleH,
                                 Map<VertexKey, Integer> dedup,
                                 List<Float> positions, List<Float> normals, List<Float> uvs,
                                 List<Integer> vcounts, List<Integer> vindices, List<Float> weights,
                                 Map<String, List<Integer>> partIndices, int[] quadCount, int depth) {
        if (depth > YSMGeoModel.MAX_BONE_DEPTH) {
            throw new IllegalStateException(
                    "bone hierarchy deeper than " + YSMGeoModel.MAX_BONE_DEPTH + " while writing the mesh");
        }
        Matrix4f boneTransform = new Matrix4f(parentTransform);
        boneTransform.translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        boneTransform.rotateZ(bone.rotZ);
        boneTransform.rotateY(bone.rotY);
        boneTransform.rotateX(bone.rotX);
        boneTransform.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);

        if (!bone.quads.isEmpty()) {
            int jointId = YSMJointMapper.resolveJointId(bone);
            List<YSMGeoModel.Bone> children = blendTargets(bone);
            List<Integer> partList = partIndices.computeIfAbsent(partNameOf(bone), k -> new ArrayList<>());

            for (YSMGeoModel.Quad quad : bone.quads) {
                quadCount[0]++;
                emitQuadRefined(quad, boneTransform, scaleW, scaleH, jointId, children, bone, dedup,
                        partList, positions, normals, uvs, vcounts, vindices, weights);
            }
        }

        for (YSMGeoModel.Bone child : bone.children) {
            walkBone(child, boneTransform, scaleW, scaleH, dedup,
                    positions, normals, uvs, vcounts, vindices, weights, partIndices, quadCount, depth + 1);
        }
    }

    /**
     * Emit one source quad, resampled onto a grid so the geometry near a joint has something
     * for a blend to act on.
     *
     * <p>Two things were wrong with emitting a quad as four rigidly-bound corners, and both
     * showed up as the model tearing in poses a humanoid rig is not shaped for - the EF fly
     * animation, a raised head:
     *
     * <ul>
     *   <li>the limb geometry is far coarser than the model it came from - this mod's test model
     *       carries 456 vertices per leg against 6532 for its hair - so a bend has almost no
     *       geometry to distribute itself over;</li>
     *   <li>every vertex took its bone's joint at full weight, so the surface at a joint is a
     *       hard step between two rigid pieces rather than a continuous bend.</li>
     * </ul>
     *
     * <p>Resampling gives the second problem something to work with: a grid point can sit near
     * a joint and be shared between the two joints either side of it. Interpolation uses the
     * quad's own corners so the original four are reproduced exactly - the interior points are
     * the only ones added, which is what keeps the model's shape and UVs unchanged.
     */
    private static void emitQuadRefined(YSMGeoModel.Quad quad, Matrix4f boneTransform,
                                        float scaleW, float scaleH, int jointId,
                                        List<YSMGeoModel.Bone> children, YSMGeoModel.Bone bone,
                                        Map<VertexKey, Integer> dedup, List<Integer> partList,
                                        List<Float> positions, List<Float> normals, List<Float> uvs,
                                        List<Integer> vcounts, List<Integer> vindices, List<Float> weights) {
        // How many cells this quad is worth, from its own size in blocks.
        float longest = 0.0F;
        for (int i = 0; i < 4; i++) {
            Vector3f a = new Vector3f(quad.positions[i]).mulPosition(boneTransform);
            Vector3f b = new Vector3f(quad.positions[(i + 1) % 4]).mulPosition(boneTransform);
            longest = Math.max(longest, a.distance(b));
        }
        int cells = Math.min(MAX_CELLS_PER_EDGE, Math.max(1, Math.round(longest / TARGET_EDGE_BLOCKS)));

        Vector3f pos = new Vector3f();
        Vector3f normal = new Vector3f();
        int[] cornerIndices = new int[cells * cells * 4];
        for (int cy = 0; cy < cells; cy++) {
            for (int cx = 0; cx < cells; cx++) {
                int c = (cy * cells + cx) * 4;
                cornerIndices[c] = emitVertex(quad, boneTransform, scaleW, scaleH, jointId, children, bone,
                        cx / (float) cells, cy / (float) cells, dedup, positions, normals, uvs,
                        vcounts, vindices, weights, pos, normal);
                cornerIndices[c + 1] = emitVertex(quad, boneTransform, scaleW, scaleH, jointId, children, bone,
                        (cx + 1) / (float) cells, cy / (float) cells, dedup, positions, normals, uvs,
                        vcounts, vindices, weights, pos, normal);
                cornerIndices[c + 2] = emitVertex(quad, boneTransform, scaleW, scaleH, jointId, children, bone,
                        (cx + 1) / (float) cells, (cy + 1) / (float) cells, dedup, positions, normals, uvs,
                        vcounts, vindices, weights, pos, normal);
                cornerIndices[c + 3] = emitVertex(quad, boneTransform, scaleW, scaleH, jointId, children, bone,
                        cx / (float) cells, (cy + 1) / (float) cells, dedup, positions, normals, uvs,
                        vcounts, vindices, weights, pos, normal);
            }
        }

        // Epic Fight parts store pre-triangulated corner triplets (see biped.json: six corners
        // per quad); every three consecutive VertexBuilders become one triangle at draw time.
        // Fan each cell as (0,1,2) + (2,3,0), preserving the quad's winding.
        for (int cell = 0; cell < cells * cells; cell++) {
            int c = cell * 4;
            int[] fan = {cornerIndices[c], cornerIndices[c + 1], cornerIndices[c + 2],
                    cornerIndices[c + 2], cornerIndices[c + 3], cornerIndices[c]};
            for (int index : fan) {
                partList.add(index);
                partList.add(index);
                partList.add(index);
            }
        }
    }

    /** Emit one grid point of a quad and return its vertex index. */
    private static int emitVertex(YSMGeoModel.Quad quad, Matrix4f boneTransform,
                                  float scaleW, float scaleH, int jointId, List<YSMGeoModel.Bone> children,
                                  YSMGeoModel.Bone bone, float fu, float fv,
                                  Map<VertexKey, Integer> dedup, List<Float> positions, List<Float> normals,
                                  List<Float> uvs, List<Integer> vcounts, List<Integer> vindices,
                                  List<Float> weights, Vector3f pos, Vector3f normal) {
        bilinear(quad.positions, fu, fv, pos);
        pos.mulPosition(boneTransform);
        bilinearDirection(quad.normal, normal);
        normal.mulDirection(boneTransform);

        float px = pos.x() * scaleW;
        float py = pos.y() * scaleH;
        float pz = pos.z() * scaleW;

        // Non-uniform player scales squash the vertices anisotropically; the normals must
        // follow the inverse-transpose (1/s per axis), or Epic Fight's lighting (compute and
        // GPU paths) shades the model wrong.
        if (Math.abs(scaleW - scaleH) > 1e-6f && scaleW > 1e-6f && scaleH > 1e-6f) {
            normal.x /= scaleW;
            normal.y /= scaleH;
            normal.z /= scaleW;
            normal.normalize();
        }

        float u = bilinearValue(quad.uvs, 0, fu, fv);
        float v = bilinearValue(quad.uvs, 1, fu, fv);

        // Up to two influences: this bone's joint, and the nearest child's within reach.
        int jointA = jointId;
        int weightSlotA = weights.size();
        weights.add(1.0F);
        int jointB = jointA;
        int count = 1;
        float blend = 0.0F;
        for (YSMGeoModel.Bone child : children) {
            float radius = Math.max(blendRadius(child), 1.0E-4F);
            float distance = (float) Math.sqrt(
                    sq(px - child.pivotX) + sq(py - child.pivotY) + sq(pz - child.pivotZ));
            float w = 1.0F - smoothstep(radius * 1.5F, radius * 3.0F, distance);
            if (w > blend) {
                blend = w;
                jointB = YSMJointMapper.resolveJointId(child);
            }
        }
        if (blend > 0.0F && jointB != jointA) {
            weights.set(weightSlotA, 1.0F - blend);
            weights.add(blend);
            count = 2;
        }

        VertexKey key = new VertexKey(
                Math.round(px * 1000f), Math.round(py * 1000f), Math.round(pz * 1000f),
                Math.round(normal.x() * 100f), Math.round(normal.y() * 100f), Math.round(normal.z() * 100f),
                Math.round(u * 4096f), Math.round(v * 4096f), jointA,
                count > 1 ? jointB : -1, Math.round(blend * 255f));
        Integer index = dedup.get(key);
        if (index != null) {
            // A vertex shared with an earlier quad keeps that quad's weight slots; the ones
            // just appended for this attempt are the duplicates.
            if (count > 1) {
                weights.remove(weights.size() - 1);
            }
            return index;
        }

        index = positions.size() / 3;
        // Epic Fight's mesh JSON is authored in Blender space and the loader applies
        // (x, y, z)_mc -> (x, -z, y); convert accordingly.
        positions.add(px);
        positions.add(-pz);
        positions.add(py);
        normals.add(normal.x());
        normals.add(-normal.z());
        normals.add(normal.y());
        uvs.add(u);
        uvs.add(v);
        vcounts.add(1);
        vindices.add(jointA);
        vindices.add(jointB);
        dedup.put(key, index);
        return index;
    }

    /** The children of a bone whose pivot is far enough from the bone's to be a joint. */
    private static List<YSMGeoModel.Bone> blendTargets(YSMGeoModel.Bone bone) {
        List<YSMGeoModel.Bone> targets = new ArrayList<>();
        for (YSMGeoModel.Bone child : bone.children) {
            if (blendRadius(child) > MIN_JOINT_OFFSET) {
                targets.add(child);
            }
        }
        return targets;
    }

    /**
     * How far from a child's pivot the blend reaches, in blocks: the child bone's own size, so
     * a long limb blends over a long distance and a fingertip over a short one.
     *
     * <p>Zero for a child that sits on its parent, which is a bone split for authoring rather
     * than a joint - blending across it would soften geometry that is not bending.
     */
    private static float blendRadius(YSMGeoModel.Bone child) {
        float extent = 0.0F;
        for (YSMGeoModel.Quad quad : child.quads) {
            for (Vector3f corner : quad.positions) {
                extent = Math.max(extent, (float) Math.sqrt(
                        sq(corner.x() - child.pivotX) + sq(corner.y() - child.pivotY)
                                + sq(corner.z() - child.pivotZ)));
            }
        }
        if (extent <= 1.0E-5F) {
            // A bone that draws nothing of its own still marks a joint: reach the whole way to
            // its children, whose geometry is what actually bends.
            for (YSMGeoModel.Bone grandchild : child.children) {
                extent = Math.max(extent, (float) Math.sqrt(
                        sq(grandchild.pivotX - child.pivotX) + sq(grandchild.pivotY - child.pivotY)
                                + sq(grandchild.pivotZ - child.pivotZ)));
            }
        }
        return extent;
    }

    private static void bilinear(Vector3f[] corners, float u, float v, Vector3f out) {
        out.set(0.0F, 0.0F, 0.0F);
        out.x = bilinearValue(corners, 0, u, v);
        out.y = bilinearValue(corners, 1, u, v);
        out.z = bilinearValue(corners, 2, u, v);
    }

    /** Interpolate one axis of four corners in quad order (0,1,2,3 = TL,TR,BR,BL). */
    private static float bilinearValue(Vector3f[] corners, int axis, float u, float v) {
        float top = corners[0].get(axis) + (corners[1].get(axis) - corners[0].get(axis)) * u;
        float bottom = corners[3].get(axis) + (corners[2].get(axis) - corners[3].get(axis)) * u;
        return top + (bottom - top) * v;
    }

    private static float bilinearValue(float[][] uvs, int axis, float u, float v) {
        float top = uvs[0][axis] + (uvs[1][axis] - uvs[0][axis]) * u;
        float bottom = uvs[3][axis] + (uvs[2][axis] - uvs[3][axis]) * u;
        return top + (bottom - top) * v;
    }

    private static void bilinearDirection(Vector3f normal, Vector3f out) {
        // A quad carries one normal rather than four, so every grid point of it shares it;
        // only the transform below changes it.
        out.set(normal);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) {
            return x <= edge0 ? 1.0F : 0.0F;
        }
        float t = Math.max(0.0F, Math.min(1.0F, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0F - 2.0F * t);
    }

    private static float sq(float value) {
        return value * value;
    }

    /** The Epic Fight part name carrying the geometry of the given YSM bone. */
    public static String partNameOf(YSMGeoModel.Bone bone) {
        return BONE_PART_PREFIX + bone.name;
    }

    /** The flat per-influence weight table that {@code affectingWeightIndices} indexes into. */
    private static JsonObject weightArray(List<Float> weights) {
        JsonObject obj = new JsonObject();
        obj.addProperty("stride", 1);
        obj.addProperty("count", weights.size());
        JsonArray array = new JsonArray();
        for (Float weight : weights) {
            array.add(weight);
        }
        obj.add("array", array);
        return obj;
    }

    private static JsonObject partArray(List<Integer> indices) {
        JsonObject partObj = new JsonObject();
        partObj.addProperty("stride", 3);
        partObj.addProperty("count", indices.size() / 3);
        JsonArray partArray = new JsonArray();
        for (Integer index : indices) {
            partArray.add(index);
        }
        partObj.add("array", partArray);
        return partObj;
    }

    private static JsonObject floatArray(List<Float> values, int stride) {
        JsonObject obj = new JsonObject();
        obj.addProperty("stride", stride);
        obj.addProperty("count", values.size() / stride);
        JsonArray array = new JsonArray();
        for (Float value : values) {
            array.add(value);
        }
        obj.add("array", array);
        return obj;
    }

    private static JsonObject intArray(List<Integer> values, int stride) {
        JsonObject obj = new JsonObject();
        obj.addProperty("stride", stride);
        obj.addProperty("count", values.size() / stride);
        JsonArray array = new JsonArray();
        for (Integer value : values) {
            array.add(value);
        }
        obj.add("array", array);
        return obj;
    }
}
