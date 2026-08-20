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

    private record VertexKey(int px, int py, int pz, int nx, int ny, int nz, int u, int v, int jointId) {}

    private static VertexKey keyOf(Vector3f pos, Vector3f normal, float u, float v, int jointId) {
        return new VertexKey(
                Math.round(pos.x() * 1000f), Math.round(pos.y() * 1000f), Math.round(pos.z() * 1000f),
                Math.round(normal.x() * 100f), Math.round(normal.y() * 100f), Math.round(normal.z() * 100f),
                Math.round(u * 4096f), Math.round(v * 4096f), jointId);
    }

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

        Map<VertexKey, Integer> dedup = new HashMap<>();
        Map<String, List<Integer>> partIndices = new LinkedHashMap<>();

        int[] quadCount = {0};
        for (YSMGeoModel.Bone rootBone : geoModel.topLevelBones) {
            walkBone(rootBone, new Matrix4f(), scaleW, scaleH, dedup,
                    positions, normals, uvs, vcounts, vindices, partIndices, quadCount);
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

        JsonObject weightsObj = new JsonObject();
        weightsObj.addProperty("stride", 1);
        weightsObj.addProperty("count", 1);
        JsonArray weightsArray = new JsonArray();
        weightsArray.add(1.0f);
        weightsObj.add("array", weightsArray);
        vertices.add("weights", weightsObj);

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
        // eyes position matches the scaled rendered mesh.
        YsmCameraTargetSolver.CameraUvs cameraUvs = YsmCameraTargetSolver.solve(geoModel, pkg.widthScale, pkg.heightScale);
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
                                 List<Integer> vcounts, List<Integer> vindices,
                                 Map<String, List<Integer>> partIndices, int[] quadCount) {
        Matrix4f boneTransform = new Matrix4f(parentTransform);
        boneTransform.translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        boneTransform.rotateZ(bone.rotZ);
        boneTransform.rotateY(bone.rotY);
        boneTransform.rotateX(bone.rotX);
        boneTransform.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);

        if (!bone.quads.isEmpty()) {
            int jointId = YSMJointMapper.resolveJointId(bone);
            List<Integer> partList = partIndices.computeIfAbsent(partNameOf(bone), k -> new ArrayList<>());

            for (YSMGeoModel.Quad quad : bone.quads) {
                quadCount[0]++;
                int[] cornerIndices = new int[4];
                for (int i = 0; i < 4; i++) {
                    Vector3f pos = new Vector3f(quad.positions[i]);
                    pos.mulPosition(boneTransform);
                    Vector3f normal = new Vector3f(quad.normal);
                    normal.mulDirection(boneTransform);

                    float px = pos.x() * scaleW;
                    float py = pos.y() * scaleH;
                    float pz = pos.z() * scaleW;

                    // Non-uniform (width != height) player scales squash the
                    // vertices anisotropically; the normals must follow the
                    // inverse-transpose (1/s per axis), otherwise Epic Fight's
                    // lighting (compute and GPU paths) shades the model wrong.
                    if (Math.abs(scaleW - scaleH) > 1e-6f && scaleW > 1e-6f && scaleH > 1e-6f) {
                        normal.x /= scaleW;
                        normal.y /= scaleH;
                        normal.z /= scaleW;
                        normal.normalize();
                    }

                    VertexKey key = keyOf(new Vector3f(px, py, pz), normal, quad.uvs[i][0], quad.uvs[i][1], jointId);
                    Integer index = dedup.get(key);
                    if (index == null) {
                        index = positions.size() / 3;
                        // Epic Fight's mesh JSON is authored in Blender space and the
                        // loader applies (x, y, z)_mc -> (x, -z, y); convert accordingly.
                        positions.add(px);
                        positions.add(-pz);
                        positions.add(py);
                        normals.add(normal.x());
                        normals.add(-normal.z());
                        normals.add(normal.y());
                        uvs.add(quad.uvs[i][0]);
                        uvs.add(quad.uvs[i][1]);
                        vcounts.add(1);
                        vindices.add(jointId);
                        vindices.add(0);
                        dedup.put(key, index);
                    }
                    cornerIndices[i] = index;
                }
                // Epic Fight parts store pre-triangulated corner triplets
                // (see biped.json: six corners per quad); every three consecutive
                // VertexBuilders become one triangle at draw time. Fan each quad
                // as (0,1,2) + (2,3,0), preserving the quad's winding.
                int[] fan = {cornerIndices[0], cornerIndices[1], cornerIndices[2],
                        cornerIndices[2], cornerIndices[3], cornerIndices[0]};
                for (int index : fan) {
                    partList.add(index);
                    partList.add(index);
                    partList.add(index);
                }
            }
        }

        for (YSMGeoModel.Bone child : bone.children) {
            walkBone(child, boneTransform, scaleW, scaleH, dedup,
                    positions, normals, uvs, vcounts, vindices, partIndices, quadCount);
        }
    }

    /** The Epic Fight part name carrying the geometry of the given YSM bone. */
    public static String partNameOf(YSMGeoModel.Bone bone) {
        return BONE_PART_PREFIX + bone.name;
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
