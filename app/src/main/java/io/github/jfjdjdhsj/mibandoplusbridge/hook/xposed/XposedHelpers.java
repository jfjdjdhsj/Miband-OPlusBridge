// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.hook.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The reflection helpers the hooks used from the legacy XposedHelpers, with the same lookup rules.
 *
 * <p>Methods and fields are searched in the class and its superclasses. A call picks the overload
 * whose parameters fit the arguments at the lowest conversion cost, as the legacy helper did.</p>
 */
public final class XposedHelpers {
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Executable> MEMBERS = new ConcurrentHashMap<>();
    private static final Class<?>[] PRIMITIVE_ORDER = {
            byte.class, short.class, char.class, int.class, long.class, float.class, double.class
    };

    private XposedHelpers() {}

    public static final class ClassNotFoundError extends Error {
        ClassNotFoundError(String message, Throwable cause) { super(message, cause); }
    }

    /** Thrown by the call helpers. The cause is what the called method threw. */
    public static final class InvocationTargetError extends Error {
        InvocationTargetError(Throwable cause) { super(cause); }
    }

    public static Class<?> findClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException missing) {
            throw new ClassNotFoundError(name, missing);
        }
    }

    // ---- hooks ----

    public static io.github.libxposed.api.XposedInterface.HookHandle findAndHookMethod(
            String className, ClassLoader loader, String name, Object... typesAndCallback) {
        return findAndHookMethod(findClass(className, loader), name, typesAndCallback);
    }

    public static io.github.libxposed.api.XposedInterface.HookHandle findAndHookMethod(
            Class<?> type, String name, Object... typesAndCallback) {
        if (typesAndCallback.length == 0
                || !(typesAndCallback[typesAndCallback.length - 1] instanceof XC_MethodHook callback)) {
            throw new IllegalArgumentException("No callback defined");
        }
        Class<?>[] parameters = parameterTypes(type.getClassLoader(),
                Arrays.copyOf(typesAndCallback, typesAndCallback.length - 1));
        return XposedBridge.hookMethod(findMethodExact(type, name, parameters), callback);
    }

    public static Method findMethodExact(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException missing) {
            throw new NoSuchMethodError(type.getName() + '#' + name + Arrays.toString(parameters) + "#exact");
        }
    }

    private static Class<?>[] parameterTypes(ClassLoader loader, Object[] types) {
        Class<?>[] result = new Class<?>[types.length];
        for (int i = 0; i < types.length; i++) {
            Object type = types[i];
            if (type instanceof Class<?> value) result[i] = value;
            else if (type instanceof String name) result[i] = findClass(name, loader);
            else throw new IllegalArgumentException("Parameter type must be a Class or a class name: " + type);
        }
        return result;
    }

    // ---- fields ----

    public static Field findField(Class<?> type, String name) {
        String key = type.getName() + '#' + name + '@' + System.identityHashCode(type.getClassLoader());
        Field cached = FIELDS.get(key);
        if (cached != null && cached.getDeclaringClass().isAssignableFrom(type)) return cached;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                FIELDS.put(key, field);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Look in the superclass.
            }
        }
        throw new NoSuchFieldError(type.getName() + '#' + name);
    }

    public static Object getObjectField(Object target, String name) {
        try {
            return findField(target.getClass(), name).get(target);
        } catch (IllegalAccessException denied) {
            throw new IllegalAccessError(denied.getMessage());
        }
    }

    public static int getIntField(Object target, String name) {
        try {
            return findField(target.getClass(), name).getInt(target);
        } catch (IllegalAccessException denied) {
            throw new IllegalAccessError(denied.getMessage());
        }
    }

    public static void setIntField(Object target, String name, int value) {
        try {
            findField(target.getClass(), name).setInt(target, value);
        } catch (IllegalAccessException denied) {
            throw new IllegalAccessError(denied.getMessage());
        }
    }

    public static void setBooleanField(Object target, String name, boolean value) {
        try {
            findField(target.getClass(), name).setBoolean(target, value);
        } catch (IllegalAccessException denied) {
            throw new IllegalAccessError(denied.getMessage());
        }
    }

    public static Object getStaticObjectField(Class<?> type, String name) {
        try {
            return findField(type, name).get(null);
        } catch (IllegalAccessException denied) {
            throw new IllegalAccessError(denied.getMessage());
        }
    }

    // ---- calls ----

    public static Object callMethod(Object target, String name, Object... args) {
        return invoke(findMethodBestMatch(target.getClass(), name, args), target, args);
    }

    public static Object callStaticMethod(Class<?> type, String name, Object... args) {
        return invoke(findMethodBestMatch(type, name, args), null, args);
    }

    public static Object newInstance(Class<?> type, Object... args) {
        Constructor<?> constructor = findConstructorBestMatch(type, args);
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException failed) {
            throw new InvocationTargetError(failed.getCause());
        } catch (IllegalAccessException denied) {
            throw new IllegalAccessError(denied.getMessage());
        } catch (InstantiationException abstractType) {
            throw new InstantiationError(abstractType.getMessage());
        }
    }

    private static Object invoke(Method method, Object target, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failed) {
            throw new InvocationTargetError(failed.getCause());
        } catch (IllegalAccessException denied) {
            throw new IllegalAccessError(denied.getMessage());
        }
    }

    public static Method findMethodBestMatch(Class<?> type, String name, Object... args) {
        Class<?>[] argTypes = argumentTypes(args);
        String key = memberKey(type, name, argTypes);
        Executable cached = MEMBERS.get(key);
        if (cached instanceof Method method) return method;
        Method best = null;
        float bestCost = Float.MAX_VALUE;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                float cost = cost(argTypes, method.getParameterTypes());
                // A subclass declaration wins a tie, so overriding methods resolve as before.
                if (cost < bestCost) {
                    best = method;
                    bestCost = cost;
                }
            }
        }
        if (best == null) {
            throw new NoSuchMethodError(type.getName() + '#' + name + Arrays.toString(argTypes) + "#bestmatch");
        }
        best.setAccessible(true);
        MEMBERS.put(key, best);
        return best;
    }

    public static Constructor<?> findConstructorBestMatch(Class<?> type, Object... args) {
        Class<?>[] argTypes = argumentTypes(args);
        String key = memberKey(type, "<init>", argTypes);
        Executable cached = MEMBERS.get(key);
        if (cached instanceof Constructor<?> constructor) return constructor;
        Constructor<?> best = null;
        float bestCost = Float.MAX_VALUE;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            float cost = cost(argTypes, constructor.getParameterTypes());
            if (cost < bestCost) {
                best = constructor;
                bestCost = cost;
            }
        }
        if (best == null) {
            throw new NoSuchMethodError(type.getName() + "#<init>" + Arrays.toString(argTypes) + "#bestmatch");
        }
        best.setAccessible(true);
        MEMBERS.put(key, best);
        return best;
    }

    private static String memberKey(Class<?> type, String name, Class<?>[] argTypes) {
        return type.getName() + '@' + System.identityHashCode(type.getClassLoader()) + '#' + name
                + Arrays.toString(argTypes);
    }

    private static Class<?>[] argumentTypes(Object[] args) {
        Class<?>[] result = new Class<?>[args == null ? 0 : args.length];
        for (int i = 0; i < result.length; i++) result[i] = args[i] == null ? null : args[i].getClass();
        return result;
    }

    /** Conversion cost, or {@link Float#MAX_VALUE} when an argument cannot be passed. */
    static float cost(Class<?>[] args, Class<?>[] parameters) {
        if (args.length != parameters.length) return Float.MAX_VALUE;
        float total = 0;
        for (int i = 0; i < args.length; i++) {
            float step = cost(args[i], parameters[i]);
            if (step == Float.MAX_VALUE) return Float.MAX_VALUE;
            total += step;
        }
        return total;
    }

    private static float cost(Class<?> arg, Class<?> parameter) {
        if (arg == null) return parameter.isPrimitive() ? Float.MAX_VALUE : 1.5f;
        if (parameter.isPrimitive()) return primitiveCost(arg, parameter);
        if (!parameter.isAssignableFrom(arg)) return Float.MAX_VALUE;
        float cost = 0;
        Class<?> current = arg;
        while (current != null && current != parameter) {
            if (parameter.isInterface() && parameter.isAssignableFrom(current)) {
                cost += 0.25f;
                break;
            }
            cost++;
            current = current.getSuperclass();
        }
        if (current == null) cost += 1.5f;
        return cost;
    }

    private static float primitiveCost(Class<?> arg, Class<?> parameter) {
        Class<?> current = unbox(arg);
        if (current == null) return Float.MAX_VALUE;
        float cost = arg.isPrimitive() ? 0 : 0.1f;
        if (current == parameter) return cost;
        if (current == boolean.class || parameter == boolean.class) return Float.MAX_VALUE;
        if (!widens(current, parameter)) return Float.MAX_VALUE;
        int from = indexOf(current);
        int to = indexOf(parameter);
        return cost + 0.1f * (to - from);
    }

    private static boolean widens(Class<?> from, Class<?> to) {
        if (from == char.class) return to == int.class || to == long.class || to == float.class || to == double.class;
        if (to == char.class) return false;
        return indexOf(from) >= 0 && indexOf(to) > indexOf(from);
    }

    private static int indexOf(Class<?> primitive) {
        for (int i = 0; i < PRIMITIVE_ORDER.length; i++) if (PRIMITIVE_ORDER[i] == primitive) return i;
        return -1;
    }

    private static Class<?> unbox(Class<?> type) {
        if (type.isPrimitive()) return type;
        if (type == Integer.class) return int.class;
        if (type == Long.class) return long.class;
        if (type == Boolean.class) return boolean.class;
        if (type == Double.class) return double.class;
        if (type == Float.class) return float.class;
        if (type == Short.class) return short.class;
        if (type == Byte.class) return byte.class;
        if (type == Character.class) return char.class;
        return null;
    }
}
