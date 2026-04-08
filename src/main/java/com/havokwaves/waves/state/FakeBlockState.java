package com.havokwaves.waves.state;

import org.bukkit.Material;

public record FakeBlockState(Material fakeMaterial, Material restoreMaterial, String fakeBlockData) {
	public FakeBlockState(final Material fakeMaterial, final Material restoreMaterial) {
		this(fakeMaterial, restoreMaterial, null);
	}
}
