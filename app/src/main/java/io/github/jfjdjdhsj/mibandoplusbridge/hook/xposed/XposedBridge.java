// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.hook.xposed;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Legacy hook entry points, installed through the libxposed 102 interceptor chain. */
public final class XposedBridge {
    private XposedBridge() {}

    public static XposedInterface.HookHandle hookMethod(Member member, XC_MethodHook callback) {
        if (!(member instanceof Executable executable)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + member);
        }
        if (Modifier.isAbstract(member.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + member);
        }
        if (callback == null) throw new IllegalArgumentException("callback");
        return HookRuntime.framework().hook(executable)
                // The adapter already contains callback failures. What it throws is the host's own
                // exception, or one a callback chose with setThrowable, and must reach the caller.
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(callback::intercept);
    }

    /** Every method declared by {@code type} with this name. Inherited methods are not included. */
    public static List<XposedInterface.HookHandle> hookAllMethods(Class<?> type, String name, XC_MethodHook callback) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) handles.add(hookMethod(method, callback));
        }
        return handles;
    }

    public static void log(String line) { HookRuntime.log(line); }

    public static void log(Throwable failure) { HookRuntime.log("OplusBandBridge failure", failure); }
}
