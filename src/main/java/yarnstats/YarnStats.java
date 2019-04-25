package yarnstats;

import cuchaz.enigma.analysis.ParsedJar;
import cuchaz.enigma.analysis.index.BridgeMethodIndex;
import cuchaz.enigma.analysis.index.JarIndex;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.mappings.MethodEntry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class YarnStats {
    private static final String ROOT_PACKAGE = "/net/minecraft/";

    public static void main(String[] args) throws Throwable {
        String minecraftJar = args[0];
        String mappingsTiny = args[1];

        Set<String> syntheticMethods = new HashSet<>();
        try (JarFile jarFile = new JarFile(minecraftJar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }

                String className = entry.getName().substring(0, entry.getName().length() - 6);
                new ClassReader(jarFile.getInputStream(entry)).accept(new ClassVisitor(Opcodes.ASM7) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
                            syntheticMethods.add(className + ":" + name + descriptor);
                        }

                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }
                }, 0);
            }
        }

        Map<String, String> accessedToBridge = new HashMap<>();
        try (JarFile jarFile = new JarFile(minecraftJar)) {
            JarIndex jarIndex = JarIndex.empty();
            jarIndex.indexJar(new ParsedJar(jarFile), s -> {});
            Field accessedToBridgeField = BridgeMethodIndex.class.getDeclaredField("accessedToBridge");
            accessedToBridgeField.setAccessible(true);
            Map<cuchaz.enigma.translation.representation.entry.MethodEntry, cuchaz.enigma.translation.representation.entry.MethodEntry> accessedToBridge2 = (Map<cuchaz.enigma.translation.representation.entry.MethodEntry, cuchaz.enigma.translation.representation.entry.MethodEntry>) accessedToBridgeField.get(jarIndex.getBridgeMethodIndex());
            accessedToBridge2.forEach((accessed, bridge) -> accessedToBridge.put(
                    accessed.getContainingClass().getFullName() + ":" + accessed.getName() + accessed.getDesc(),
                    bridge.getContainingClass().getFullName() + ":" + bridge.getName() + bridge.getDesc()
            ));
        }
        Set<String> bridges = new HashSet<>(accessedToBridge.values());

        Mappings mappings;
        try (InputStream stream = new FileInputStream(mappingsTiny)) {
            mappings = MappingsProvider.readTinyMappings(stream);
        }

        Map<String, Integer> unmappedCount = new LinkedHashMap<>();
        Map<String, Integer> mappedCount = new LinkedHashMap<>();
        Set<String> seenNames = new HashSet<>();
        for (MethodEntry methodEntry : mappings.getMethodEntries()) {
            EntryTriple intermediary = methodEntry.get("intermediary");
            if (!seenNames.add(intermediary.getName())) {
                continue;
            }

            if (!intermediary.getName().startsWith("method_")) {
                continue; // unobfuscated name
            }

            EntryTriple original = methodEntry.get("official");
            String key = original.getOwner() + ":" + original.getName() + original.getDesc();
            if (syntheticMethods.contains(key) || accessedToBridge.containsKey(key) || bridges.contains(key)) {
                continue;
            }

            EntryTriple named = methodEntry.get("named");

            if (!("/" + named.getOwner()).startsWith(ROOT_PACKAGE)) {
                continue;
            }

            boolean mapped = !named.getName().equals(intermediary.getName());

            if (original.getOwner().startsWith("net/minecraft/realms")) {
                // https://github.com/FabricMC/Enigma/issues/121
                mapped = true;
            }

            StringBuilder partialName = new StringBuilder();
            for (String part : (named.getOwner().split("\\$")[0] + "/ ").split("/")) {
                if (mapped) {
                    mappedCount.put(partialName.toString(), mappedCount.getOrDefault(partialName.toString(), 0) + 1);
                } else {
                    unmappedCount.put(partialName.toString(), unmappedCount.getOrDefault(partialName.toString(), 0) + 1);
                }
                partialName.append("/").append(part);
            }
        }

        StringBuilder partialName = new StringBuilder();
        for (String part : ROOT_PACKAGE.substring(1, ROOT_PACKAGE.length() - 1).split("/")) {
            unmappedCount.remove(partialName.toString());
            partialName.append("/").append(part);
        }

        String[] last = {""};
        int[] lastLevel = {0};
        int[] indentLevel = {0};
        boolean[] first = {true};
        StringBuilder s = new StringBuilder();
        unmappedCount
                .keySet()
                .stream()
                .sorted((a, b) -> {
                    if (a.equals(b)) {
                        return 0;
                    }

                    if (a.length() < ROOT_PACKAGE.length() || b.length() < ROOT_PACKAGE.length()) {
                        return a.compareToIgnoreCase(b);
                    }

                    a = a.substring(ROOT_PACKAGE.length());
                    b = b.substring(ROOT_PACKAGE.length());

                    if (a.startsWith("class_") ^ b.startsWith("class_")) {
                        return a.startsWith("class_") ? 1 : -1;
                    }

                    boolean aUpper = !a.substring(0, 1).toLowerCase().equals(a.substring(0, 1));
                    boolean bUpper = !b.substring(0, 1).toLowerCase().equals(b.substring(0, 1));
                    if (aUpper ^ bUpper) {
                        return aUpper ? 1 : -1;
                    }

                    return a.compareToIgnoreCase(b);
                })
                .forEachOrdered(packageName -> {
                    int prefixSize = findCommonPrefixSize(last[0].split("/"), packageName.split("/"));
                    int level = countCharacters(packageName.substring(0, prefixSize), '/');

                    if (lastLevel[0] < level) {
                        indentLevel[0]++;
                        s.append(", children: [\n");
                    } else if (!first[0]) {
                        while (lastLevel[0] > level) {
                            lastLevel[0]--;
                            s.append("}\n");

                            for (int i = 1; i < indentLevel[0]; i++) {
                                s.append("    ");
                            }

                            s.append("]");
                            indentLevel[0]--;
                        }
                        s.append("},\n");
                    }

                    for (int i = 0; i < indentLevel[0]; i++) {
                        s.append("    ");
                    }

                    int mapped = mappedCount.getOrDefault(packageName, 0);
                    int unmapped = unmappedCount.getOrDefault(packageName, 0);
                    int total = unmapped + mapped;

                    s.append("{name: \"")
                     .append(packageName.substring(prefixSize == 0 ? 0 : prefixSize + 1))
                     .append("\", value: ")
                     .append(unmapped);

                    first[0] = false;
                    lastLevel[0] = level;
                    last[0] = packageName;
                });
        while (indentLevel[0] > 0) {
            indentLevel[0]--;
            s.append("}\n");

            for (int i = 0; i < indentLevel[0]; i++) {
                s.append("    ");
            }

            s.append("]");
        }
        s.append("}\n");

        try (InputStream is = YarnStats.class.getResourceAsStream("/template.html");
             OutputStream os = new FileOutputStream(new File("methods.html"))) {
            String result = toString(is, "UTF-8")
                    .replace("{{type}}", "methods")
                    .replace("{{data}}", s.toString());
            os.write(result.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String toString(InputStream is, String encoding) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length = 0;
        while ((length = is.read(buffer)) != -1) {
            os.write(buffer, 0, length);
        }
        return os.toString(encoding);
    }

    private static int countCharacters(String s, char c) {
        int n = 0;

        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }

        return n;
    }

    private static int findCommonPrefixSize(String[] a, String[] b) {
        int shortestLength = Math.min(a.length, b.length);
        int size = 0;
        for (int i = 0; i < shortestLength; i++) {
            if (!a[i].equals(b[i])) {
                return size - 1;
            }

            size += a[i].length() + 1;
        }

        return size - 1;
    }
}
