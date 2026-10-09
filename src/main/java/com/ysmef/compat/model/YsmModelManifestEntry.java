package com.ysmef.compat.model;

import com.google.gson.JsonObject;
import com.ysmef.compat.model.YsmModelConverter.ModelResult;
import com.ysmef.compat.model.YsmModelConverter.TextureEntry;

/** Encodes one converted model's output hashes and texture metadata for the manifest. */
final class YsmModelManifestEntry {
    private YsmModelManifestEntry() {}

    static JsonObject from(ModelResult result) {
        JsonObject entry = new JsonObject();
        entry.addProperty("sig", result.fingerprint());
        entry.addProperty("csig", result.contentFingerprint());
        entry.addProperty("mesh", result.meshId());
        entry.addProperty("mhash", result.meshHash());
        entry.addProperty("msize", result.meshSize());
        entry.addProperty("rhash", result.runtimeHash());
        entry.addProperty("rsize", result.runtimeSize());

        JsonObject textures = new JsonObject();
        for (TextureEntry texture : result.textures()) {
            JsonObject value = new JsonObject();
            value.addProperty("rl", texture.location().toString());
            if (texture.info() != null) {
                value.addProperty("w", texture.info()[0]);
                value.addProperty("h", texture.info()[1]);
                value.addProperty("fmt", texture.info()[2]);
            }
            value.addProperty("hash", texture.hash());
            value.addProperty("size", texture.size());
            textures.add(texture.textureName(), value);
        }
        entry.add("textures", textures);
        return entry;
    }
}
