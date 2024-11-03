package com.oracle.svm.hosted.reflectionanalysis;

import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ConstantReflectionRegistry {

    private final Set<String> registry = ConcurrentHashMap.newKeySet();

    public void add(ClassNode clazz, MethodNode method, int bci) {
        String encoded = clazz.name + ":" + method.name + ":" + method.desc + ":" + bci;
        registry.add(encoded);
    }

    public boolean contains(ResolvedJavaMethod method, int bci) {
        String encoded = method.getDeclaringClass().toClassName().replace('.', '/') + ":"
                + method.getName() + ":"
                + method.getSignature().toMethodDescriptor() + ":"
                + bci;
        return registry.contains(encoded);
    }
}
