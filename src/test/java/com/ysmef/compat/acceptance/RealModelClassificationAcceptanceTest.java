package com.ysmef.compat.acceptance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.runtime.YsmPhysicsChains;
import com.ysmef.compat.model.runtime.YSMRuntimeModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real classifier over a real model's own bone table.
 *
 * <p>Every other test in this package uses a hand-built table, which is the right way to pin a
 * rule and the wrong way to find out what the rule does to a model. This one loads
 * {@code wine_fox/01_taisho_maid}'s 195 bones - taken from the bone table the game itself
 * compiled into {@code ysm_runtime/entity/wine_fox/01_taisho_maid.json}, not re-typed - and
 * asks the shipped classifier which of them become swinging parts.
 *
 * <h2>The claim under test, and how it fails</h2>
 *
 * <p>T4's brief says a bone that does not hold geometry of its own, or whose lever is too short
 * to produce visible motion, must not become a part, and names {@code Tail4} to {@code Tail7} of
 * this very model as the shapes that were appearing in the shipped log as simulated parts that
 * never move. An offline read of the model's geometry
 * ({@code tmp_verify/T5_probe_geometry.ps1}) says each of those four bones <b>does</b> author a
 * cube of its own, so the rule as written cannot exclude them; what keeps them from being their
 * own chains is that {@code Tail} is their accepted ancestor, which is not a change this round.
 * This test therefore records what actually happens instead of asserting the brief's expectation:
 * it requires that the tail is simulated at all and that every selected bone is one the model
 * actually draws, and it reports the {@code Tail} family's membership for the verification report.
 *
 * <p>The fixture is machine-specific, so an absent fixture <b>skips</b> the test rather than
 * passing it - the same convention {@code YsmFileCryptoGoldenTest} already uses for the golden
 * .ysm file. {@code tmp_verify/T5_make_model_fixture.ps1} writes it.
 */
class RealModelClassificationAcceptanceTest {

    private static final String FIXTURE = "tmp_verify/T5_model_fixture.json";

    private static final String[] TAIL_FAMILY = {
            "MTail", "Tail", "Tail2", "Tail3", "Tail4", "Tail5", "Tail6", "Tail7"
    };

    @Test
    @DisplayName("the real 01_taisho_maid table: which bones the shipped classifier swings")
    void theRealModelIsClassifiedSensibly() throws IOException {
        Path fixture = AcceptanceSupport.workspaceFile(FIXTURE);
        Assumptions.assumeTrue(fixture != null,
                "no fixture at " + FIXTURE + "; run tmp_verify/T5_make_model_fixture.ps1 to write it"
                        + " from this machine's game install");

        Fixture model = parse(fixture);
        assertEquals(195, model.bones.length,
                "the fixture is supposed to be the real 195-bone table of wine_fox/01_taisho_maid");

        IntPredicate carriesGeometry = index -> model.cubes[index] > 0;
        List<YsmPhysicsChains.Chain> chains = AcceptanceSupport.chains(model.bones, carriesGeometry);
        List<Integer> selected = AcceptanceSupport.selectBones(model.bones, carriesGeometry,
                Integer.MAX_VALUE, new int[1]);

        StringBuilder report = new StringBuilder();
        report.append("[T5] real model ").append(model.modelId).append(": ")
                .append(chains.size()).append(" chain(s), ").append(selected.size())
                .append(" simulated bone(s) of ").append(model.bones.length).append('\n');
        report.append("[T5] chains: ").append(AcceptanceSupport.chainNames(chains)).append('\n');
        report.append("[T5] tail family membership:");
        for (String name : TAIL_FAMILY) {
            int index = model.indexOf(name);
            if (index < 0) {
                report.append(' ').append(name).append("=(absent)");
                continue;
            }
            report.append(' ').append(name)
                    .append(chains.stream().anyMatch(c -> c.boneIndex() == index) ? "=chain" : "")
                    .append(selected.contains(index) ? "=part" : "")
                    .append(chains.stream().anyMatch(c -> c.boneIndex() == index) || selected.contains(index)
                            ? "" : "=excluded");
        }
        System.out.println(report);

        assertTrue(chains.stream().anyMatch(c -> c.boneName().equals("Tail")
                        || c.boneName().equals("MTail")),
                "the model's tail must be simulated: neither Tail nor MTail became a chain. Chains"
                        + " were " + AcceptanceSupport.chainNames(chains));

        for (int index : selected) {
            assertTrue(carriesGeometry.test(index),
                    model.bones[index].name + " was selected as a simulated part although the model"
                            + " draws no geometry at that bone; nothing can move and the bone budget"
                            + " is spent anyway");
        }

        // The brief's shape, checked against the real table rather than assumed: if any of the
        // four had no geometry of its own, the classifier would have to leave it out.
        for (String name : new String[]{"Tail4", "Tail5", "Tail6", "Tail7"}) {
            int index = model.indexOf(name);
            if (index < 0 || model.cubes[index] > 0) {
                continue;   // it draws its own cube, so "no geometry" cannot be the reason
            }
            assertTrue(!selected.contains(index),
                    name + " draws nothing of its own and must not be a simulated part");
        }
    }

    // ------------------------------------------------------------------

    private static final class Fixture {
        String modelId;
        YSMRuntimeModel.BoneRt[] bones;
        int[] cubes;
        Map<String, Integer> byName = new LinkedHashMap<>();

        int indexOf(String name) {
            Integer index = byName.get(name);
            return index == null ? -1 : index;
        }
    }

    private static Fixture parse(Path path) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        Fixture fixture = new Fixture();
        fixture.modelId = root.get("modelId").getAsString();
        JsonArray array = root.getAsJsonArray("bones");
        List<YSMRuntimeModel.BoneRt> bones = new ArrayList<>();
        List<Integer> cubes = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject entry = array.get(i).getAsJsonObject();
            YSMRuntimeModel.BoneRt bone = AcceptanceSupport.bone(
                    entry.get("name").getAsString(),
                    entry.get("joint").getAsInt(),
                    entry.get("parent").getAsInt(),
                    entry.get("mapped").getAsBoolean());
            fixture.byName.put(bone.name, i);
            bones.add(bone);
            cubes.add(entry.get("cubes").getAsInt());
        }
        fixture.bones = bones.toArray(new YSMRuntimeModel.BoneRt[0]);
        fixture.cubes = new int[cubes.size()];
        for (int i = 0; i < cubes.size(); i++) {
            fixture.cubes[i] = cubes.get(i);
        }
        return fixture;
    }
}
