// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.hook.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class XposedAdapterTest {
    /** Records what reaches the original method. */
    private static final class FakeChain implements XposedInterface.Chain {
        final Object[] args;
        final Object result;
        final Throwable failure;
        Object[] proceeded;

        FakeChain(Object result, Throwable failure, Object... args) {
            this.args = args;
            this.result = result;
            this.failure = failure;
        }

        @Override public Executable getExecutable() {
            try {
                return String.class.getMethod("length");
            } catch (NoSuchMethodException impossible) {
                throw new AssertionError(impossible);
            }
        }
        @Override public Object getThisObject() { return "self"; }
        @Override public List<Object> getArgs() { return List.of(args); }
        @Override public Object getArg(int index) { return args[index]; }
        @Override public Object proceed() throws Throwable { return proceed(args); }
        @Override public Object proceed(Object[] next) throws Throwable {
            proceeded = next.clone();
            if (failure != null) throw failure;
            return result;
        }
        @Override public Object proceedWith(Object thisObject) { throw new UnsupportedOperationException(); }
        @Override public Object proceedWith(Object thisObject, Object[] next) { throw new UnsupportedOperationException(); }
    }

    @Test public void beforeCanRewriteArgumentsAndAfterCanReplaceResult() throws Throwable {
        FakeChain chain = new FakeChain("original", null, "a", 1);
        Object result = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { param.args[0] = "b"; }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                assertEquals("original", param.getResult());
                param.setResult("changed");
            }
        }.intercept(chain);
        assertEquals("changed", result);
        assertEquals(Arrays.asList("b", 1), Arrays.asList(chain.proceeded));
    }

    @Test public void setResultInBeforeSkipsOriginal() throws Throwable {
        FakeChain chain = new FakeChain("original", null);
        Object result = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { param.setResult(7); }
        }.intercept(chain);
        assertEquals(7, result);
        assertTrue(chain.proceeded == null);
    }

    @Test public void hostExceptionReachesCallerAndAfterSeesIt() {
        IllegalStateException host = new IllegalStateException("host");
        boolean[] seen = new boolean[1];
        try {
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    seen[0] = param.hasThrowable() && param.getThrowable() == host;
                }
            }.intercept(new FakeChain(null, host));
            fail("host exception swallowed");
        } catch (Throwable thrown) {
            assertSame(host, thrown);
        }
        assertTrue(seen[0]);
    }

    @Test public void failingCallbacksDoNotChangeTheCall() throws Throwable {
        FakeChain chain = new FakeChain("original", null, "a");
        Object result = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult("early");
                throw new RuntimeException("before");
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                param.setResult("lost");
                throw new RuntimeException("after");
            }
        }.intercept(chain);
        assertEquals("original", result);
        assertFalse(chain.proceeded == null);
    }

    static class Base {
        String pick(Object value) { return "object"; }
        String pick(CharSequence value) { return "chars"; }
        String wide(long value) { return "long"; }
        private int secret = 3;
    }

    static final class Child extends Base {
        @Override String pick(CharSequence value) { return "child-chars"; }
    }

    @Test public void callMethodPicksClosestOverloadAndSearchesSuperclasses() {
        Child child = new Child();
        assertEquals("child-chars", XposedHelpers.callMethod(child, "pick", "text"));
        assertEquals("object", XposedHelpers.callMethod(child, "pick", 5));
        assertEquals("long", XposedHelpers.callMethod(child, "wide", 5));
        assertEquals(3, XposedHelpers.getIntField(child, "secret"));
        XposedHelpers.setIntField(child, "secret", 9);
        assertEquals(9, XposedHelpers.getObjectField(child, "secret"));
    }

    @Test public void callMethodWrapsHostException() {
        try {
            XposedHelpers.callMethod("abc", "charAt", 10);
            fail("expected failure");
        } catch (XposedHelpers.InvocationTargetError wrapped) {
            assertTrue(wrapped.getCause() instanceof IndexOutOfBoundsException);
        }
    }

    @Test public void missingMethodIsAnError() {
        try {
            XposedHelpers.callMethod("abc", "nope");
            fail("expected failure");
        } catch (NoSuchMethodError expected) {
            assertTrue(expected.getMessage().contains("nope"));
        }
    }

    /** One test, because HookRuntime state is per class loader and close() is final. */
    @Test public void workerRejectsWhenFullAndDropsAfterReload() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ThreadPoolExecutor worker = HookRuntime.worker("test-worker", 1);
        worker.execute(() -> {
            try { release.await(); } catch (InterruptedException ignored) { }
        });
        boolean rejected = false;
        for (int i = 0; i < 3 && !rejected; i++) {
            try {
                worker.execute(() -> { });
            } catch (java.util.concurrent.RejectedExecutionException expected) {
                rejected = true;
            }
        }
        assertTrue("a full queue must still reject while the generation is live", rejected);
        release.countDown();

        int[] runs = new int[1];
        HookRuntime.track(() -> runs[0]++);
        HookRuntime.close();
        assertEquals(1, runs[0]);
        assertTrue(worker.isShutdown());
        // Old code that is still running must not throw on a host thread.
        worker.execute(() -> runs[0] += 10);
        HookRuntime.track(() -> runs[0]++);
        assertEquals(2, runs[0]);
        assertTrue(worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(2, runs[0]);
    }
}
