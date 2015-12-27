package com.offsec.nhterm;

// 3 types of promt: good old android, su and kali

// WHICH SU

// todo: Find a good way to get the paths

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ShellType {
    public static final String ANDROID_SHELL =  whichCMD("sh") + " -";
    public static final String ANDROID_SU_SHELL = whichCMD("su");
    public static final String KALI_SHELL = whichCMD("su") + " -c /system/bin/bootkali";
    public static final String KALI_LOGIN_SHELL = whichCMD("su") +" -c /system/bin/bootkali_login";

    private static String whichCMD(String theCmd){
        String output = null;
        try {
            Process p = Runtime.getRuntime().exec("which " + theCmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            output = reader.readLine();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return output;
    }
}
