package com.vectras.vm.container;

import com.google.gson.annotations.SerializedName;

/** One lightweight container from assets/containers.json. */
public class ContainerEntry {
    @SerializedName("id") public String id;
    @SerializedName("name") public String name;
    @SerializedName("category") public String category = "linux";
    @SerializedName("icon") public String icon = "";
    @SerializedName("desc") public String desc = "";
    @SerializedName("size") public String size = "";
    /** Directory listing / yaml used to find the newest file name (optional). */
    @SerializedName("listing_url") public String listingUrl = "";
    /** Regex with one group-0 match that is the file name inside the listing. Use {arch} placeholder. */
    @SerializedName("file_regex") public String fileRegex = "";
    /** Download URL. {arch} and {file} are replaced. */
    @SerializedName("url_template") public String urlTemplate = "";
    @SerializedName("arch_arm64") public String archArm64 = "aarch64";
    @SerializedName("arch_x86_64") public String archX8664 = "x86_64";
    @SerializedName("root_supported") public boolean rootSupported = true;
    @SerializedName("shell") public String shell = "/bin/sh";
    /** Commands run once inside the new container as root. */
    @SerializedName("first_run") public String firstRun = "";
    @SerializedName("available") public boolean available = true;
    @SerializedName("note") public String note = "";
}
