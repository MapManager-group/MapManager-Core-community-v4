package studio.lorian.mapmanager.plugin.internal.notification;

public record NotificationTarget(Kind kind, String playerName) {
    public enum Kind { PLAYER, CONSOLE, LOG_ONLY }

    public static NotificationTarget player(String name) {
        return new NotificationTarget(Kind.PLAYER, name);
    }

    public static NotificationTarget console() {
        return new NotificationTarget(Kind.CONSOLE, null);
    }

    public static NotificationTarget logOnly() {
        return new NotificationTarget(Kind.LOG_ONLY, null);
    }
}
