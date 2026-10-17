package com.github.squi2rel.vp.permission;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class ResidenceReflection {
    private ResidenceReflection() {
    }

    static Handle staticMethod(Class<?> owner, String name, Class<?> returnType, Class<?>... parameterTypes) {
        try {
            MethodHandle method = MethodHandles.publicLookup().findStatic(
                    owner,
                    name,
                    MethodType.methodType(returnType, parameterTypes)
            );
            return new Handle(owner.getName(), name, method);
        } catch (NoSuchMethodException | IllegalAccessException error) {
            throw unavailable(owner, name, error);
        }
    }

    static Handle virtualMethod(Class<?> owner, String name, Class<?> returnType, Class<?>... parameterTypes) {
        try {
            MethodHandle method = MethodHandles.publicLookup().findVirtual(
                    owner,
                    name,
                    MethodType.methodType(returnType, parameterTypes)
            );
            return new Handle(owner.getName(), name, method);
        } catch (NoSuchMethodException | IllegalAccessException error) {
            throw unavailable(owner, name, error);
        }
    }

    static Object invoke(Handle handle, Object... arguments) {
        try {
            return (Object) handle.method().invokeExact(arguments);
        } catch (RuntimeException error) {
            throw error;
        } catch (Error error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("Residence method failed: " + handle.owner() + "." + handle.name(), error);
        }
    }

    private static IllegalStateException unavailable(Class<?> owner, String name, Throwable error) {
        return new IllegalStateException("Residence method not found: " + owner.getName() + "." + name, error);
    }

    record Handle(String owner, String name, MethodHandle method) {
        Handle {
            method = method.asFixedArity()
                    .asSpreader(Object[].class, method.type().parameterCount())
                    .asType(MethodType.methodType(Object.class, Object[].class));
        }
    }
}
