package com.ysmef.compat.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed YSM Bedrock geometry (format_version 1.12.0).
 *
 * Conversion rules replicate YSM's own GeoBuilder / GeoCube / GeoQuad so that the
 * resulting bind-pose vertices are identical to what YSM renders:
 * - Bone pivot: x negated, stored in block units (bedrock pixels / 16)
 * - Bone rotation: (x, y) negated, degrees to radians
 * - Cube origin: (-(x + sizeX) / 16, y / 16, z / 16)
 * - Cube pivot: x negated; cube rotation: (x, y) negated, degrees to radians
 * - Quad vertex order and UV assignment identical to GeoQuad
 *
 * Cube-level pivot rotation is already baked into the quad positions.
 * Bone-level bind rotations are NOT baked; they are applied by the converter
 * when walking the bone hierarchy.
 */
public class YSMGeoModel {

    public final List<Bone> topLevelBones = new ArrayList<>();
    public final Map<String, Bone> bonesByName = new HashMap<>();
    public int textureWidth = 64;
    public int textureHeight = 64;

    /**
     * Build from a decrypted .ysm binary package (YsmBinaryReader output).
     * Binary faces are already in the same bind-ready form as parsed quads:
     * positions/normals pre-baked with cube rotations, UVs normalized.
     * Bone pivots are in bedrock units (1/16 block) with x negated; rotations
     * are radians with x/y negated, matching the JSON path conventions.
     */
    public static YSMGeoModel fromBinary(com.ysmef.compat.ysm.YsmBinaryReader.BinaryModel binary) {
        YSMGeoModel model = new YSMGeoModel();
        Map<String, Bone> byName = new HashMap<>();
        for (com.ysmef.compat.ysm.YsmBinaryReader.BinaryBone binaryBone : binary.mainBones) {
            Bone bone = new Bone();
            bone.name = binaryBone.name;
            bone.pivotX = binaryBone.pivotX / 16.0f;
            bone.pivotY = binaryBone.pivotY / 16.0f;
            bone.pivotZ = binaryBone.pivotZ / 16.0f;
            bone.rotX = binaryBone.rotX;
            bone.rotY = binaryBone.rotY;
            bone.rotZ = binaryBone.rotZ;

            for (com.ysmef.compat.ysm.YsmBinaryReader.BinaryFace binaryFace : binaryBone.faces) {
                Vector3f[] positions = new Vector3f[4];
                float[][] uvs = new float[4][2];
                for (int i = 0; i < 4; i++) {
                    positions[i] = new Vector3f(binaryFace.px[i], binaryFace.py[i], binaryFace.pz[i]);
                    uvs[i] = new float[]{binaryFace.u[i], binaryFace.v[i]};
                }
                bone.quads.add(new Quad(positions, uvs, new Vector3f(binaryFace.nx, binaryFace.ny, binaryFace.nz)));
            }
            byName.put(bone.name, bone);
            model.bonesByName.put(bone.name, bone);
        }

        for (com.ysmef.compat.ysm.YsmBinaryReader.BinaryBone binaryBone : binary.mainBones) {
            Bone bone = byName.get(binaryBone.name);
            Bone parent = binaryBone.parentName.isEmpty() ? null : byName.get(binaryBone.parentName);
            if (parent != null) {
                bone.parent = parent;
                parent.children.add(bone);
            } else {
                model.topLevelBones.add(bone);
            }
        }
        return model;
    }

    public static YSMGeoModel parse(String json) {        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) {
            return null;
        }
        JsonObject geo = geometries.get(0).getAsJsonObject();

        YSMGeoModel model = new YSMGeoModel();
        JsonObject description = geo.getAsJsonObject("description");
        if (description != null) {
            if (description.has("texture_width")) {
                model.textureWidth = description.get("texture_width").getAsInt();
            }
            if (description.has("texture_height")) {
                model.textureHeight = description.get("texture_height").getAsInt();
            }
        }

        JsonArray bones = geo.getAsJsonArray("bones");
        if (bones == null) {
            return null;
        }

        Map<String, JsonObject> boneJsonByName = new HashMap<>();
        for (JsonElement element : bones) {
            JsonObject boneJson = element.getAsJsonObject();
            boneJsonByName.put(boneJson.get("name").getAsString(), boneJson);
        }

        for (JsonElement element : bones) {
            JsonObject boneJson = element.getAsJsonObject();
            String name = boneJson.get("name").getAsString();
            if (boneJson.has("parent")) {
                continue;
            }
            Bone bone = buildBone(model, boneJson, null, boneJsonByName);
            if (bone != null) {
                model.topLevelBones.add(bone);
            }
        }
        return model;
    }

    private static Bone buildBone(YSMGeoModel model, JsonObject boneJson, Bone parent,
                                  Map<String, JsonObject> boneJsonByName) {
        String name = boneJson.get("name").getAsString();
        Bone bone = new Bone();
        bone.name = name;
        bone.parent = parent;

        float[] pivot = readVec3(boneJson, "pivot", 0, 0, 0);
        bone.pivotX = -pivot[0] / 16.0f;
        bone.pivotY = pivot[1] / 16.0f;
        bone.pivotZ = pivot[2] / 16.0f;

        float[] rotation = readVec3(boneJson, "rotation", 0, 0, 0);
        bone.rotX = (float) Math.toRadians(-rotation[0]);
        bone.rotY = (float) Math.toRadians(-rotation[1]);
        bone.rotZ = (float) Math.toRadians(rotation[2]);

        bone.mirror = boneJson.has("mirror") && boneJson.get("mirror").getAsBoolean();
        bone.inflate = boneJson.has("inflate") ? boneJson.get("inflate").getAsFloat() / 16.0f : 0.0f;

        model.bonesByName.put(name, bone);

        if (boneJson.has("cubes")) {
            for (JsonElement cubeElement : boneJson.getAsJsonArray("cubes")) {
                bone.quads.addAll(buildCube(model, cubeElement.getAsJsonObject(), bone));
            }
        }

        for (Map.Entry<String, JsonObject> entry : boneJsonByName.entrySet()) {
            JsonObject childJson = entry.getValue();
            if (childJson.has("parent") && childJson.get("parent").getAsString().equals(name)) {
                Bone child = buildBone(model, childJson, bone, boneJsonByName);
                bone.children.add(child);
            }
        }
        return bone;
    }

    /**
     * Replicates GeoCube.createFromPojoCube + the cube pivot rotation from
     * IGeoRenderer.renderCube, producing quads in bone-bind space.
     */
    private static List<Quad> buildCube(YSMGeoModel model, JsonObject cubeJson, Bone bone) {
        float[] origin = readVec3(cubeJson, "origin", 0, 0, 0);
        float[] size = readVec3(cubeJson, "size", 0, 0, 0);
        boolean cubeMirror = cubeJson.has("mirror") && cubeJson.get("mirror").getAsBoolean();
        boolean mirror = cubeMirror || bone.mirror;
        float inflate = cubeJson.has("inflate") ? cubeJson.get("inflate").getAsFloat() / 16.0f : bone.inflate;

        float texW = model.textureWidth;
        float texH = model.textureHeight;

        float ox = -(origin[0] + size[0]) / 16.0f;
        float oy = origin[1] / 16.0f;
        float oz = origin[2] / 16.0f;
        float sx = size[0] / 16.0f;
        float sy = size[1] / 16.0f;
        float sz = size[2] / 16.0f;

        Vector3f p1 = new Vector3f(ox - inflate, oy - inflate, oz - inflate);
        Vector3f p2 = new Vector3f(ox - inflate, oy - inflate, oz + sz + inflate);
        Vector3f p3 = new Vector3f(ox - inflate, oy + sy + inflate, oz - inflate);
        Vector3f p4 = new Vector3f(ox - inflate, oy + sy + inflate, oz + sz + inflate);
        Vector3f p5 = new Vector3f(ox + sx + inflate, oy - inflate, oz - inflate);
        Vector3f p6 = new Vector3f(ox + sx + inflate, oy - inflate, oz + sz + inflate);
        Vector3f p7 = new Vector3f(ox + sx + inflate, oy + sy + inflate, oz - inflate);
        Vector3f p8 = new Vector3f(ox + sx + inflate, oy + sy + inflate, oz + sz + inflate);

        double bsx = Math.floor(size[0]);
        double bsy = Math.floor(size[1]);
        double bsz = Math.floor(size[2]);

        double[] uv = readBoxUv(cubeJson);
        JsonObject faceUv = readFaceUv(cubeJson);

        Quad quadWest, quadEast, quadNorth, quadSouth, quadUp, quadDown;

        if (faceUv != null) {
            double[] west = faceRect(faceUv, "west");
            double[] east = faceRect(faceUv, "east");
            double[] north = faceRect(faceUv, "north");
            double[] south = faceRect(faceUv, "south");
            double[] up = faceRect(faceUv, "up");
            double[] down = faceRect(faceUv, "down");

            quadWest = west == null ? null : makeQuad(new Vector3f[]{p4, p3, p1, p2}, west, texW, texH, cubeMirror, NORMAL_WEST);
            quadEast = east == null ? null : makeQuad(new Vector3f[]{p7, p8, p6, p5}, east, texW, texH, cubeMirror, NORMAL_EAST);
            quadNorth = north == null ? null : makeQuad(new Vector3f[]{p3, p7, p5, p1}, north, texW, texH, cubeMirror, NORMAL_NORTH);
            quadSouth = south == null ? null : makeQuad(new Vector3f[]{p8, p4, p2, p6}, south, texW, texH, cubeMirror, NORMAL_SOUTH);
            quadUp = up == null ? null : makeQuad(new Vector3f[]{p4, p8, p7, p3}, up, texW, texH, cubeMirror, NORMAL_UP);
            quadDown = down == null ? null : makeQuad(new Vector3f[]{p1, p5, p6, p2}, down, texW, texH, cubeMirror, NORMAL_DOWN);

            if (mirror) {
                quadWest = west == null ? null : makeQuad(new Vector3f[]{p7, p8, p6, p5}, west, texW, texH, cubeMirror, NORMAL_WEST);
                quadEast = east == null ? null : makeQuad(new Vector3f[]{p4, p3, p1, p2}, east, texW, texH, cubeMirror, NORMAL_EAST);
                quadNorth = north == null ? null : makeQuad(new Vector3f[]{p3, p7, p5, p1}, north, texW, texH, cubeMirror, NORMAL_NORTH);
                quadSouth = south == null ? null : makeQuad(new Vector3f[]{p8, p4, p2, p6}, south, texW, texH, cubeMirror, NORMAL_SOUTH);
                quadUp = up == null ? null : makeQuad(new Vector3f[]{p1, p5, p6, p2}, up, texW, texH, cubeMirror, NORMAL_UP);
                quadDown = down == null ? null : makeQuad(new Vector3f[]{p4, p8, p7, p3}, down, texW, texH, cubeMirror, NORMAL_DOWN);
            }
        } else {
            if (uv == null) {
                uv = new double[]{0, 0};
            }
            double u = uv[0];
            double v = uv[1];

            quadWest = makeQuad(new Vector3f[]{p4, p3, p1, p2}, rect(u + bsz + bsx, v + bsz, bsz, bsy), texW, texH, cubeMirror, NORMAL_WEST);
            quadEast = makeQuad(new Vector3f[]{p7, p8, p6, p5}, rect(u, v + bsz, bsz, bsy), texW, texH, cubeMirror, NORMAL_EAST);
            quadNorth = makeQuad(new Vector3f[]{p3, p7, p5, p1}, rect(u + bsz, v + bsz, bsx, bsy), texW, texH, cubeMirror, NORMAL_NORTH);
            quadSouth = makeQuad(new Vector3f[]{p8, p4, p2, p6}, rect(u + bsz + bsx + bsz, v + bsz, bsx, bsy), texW, texH, cubeMirror, NORMAL_SOUTH);
            quadUp = makeQuad(new Vector3f[]{p4, p8, p7, p3}, rect(u + bsz, v, bsx, bsz), texW, texH, cubeMirror, NORMAL_UP);
            quadDown = makeQuad(new Vector3f[]{p1, p5, p6, p2}, rect(u + bsz + bsx, v, bsx, -bsz), texW, texH, cubeMirror, NORMAL_DOWN);

            if (mirror) {
                quadWest = makeQuad(new Vector3f[]{p7, p8, p6, p5}, rect(u + bsz + bsx, v + bsz, bsz, bsy), texW, texH, cubeMirror, NORMAL_WEST);
                quadEast = makeQuad(new Vector3f[]{p4, p3, p1, p2}, rect(u, v + bsz, bsz, bsy), texW, texH, cubeMirror, NORMAL_EAST);
                quadNorth = makeQuad(new Vector3f[]{p3, p7, p5, p1}, rect(u + bsz, v + bsz, bsx, bsy), texW, texH, cubeMirror, NORMAL_NORTH);
                quadSouth = makeQuad(new Vector3f[]{p8, p4, p2, p6}, rect(u + bsz + bsx + bsz, v + bsz, bsx, bsy), texW, texH, cubeMirror, NORMAL_SOUTH);
                quadUp = makeQuad(new Vector3f[]{p1, p5, p6, p2}, rect(u + bsz, v, bsx, bsz), texW, texH, cubeMirror, NORMAL_UP);
                quadDown = makeQuad(new Vector3f[]{p4, p8, p7, p3}, rect(u + bsz + bsx, v, bsx, -bsz), texW, texH, cubeMirror, NORMAL_DOWN);
            }
        }

        List<Quad> quads = new ArrayList<>(6);
        if (quadWest != null) quads.add(quadWest);
        if (quadEast != null) quads.add(quadEast);
        if (quadNorth != null) quads.add(quadNorth);
        if (quadSouth != null) quads.add(quadSouth);
        if (quadUp != null) quads.add(quadUp);
        if (quadDown != null) quads.add(quadDown);

        float[] cubePivot = readVec3(cubeJson, "pivot", 0, 0, 0);
        float[] cubeRotation = readVec3(cubeJson, "rotation", 0, 0, 0);
        boolean hasCubeRotation = cubeRotation[0] != 0 || cubeRotation[1] != 0 || cubeRotation[2] != 0;
        if (hasCubeRotation) {
            float cpx = -cubePivot[0] / 16.0f;
            float cpy = cubePivot[1] / 16.0f;
            float cpz = cubePivot[2] / 16.0f;
            float crx = (float) Math.toRadians(-cubeRotation[0]);
            float cry = (float) Math.toRadians(-cubeRotation[1]);
            float crz = (float) Math.toRadians(cubeRotation[2]);

            org.joml.Matrix4f cubeMat = new org.joml.Matrix4f();
            cubeMat.translate(cpx, cpy, cpz);
            cubeMat.rotateZ(crz);
            cubeMat.rotateY(cry);
            cubeMat.rotateX(crx);
            cubeMat.translate(-cpx, -cpy, -cpz);

            for (Quad quad : quads) {
                for (Vector3f pos : quad.positions) {
                    pos.mulPosition(cubeMat);
                }
                quad.normal.mulDirection(cubeMat);
            }
        }
        return quads;
    }

    private static final Vector3f NORMAL_WEST = new Vector3f(-1, 0, 0);
    private static final Vector3f NORMAL_EAST = new Vector3f(1, 0, 0);
    private static final Vector3f NORMAL_NORTH = new Vector3f(0, 0, -1);
    private static final Vector3f NORMAL_SOUTH = new Vector3f(0, 0, 1);
    private static final Vector3f NORMAL_UP = new Vector3f(0, 1, 0);
    private static final Vector3f NORMAL_DOWN = new Vector3f(0, -1, 0);

    private static Quad makeQuad(Vector3f[] positions, double[] uvRect, float texW, float texH,
                                 boolean mirror, Vector3f directionNormal) {
        float u1 = (float) uvRect[0];
        float v1 = (float) uvRect[1];
        float u2 = u1 + (float) uvRect[2];
        float v2 = v1 + (float) uvRect[3];

        u1 /= texW;
        u2 /= texW;
        v1 /= texH;
        v2 /= texH;

        float[][] uvs = new float[4][2];
        if (mirror) {
            uvs[0] = new float[]{u1, v1};
            uvs[1] = new float[]{u2, v1};
            uvs[2] = new float[]{u2, v2};
            uvs[3] = new float[]{u1, v2};
        } else {
            uvs[0] = new float[]{u2, v1};
            uvs[1] = new float[]{u1, v1};
            uvs[2] = new float[]{u1, v2};
            uvs[3] = new float[]{u2, v2};
        }

        Vector3f normal = new Vector3f(directionNormal);
        if (mirror) {
            normal.mul(-1.0f, 1.0f, 1.0f);
        }
        Vector3f[] copiedPositions = new Vector3f[4];
        for (int i = 0; i < 4; i++) {
            copiedPositions[i] = new Vector3f(positions[i]);
        }
        return new Quad(copiedPositions, uvs, normal);
    }

    private static double[] rect(double u, double v, double w, double h) {
        return new double[]{u, v, w, h};
    }

    private static double[] faceRect(JsonObject faceUv, String face) {
        if (!faceUv.has(face)) {
            return null;
        }
        JsonObject f = faceUv.getAsJsonObject(face);
        JsonArray uvArr = f.getAsJsonArray("uv");
        JsonArray sizeArr = f.has("uv_size") ? f.getAsJsonArray("uv_size") : null;
        if (uvArr == null || sizeArr == null) {
            return null;
        }
        return new double[]{uvArr.get(0).getAsDouble(), uvArr.get(1).getAsDouble(),
                sizeArr.get(0).getAsDouble(), sizeArr.get(1).getAsDouble()};
    }

    private static double[] readBoxUv(JsonObject cubeJson) {
        if (!cubeJson.has("uv")) {
            return null;
        }
        JsonElement uvElement = cubeJson.get("uv");
        if (uvElement.isJsonArray()) {
            JsonArray arr = uvElement.getAsJsonArray();
            return new double[]{arr.get(0).getAsDouble(), arr.get(1).getAsDouble()};
        }
        return null;
    }

    private static JsonObject readFaceUv(JsonObject cubeJson) {
        if (!cubeJson.has("uv")) {
            return null;
        }
        JsonElement uvElement = cubeJson.get("uv");
        return uvElement.isJsonObject() ? uvElement.getAsJsonObject() : null;
    }

    private static float[] readVec3(JsonObject json, String key, float defX, float defY, float defZ) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return new float[]{defX, defY, defZ};
        }
        JsonArray arr = json.getAsJsonArray(key);
        return new float[]{arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat()};
    }

    public static class Bone {
        public String name;
        public Bone parent;
        public final List<Bone> children = new ArrayList<>();
        public final List<Quad> quads = new ArrayList<>();
        public float pivotX, pivotY, pivotZ;
        public float rotX, rotY, rotZ;
        public boolean mirror;
        public float inflate;
    }

    public static class Quad {
        public final Vector3f[] positions;
        public final float[][] uvs;
        public final Vector3f normal;

        public Quad(Vector3f[] positions, float[][] uvs, Vector3f normal) {
            this.positions = positions;
            this.uvs = uvs;
            this.normal = normal;
        }
    }
}
