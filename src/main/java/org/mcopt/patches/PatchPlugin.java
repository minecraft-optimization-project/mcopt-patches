package org.mcopt.patches;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Applies each patch mixin only when its target mod is loaded at a version the patch was written
 * and tested against, and the patch is not disabled in config/mcopt_patches.properties.
 */
public class PatchPlugin implements IMixinConfigPlugin {
    public static final Logger LOGGER = LoggerFactory.getLogger("mcopt_patches");

    private static final String MIXIN_PACKAGE = "org.mcopt.patches.mixin.";

    /** Mixin class (relative to the mixin package) to the patch it belongs to. */
    private static final Map<String, Patch> PATCHES = Map.of(
        "minecells.EntityFallResetMixin",
        new Patch("minecells_fall_reset_chunk_loads", "minecells", "2.0.0",
            "Fall protection no longer force-loads every checkpoint chunk in the Ramparts")
    );

    private Properties config;

    record Patch(String id, String modId, String versions, String description) {}

    @Override
    public void onLoad(String mixinPackage) {
        config = loadConfig();
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        var patch = PATCHES.get(mixinClassName.substring(MIXIN_PACKAGE.length()));
        if (patch == null) return true;

        if (!Boolean.parseBoolean(config.getProperty(patch.id(), "true"))) {
            LOGGER.info("Patch {} disabled in config", patch.id());
            return false;
        }

        var mod = FabricLoader.getInstance().getModContainer(patch.modId());
        if (mod.isEmpty()) return false;

        var version = mod.get().getMetadata().getVersion();
        try {
            if (!VersionPredicate.parse(patch.versions()).test(version)) {
                LOGGER.warn("Patch {} skipped: {} {} is not a tested version ({})",
                    patch.id(), patch.modId(), version.getFriendlyString(), patch.versions());
                return false;
            }
        } catch (VersionParsingException e) {
            throw new IllegalStateException("Bad version predicate for patch " + patch.id(), e);
        }

        LOGGER.info("Applying patch {} to {} {}: {}", patch.id(), patch.modId(), version.getFriendlyString(), patch.description());
        return true;
    }

    private static Properties loadConfig() {
        var props = new Properties();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("mcopt_patches.properties");
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    props.load(reader);
                }
            }
            boolean changed = false;
            for (var patch : PATCHES.values()) {
                if (!props.containsKey(patch.id())) {
                    props.setProperty(patch.id(), "true");
                    changed = true;
                }
            }
            if (changed) {
                try (Writer writer = Files.newBufferedWriter(path)) {
                    props.store(writer, "MCOpt Patches: set a patch to false to disable it");
                }
            }
        } catch (IOException e) {
            LOGGER.error("Could not read or write {}, using defaults", path, e);
        }
        return props;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
