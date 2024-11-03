package com.oracle.svm.hosted.reflectionanalysis;

import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConstantReflectionRegistry {

    private final Map<String, List<Object>> registry = new ConcurrentHashMap<>();

    public void add(ClassNode clazz, MethodNode method, int bci, List<Object> arguments) {
        String encoded = clazz.name + ":" + method.name + ":" + method.desc + ":" + bci;
        registry.put(encoded, arguments);
    }

    public List<Object> get(ResolvedJavaMethod method, int bci) {
        String encoded = method.getDeclaringClass().toClassName().replace('.', '/') + ":"
                + method.getName() + ":"
                + method.getSignature().toMethodDescriptor() + ":"
                + bci;
        return registry.get(encoded);
    }
}
