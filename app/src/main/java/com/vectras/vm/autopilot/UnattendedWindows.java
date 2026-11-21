package com.vectras.vm.autopilot;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Builds an "autounattend.xml" that Windows Setup picks up from a removable drive, so the whole install
 * (language, disk, edition, licence screen, local account, OOBE) runs with no clicks.
 *
 * The folder is handed to QEMU as a virtual FAT USB drive (see {@link #qemuArgs(File)}).
 */
public final class UnattendedWindows {
    public static final String USER = "ByteLab";

    private UnattendedWindows() { }

    public static File prepare(File dir, boolean uefi, boolean windows11) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("cannot create " + dir);
        File xml = new File(dir, "autounattend.xml");
        try (FileOutputStream out = new FileOutputStream(xml)) {
            out.write(build(uefi, windows11).getBytes("UTF-8"));
        }
        return dir;
    }

    /** Extra QEMU arguments that expose the folder as a USB stick. */
    public static String qemuArgs(File dir) {
        return "-drive file=fat:rw:" + dir.getAbsolutePath() + ",format=raw,if=none,id=bytelabunattend"
                + " -device usb-ehci,id=bytelabehci"
                + " -device usb-storage,bus=bytelabehci.0,drive=bytelabunattend";
    }

    static String build(boolean uefi, boolean w11) {
        String bypass = w11
                ? reg(1, "BypassTPMCheck") + reg(2, "BypassSecureBootCheck") + reg(3, "BypassRAMCheck")
                + reg(4, "BypassStorageCheck") + reg(5, "BypassCPUCheck")
                : "";
        String disk = uefi
                ? "<CreatePartitions>"
                + part(1, "Primary", "<Size>100</Size>")
                + part(2, "MSR", "<Size>16</Size>")
                + part(3, "Primary", "<Extend>true</Extend>")
                + "</CreatePartitions><ModifyPartitions>"
                + "<ModifyPartition wcm:action=\"add\"><Order>1</Order><PartitionID>1</PartitionID><Format>FAT32</Format><Label>System</Label></ModifyPartition>"
                + "<ModifyPartition wcm:action=\"add\"><Order>2</Order><PartitionID>2</PartitionID></ModifyPartition>"
                + "<ModifyPartition wcm:action=\"add\"><Order>3</Order><PartitionID>3</PartitionID><Format>NTFS</Format><Label>Windows</Label></ModifyPartition>"
                + "</ModifyPartitions>"
                : "<CreatePartitions><CreatePartition wcm:action=\"add\"><Order>1</Order><Type>Primary</Type><Extend>true</Extend></CreatePartition></CreatePartitions>"
                + "<ModifyPartitions><ModifyPartition wcm:action=\"add\"><Order>1</Order><PartitionID>1</PartitionID><Active>true</Active><Format>NTFS</Format><Label>Windows</Label></ModifyPartition></ModifyPartitions>";
        String installTo = uefi ? 3 + "" : 1 + "";

        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<unattend xmlns=\"urn:schemas-microsoft-com:unattend\" xmlns:wcm=\"http://schemas.microsoft.com/WMIConfig/2002/State\">\n"
                + "<settings pass=\"windowsPE\">\n"
                + " <component name=\"Microsoft-Windows-International-Core-WinPE\" processorArchitecture=\"amd64\" publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\">"
                + "<SetupUILanguage><UILanguage>en-US</UILanguage></SetupUILanguage><InputLocale>en-US</InputLocale><SystemLocale>en-US</SystemLocale><UILanguage>en-US</UILanguage><UserLocale>en-US</UserLocale></component>\n"
                + " <component name=\"Microsoft-Windows-Setup\" processorArchitecture=\"amd64\" publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\">\n"
                + (bypass.isEmpty() ? "" : "  <RunSynchronous>" + bypass + "</RunSynchronous>\n")
                + "  <DiskConfiguration><WillShowUI>OnError</WillShowUI><Disk wcm:action=\"add\"><DiskID>0</DiskID><WillWipeDisk>true</WillWipeDisk>" + disk + "</Disk></DiskConfiguration>\n"
                // Generic installation key (selects Pro, does not activate) so no edition/key screen appears.
                + "  <ImageInstall><OSImage><InstallTo><DiskID>0</DiskID><PartitionID>" + installTo + "</PartitionID></InstallTo><WillShowUI>OnError</WillShowUI></OSImage></ImageInstall>\n"
                + "  <UserData><AcceptEula>true</AcceptEula><ProductKey><Key>VK7JG-NPHTM-C97JM-9MPGT-3V66T</Key><WillShowUI>OnError</WillShowUI></ProductKey></UserData>\n"
                + " </component>\n"
                + "</settings>\n"
                + "<settings pass=\"specialize\">\n"
                + " <component name=\"Microsoft-Windows-Shell-Setup\" processorArchitecture=\"amd64\" publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\"><ComputerName>BYTELAB-PC</ComputerName></component>\n"
                + "</settings>\n"
                + "<settings pass=\"oobeSystem\">\n"
                + " <component name=\"Microsoft-Windows-Shell-Setup\" processorArchitecture=\"amd64\" publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\">\n"
                + "  <OOBE><HideEULAPage>true</HideEULAPage><HideLocalAccountScreen>true</HideLocalAccountScreen><HideOEMRegistrationScreen>true</HideOEMRegistrationScreen><HideOnlineAccountScreens>true</HideOnlineAccountScreens><HideWirelessSetupInOOBE>true</HideWirelessSetupInOOBE><ProtectYourPC>3</ProtectYourPC><SkipMachineOOBE>true</SkipMachineOOBE><SkipUserOOBE>true</SkipUserOOBE></OOBE>\n"
                + "  <UserAccounts><LocalAccounts><LocalAccount wcm:action=\"add\"><Name>" + USER + "</Name><Group>Administrators</Group><DisplayName>" + USER + "</DisplayName><Password><Value></Value><PlainText>true</PlainText></Password></LocalAccount></LocalAccounts></UserAccounts>\n"
                + "  <AutoLogon><Enabled>true</Enabled><LogonCount>9999</LogonCount><Username>" + USER + "</Username><Password><Value></Value><PlainText>true</PlainText></Password></AutoLogon>\n"
                + " </component>\n"
                + " <component name=\"Microsoft-Windows-International-Core\" processorArchitecture=\"amd64\" publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\"><InputLocale>en-US</InputLocale><SystemLocale>en-US</SystemLocale><UILanguage>en-US</UILanguage><UserLocale>en-US</UserLocale></component>\n"
                + "</settings>\n"
                + "</unattend>\n";
    }

    private static String part(int order, String type, String size) {
        return "<CreatePartition wcm:action=\"add\"><Order>" + order + "</Order><Type>" + type + "</Type>" + size + "</CreatePartition>";
    }

    private static String reg(int order, String name) {
        return "<RunSynchronousCommand wcm:action=\"add\"><Order>" + order + "</Order><Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v "
                + name + " /t REG_DWORD /d 1 /f</Path></RunSynchronousCommand>";
    }
}
