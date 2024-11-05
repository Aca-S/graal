package com.oracle.svm.hosted.reflectionanalysis;

import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

public class Utils {
    public static SourceValue getCallArg(Frame<SourceValue> frame, int argIdx) {
        int numOfArgs = frame.getStackSize();
        int stackPos = frame.getStackSize() - numOfArgs + argIdx;
        return frame.getStack(stackPos);
    }

    public static String encodeMethodCall(MethodInsnNode methodCall) {
        return encodeMethodCall(methodCall.owner, methodCall.name, methodCall.desc);
    }

    public static String encodeMethodCall(String owner, String name, String desc) {
        return owner + ":" + name + ":" + desc;
    }
}
