package com.vectras.vm.main.romstore;

import com.google.gson.annotations.SerializedName;

public class DataRoms {
    @SerializedName("rom_icon")
    public String romIcon;
    @SerializedName("rom_name")
    public String romName;
    @SerializedName("rom_arch")
    public String romArch;
    @SerializedName("rom_kernel")
    public String romKernel;
    @SerializedName("rom_avail")
	public Boolean romAvail;
    @SerializedName("rom_size")
    public String romSize;
    @SerializedName("rom_url")
    public String romUrl;
    @SerializedName("rom_path")
    public String romPath;
    @SerializedName("finalromfilename")
    public String finalromfilename;
    @SerializedName("rom_extra")
    public String romExtra;
    @SerializedName("desc")
    public String desc;
    @SerializedName("file_size")
    public String fileSize;
    @SerializedName("creator")
    public String creator;
    @SerializedName("verified")
    public boolean verified;
    @SerializedName("rom_mirrors")
    public String[] romMirrors;
    @SerializedName("rom_direct")
    public boolean romDirect;
    @SerializedName("rom_app")
    public boolean romApp;
    @SerializedName("id")
    public String id;
    @SerializedName("vecid")
    public String vecid;
    public boolean gui;

    @SerializedName("rom_category")
    public String romCategory = "";

    @SerializedName("rom_version")
    public String romVersion = "";

    @SerializedName("root_supported")
    public boolean rootSupported = false;

    @SerializedName("rom_format")
    public String romFormat = "iso";
}
