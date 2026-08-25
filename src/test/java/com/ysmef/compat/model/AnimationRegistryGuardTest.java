package com.ysmef.compat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the registry-name filter used to exempt this mod's runtime-generated
 * wheel templates from Epic Fight's client-vs-server animation registry
 * consistency check (which disconnects players on any mismatch).
 */
class AnimationRegistryGuardTest {

    @Test
    void canonicalTemplateNamesAreIgnored() {
        assertTrue(AnimationRegistryGuard.shouldIgnore("ysm_epicfight_compat:public/pub_12af9154f2a5"));
        assertTrue(AnimationRegistryGuard.shouldIgnore("ysm_epicfight_compat:public/pub_00751c8bff80"));
    }

    @Test
    void mangledLegacyVariantsAreIgnored() {
        // Missing ':' and '_' in the namespace.
        assertTrue(AnimationRegistryGuard.shouldIgnore("ysmepicfightcompatpublic/pub_12af9154f2a5"));
        // '_' replaced by a space in the namespace, missing '/' in the path.
        assertTrue(AnimationRegistryGuard.shouldIgnore("ysm_epicfight compat:publicpub_771a162fe082"));
        // Missing '_' in both namespace and path.
        assertTrue(AnimationRegistryGuard.shouldIgnore("ysmepicfightcompat:public/pubfc4138fd407c"));
        // Fully collapsed.
        assertTrue(AnimationRegistryGuard.shouldIgnore("ysmepicfightcompatpublicpub_771a162fe082"));
    }

    @Test
    void unrelatedAnimationNamesAreNotIgnored() {
        assertFalse(AnimationRegistryGuard.shouldIgnore("epicfight:basic_attack"));
        assertFalse(AnimationRegistryGuard.shouldIgnore("epicfight:entity/biped"));
        assertFalse(AnimationRegistryGuard.shouldIgnore("minecraft:animation"));
        assertFalse(AnimationRegistryGuard.shouldIgnore("empty"));
        assertFalse(AnimationRegistryGuard.shouldIgnore("ysm:builtin/player"));
        assertFalse(AnimationRegistryGuard.shouldIgnore("zylob:public/pub_1"));
        assertFalse(AnimationRegistryGuard.shouldIgnore("my_epicfight_compat_mod:public/pub_1"));
    }

    @Test
    void nullAndEmptyAreNotIgnored() {
        assertFalse(AnimationRegistryGuard.shouldIgnore(null));
        assertFalse(AnimationRegistryGuard.shouldIgnore(""));
    }

    @Test
    void caseVariantsAreIgnored() {
        assertTrue(AnimationRegistryGuard.shouldIgnore("YSM_EPICFIGHT_COMPAT:public/pub_1"));
        assertTrue(AnimationRegistryGuard.shouldIgnore("YsmEpicfightCompatPublicPub_1"));
    }
}
