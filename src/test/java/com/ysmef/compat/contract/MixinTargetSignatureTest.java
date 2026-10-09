package com.ysmef.compat.contract;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the actual class/method descriptors behind optional mixin injections.
 * A require=0 injection with a stale descriptor otherwise compiles and fails
 * silently at runtime. Alternate Open/Modern entry points are checked against
 * their matching jar when one is supplied to the test run.
 */
class MixinTargetSignatureTest {
    private static final Path MIXINS = Path.of("src/main/java/com/ysmef/compat/mixin");
    private static final Pattern CLASS_TARGET = Pattern.compile(
            "@Mixin\\(value\\s*=\\s*([\\w.]+)\\.class|@Mixin\\(targets\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern METHOD_TARGET = Pattern.compile("method\\s*=\\s*\"([^\"]+)\"");

    @Test
    void officialYsmTargetsMatchThePackagedJar() throws IOException {
        Path jar = Path.of("libs/ysm-2.6.5.jar");
        assertTrue(Files.isRegularFile(jar), "official YSM jar missing: " + jar);
        List<String> problems = new ArrayList<>();
        int checked = checkJar(jar, source -> source.getFileName().toString().startsWith("Ysm")
                && !source.getFileName().toString().startsWith("YsmUnobf")
                && !source.getFileName().toString().equals("YsmExtraPlayerOverlayMixin.java")
                && !source.getFileName().toString().equals("YsmAnimationTransitionGuardMixin.java")
                && !source.getFileName().toString().equals("YsmLivingMovementPredicateMixin.java")
                && !source.getFileName().toString().equals("YsmRouletteConfigExpressionMixin.java"), true, problems);
        assertTrue(checked >= 8, "the contract did not inspect the obfuscated YSM mixins");
        assertTrue(problems.isEmpty(), "official YSM mixin targets drifted: " + problems);
    }

    @Test
    void suppliedForkTargetsMatchTheirJar() throws IOException {
        String fork = System.getProperty("ysmef.fork", "").toLowerCase(java.util.Locale.ROOT);
        String jarPath = System.getProperty("ysmef.fork.jar", "");
        Assumptions.assumeTrue(!jarPath.isBlank(), "supply -Dysmef.fork.jar and -Dysmef.fork to check a fork");
        assertTrue(fork.equals("open") || fork.equals("modern"), "ysmef.fork must be open or modern");
        Path jar = Path.of(jarPath);
        assertTrue(Files.isRegularFile(jar), "fork jar missing: " + jar);
        List<String> problems = new ArrayList<>();
        int checked = checkJar(jar, path -> isSourceContractMixin(path, fork), false, problems);
        assertTrue(checked >= 12, "the contract did not inspect the " + fork + " mixins");
        assertTrue(problems.isEmpty(), fork + " YSM mixin targets drifted: " + problems);
    }

    @Test
    void suppliedForkTargetsMatchTheirSource() throws IOException {
        String fork = System.getProperty("ysmef.fork", "").toLowerCase(java.util.Locale.ROOT);
        String sourcePath = System.getProperty("ysmef.fork.source", "");
        Assumptions.assumeTrue(!sourcePath.isBlank(), "supply -Dysmef.fork.source and -Dysmef.fork");
        assertTrue(fork.equals("open") || fork.equals("modern"), "ysmef.fork must be open or modern");
        Path project = Path.of(sourcePath);
        Path javaRoot = fork.equals("open") ? project.resolve("src/main/java")
                : project.resolve("common/src/main/java");
        assertTrue(Files.isDirectory(javaRoot), "fork Java source missing: " + javaRoot);
        List<String> problems = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> sources = Files.list(MIXINS)) {
            for (Path mixin : (Iterable<Path>) sources.filter(path -> isSourceContractMixin(path, fork))::iterator) {
                String name = mixin.getFileName().toString();
                String text = Files.readString(mixin, StandardCharsets.UTF_8);
                Matcher target = CLASS_TARGET.matcher(text);
                if (!target.find()) {
                    problems.add(name + ": no mixin class target found");
                    continue;
                }
                String className = target.group(1) != null ? target.group(1) : target.group(2);
                Path javaFile = javaRoot.resolve(className.replace('.', '/') + ".java");
                if (!Files.isRegularFile(javaFile) && fork.equals("modern")) {
                    javaFile = project.resolve("forge/src/main/java")
                            .resolve(className.replace('.', '/') + ".java");
                }
                if (!Files.isRegularFile(javaFile)) {
                    problems.add(name + ": source class " + className + " is absent");
                    continue;
                }
                List<SourceMethod> methods = sourceMethods(javaFile);
                Matcher requested = METHOD_TARGET.matcher(text);
                boolean foundAny = false;
                while (requested.find()) {
                    String signature = requested.group(1);
                    foundAny |= methods.stream().anyMatch(method -> method.matches(signature));
                }
                if (!foundAny) {
                    problems.add(name + ": none of its method targets exists in " + className);
                }
                checked++;
            }
        }
        assertTrue(checked >= 12, "too few fork mixins were checked: " + checked);
        assertTrue(problems.isEmpty(), fork + " YSM source targets drifted: " + problems);
    }

    private static boolean isSourceContractMixin(Path path, String fork) {
        String name = path.getFileName().toString();
        if (name.equals("ModernYsmCompatibilityWarningMixin.java")) {
            return fork.equals("modern");
        }
        if (name.equals("OpenYsmCompatibilityWarningMixin.java")) {
            return fork.equals("open");
        }
        return name.startsWith("OpenYsm") || name.startsWith("YsmUnobf")
                || name.equals("YsmExtraPlayerOverlayMixin.java")
                || name.equals("YsmAnimationTransitionGuardMixin.java")
                || name.equals("YsmLivingMovementPredicateMixin.java")
                || name.equals("YsmRouletteConfigExpressionMixin.java");
    }

    private record SourceMethod(String name, List<String> parameters, String returnType) {
        boolean matches(String signature) {
            int open = signature.indexOf('(');
            String expectedName = open < 0 ? signature : signature.substring(0, open);
            // Forge's production name for Screen#init; the supplied source uses Mojang mappings.
            if (expectedName.equals("m_7856_")) {
                expectedName = "init";
            }
            if (!name.equals(expectedName)) {
                return false;
            }
            if (open < 0) {
                return true;
            }
            int close = signature.indexOf(')', open);
            List<String> expected = descriptorTypes(signature.substring(open + 1, close));
            return parameters.equals(expected)
                    && returnType.equals(descriptorTypes(signature.substring(close + 1)).get(0));
        }
    }

    private static List<SourceMethod> sourceMethods(Path file) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "source contract requires a JDK");
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var units = files.getJavaFileObjects(file.toFile());
            JavacTask task = (JavacTask) compiler.getTask(new StringWriter(), files, null,
                    List.of("-proc:none"), null, units);
            List<SourceMethod> methods = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                for (Tree declaration : unit.getTypeDecls()) {
                    if (declaration instanceof ClassTree type) {
                        for (Tree member : type.getMembers()) {
                            if (member instanceof MethodTree method && method.getReturnType() != null) {
                                methods.add(new SourceMethod(method.getName().toString(),
                                        method.getParameters().stream()
                                                .map(parameter -> simpleType(parameter.getType().toString())).toList(),
                                        simpleType(method.getReturnType().toString())));
                            }
                        }
                    }
                }
            }
            return methods;
        }
    }

    private static String simpleType(String type) {
        String raw = type.split("<", 2)[0];
        return raw.substring(raw.lastIndexOf('.') + 1);
    }

    private static List<String> descriptorTypes(String descriptor) {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < descriptor.length(); ) {
            char code = descriptor.charAt(i++);
            if (code == 'L') {
                int end = descriptor.indexOf(';', i);
                assertTrue(end >= 0, "invalid descriptor: " + descriptor);
                String type = descriptor.substring(i, end);
                types.add(type.substring(Math.max(type.lastIndexOf('/'), type.lastIndexOf('$')) + 1));
                i = end + 1;
            } else {
                String type = switch (code) {
                    case 'V' -> "void";
                    case 'Z' -> "boolean";
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'S' -> "short";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'F' -> "float";
                    case 'D' -> "double";
                    default -> throw new IllegalArgumentException("unsupported descriptor: " + descriptor);
                };
                types.add(type);
            }
        }
        return types;
    }

    private static int checkJar(Path jarPath, java.util.function.Predicate<Path> include,
                                boolean requireEveryMethod, List<String> problems) throws IOException {
        int checked = 0;
        try (ZipFile jar = new ZipFile(jarPath.toFile()); Stream<Path> sources = Files.list(MIXINS)) {
            for (Path source : (Iterable<Path>) sources.filter(path -> path.toString().endsWith("Mixin.java"))
                    .filter(include)::iterator) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                Matcher target = CLASS_TARGET.matcher(text);
                if (!target.find()) {
                    problems.add(source.getFileName() + ": no mixin class target found");
                    continue;
                }
                String className = target.group(1) != null ? target.group(1) : target.group(2);
                if (!className.startsWith("com.elfmcys.yesstevemodel.")) {
                    continue;
                }
                ZipEntry entry = jar.getEntry(className.replace('.', '/') + ".class");
                if (entry == null) {
                    problems.add(source.getFileName() + ": class " + className + " is absent");
                    continue;
                }
                Set<String> methods = new HashSet<>();
                new ClassReader(jar.getInputStream(entry)).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        methods.add(name + descriptor);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                Matcher requested = METHOD_TARGET.matcher(text);
                boolean matchedAny = false;
                boolean sawMethod = false;
                while (requested.find()) {
                    sawMethod = true;
                    String signature = requested.group(1);
                    boolean found = signature.indexOf('(') >= 0
                            ? methods.contains(signature)
                            : methods.stream().anyMatch(method -> method.startsWith(signature + "("));
                    matchedAny |= found;
                    if (requireEveryMethod && !found) {
                        problems.add(source.getFileName() + ": method " + signature + " is absent from " + className);
                    }
                }
                if (!sawMethod || (!requireEveryMethod && !matchedAny)) {
                    problems.add(source.getFileName() + ": none of its method targets exists in " + className);
                }
                checked++;
            }
        }
        assertFalse(checked == 0 && problems.isEmpty(), "no mixin sources were examined");
        return checked;
    }
}
