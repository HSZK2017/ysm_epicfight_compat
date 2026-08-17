package com.ysmef.compat.ysm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader for YSM's modern binary model format (format >= 16, produced by
 * YsmFileCrypto decryption of .ysm packages).
 *
 * Faithfully skips sections that are irrelevant to mesh conversion (sounds,
 * functions, languages, sub-entities, animations, animation controllers) while
 * extracting:
 * - the main entity geometry (bones with pre-baked faces, see YSM's
 *   YSMBinaryDeserializer / YSMFolderDeserializer)
 * - the texture table (name -> PNG data)
 * - model properties (width/height scale, default texture name)
 *
 * All multi-byte numbers are big-endian (Netty ByteBuf convention); varints are
 * standard LEB128.
 */
public class YsmBinaryReader {

    public static class BinaryModel {
        public final List<BinaryBone> mainBones = new ArrayList<>();
        public final Map<String, byte[]> textures = new LinkedHashMap<>();
        public final Map<String, int[]> textureInfo = new LinkedHashMap<>();
        /** Runtime-relevant animations (parallel loops, locomotion states, hold/use overlays). */
        public final Map<String, com.ysmef.compat.ysm.script.ScriptAnim> animations = new LinkedHashMap<>();
        /** Every parsed animation, including the wheel-selectable "extra" animations. */
        public final Map<String, com.ysmef.compat.ysm.script.ScriptAnim> allAnimations = new LinkedHashMap<>();
        /**
         * Wheel-selectable extra animations parsed from model properties
         * (animation name -> description / empty string).
         */
        public final Map<String, String> extraAnimations = new LinkedHashMap<>();
        public float widthScale = 0.7f;
        public float heightScale = 0.7f;
        public String defaultTexture = "";
        public float textureWidth = 64;
        public float textureHeight = 64;
    }

    public static class BinaryBone {
        public String name;
        public String parentName;
        public final List<BinaryFace> faces = new ArrayList<>();
        public float pivotX, pivotY, pivotZ;
        public float rotX, rotY, rotZ;
    }

    public static class BinaryFace {
        public float nx, ny, nz;
        public final float[] px = new float[4];
        public final float[] py = new float[4];
        public final float[] pz = new float[4];
        public final float[] u = new float[4];
        public final float[] v = new float[4];
    }

    public static BinaryModel read(byte[] data) {
        Reader r = new Reader(data);
        int format = (int) r.readDword();
        if (format < 4) {
            return readLegacyV1(r, format);
        } else if (format <= 15) {
            return readLegacyV15(r, format);
        }
        return readModern(r, format);
    }

    private static BinaryModel readLegacyV1(Reader r, int format) {
        BinaryModel model = new BinaryModel();

        int unknownNeedSkipBytes = r.readVarInt();
        r.skipBytes(unknownNeedSkipBytes);

        int modelCount = r.readVarInt();
        for (int i = 0; i < modelCount; ++i) {
            int modelId = r.readVarInt();
            int unknownMustBeOneFlag = r.readVarInt();
            if (unknownMustBeOneFlag != 1) throw new IllegalStateException("Expected 1");
            List<BinaryBone> bones = readGeometry(r);
            if (modelId == 1) {
                model.mainBones.addAll(bones);
            }
        }

        int animationBlobCount = r.readVarInt();
        for (int i = 0; i < animationBlobCount; ++i) {
            r.readVarInt();
            int unknownPadding = r.readVarInt();
            if (unknownPadding != 1) throw new IllegalStateException("Expected 1");
            readAnimations(r, format, model.allAnimations);
        }

        int customTextureCount = r.readVarInt();
        for (int i = 0; i < customTextureCount; ++i) {
            String name = r.readString();
            if (format < 4) {
                int unknownFormatFlag = r.readVarInt();
                if (unknownFormatFlag != 0x01) throw new IllegalStateException("Expected 0x01");
            }
            byte[] texData = r.readByteArray();
            int texWidth = r.readVarInt();
            int texHeight = r.readVarInt();
            model.textures.put(name, texData);
            model.textureInfo.put(name, new int[]{texWidth, texHeight, -1});
        }

        int modelTableSize = r.readVarInt();
        for (int i = 0; i < modelTableSize; ++i) {
            r.readVarInt();
            r.readString();
        }

        int animationTableSize = r.readVarInt();
        for (int i = 0; i < animationTableSize; ++i) {
            r.readVarInt();
            r.readString();
        }

        int textureTableSize = r.readVarInt();
        for (int i = 0; i < textureTableSize; ++i) {
            r.readString();
            r.readString();
        }

        r.readString();
        copyRuntimeAnimations(model);
        return model;
    }

    private static BinaryModel readLegacyV15(Reader r, int format) {
        BinaryModel model = new BinaryModel();

        int unknownNeedSkipBytes = r.readVarInt();
        r.skipBytes(unknownNeedSkipBytes);

        int modelCount = r.readVarInt();
        for (int i = 0; i < modelCount; ++i) {
            int modelId = r.readVarInt();
            int unknownPadding = r.readVarInt();
            if (unknownPadding != 1) throw new IllegalStateException("Expected 1");
            List<BinaryBone> bones = readGeometry(r);
            if (modelId == 1) {
                model.mainBones.addAll(bones);
            }
        }

        int animationBlobCount = r.readVarInt();
        for (int i = 0; i < animationBlobCount; ++i) {
            r.readVarInt();
            int unknownPadding = r.readVarInt();
            if (unknownPadding != 1) throw new IllegalStateException("Expected 1");
            readAnimations(r, format, model.allAnimations);
        }

        if (format > 9) {
            skipAnimationControllers(r, format, false);
            int animationControllerTableSize = r.readVarInt();
            for (int i = 0; i < animationControllerTableSize; ++i) {
                r.readString();
                r.readString();
            }
        }

        int customTextureCount = r.readVarInt();
        for (int i = 0; i < customTextureCount; ++i) {
            String name = r.readString();
            byte[] texData = r.readByteArray();
            int texWidth = r.readVarInt();
            int texHeight = r.readVarInt();
            int subTextureSize = r.readVarInt();
            for (int j = 0; j < subTextureSize; ++j) {
                r.readVarInt();
                r.readByteArray();
                r.readVarInt();
                r.readVarInt();
            }
            model.textures.put(name, texData);
            model.textureInfo.put(name, new int[]{texWidth, texHeight, -1});
        }

        if (format > 9) {
            skipSoundFiles(r, format);
            int soundTableCount = r.readVarInt();
            for (int i = 0; i < soundTableCount; ++i) {
                r.readString();
                r.readString();
            }
        }

        int extraTextureCount = r.readVarInt();
        for (int i = 0; i < extraTextureCount; ++i) {
            String name = r.readString();
            byte[] texData = r.readByteArray();
            int texWidth = r.readVarInt();
            int texHeight = r.readVarInt();
            model.textures.put(name, texData);
            model.textureInfo.put(name, new int[]{texWidth, texHeight, -1});
        }

        int modelTableSize = r.readVarInt();
        for (int i = 0; i < modelTableSize; ++i) {
            r.readVarInt();
            r.readString();
        }

        int animationTableSize = r.readVarInt();
        for (int i = 0; i < animationTableSize; ++i) {
            r.readVarInt();
            r.readString();
        }

        int textureTableSize = r.readVarInt();
        for (int i = 0; i < textureTableSize; ++i) {
            r.readString();
            r.readString();
            int subTextureSize = r.readVarInt();
            for (int j = 0; j < subTextureSize; ++j) {
                r.readVarInt();
                r.readString();
            }
        }

        readProperties(r, model, format);
        copyRuntimeAnimations(model);
        return model;
    }

    private static BinaryModel readModern(Reader r, int format) {
        BinaryModel model = new BinaryModel();

        skipSoundFiles(r, format);
        skipFunctionFiles(r);
        skipLanguageFiles(r);

        if (format < 26) {
            int subEntityTotalCount = r.readVarInt();
            for (int i = 0; i < subEntityTotalCount; ++i) {
                skipSubEntity(r, format);
            }
            r.readVarInt();
        } else {
            int vehiclesTotalCount = r.readVarInt();
            for (int i = 0; i < vehiclesTotalCount; ++i) {
                skipSubEntity(r, format);
            }
            int projectilesTotalCount = r.readVarInt();
            for (int i = 0; i < projectilesTotalCount; ++i) {
                skipSubEntity(r, format);
            }
        }

        int unknownEntityFlag = r.readVarInt();
        if (unknownEntityFlag != 1) {
            throw new IllegalStateException("Expected 1 after SubEntities");
        }

        int animationCount = r.readVarInt();
        for (int i = 0; i < animationCount; ++i) {
            r.readVarInt();
            r.readString();
            readAnimations(r, format, model.allAnimations);
        }

        skipAnimationControllers(r, format, true);

        int textureCount = r.readVarInt();
        for (int i = 0; i < textureCount; i++) {
            String name = r.readString();
            r.readString();
            byte[] texData = r.readByteArray();
            int texWidth = r.readVarInt();
            int texHeight = r.readVarInt();
            int imageFormat = r.readVarInt();
            r.readVarInt();
            int subTextureSize = r.readVarInt();
            for (int j = 0; j < subTextureSize; j++) {
                r.readVarInt();
                r.readString();
                r.readByteArray();
                r.readVarInt();
                r.readVarInt();
                r.readVarInt();
                r.readVarInt();
            }
            model.textures.put(name, texData);
            model.textureInfo.put(name, new int[]{texWidth, texHeight, imageFormat});
        }

        int modelTotalCount = r.readVarInt();
        for (int i = 0; i < modelTotalCount; ++i) {
            int modelType = r.readVarInt();
            r.readString();
            List<BinaryBone> bones = readGeometry(r);
            if (modelType == 1) {
                model.mainBones.addAll(bones);
            }
        }

        readProperties(r, model, format);
        copyRuntimeAnimations(model);
        return model;
    }

    private static List<BinaryBone> readGeometry(Reader r) {
        int boneCount = r.readVarInt();
        List<BinaryBone> bones = new ArrayList<>(boneCount);
        for (int i = 0; i < boneCount; i++) {
            BinaryBone bone = new BinaryBone();
            bone.parentName = r.readString();
            int cubeCount = r.readVarInt();
            for (int j = 0; j < cubeCount; j++) {
                int faceCount = r.readVarInt();
                for (int k = 0; k < faceCount; k++) {
                    BinaryFace face = new BinaryFace();
                    face.nx = r.readFloat();
                    face.ny = r.readFloat();
                    face.nz = r.readFloat();
                    for (int v = 0; v < 4; v++) {
                        face.px[v] = r.readFloat();
                        face.py[v] = r.readFloat();
                        face.pz[v] = r.readFloat();
                        face.u[v] = r.readFloat();
                        face.v[v] = r.readFloat();
                    }
                    bone.faces.add(face);
                }
                r.readVarInt();
                r.readVarInt();
                r.readVarInt();
            }
            bone.name = r.readString();
            r.readVarInt();
            r.readVarInt();
            r.readVarInt();
            r.readVarInt();
            r.readVarInt();
            bone.pivotX = r.readFloat();
            bone.pivotY = r.readFloat();
            bone.pivotZ = r.readFloat();
            bone.rotX = r.readFloat();
            bone.rotY = r.readFloat();
            bone.rotZ = r.readFloat();
            bones.add(bone);
        }

        r.readString();
        r.readFloat();
        r.readFloat();
        r.readFloat();
        r.readFloat();
        int visibleBoundsOffsetSize = r.readVarInt();
        for (int i = 0; i < visibleBoundsOffsetSize; i++) {
            r.readFloat();
        }
        r.readFloat();
        r.readFloat();
        int hasInfoJsonFlag = r.readVarInt();
        if (hasInfoJsonFlag > 0) {
            skipLegacyYsmInfo(r);
        }
        r.readVarInt();
        r.readVarInt();
        r.readVarInt();
        return bones;
    }

    private static void readProperties(Reader r, BinaryModel model, int format) {
        r.readString();
        int isNewVersionYsm = r.readVarInt();

        if (isNewVersionYsm != 0) {
            if (format <= 15) {
                r.readVarInt();
            }
            r.readString();
            r.readString();
            r.readString();
            r.readString();
            int authorsCount = r.readVarInt();
            for (int i = 0; i < authorsCount; i++) {
                r.readString();
                r.readString();
                int contactsCount = r.readVarInt();
                for (int j = 0; j < contactsCount; j++) {
                    r.readString();
                    r.readString();
                }
                r.readString();
            }
            int linksCount = r.readVarInt();
            for (int i = 0; i < linksCount; i++) {
                r.readString();
                r.readString();
            }
        }

        if (isNewVersionYsm == 0 && format <= 15) {
            // Legacy pre-V16 packages without the new ysm.json end the data
            // stream right after the metadata block (see YSMBinaryDeserializer#
            // parseYsmJson). Reading further would overrun the buffer and fail
            // the whole model load.
            return;
        }

        model.widthScale = r.readFloat();
        model.heightScale = r.readFloat();

        int extraAnimationsCount = r.readVarInt();
        for (int i = 0; i < extraAnimationsCount; i++) {
            model.extraAnimations.put(r.readString(), r.readString());
        }

        if (format > 9) {
            int extraAnimationButtonsCount = r.readVarInt();
            for (int i = 0; i < extraAnimationButtonsCount; i++) {
                r.readString();
                r.readString();
                r.readVarInt();
                int configurationFormsCount = r.readVarInt();
                for (int j = 0; j < configurationFormsCount; j++) {
                    r.readString();
                    r.readString();
                    r.readString();
                    r.readString();
                    r.readFloat();
                    r.readFloat();
                    r.readFloat();
                    int labelsSize = r.readVarInt();
                    for (int l = 0; l < labelsSize; l++) {
                        r.readString();
                        r.readString();
                    }
                }
            }
            int extraAnimationClassifyCount = r.readVarInt();
            for (int i = 0; i < extraAnimationClassifyCount; i++) {
                r.readString();
                int classificationExtrasCount = r.readVarInt();
                for (int j = 0; j < classificationExtrasCount; j++) {
                    r.readString();
                    r.readString();
                }
            }
        }

        model.defaultTexture = r.readString();
        r.readString();
        r.readVarInt();

        if (format > 4) {
            r.readVarInt();
        }
        if (format >= 15) {
            r.readVarInt();
            r.readVarInt();
        }
        if (format > 15) {
            r.readVarInt();
            if (format >= 32) {
                r.readVarInt();
            }
            r.readString();
            r.readString();
            int avatarsCount = r.readVarInt();
            for (int i = 0; i < avatarsCount; i++) {
                r.readString();
                r.readByteArray();
                r.readVarInt();
                r.readVarInt();
                r.readVarInt();
                r.readVarInt();
            }
        }

        if (format <= 15) {
            return;
        }

        int backgroundImagesCount = r.readVarInt();
        for (int i = 0; i < backgroundImagesCount; i++) {
            r.readString();
            r.readByteArray();
            r.readVarInt();
            r.readVarInt();
            r.readVarInt();
            r.readVarInt();
        }
    }

    private static void skipLegacyYsmInfo(Reader r) {
        r.readString();
        r.readString();
        int extraAnimationsCount = r.readVarInt();
        for (int i = 0; i < extraAnimationsCount; i++) {
            r.readString();
        }
        int authorsCount = r.readVarInt();
        for (int i = 0; i < authorsCount; i++) {
            r.readString();
        }
        r.readString();
        r.readVarInt();
    }

    private static void skipSubEntity(Reader r, int format) {
        if (format <= 26) {
            r.readString();
        }
        int animationCount = r.readVarInt();
        for (int i = 0; i < animationCount; ++i) {
            r.readString();
            skipAnimations(r, format);
        }
        // Sub-entity animation controllers: the count varint is followed by the
        // controllers themselves (see YSMBinaryDeserializer#parseAnimationControllers
        // with readName=false - for format > 15 only ONE hash string per controller,
        // unlike the main entity's name+hash pair). Treating the count as a
        // separator and requiring it to be 0 made every sub-entity with animation
        // controllers fail to load.
        skipAnimationControllers(r, format, false);
        r.readString();
        r.readByteArray();
        r.readVarInt();
        r.readVarInt();
        r.readVarInt();
        r.readVarInt();
        int subTextureSize = r.readVarInt();
        for (int i = 0; i < subTextureSize; ++i) {
            r.readVarInt();
            r.readString();
            r.readByteArray();
            r.readVarInt();
            r.readVarInt();
            r.readVarInt();
            r.readVarInt();
        }
        r.readString();
        skipGeometry(r);
        if (format > 26) {
            // matchIds: count + N strings (YSMBinaryDeserializer#parseSubEntity);
            // reading only one string misaligned every sub-entity with more than
            // one match id.
            int matchIdSize = r.readVarInt();
            for (int j = 0; j < matchIdSize; j++) {
                r.readString();
            }
        }
    }

    private static void skipGeometry(Reader r) {
        int boneCount = r.readVarInt();
        for (int i = 0; i < boneCount; i++) {
            r.readString();
            int cubeCount = r.readVarInt();
            for (int j = 0; j < cubeCount; j++) {
                int faceCount = r.readVarInt();
                for (int k = 0; k < faceCount; k++) {
                    r.skipBytes(12 + 4 * 20);
                }
                r.readVarInt();
                r.readVarInt();
                r.readVarInt();
            }
            r.readString();
            r.readVarInt();
            r.readVarInt();
            r.readVarInt();
            r.readVarInt();
            r.readVarInt();
            r.skipBytes(24);
        }
        r.readString();
        r.skipBytes(16);
        int visibleBoundsOffsetSize = r.readVarInt();
        r.skipBytes(4 * visibleBoundsOffsetSize);
        r.skipBytes(8);
        int hasInfoJsonFlag = r.readVarInt();
        if (hasInfoJsonFlag > 0) {
            skipLegacyYsmInfo(r);
        }
        r.readVarInt();
        r.readVarInt();
        r.readVarInt();
    }

    private static void skipAnimations(Reader r, int format) {
        int animationCount = r.readVarInt();
        for (int animIndex = 0; animIndex < animationCount; ++animIndex) {
            r.readString();
            r.readFloat();
            r.readVarInt();
            if (format > 9) {
                r.readVarInt();
                r.readVarInt();
                int blendWeightMolangCount = r.readVarInt();
                for (int i = 0; i < blendWeightMolangCount; i++) {
                    byte datatype = r.readByte();
                    if (datatype == 0x01) {
                        r.readFloat();
                    } else if (datatype == 0x02) {
                        r.readString();
                    }
                }
                r.readVarInt();
            }
            int boneCount = r.readVarInt();
            for (int i = 0; i < boneCount; ++i) {
                r.readString();
                skipChannel(r);
                skipChannel(r);
                skipChannel(r);
            }
            int timelineEventGroupsCount = r.readVarInt();
            for (int i = 0; i < timelineEventGroupsCount; ++i) {
                int timelineEventsCount = r.readVarInt();
                for (int j = 0; j < timelineEventsCount; ++j) {
                    r.readString();
                }
                r.readFloat();
            }
            if (format > 9) {
                int soundEffectsCount = r.readVarInt();
                for (int i = 0; i < soundEffectsCount; i++) {
                    r.readString();
                    r.readFloat();
                }
            }
        }
    }

    /**
     * Parses the binary animation section (modern format, see YSMParserV3's
     * ParseAnimations) instead of skipping it, collecting the animations the
     * Epic Fight compat runtime needs (parallel loops, locomotion states,
     * hold/use condition overlays).
     */
    private static void readAnimations(Reader r, int format, Map<String, com.ysmef.compat.ysm.script.ScriptAnim> out) {
        int animationCount = r.readVarInt();
        for (int animIndex = 0; animIndex < animationCount; ++animIndex) {
            com.ysmef.compat.ysm.script.ScriptAnim anim = new com.ysmef.compat.ysm.script.ScriptAnim();
            anim.name = r.readString();
            // The binary format stores the length in ticks (see YSMBinarySerializer's
            // "writeFloat(anim.length * 20f) // 还原ticks"); the runtime evaluator works
            // in seconds (like the keyframe times below), so convert here.
            anim.length = r.readFloat() / 20.0f;
            int loopMode = r.readVarInt();
            anim.loop = switch (loopMode) {
                case 1 -> com.ysmef.compat.ysm.script.ScriptAnim.LOOP_REPEAT;
                case 3 -> com.ysmef.compat.ysm.script.ScriptAnim.LOOP_HOLD;
                default -> com.ysmef.compat.ysm.script.ScriptAnim.LOOP_ONCE;
            };
            if (format > 9) {
                r.readVarInt();
                r.readVarInt();
                int blendWeightMolangCount = r.readVarInt();
                for (int i = 0; i < blendWeightMolangCount; i++) {
                    byte datatype = r.readByte();
                    if (datatype == 0x01) {
                        r.readFloat();
                    } else if (datatype == 0x02) {
                        r.readString();
                    }
                }
                r.readVarInt();
            }
            int boneCount = r.readVarInt();
            for (int i = 0; i < boneCount; ++i) {
                String boneName = r.readString();
                com.ysmef.compat.ysm.script.ScriptAnim.BoneChannels channels =
                        new com.ysmef.compat.ysm.script.ScriptAnim.BoneChannels();
                channels.rotation = readScriptChannel(r);
                channels.position = readScriptChannel(r);
                channels.scale = readScriptChannel(r);
                if (!channels.isEmpty()) {
                    anim.bones.put(boneName, channels);
                }
            }
            int timelineEventGroupsCount = r.readVarInt();
            for (int i = 0; i < timelineEventGroupsCount; ++i) {
                int timelineEventsCount = r.readVarInt();
                String[] code = new String[timelineEventsCount];
                for (int j = 0; j < timelineEventsCount; ++j) {
                    code[j] = r.readString();
                }
                float time = r.readFloat() / 20.0f;
                if (timelineEventsCount > 0) {
                    anim.timelines.add(new com.ysmef.compat.ysm.script.ScriptAnim.Timeline(time, code));
                }
            }
            if (format > 9) {
                int soundEffectsCount = r.readVarInt();
                for (int i = 0; i < soundEffectsCount; i++) {
                    r.readString();
                    r.readFloat();
                }
            }
            out.put(anim.name, anim);
        }
    }

    /** Copy the runtime-relevant subset of allAnimations into the runtime map. */
    private static void copyRuntimeAnimations(BinaryModel model) {
        model.animations.clear();
        for (Map.Entry<String, com.ysmef.compat.ysm.script.ScriptAnim> entry : model.allAnimations.entrySet()) {
            if (com.ysmef.compat.ysm.script.ScriptJson.isRuntimeRelevant(entry.getKey())) {
                model.animations.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static com.ysmef.compat.ysm.script.ScriptAnim.Channel readScriptChannel(Reader r) {
        int keyframeCount = r.readVarInt();
        if (keyframeCount == 0) {
            return null;
        }
        com.ysmef.compat.ysm.script.ScriptAnim.Channel channel = new com.ysmef.compat.ysm.script.ScriptAnim.Channel();
        for (int i = 0; i < keyframeCount; i++) {
            com.ysmef.compat.ysm.script.ScriptAnim.Key key = new com.ysmef.compat.ysm.script.ScriptAnim.Key();
            key.time = r.readFloat() / 20.0f;
            key.lerp = r.readVarInt();
            key.post = readScriptValue(r);
            boolean hasPreData = r.readVarInt() > 0;
            if (hasPreData) {
                key.pre = readScriptValue(r);
            }
            if (key.post != null) {
                channel.keys.add(key);
            }
        }
        return channel.keys.isEmpty() ? null : channel;
    }

    private static com.ysmef.compat.ysm.script.ScriptAnim.Value readScriptValue(Reader r) {
        com.ysmef.compat.ysm.script.ScriptAnim.Value value = new com.ysmef.compat.ysm.script.ScriptAnim.Value();
        for (int j = 0; j < 3; j++) {
            byte datatype = r.readByte();
            if (datatype == 0x01) {
                value.num[j] = r.readFloat();
            } else if (datatype == 0x02) {
                value.expr[j] = r.readString();
            }
        }
        return value;
    }

    private static void skipChannel(Reader r) {
        int keyframeCount = r.readVarInt();
        for (int i = 0; i < keyframeCount; i++) {
            r.readFloat();
            r.readVarInt();
            for (int j = 0; j < 3; j++) {
                byte datatype = r.readByte();
                if (datatype == 0x01) {
                    r.readFloat();
                } else if (datatype == 0x02) {
                    r.readString();
                }
            }
            boolean hasPreData = r.readVarInt() > 0;
            if (hasPreData) {
                for (int j = 0; j < 3; j++) {
                    byte datatype = r.readByte();
                    if (datatype == 0x01) {
                        r.readFloat();
                    } else if (datatype == 0x02) {
                        r.readString();
                    }
                }
            }
        }
    }

    private static void skipAnimationControllers(Reader r, int format, boolean readName) {
        int controllerCount = r.readVarInt();
        for (int i = 0; i < controllerCount; i++) {
            if (format <= 15) {
                r.readVarInt();
            } else {
                // The main entity's controllers carry a name + hash pair, the
                // sub-entity ones only the hash (see the serializer's
                // writeAnimationControllers with writeName=false).
                if (readName) {
                    r.readString();
                }
                r.readString();
            }
            int animationCount = r.readVarInt();
            for (int animIndex = 0; animIndex < animationCount; ++animIndex) {
                r.readString();
                r.readString();
                int statesCount = r.readVarInt();
                for (int s = 0; s < statesCount; s++) {
                    r.readString();
                    int animationsSize = r.readVarInt();
                    for (int j = 0; j < animationsSize; j++) {
                        r.readString();
                        r.readString();
                    }
                    int transitionsSize = r.readVarInt();
                    for (int j = 0; j < transitionsSize; j++) {
                        r.readString();
                        r.readString();
                    }
                    int onEntryCount = r.readVarInt();
                    for (int j = 0; j < onEntryCount; j++) {
                        r.readString();
                    }
                    int onExitCount = r.readVarInt();
                    for (int j = 0; j < onExitCount; j++) {
                        r.readString();
                    }
                    if (r.readVarInt() != 0) {
                        r.readFloat();
                    } else {
                        int blendTransitionsCount = r.readVarInt();
                        for (int j = 0; j < blendTransitionsCount; j++) {
                            r.readFloat();
                            r.readFloat();
                        }
                    }
                    r.readVarInt();
                    if (format > 26) {
                        int soundEffectsCount = r.readVarInt();
                        for (int j = 0; j < soundEffectsCount; j++) {
                            r.readString();
                        }
                    }
                }
            }
        }
    }

    private static void skipSoundFiles(Reader r, int format) {
        int soundCount = r.readVarInt();
        for (int i = 0; i < soundCount; i++) {
            r.readString();
            if (format > 15) {
                r.readString();
            }
            r.readByteArray();
        }
    }

    private static void skipFunctionFiles(Reader r) {
        int functionCount = r.readVarInt();
        for (int i = 0; i < functionCount; i++) {
            r.readString();
            r.readString();
            r.readByteArray();
        }
    }

    private static void skipLanguageFiles(Reader r) {
        int languageCount = r.readVarInt();
        for (int i = 0; i < languageCount; i++) {
            r.readString();
            r.readString();
            int nodesCount = r.readVarInt();
            for (int j = 0; j < nodesCount; j++) {
                r.readString();
                r.readString();
            }
        }
    }

    private static class Reader {
        private final ByteBuffer buf;

        Reader(byte[] data) {
            this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        }

        long readDword() {
            return buf.getInt() & 0xFFFFFFFFL;
        }

        float readFloat() {
            return buf.getFloat();
        }

        byte readByte() {
            return buf.get();
        }

        void skipBytes(int n) {
            buf.position(buf.position() + n);
        }

        int readVarInt() {
            int value = 0;
            int position = 0;
            while (true) {
                byte currentByte = buf.get();
                value |= (currentByte & 0x7F) << position;
                if ((currentByte & 0x80) == 0) break;
                position += 7;
                if (position >= 64) throw new IllegalStateException("VarInt too big");
            }
            return value;
        }

        String readString() {
            int len = readVarInt();
            if (len == 0) return "";
            byte[] bytes = new byte[len];
            buf.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        byte[] readByteArray() {
            int len = readVarInt();
            if (len == 0) return new byte[0];
            byte[] bytes = new byte[len];
            buf.get(bytes);
            return bytes;
        }
    }
}
