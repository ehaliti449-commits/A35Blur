package com.erion.a35blur;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.android.systemui";
    private static final String BLUR_PROP = "ro.surface_flinger.supports_background_blur";

    private static final String[] FLAG_CLASSES = {
        "com.android.systemui.LsRune",
        "com.android.systemui.BasicRune",
        "com.android.systemui.Rune",
    };

    private static final Set<String> FORCE_FLAGS = new HashSet<>(Arrays.asList(
        "LOCKUI_BLUR", "LOCKUI_SHORTCUT_BLUR_BG"));

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        final ClassLoader cl = lpparam.classLoader;

        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", cl);
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
            XposedBridge.log("A35Fail prop hook: " + e);
        }

        try {
            Class<?> bu = XposedHelpers.findClass("com.android.systemui.statusbar.BlurUtils", cl);
            XposedBridge.hookAllMethods(bu, "supportsBlursOnWindows",
                XC_MethodReplacement.returnConstant(true));
        } catch (Throwable e) {
            XposedBridge.log("A35Fail BlurUtils: " + e);
        }

        final AtomicBoolean done = new AtomicBoolean(false);
        try {
            XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (done.compareAndSet(false, true)) setFlags(cl);
                    }
                });
        } catch (Throwable e) {
            XposedBridge.log("A35Fail onCreate hook: " + e);
            setFlags(cl);
        }
    }

    private static void setFlags(ClassLoader cl) {
        for (String cn : FLAG_CLASSES) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(cn, cl);
                if (c == null) {
                    XposedBridge.log("A35Flag no class " + cn);
                    continue;
                }
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (f.getType() != boolean.class) continue;
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        String name = f.getName();
                        if (!name.toUpperCase().contains("BLUR")) continue;
                        f.setAccessible(true);
                        boolean old = f.getBoolean(null);
                        if (FORCE_FLAGS.contains(name) && !old) {
                            boolean ok = forceSet(f, true);
                            XposedBridge.log("A35Set " + c.getSimpleName() + "." + name
                                + " ok=" + ok + " now=" + f.getBoolean(null));
                        } else {
                            XposedBridge.log("A35Flag " + c.getSimpleName() + "." + name + " " + old);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("A35Fail field " + f.getName() + ": " + t);
                    }
                }
            } catch (Throwable e) {
                XposedBridge.log("A35Fail class " + cn + ": " + e);
            }
        }
    }

    private static boolean forceSet(Field f, boolean v) {
        String n = f.getName();
        try {
            f.setAccessible(true);
            f.setBoolean(null, v);
            if (f.getBoolean(null) == v) return true;
        } catch (Throwable t) {
            XposedBridge.log("A35Fail plain " + n + ": " + t);
        }
        try {
            Field af = Field.class.getDeclaredField("accessFlags");
            af.setAccessible(true);
            af.setInt(f, af.getInt(f) & ~Modifier.FINAL);
            f.setBoolean(null, v);
            if (f.getBoolean(null) == v) {
                XposedBridge.log("A35Set method=accessFlags " + n);
                return true;
            }
        } catch (Throwable t) {
            XposedBridge.log("A35Fail accessFlags " + n + ": " + t);
        }
        try {
            Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object unsafe = uf.get(null);
            Field of = Field.class.getDeclaredField("offset");
            of.setAccessible(true);
            long off = ((Number) of.get(f)).longValue();
            Method put = unsafe.getClass().getMethod("putBoolean",
                Object.class, long.class, boolean.class);
            put.invoke(unsafe, f.getDeclaringClass(), off, v);
            if (f.getBoolean(null) == v) {
                XposedBridge.log("A35Set method=unsafe " + n);
                return true;
            }
        } catch (Throwable t) {
            XposedBridge.log("A35Fail unsafe " + n + ": " + t);
        }
        return false;
    }
}
