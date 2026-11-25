package net.rose.saturnProtect.util;

public final class BlockText {

    private BlockText() {
    }

    /** {@code minecraft:oak_stairs[facing=north]} -> {@code oak_stairs} */
    public static String name(String blockData) {
        int bracket = blockData.indexOf('[');
        String id = bracket >= 0 ? blockData.substring(0, bracket) : blockData;
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** {@code oak_stairs} -> {@code Oak Stairs}; also accepts a full block data string. */
    public static String pretty(String blockData) {
        String[] words = name(blockData).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /**
     * A container key as a phrase: {@code double_chest} -> {@code a double chest}, {@code ender_chest} ->
     * {@code an ender chest}. Null (rows logged before containers were named) gives {@code a container}.
     */
    public static String containerPhrase(String key) {
        String name = key == null || key.isBlank() ? "container" : name(key).replace('_', ' ');
        return ("aeiou".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
    }

    public static boolean isAir(String name) {
        return name.equals("air") || name.equals("cave_air") || name.equals("void_air");
    }
}
