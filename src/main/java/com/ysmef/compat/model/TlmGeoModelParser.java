package com.ysmef.compat.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for Touhou Little Maid bedrock maid models (the "GEO" models of TLM
 * model packs), producing the same YSMGeoModel structure the Epic Fight mesh
 * converter consumes.
 *
 * TLM's own bedrock renderer (simplebedrockmodel) works in vanilla's y-down
 * model space: root part position (pivotX, 24 - pivotY, pivotZ), child part
 * position parentPos + (pivot - parentPivot, parentPivot.y - pivot.y, pivotZ -
 * parentPivot.z), cube origins (origin - pivot, pivot.y - origin.y - size.y,
 * originZ - pivotZ), rotations in radians applied ZYX without negation. Epic
 * Fight's mesh space is y-up with the entity origin at the feet, so this parser
 * outputs plain bedrock coordinates (the reflection between the two spaces
 * cancels TLM's conversions):
 * - bone pivots: plain bedrock absolute positions (blocks), children summing
 *   plain bedrock deltas
 * - bone/cube rotations: (rad(-x), rad(y), rad(-z)) - the y-flip conjugation
 *   of TLM's (rx, ry, rz)
 * - quads: TLM part-frame corners with y negated, shifted by the bedrock pivot
 * - box UV layout and per-face UV handling identical to BedrockCubeBox /
 *   BedrockCubePerFace (including the up/down and east/west face swaps and
 *   uv_rotation)
 * - bone-level "mirror" is ignored (TLM sets but never uses it); cube mirror
 *   only swaps the UV corner table
 */
public final class TlmGeoModelParser {

    private TlmGeoModelParser() {}

    // BedrockCube.VERTEX_ORDER: per-face vertex indices into the 8 cube corners
    // (0=X1Y1Z1, 1=X2Y1Z1, 2=X2Y2Z1, 3=X1Y2Z1, 4=X1Y1Z2, 5=X2Y1Z2, 6=X2Y2Z2, 7=X1Y2Z2)
    private static final int[][] VERTEX_ORDER = {
            {5, 4, 0, 1},
            {2, 3, 7, 6},
            {1, 0, 3, 2},
            {4, 5, 6, 7},
            {0, 4, 7, 3},
            {5, 1, 2, 6},
    };

    private static final Vector3f[] FACE_NORMALS = {
            new Vector3f(0, -1, 0),
            new Vector3f(0, 1, 0),
            new Vector3f(0, 0, -1),
            new Vector3f(0, 0, 1),
            new Vector3f(-1, 0, 0),
            new Vector3f(1, 0, 0),
    };

    // BedrockCubeBox uv corner tables (indices into the uvs[9] box-uv array)
    private static final int[][] UV_ORDER_NO_MIRROR = {
            {1, 2, 6, 7},
            {2, 3, 7, 6},
            {1, 2, 7, 8},
            {4, 5, 7, 8},
            {0, 1, 7, 8},
            {2, 4, 7, 8},
    };
    private static final int[][] UV_ORDER_MIRRORED = {
            {2, 1, 6, 7},
            {3, 2, 7, 6},
            {2, 1, 7, 8},
            {5, 4, 7, 8},
            {4, 2, 7, 8},
            {1, 0, 7, 8},
    };

    /**
     * Parse a TLM bedrock model (legacy "geometry.model" or new
     * "minecraft:geometry" layout). Returns null when no geometry is present.
     */
    public static YSMGeoModel parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        int texWidth = 64;
        int texHeight = 64;
        JsonArray bones = null;

        if (root.has("geometry.model")) {
            JsonObject geo = root.getAsJsonObject("geometry.model");
            if (geo.has("texturewidth")) {
                texWidth = geo.get("texturewidth").getAsInt();
            }
            if (geo.has("textureheight")) {
                texHeight = geo.get("textureheight").getAsInt();
            }
            bones = geo.getAsJsonArray("bones");
        } else if (root.has("minecraft:geometry")) {
            JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
            if (geometries == null || geometries.isEmpty()) {
                return null;
            }
            JsonObject geo = geometries.get(0).getAsJsonObject();
            JsonObject description = geo.getAsJsonObject("description");
            if (description != null) {
                if (description.has("texture_width")) {
                    texWidth = description.get("texture_width").getAsInt();
                }
                if (description.has("texture_height")) {
                    texHeight = description.get("texture_height").getAsInt();
                }
            }
            bones = geo.getAsJsonArray("bones");
        } else {
            return null;
        }

        if (bones == null || bones.isEmpty()) {
            return null;
        }

        YSMGeoModel model = new YSMGeoModel();
        model.textureWidth = texWidth;
        model.textureHeight = texHeight;

        Map<String, JsonObject> boneJsonByName = new HashMap<>();
        Map<String, YSMGeoModel.Bone> boneByName = new HashMap<>();
        Map<String, float[]> pivotByName = new HashMap<>();
        for (JsonElement element : bones) {
            JsonObject boneJson = element.getAsJsonObject();
            String name = boneJson.get("name").getAsString();
            boneJsonByName.put(name, boneJson);
            pivotByName.put(name, readVec3(boneJson, "pivot", 0, 0, 0));
        }

        // create bones with TLM-converted absolute positions (blocks)
        for (JsonElement element : bones) {
            JsonObject boneJson = element.getAsJsonObject();
            buildBone(model, boneJson, boneJsonByName, pivotByName, boneByName);
        }
        return model;
    }

    private static YSMGeoModel.Bone buildBone(YSMGeoModel model, JsonObject boneJson,
                                              Map<String, JsonObject> boneJsonByName,
                                              Map<String, float[]> pivotByName,
                                              Map<String, YSMGeoModel.Bone> boneByName) {
        String name = boneJson.get("name").getAsString();
        YSMGeoModel.Bone existing = boneByName.get(name);
        if (existing != null) {
            return existing;
        }

        YSMGeoModel.Bone bone = new YSMGeoModel.Bone();
        bone.name = name;
        bone.mirror = boneJson.has("mirror") && boneJson.get("mirror").getAsBoolean();
        bone.inflate = boneJson.has("inflate") ? boneJson.get("inflate").getAsFloat() : 0.0f;

        float[] pivot = pivotByName.get(name);
        String parentName = boneJson.has("parent") ? boneJson.get("parent").getAsString() : null;
        YSMGeoModel.Bone parent = null;
        if (parentName != null && boneJsonByName.containsKey(parentName)) {
            parent = buildBone(model, boneJsonByName.get(parentName), boneJsonByName, pivotByName, boneByName);
        }

        // Epic Fight-space (y-up) absolute bone pivots, plain bedrock values:
        // root -> (px, py, pz); child -> parentPivot + (px - ppx, py - ppy, pz - ppz)
        if (parent != null) {
            float[] parentPivot = pivotByName.get(parentName);
            bone.pivotX = parent.pivotX + (pivot[0] - parentPivot[0]) / 16.0f;
            bone.pivotY = parent.pivotY + (pivot[1] - parentPivot[1]) / 16.0f;
            bone.pivotZ = parent.pivotZ + (pivot[2] - parentPivot[2]) / 16.0f;
            bone.parent = parent;
            parent.children.add(bone);
        } else {
            bone.pivotX = pivot[0] / 16.0f;
            bone.pivotY = pivot[1] / 16.0f;
            bone.pivotZ = pivot[2] / 16.0f;
            model.topLevelBones.add(bone);
        }

        // y-flip conjugation of TLM's vanilla-space rotations: (rad(-x), rad(y), rad(-z))
        float[] rotation = readVec3(boneJson, "rotation", 0, 0, 0);
        bone.rotX = (float) Math.toRadians(-rotation[0]);
        bone.rotY = (float) Math.toRadians(rotation[1]);
        bone.rotZ = (float) Math.toRadians(-rotation[2]);

        if (boneJson.has("cubes")) {
            for (JsonElement cubeElement : boneJson.getAsJsonArray("cubes")) {
                bone.quads.addAll(buildCube(model, cubeElement.getAsJsonObject(), bone, pivot));
            }
        }

        model.bonesByName.put(name, bone);
        boneByName.put(name, bone);
        return bone;
    }

    /**
     * Bake one cube into quads (TLM part-frame coordinates), then shift them by
     * the bone's absolute position so quads are in model bind space.
     */
    private static List<YSMGeoModel.Quad> buildCube(YSMGeoModel model, JsonObject cubeJson,
                                                    YSMGeoModel.Bone bone, float[] bonePivot) {
        float[] origin = readVec3(cubeJson, "origin", 0, 0, 0);
        float[] size = readVec3(cubeJson, "size", 0, 0, 0);
        boolean mirror = cubeJson.has("mirror") ? cubeJson.get("mirror").getAsBoolean() : bone.mirror;
        float delta = (cubeJson.has("inflate") ? cubeJson.get("inflate").getAsFloat() : bone.inflate);

        float texW = model.textureWidth;
        float texH = model.textureHeight;

        // TLM convertOrigin(bone, cube): (origin - pivot, pivot.y - origin.y -
        // size.y, originZ - pivotZ), minus delta, in blocks
        float x1 = (origin[0] - bonePivot[0] - delta) / 16.0f;
        float y1 = (bonePivot[1] - origin[1] - size[1] - delta) / 16.0f;
        float z1 = (origin[2] - bonePivot[2] - delta) / 16.0f;
        float x2 = (origin[0] - bonePivot[0] + size[0] + delta) / 16.0f;
        float y2 = (bonePivot[1] - origin[1] + delta) / 16.0f;
        float z2 = (origin[2] - bonePivot[2] + size[2] + delta) / 16.0f;

        Vector3f[] verts = {
                new Vector3f(x1, y1, z1), new Vector3f(x2, y1, z1),
                new Vector3f(x2, y2, z1), new Vector3f(x1, y2, z1),
                new Vector3f(x1, y1, z2), new Vector3f(x2, y1, z2),
                new Vector3f(x2, y2, z2), new Vector3f(x1, y2, z2),
        };

        // per-face UV rect corners: [face][4 corners][u,v]
        float[][][] faceUvs;
        boolean[] facePresent = new boolean[6];
        JsonElement uvElement = cubeJson.get("uv");
        if (uvElement != null && uvElement.isJsonObject()) {
            faceUvs = readPerFaceUvs(uvElement.getAsJsonObject(), texW, texH, facePresent);
        } else {
            float[] uv = readVec2(cubeJson, "uv", 0, 0);
            faceUvs = boxUvs(uv[0], uv[1], size, texW, texH, mirror);
            for (int i = 0; i < 6; i++) {
                facePresent[i] = true;
            }
        }

        // cube-level pivot rotation (new format only), TLM convention
        float[] cubePivot = cubeJson.has("pivot") && cubeJson.get("pivot").isJsonArray()
                ? readVec3(cubeJson, "pivot", 0, 0, 0) : null;
        float[] cubeRotation = cubeJson.has("rotation") && cubeJson.get("rotation").isJsonArray()
                ? readVec3(cubeJson, "rotation", 0, 0, 0) : null;
        Matrix4f cubeMat = null;
        if (cubePivot != null && cubeRotation != null
                && (cubeRotation[0] != 0 || cubeRotation[1] != 0 || cubeRotation[2] != 0)) {
            // TLM convertPivot(bone, cube): (cube.pivot - bone.pivot, bone.pivot.y -
            // cube.pivot.y, cube.pivotZ - bone.pivotZ) in blocks
            float cpx = (cubePivot[0] - bonePivot[0]) / 16.0f;
            float cpy = (bonePivot[1] - cubePivot[1]) / 16.0f;
            float cpz = (cubePivot[2] - bonePivot[2]) / 16.0f;
            float crx = (float) Math.toRadians(cubeRotation[0]);
            float cry = (float) Math.toRadians(cubeRotation[1]);
            float crz = (float) Math.toRadians(cubeRotation[2]);
            cubeMat = new Matrix4f()
                    .translate(cpx, cpy, cpz)
                    .rotateZ(crz).rotateY(cry).rotateX(crx);
        }

        List<YSMGeoModel.Quad> quads = new ArrayList<>(6);
        for (int face = 0; face < 6; face++) {
            if (!facePresent[face]) {
                continue;
            }
            Vector3f[] positions = new Vector3f[4];
            for (int i = 0; i < 4; i++) {
                Vector3f v = new Vector3f(verts[VERTEX_ORDER[face][i]]);
                if (cubeMat != null) {
                    v.mulPosition(cubeMat);
                }
                // y-flip into Epic Fight space, then shift by the bedrock pivot
                v.set(v.x(), -v.y(), v.z());
                v.add(bone.pivotX, bone.pivotY, bone.pivotZ);
                positions[i] = v;
            }
            Vector3f normal = new Vector3f(FACE_NORMALS[face]);
            if (cubeMat != null) {
                normal.mulDirection(cubeMat);
            }
            normal.set(normal.x(), -normal.y(), normal.z());
            quads.add(new YSMGeoModel.Quad(positions, faceUvs[face], normal));
        }
        return quads;
    }

    /**
     * BedrockCubeBox box-uv layout: per-face 4 uv corners
     * [ (o1,o2), (o0,o2), (o0,o3), (o1,o3) ] over the uvs[9] array.
     */
    private static float[][][] boxUvs(float u, float v, float[] size, float texW, float texH, boolean mirror) {
        float dx = (float) Math.floor(size[0]);
        float dy = (float) Math.floor(size[1]);
        float dz = (float) Math.floor(size[2]);

        float scaleU = 1.0f / texW;
        float scaleV = 1.0f / texH;
        float[] uvs = new float[9];
        uvs[0] = scaleU * u;
        uvs[1] = scaleU * (u + dz);
        uvs[2] = scaleU * (u + dz + dx);
        uvs[3] = scaleU * (u + dz + dx + dx);
        uvs[4] = scaleU * (u + dz + dx + dz);
        uvs[5] = scaleU * (u + dz + dx + dz + dx);
        uvs[6] = scaleV * v;
        uvs[7] = scaleV * (v + dz);
        uvs[8] = scaleV * (v + dz + dy);

        int[][] order = mirror ? UV_ORDER_MIRRORED : UV_ORDER_NO_MIRROR;
        float[][][] faceUvs = new float[6][4][2];
        for (int face = 0; face < 6; face++) {
            int[] o = order[face];
            faceUvs[face][0] = new float[]{uvs[o[1]], uvs[o[2]]};
            faceUvs[face][1] = new float[]{uvs[o[0]], uvs[o[2]]};
            faceUvs[face][2] = new float[]{uvs[o[0]], uvs[o[3]]};
            faceUvs[face][3] = new float[]{uvs[o[1]], uvs[o[3]]};
        }
        return faceUvs;
    }

    /**
     * BedrockCubePerFace handling: faces keyed down/east/north/south/up/west,
     * mapped onto the renderer's face indices with TLM's swaps (face0(-Y) <- up,
     * face1(+Y) <- down, face4(-X) <- east, face5(+X) <- west). Empty or
     * zero-size faces are dropped.
     */
    private static float[][][] readPerFaceUvs(JsonObject uvObj, float texW, float texH, boolean[] facePresent) {
        float[][][] faceUvs = new float[6][4][2];
        String[] keys = {"up", "down", "north", "south", "east", "west"};
        for (int face = 0; face < 6; face++) {
            facePresent[face] = false;
            if (!uvObj.has(keys[face]) || !uvObj.get(keys[face]).isJsonObject()) {
                continue;
            }
            JsonObject faceJson = uvObj.getAsJsonObject(keys[face]);
            if (!faceJson.has("uv") || !faceJson.has("uv_size")) {
                continue;
            }
            JsonArray uvArr = faceJson.getAsJsonArray("uv");
            JsonArray sizeArr = faceJson.getAsJsonArray("uv_size");
            float ux = uvArr.get(0).getAsFloat();
            float uy = uvArr.get(1).getAsFloat();
            float w = sizeArr.get(0).getAsFloat();
            float h = sizeArr.get(1).getAsFloat();
            if (Math.abs(w) < 1e-9 && Math.abs(h) < 1e-9) {
                continue;
            }
            int rotation = faceJson.has("uv_rotation") ? faceJson.get("uv_rotation").getAsInt() : 0;
            faceUvs[face] = rotatedUvs(ux, uy, w, h, texW, texH, rotation);
            facePresent[face] = true;
        }
        return faceUvs;
    }

    /**
     * FaceItem.getRotatedUVs: corners ordered 右上, 左上, 左下, 右下 (matching the
     * renderer's vertex order), with uv_rotation rotating the assignment.
     */
    private static float[][] rotatedUvs(float ux, float uy, float w, float h, float texW, float texH, int rotation) {
        float u1 = ux / texW;
        float v1 = uy / texH;
        float u2 = (ux + w) / texW;
        float v2 = (uy + h) / texH;
        return switch (rotation) {
            case 90 -> new float[][]{{u1, v1}, {u1, v2}, {u2, v2}, {u2, v1}};
            case 180 -> new float[][]{{u1, v2}, {u2, v2}, {u2, v1}, {u1, v1}};
            case 270 -> new float[][]{{u2, v2}, {u2, v1}, {u1, v1}, {u1, v2}};
            default -> new float[][]{{u2, v1}, {u1, v1}, {u1, v2}, {u2, v2}};
        };
    }

    private static float[] readVec3(JsonObject json, String key, float defX, float defY, float defZ) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return new float[]{defX, defY, defZ};
        }
        JsonArray arr = json.getAsJsonArray(key);
        return new float[]{arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat()};
    }

    private static float[] readVec2(JsonObject json, String key, float defX, float defY) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return new float[]{defX, defY};
        }
        JsonArray arr = json.getAsJsonArray(key);
        return new float[]{arr.get(0).getAsFloat(), arr.get(1).getAsFloat()};
    }
}
