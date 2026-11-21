package com.vectras.vm.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs QEMU arguments that older ByteLab builds saved in a VM's config.
 *
 * The crash seen on the "Problem has been detected" screen was:
 *   qemu-system-x86_64: -boot order=c,d: Expected '=' after parameter 'd'
 * QEMU wants the boot order as ONE value ("order=cd"). A comma starts a new
 * key=value option, so "order=c,d" is invalid. Because the bad string was stored
 * inside each VM's saved config, simply fixing the generator is not enough: every
 * VM that was already created kept failing. This class heals the string at launch
 * time, so old and new VMs both start.
 */
public final class QemuArgFixer {
    private static final Pattern BOOT = Pattern.compile("(^|\\s)-boot\\s+(\\S+)");
    private static final Pattern LETTERS = Pattern.compile("[a-dnA-DN]+");

    private QemuArgFixer() { }

    /** Fix every known-bad argument in a QEMU parameter string. Never returns null. */
    public static String fix(String params) {
        if (params == null || params.isEmpty()) return "";
        return fixBoot(params);
    }

    public static boolean hasBoot(String params) {
        return params != null && BOOT.matcher(params).find();
    }

    private static String fixBoot(String params) {
        Matcher m = BOOT.matcher(params);
        StringBuffer out = new StringBuffer();
        boolean seen = false;
        while (m.find()) {
            String replacement;
            if (seen) {
                // QEMU accepts several -boot flags, but merging into the first is safer.
                replacement = m.group(1);
            } else {
                replacement = m.group(1) + "-boot " + normalizeBootValue(m.group(2));
                seen = true;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString().replaceAll("\\s{2,}", " ").trim();
    }

    /** "order=c,d" -> "order=cd"; "c" -> "order=c"; "order=dc,menu=on" stays as is. */
    static String normalizeBootValue(String value) {
        List<String> parts = new ArrayList<>();
        boolean lastWasOrder = false;
        for (String part : value.split(",")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq > 0) {
                parts.add(part);
                lastWasOrder = part.substring(0, eq).equals("order");
            } else if (lastWasOrder && LETTERS.matcher(part).matches()) {
                int last = parts.size() - 1;
                parts.set(last, parts.get(last) + part);
            } else if (parts.isEmpty() && LETTERS.matcher(part).matches()) {
                parts.add("order=" + part);
                lastWasOrder = true;
            } else {
                parts.add(part);
                lastWasOrder = false;
            }
        }
        return String.join(",", parts);
    }
}
