package studio.lorian.mapmanager.plugin.internal.world;

/** Internal equivalent of v3 VersionCheck for imported world files. */
public interface WorldVersionValidator {
    boolean canImport(String worldName);
}
