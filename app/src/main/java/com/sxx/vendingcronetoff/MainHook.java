package com.sxx.vendingcronetoff;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Disable Play Store's Cronet path in com.android.vending.
 *
 * This module avoids crashes from ROM/APEX Cronet (e.g. /apex/.../libcronet.*.so)
 * by blocking HttpEngine/Cronet provider entry points and forcing fallback transport.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "VendingCronetOff";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.vending".equals(lpparam.packageName)) {
            return;
        }

        log("hooking com.android.vending (pid=" + android.os.Process.myPid() + ")");

        hookHttpEngineBuild();

        hookHttpEngineNativeProvider(lpparam);

        hookNativeCronetProvider(lpparam);

        hookAppCronetBuilders(lpparam);

        dumpAndHookProviderHierarchy(lpparam);
    }

    private void hookHttpEngineBuild() {
        boolean hooked = false;

        hooked |= hookBuildMethodOnly(null, "android.net.http.HttpEngine$Builder", "bootclasspath");

        hooked |= hookStaticFactories(null, "android.net.http.HttpEngine");

        if (hooked) {
            log("Phase1: HttpEngine$Builder#build() hooked (constructors left open for graceful fallback)");
        } else {
            log("Phase1: android.net.http.HttpEngine$Builder not found on bootclasspath");
        }
    }

    private void hookHttpEngineNativeProvider(XC_LoadPackage.LoadPackageParam lpparam) {
        String className = "org.chromium.net.impl.HttpEngineNativeProvider";
        Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        if (clazz == null) {
            log("Phase2: " + className + " not found in app classloader");
            return;
        }

        Method[] methods = clazz.getDeclaredMethods();
        int hookedBool = 0;
        int hookedOther = 0;

        for (Method m : methods) {
            String name = m.getName();
            if (isStandardObjectMethod(name)) continue;

            Class<?> ret = m.getReturnType();

            if (ret == boolean.class && m.getParameterTypes().length == 0) {
                // isEnabled() or obfuscated boolean -> false
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                    hookedBool++;
                    log("Phase2: " + className + "#" + name + "() -> false");
                } catch (Throwable t) {
                    log("Phase2: failed hooking " + name + ": " + t.getMessage());
                }
            } else {
                // All other methods -> return null/default
                try {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            log("Phase2: blocked " + className + "#" + name
                                    + "() ret=" + ret.getName());
                            return getDefaultValue(ret);
                        }
                    });
                    hookedOther++;
                } catch (Throwable t) {
                    log("Phase2: failed hooking " + className + "#" + name + ": " + t.getMessage());
                }
            }
        }

        try {
            XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    log("Phase2: HttpEngineNativeProvider constructed");
                    logStackBrief();
                }
            });
        } catch (Throwable ignored) {}

        log("Phase2: HttpEngineNativeProvider hooked: " + hookedBool + " bool, " + hookedOther + " other");
    }

    private void hookNativeCronetProvider(XC_LoadPackage.LoadPackageParam lpparam) {
        String className = "org.chromium.net.impl.NativeCronetProvider";
        Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        if (clazz == null) {
            log("Phase3: " + className + " not found");
            return;
        }

        Method[] methods = clazz.getDeclaredMethods();
        int hookedBool = 0;
        int hookedOther = 0;

        for (Method m : methods) {
            String name = m.getName();
            if (isStandardObjectMethod(name)) continue;

            Class<?> ret = m.getReturnType();

            if (ret == boolean.class && m.getParameterTypes().length == 0) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false));
                    hookedBool++;
                    log("Phase3: " + className + "#" + name + "() -> false");
                } catch (Throwable t) {
                    log("Phase3: failed hooking " + name + ": " + t.getMessage());
                }
            } else {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return getDefaultValue(ret);
                        }
                    });
                    hookedOther++;
                } catch (Throwable t) {
                    log("Phase3: failed hooking " + className + "#" + name);
                }
            }
        }

        log("Phase3: NativeCronetProvider hooked: " + hookedBool + " bool, " + hookedOther + " other");
    }

    private void hookAppCronetBuilders(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] candidates = {
                "org.chromium.net.CronetEngine$Builder",
                "org.chromium.net.ExperimentalCronetEngine$Builder",
                "org.chromium.net.impl.NativeCronetEngineBuilderImpl",
                "org.chromium.net.impl.CronetEngineBuilderImpl",
                "org.chromium.net.CronetEngine",
                "org.chromium.net.ExperimentalCronetEngine",
                "org.chromium.net.impl.NativeCronetEngine",
        };

        int count = 0;
        for (String cls : candidates) {
            boolean a = hookBuildMethodOnly(lpparam.classLoader, cls, "app");
            boolean b = hookStaticFactories(lpparam.classLoader, cls);
            if (a || b) count++;
        }

        log("Phase4: app-classloader Cronet classes hooked: " + count);
    }

    private void dumpAndHookProviderHierarchy(XC_LoadPackage.LoadPackageParam lpparam) {
        walkHierarchy(lpparam, "org.chromium.net.impl.HttpEngineNativeProvider");
        walkHierarchy(lpparam, "org.chromium.net.impl.NativeCronetProvider");
    }

    private void walkHierarchy(XC_LoadPackage.LoadPackageParam lpparam, String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        if (clazz == null) return;

        Class<?> parent = clazz.getSuperclass();
        while (parent != null && parent != Object.class) {
            String parentName = parent.getName();
            log("Phase5: " + className + " parent: " + parentName);

            Method[] parentMethods = parent.getDeclaredMethods();
            StringBuilder sb = new StringBuilder();
            sb.append("Phase5: ").append(parentName).append(" methods:");
            for (Method m : parentMethods) {
                sb.append("\n  ").append(Modifier.toString(m.getModifiers()))
                        .append(" ").append(m.getReturnType().getSimpleName())
                        .append(" ").append(m.getName()).append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getSimpleName());
                }
                sb.append(")");
            }
            log(sb.toString());

            for (Method m : parentMethods) {
                String name = m.getName();
                if (isStandardObjectMethod(name)) continue;

                Class<?> ret = m.getReturnType();
                if (shouldHookParentMethod(name, ret)) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) {
                                log("Phase5: blocked " + parentName + "#" + name
                                        + "() ret=" + ret.getName());
                                return getDefaultValue(ret);
                            }
                        });
                        log("Phase5: hooked " + parentName + "#" + name + "()");
                    } catch (Throwable t) {
                        // already hooked or abstract; ok
                    }
                }
            }

            parent = parent.getSuperclass();
        }

        // Log interfaces
        for (Class<?> iface : clazz.getInterfaces()) {
            log("Phase5: " + className + " implements: " + iface.getName());
        }
    }

    private boolean shouldHookParentMethod(String name, Class<?> ret) {
        String retName = ret.getName().toLowerCase();
        String nameLower = name.toLowerCase();

        if (retName.contains("cronet") || retName.contains("engine")
                || retName.contains("builder") || retName.contains("chromium")) {
            return true;
        }
        if (nameLower.contains("create") || nameLower.contains("build")
                || nameLower.contains("engine") || nameLower.contains("builder")) {
            return true;
        }
        // Short obfuscated names returning objects
        if (name.length() <= 2 && ret != void.class && ret != boolean.class) {
            return true;
        }
        return false;
    }

    // ==================== Utility ====================

    /**
     * Hook only build()/create() methods — NOT constructors.
     * This allows the Builder to be constructed (so app code doesn't NPE on the builder itself),
     * but .build() will throw, which is the standard "provider unavailable" signal.
     */
    private boolean hookBuildMethodOnly(ClassLoader cl, String className, String source) {
        Class<?> clazz = findClass(cl, className);
        if (clazz == null) return false;

        boolean hooked = false;
        for (Method m : clazz.getDeclaredMethods()) {
            String name = m.getName();
            if (!"build".equals(name) && !"create".equals(name)) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("BLOCKED: " + className + "#" + name + "() [" + source + "]");
                        logStackBrief();
                        throw new RuntimeException("Cronet disabled by VendingCronetOff");
                    }
                });
                hooked = true;
                log("hooked " + className + "#" + name + "() [" + source + "]");
            } catch (Throwable t) {
                // ignore
            }
        }
        return hooked;
    }

    private boolean hookStaticFactories(ClassLoader cl, String className) {
        Class<?> clazz = findClass(cl, className);
        if (clazz == null) return false;

        boolean hooked = false;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            String name = m.getName();
            String lower = name.toLowerCase();
            if (lower.contains("builder") || lower.contains("create") || lower.contains("new")
                    || lower.contains("engine") || lower.contains("instance")) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            log("BLOCKED static: " + className + "#" + name + "()");
                            throw new RuntimeException("Cronet disabled by VendingCronetOff");
                        }
                    });
                    hooked = true;
                    log("hooked static " + className + "#" + name + "()");
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
        return hooked;
    }

    private Class<?> findClass(ClassLoader cl, String className) {
        if (cl != null) {
            return XposedHelpers.findClassIfExists(className, cl);
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Object getDefaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0.0;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }

    private static boolean isStandardObjectMethod(String name) {
        return "equals".equals(name) || "hashCode".equals(name) || "toString".equals(name)
                || "getClass".equals(name) || "notify".equals(name) || "notifyAll".equals(name)
                || "wait".equals(name) || "finalize".equals(name) || "clone".equals(name);
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void logStackBrief() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            sb.append(TAG).append(": callstack:");
            int n = Math.min(15, st.length);
            for (int i = 2; i < n; i++) {
                sb.append("\n  at ").append(st[i]);
            }
            XposedBridge.log(sb.toString());
        } catch (Throwable ignored) {
        }
    }
}
