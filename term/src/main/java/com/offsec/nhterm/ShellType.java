package com.offsec.nhterm;

// 3 types of prompt: good old android, su and kali

// WHICH SU

// todo: Find a good way to get the paths

import java.io.BufferedReader;
import java.io.InputStreamReader;

class ShellType {
    static final String ANDROID_SHELL =  whichCMD("sh") + " -";
    static final String ANDROID_SU_SHELL = whichCMD("su");
    static final String KALI_SHELL = whichCMD("su") + " -c /data/data/com.offsec.nethunter/files/scripts/bootkali";
    static final String KALI_LOGIN_SHELL = whichCMD("su") +" -c /data/data/com.offsec.nethunter/files/scripts/bootkali_login";

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
