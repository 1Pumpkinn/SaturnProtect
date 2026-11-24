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

    public static boolean isAir(String name) {
        return name.equals("air") || name.equals("cave_air") || name.equals("void_air");
    }
}
