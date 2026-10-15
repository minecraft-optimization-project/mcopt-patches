package org.mcopt.patches;

import net.fabricmc.api.ModInitializer;

public class McoptPatches implements ModInitializer {
    @Override
    public void onInitialize() {
        // Patches are mixins gated by PatchPlugin; nothing to set up at runtime.
    }
}
