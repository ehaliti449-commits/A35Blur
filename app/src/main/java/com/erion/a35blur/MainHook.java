package com.erion.a35blur;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.android.systemui";
    private static final String BLUR_PROP = "ro.surface_flinger.supports_background_blur";

    // { "class", "boolean method" } forced to return true
    private static final String[][] TARGETS = {
        { "com.android.systemui.statusbar.BlurUtils", "supportsBlursOnWindows" },
    };

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        // 1) Make SystemUI see the blur-support property as true
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader);
            XposedBridge.hookAllMethods(sp, "getBoolean", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length > 0 && BLUR_PROP.equals(param.args[0])) {
                            param.setResult(true);
                        }
                    } catch (Throwable ignored) { }
                }
            });
            XposedBridge.log("A35Blur: hooked SystemProperties.getBoolean");
        } catch (Throwable e) {
            XposedBridge.log("A35Blur: prop hook failed: " + e);
        }

        // 2) Force specific blur checks to true
        for (String[] t : TARGETS) {
            try {
                Class<?> c = XposedHelpers.findClass(t[0], lpparam.classLoader);
                XposedBridge.hookAllMethods(c, t[1], XC_MethodReplacement.returnConstant(true));
                XposedBridge.log("A35Blur: hooked " + t[0] + "#" + t[1]);
            } catch (Throwable e) {
                XposedBridge.log("A35Blur: failed " + t[0] + "#" + t[1] + ": " + e);
            }
        }
    }
}
