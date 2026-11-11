package com.erion.a35blur;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.android.systemui";
    private static final String BLUR_PROP = "ro.surface_flinger.supports_background_blur";

    private static final String[] TRACE_CLASSES = {
        "com.android.systemui.statusbar.phone.CapturedBlurContainer",
        "com.android.systemui.blur.SecQpBlurController",
        "com.android.systemui.statusbar.BlurUtils",
        "com.android.systemui.blur.SecCoverBlurController",
    };

    private static final ConcurrentHashMap<String, String> LAST = new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;

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
        } catch (Throwable e) {
            XposedBridge.log("A35Blur: prop hook failed: " + e);
        }

        try {
            Class<?> c = XposedHelpers.findClass("com.android.systemui.statusbar.BlurUtils", lpparam.classLoader);
            XposedBridge.hookAllMethods(c, "supportsBlursOnWindows", XC_MethodReplacement.returnConstant(true));
        } catch (Throwable e) {
            XposedBridge.log("A35Blur: BlurUtils hook failed: " + e);
        }

        for (String cls : TRACE_CLASSES) {
            trace(cls, lpparam.classLoader);
        }
    }

    private static void trace(String cls, ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, cl);
            final String shortName = cls.substring(cls.lastIndexOf('.') + 1);
            int count = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() == boolean.class && m.getParameterTypes().length == 0) {
                    final String key = shortName + "#" + m.getName();
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String v = String.valueOf(p.getResult());
                                String old = LAST.put(key, v);
                                if (!v.equals(old)) {
                                    XposedBridge.log("A35Trace " + key + " = " + v);
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
                    count++;
                }
            }
            XposedBridge.log("A35Trace: tracing " + count + " methods in " + shortName);
        } catch (Throwable e) {
            XposedBridge.log("A35Trace: skip " + cls + ": " + e);
        }
    }
}
