package studio.lorian.mapmanager.plugin.internal.migration;

import studio.lorian.mapmanager.api.model.MapRole;

import java.nio.file.Files;

/** 无测试框架的 v3 文件兼容性检查，可直接执行 main。 */
public final class V3MigrationSourceCheck {
    public static void main(String[] args) throws Exception {
        var dir = Files.createTempDirectory("mmc-v3-");
        Files.writeString(dir.resolve("worlds.json"), """
                {"TestWorld":{"group":"artists","exploded":true,"physical":false,"visitors":["Visitor123"]}}
                """);
        Files.writeString(dir.resolve("groups.json"), """
                {"artists":{"worlds":["TestWorld"],"admins":["Owner123"],"builders":["Owner123"]}}
                """);
        var snapshot = new V3MigrationSourceImpl(dir).read();
        assert snapshot.maps().size() == 1;
        assert snapshot.groups().contains("artists");
        assert snapshot.maps().getFirst().settings().explosion();
        assert !snapshot.maps().getFirst().settings().physical();
        assert snapshot.roles().stream().anyMatch(role -> role.role() == MapRole.VISITOR && role.worldName().equals("TestWorld"));
        assert snapshot.roles().size() == 3;
        Files.writeString(dir.resolve("groups.json"), """
                {"artists":{"worlds":["OtherWorld"]}}
                """);
        try {
            new V3MigrationSourceImpl(dir).read();
            throw new AssertionError("Expected world/group mismatch to fail");
        } catch (IllegalArgumentException expected) {
            assert expected.getMessage().contains("不一致");
        }
        System.out.println("V3 migration source check passed");
    }
}
