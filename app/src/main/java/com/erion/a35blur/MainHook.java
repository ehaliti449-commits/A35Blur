package com.erion.a35blur;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.android.systemui";

    // Fill these in after analysing SystemUI with jadx.
    // Each entry: { "fully.qualified.ClassName", "methodName" }
    // Only use methods that return boolean (they get forced to true).
    private static final String[][] TARGETS = {
        // { "com.android.systemui.example.BlurChecker", "isBlurSupported" },
    };

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

        for (String[] t : TARGETS) {
            try {
                Class<?> c = XposedHelpers.findClass(t[0], lpparam.classLoader);
                XposedBridge.hookAllMethods(c, t[1], XC_MethodReplacement.returnConstant(true));
                XposedBridge.log("A35Blur: hooked " + t[0] + "#" + t[1]);
            } catch (Throwable e) {
                // Never let a failed hook crash SystemUI
                XposedBridge.log("A35Blur: failed " + t[0] + "#" + t[1] + ": " + e);
            }
        }
    }
}
