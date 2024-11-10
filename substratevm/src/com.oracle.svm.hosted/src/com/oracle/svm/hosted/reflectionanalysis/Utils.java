package com.oracle.svm.hosted.reflectionanalysis;

import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import static jdk.internal.org.objectweb.asm.Opcodes.INVOKESTATIC;

public class Utils {
    public static SourceValue getCallArg(MethodInsnNode call, int argIdx, Frame<SourceValue> frame) {
        int numOfArgs = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == INVOKESTATIC ? 0 : 1);
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
