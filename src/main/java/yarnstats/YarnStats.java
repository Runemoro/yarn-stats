package yarnstats;

import cuchaz.enigma.analysis.ParsedJar;
import cuchaz.enigma.analysis.index.BridgeMethodIndex;
import cuchaz.enigma.analysis.index.JarIndex;
import net.fabricmc.mappings.*;
import org.objectweb.asm.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class YarnStats {
    private static final String ROOT_PACKAGE = "/net/minecraft/";

    public static void main(String[] args) throws Throwable {
        String minecraftJar = args[0];
        String mappingsTiny = args[1];

        Set<String> syntheticMethods = new HashSet<>();
        Set<String> syntheticFields = new HashSet<>();
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

                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
                            syntheticFields.add(className + ":" + name + descriptor);
                        }

                        return super.visitField(access, name, descriptor, signature, value);
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

        int totalMethods = 0;
        int mappedMethods = 0;
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

            totalMethods++;
            if (mapped) {
                mappedMethods++;
            }
        }

        int totalFields = 0;
        int mappedFields = 0;
        for (FieldEntry fieldEntry : mappings.getFieldEntries()) {
            EntryTriple intermediary = fieldEntry.get("intermediary");
            if (!seenNames.add(intermediary.getName())) {
                continue;
            }

            if (!intermediary.getName().startsWith("field_")) {
                continue; // unobfuscated name
            }

            EntryTriple original = fieldEntry.get("official");
            String key = original.getOwner() + ":" + original.getName();
            if (syntheticFields.contains(key)) {
                continue;
            }

            EntryTriple named = fieldEntry.get("named");

            if (!("/" + named.getOwner()).startsWith(ROOT_PACKAGE)) {
                continue;
            }

            boolean mapped = !named.getName().equals(intermediary.getName());

            if (original.getOwner().startsWith("net/minecraft/realms")) {
                // https://github.com/FabricMC/Enigma/issues/121
                mapped = true;
            }

            totalFields++;
            if (mapped) {
                mappedFields++;
            }
        }

        int totalClasses = 0;
        int mappedClasses = 0;
        int totalTopLevelClasses = 0;
        int mappedTopLevelClasses = 0;
        for (ClassEntry classEntry : mappings.getClassEntries()) {
            String named = classEntry.get("named");
            int lastDollar = named.lastIndexOf('$');
            if (lastDollar != -1) {
                named = named.substring(lastDollar + 1);
            }

            if (Character.isDigit(named.charAt(0))) {
                continue;
            }

            boolean mapped = !named.contains("class_");

            totalClasses++;
            if (mapped) {
                mappedClasses++;
            }

            if (lastDollar == -1) {
                totalTopLevelClasses++;
                if (mapped) {
                    mappedTopLevelClasses++;
                }
            }
        }

        System.out.println(mappedTopLevelClasses + " / " + totalTopLevelClasses + String.format(" (%.2f%%)", (double) mappedTopLevelClasses / totalTopLevelClasses * 100) + " top-level classes are mapped");
        System.out.println(mappedClasses + " / " + totalClasses + String.format(" (%.2f%%)", (double) mappedClasses / totalClasses * 100) + " classes are mapped");
        System.out.println(mappedMethods + " / " + totalMethods + String.format(" (%.2f%%)", (double) mappedMethods / totalMethods * 100) + " methods are mapped");
        System.out.println(mappedFields + " / " + totalFields + String.format(" (%.2f%%)", (double) mappedFields / totalFields * 100) + " fields are mapped");
    }
}
