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

    public static boolean isAir(String name) {
        return name.equals("air") || name.equals("cave_air") || name.equals("void_air");
    }
}
