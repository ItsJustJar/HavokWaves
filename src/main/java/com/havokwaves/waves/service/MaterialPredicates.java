package com.havokwaves.waves.service;

import org.bukkit.Material;

final class MaterialPredicates {
    private MaterialPredicates() {}

    static boolean isAirLike(final Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    static boolean isWaterBodyMaterial(final Material material) {
        return material == Material.WATER || material == Material.BUBBLE_COLUMN;
    }

    static boolean isWaterVegetation(final Material material) {
        return material == Material.KELP
                || material == Material.KELP_PLANT
                || material == Material.SEAGRASS
                || material == Material.TALL_SEAGRASS;
    }

    static boolean isWater(final Material material) {
        return isWaterBodyMaterial(material) || isWaterVegetation(material);
    }

    static boolean isVisualReplaceable(final Material material) {
        return isAirLike(material) || isWaterVegetation(material);
    }

    static boolean isRunupReplaceable(final Material material) {
        return isAirLike(material) || isWaterVegetation(material) || isWaterBodyMaterial(material);
    }
}
