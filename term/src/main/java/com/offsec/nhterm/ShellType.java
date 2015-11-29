package com.offsec.nhterm;

// 3 types of promt: good old android, su and kali

// WHICH SU

// todo: Find a good way to get the paths

public class ShellType {
    public static final String ANDROID_SHELL = "/system/bin/sh -";
    public static final String ANDROID_SU_SHELL = "/system/xbin/su";
    public static final String KALI_SHELL = "/system/xbin/su -c /system/bin/bootkali";
    public static final String KALI_LOGIN_SHELL = "/system/xbin/su -c /system/bin/bootkali_login";
}
