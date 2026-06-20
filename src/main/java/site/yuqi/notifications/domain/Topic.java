package site.yuqi.notifications.domain;

public enum Topic {
    ARTICLE_UPDATES,
    FEATURE_UPDATES,
    JOB_UPDATES;

    public static Topic fromString(String value) {
        if (value == null) return null;
        try {
            return Topic.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
