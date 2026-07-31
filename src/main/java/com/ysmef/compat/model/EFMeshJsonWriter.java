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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates Epic Fight animmodels mesh JSON files from YSM model packages.
 *
 * The output follows the exact format consumed by Epic Fight's JsonAssetLoader
 * (see assets/epicfight/animmodels/entity/biped.json in the Epic Fight jar):
 * - vertex arrays (positions/normals/uvs/vcounts/vindices/weights)
 * - named parts with index triplets (position, uv, normal)
 * - render_properties with the mesh texture
 *
 * Positions and normals are written in Epic Fight's Blender-style coordinate
 * convention (the loader applies a -90deg X rotation: (x, y, z)_mc -> (x, -z, y)),
 * and the YSM model's width/height scale is baked into the vertex positions.
 *
 * Each vertex is rigidly bound to one Epic Fight joint (weight 1.0), so Epic
 * Fight's combat animations deform the mesh through joint skinning.
 */
public class EFMeshJsonWriter {

    private enum BodyPart {
        HEAD, TORSO, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG
    }

    private static final String[] HUMANOID_PARTS = {
            "head", "torso", "leftArm", "rightArm", "leftLeg", "rightLeg",
            "hat", "jacket", "leftSleeve", "rightSleeve", "leftPants", "rightPants"
    };

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
     * @param pkg        the parsed YSM model package
     * @param outFile    the target JSON file
     * @param textureRL  the resource location of the model's default texture
     * @return the number of quads converted, or -1 if the model has no geometry
     */
    public static int write(YsmModelPackage pkg, Path outFile, String textureRL) throws IOException {
        YSMGeoModel geoModel = pkg.geometry;
        if (geoModel == null) {
            return -1;
        }

        float scaleW = pkg.widthScale;
        float scaleH = pkg.heightScale;

        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Integer> vcounts = new ArrayList<>();
        List<Integer> vindices = new ArrayList<>();

        Map<VertexKey, Integer> dedup = new HashMap<>();
        Map<BodyPart, List<Integer>> partIndices = new EnumMap<>(BodyPart.class);
        for (BodyPart part : BodyPart.values()) {
            partIndices.put(part, new ArrayList<>());
        }

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
            BodyPart bodyPart = switch (partName) {
                case "head" -> BodyPart.HEAD;
                case "torso" -> BodyPart.TORSO;
                case "leftArm" -> BodyPart.LEFT_ARM;
                case "rightArm" -> BodyPart.RIGHT_ARM;
                case "leftLeg" -> BodyPart.LEFT_LEG;
                case "rightLeg" -> BodyPart.RIGHT_LEG;
                default -> null;
            };
            List<Integer> indices = bodyPart != null ? partIndices.get(bodyPart) : List.of();
            JsonObject partObj = new JsonObject();
            partObj.addProperty("stride", 3);
            partObj.addProperty("count", indices.size() / 3);
            JsonArray partArray = new JsonArray();
            for (Integer index : indices) {
                partArray.add(index);
            }
            partObj.add("array", partArray);
            parts.add(partName, partObj);
        }
        vertices.add("parts", parts);

        root.add("vertices", vertices);

        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        return quadCount[0];
    }

    /**
     * Recursively walks a bone, applying the bind-pose transform chain and
     * emitting all quad vertices in Epic Fight's Blender-space convention.
     */
    private static void walkBone(YSMGeoModel.Bone bone, Matrix4f parentTransform, float scaleW, float scaleH,
                                 Map<VertexKey, Integer> dedup,
                                 List<Float> positions, List<Float> normals, List<Float> uvs,
                                 List<Integer> vcounts, List<Integer> vindices,
                                 Map<BodyPart, List<Integer>> partIndices, int[] quadCount) {
        Matrix4f boneTransform = new Matrix4f(parentTransform);
        boneTransform.translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        boneTransform.rotateZ(bone.rotZ);
        boneTransform.rotateY(bone.rotY);
        boneTransform.rotateX(bone.rotX);
        boneTransform.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);

        if (!bone.quads.isEmpty()) {
            int jointId = YSMJointMapper.resolveJointId(bone);
            BodyPart bodyPart = bodyPartOf(jointId);
            List<Integer> partList = partIndices.get(bodyPart);

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

    private static BodyPart bodyPartOf(int jointId) {
        return switch (YSMJointMapper.jointNameOf(jointId)) {
            case "Head" -> BodyPart.HEAD;
            case "Arm_L", "Hand_L", "Tool_L", "Elbow_L", "Shoulder_L" -> BodyPart.LEFT_ARM;
            case "Arm_R", "Hand_R", "Tool_R", "Elbow_R", "Shoulder_R" -> BodyPart.RIGHT_ARM;
            case "Thigh_L", "Leg_L", "Knee_L" -> BodyPart.LEFT_LEG;
            case "Thigh_R", "Leg_R", "Knee_R" -> BodyPart.RIGHT_LEG;
            default -> BodyPart.TORSO;
        };
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
