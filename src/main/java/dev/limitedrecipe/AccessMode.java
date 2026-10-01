package dev.limitedrecipe;

import java.util.Locale;

public enum AccessMode {
    OPEN,
    WHITELIST,
    BLACKLIST;

    public static AccessMode parse(String value) {
        return AccessMode.valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
