package com.raster.moneycraft.reader.model;

/**
 * Legacy .1800 binary store format release version detected from Sector-0 header magic and version stamps.
 */
public enum TallyReleaseVersion {
    REL_7_0("1.1.7.0"),
    REL_7_2("1.1.7.2"),
    UNKNOWN("UNKNOWN");

    private final String versionLabel;

    TallyReleaseVersion(String versionLabel) {
        this.versionLabel = versionLabel;
    }

    public String versionLabel() {
        return versionLabel;
    }
}
