// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.hook.xposed;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Member;

/**
 * Before/after callback with the legacy XposedBridge contract, run as one libxposed interceptor.
 *
 * <p>API 102 modules cannot load {@code de.robv.android.xposed}. The hooks keep this shape so their
 * logic did not have to be rewritten: {@link #beforeHookedMethod} may change {@code args} or end the
 * call early with {@link MethodHookParam#setResult}; {@link #afterHookedMethod} sees and may replace
 * the result. A callback that throws is logged and ignored, as before.</p>
 */
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    final Object intercept(XposedInterface.Chain chain) throws Throwable {
        MethodHookParam param = new MethodHookParam(chain);
        try {
            beforeHookedMethod(param);
        } catch (Throwable failure) {
            HookRuntime.log("HOOK_BEFORE_FAILED " + chain.getExecutable(), failure);
            param.result = null;
            param.throwable = null;
            param.returnEarly = false;
        }
        if (!param.returnEarly) {
            try {
                param.result = chain.proceed(param.args);
                param.throwable = null;
            } catch (Throwable thrown) {
                param.result = null;
                param.throwable = thrown;
            }
        }
        Object lastResult = param.result;
        Throwable lastThrowable = param.throwable;
        try {
            afterHookedMethod(param);
        } catch (Throwable failure) {
            HookRuntime.log("HOOK_AFTER_FAILED " + chain.getExecutable(), failure);
            param.result = lastResult;
            param.throwable = lastThrowable;
        }
        if (param.throwable != null) throw param.throwable;
        return param.result;
    }

    public static final class MethodHookParam {
        public final Member method;
        public final Object thisObject;
        public final Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        MethodHookParam(XposedInterface.Chain chain) {
            method = chain.getExecutable();
            thisObject = chain.getThisObject();
            args = chain.getArgs().toArray();
        }

        public Object getResult() { return result; }

        public void setResult(Object value) {
            result = value;
            throwable = null;
            returnEarly = true;
        }

        public Throwable getThrowable() { return throwable; }

        public boolean hasThrowable() { return throwable != null; }

        public void setThrowable(Throwable value) {
            throwable = value;
            result = null;
            returnEarly = true;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }
}
