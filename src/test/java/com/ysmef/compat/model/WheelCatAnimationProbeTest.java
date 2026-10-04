package com.ysmef.compat.model;

import com.ysmef.compat.ysm.YsmModelPackage;
import com.ysmef.compat.ysm.script.ScriptAnim;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional regression smoke test against the reported user model. */
class WheelCatAnimationProbeTest {
    @Test
    void catShakeProducesBodyAndHeadFrames() {
        Assumptions.assumeTrue(System.getenv(YsmModelPackage.CONFIG_ROOT_ENV) != null);
        YsmModelPackage pkg = YsmModelPackage.load("DS鲸鱼娘.ysm");
        Assumptions.assumeTrue(pkg != null);
        assertEquals("猫猫摇", pkg.extraAnimations.get("extra5"));
        ScriptAnim source = pkg.wheelAnim("extra5");
        assertNotNull(source);
        assertNotNull(source.bones.get("AllBody").position);
        assertNotNull(source.bones.get("MAllBody").position);

        YsmExtraFrameWriter.Clip clip = YsmExtraFrameWriter.convert(pkg, "extra5");
        assertNotNull(clip);
        assertTrue(clip.localFrames.containsKey(JointTable.CHEST));
        assertTrue(clip.localFrames.containsKey(JointTable.HEAD));
        assertEquals(58, clip.frameCount);
    }
}
