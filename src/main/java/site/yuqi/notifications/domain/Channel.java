package site.yuqi.notifications.domain;

public enum Channel {
    WEB,
    EMAIL;

    public static Channel fromString(String value) {
        if (value == null) return null;
        try {
            return Channel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
