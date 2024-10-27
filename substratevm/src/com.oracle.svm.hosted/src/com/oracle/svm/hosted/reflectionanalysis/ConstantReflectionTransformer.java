package com.oracle.svm.hosted.reflectionanalysis;

import com.oracle.svm.hosted.reflectionanalysis.analyzers.ConstantBooleanAnalyzer;
import com.oracle.svm.hosted.reflectionanalysis.analyzers.ConstantStringAnalyzer;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.InsnNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceInterpreter;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public class ConstantReflectionTransformer implements ClassFileTransformer {

    private static final Map<String, Consumer<CallContext>> reflectiveCallHandlers = new HashMap<>() {
        {
            put(encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"), ConstantReflectionTransformer::partiallyEvaluateClassForNameOne);
            put(encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"), ConstantReflectionTransformer::partiallyEvaluateClassForNameTwo);
        }
    };

    private static final Map<String, Integer> partialEvaluationCounts = new HashMap<>();

    private static ConstantStringAnalyzer stringAnalyzer;
    private static ConstantBooleanAnalyzer booleanAnalyzer;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classFileBuffer) {
        ClassNode classNode = new ClassNode();

        ClassReader reader = new ClassReader(classFileBuffer);
        reader.accept(classNode, 0);

        transformClass(classNode);

        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);

        return writer.toByteArray();
    }

    private static void transformClass(ClassNode classNode) {
        List<MethodNode> methods = new ArrayList<>(classNode.methods);
        methods.forEach(methodNode -> transformMethod(methodNode, classNode));
    }

    private static void transformMethod(MethodNode methodNode, ClassNode contextClassNode) {
        Analyzer<SourceValue> analyzer = new Analyzer<>(new SourceInterpreter());
        try {
            analyzer.analyze(contextClassNode.name, methodNode);
        } catch (AnalyzerException e) {
            throw new RuntimeException(e);
        }

        AbstractInsnNode[] instructions = methodNode.instructions.toArray();
        Frame<SourceValue>[] frames = analyzer.getFrames();

        stringAnalyzer = new ConstantStringAnalyzer(instructions, frames);
        booleanAnalyzer = new ConstantBooleanAnalyzer(instructions, frames);

        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof MethodInsnNode methodCall) {
                Consumer<CallContext> handler = reflectiveCallHandlers.get(encodeMethodCall(methodCall));
                if (handler != null) {
                    CallContext cc = new CallContext(methodCall, frames[i], methodNode, contextClassNode);
                    handler.accept(cc);
                }
            }
        }
    }

    private record CallContext(MethodInsnNode methodCall, Frame<SourceValue> frame, MethodNode contextMethodNode, ClassNode contextClassNode) {

    }

    private static SourceValue getCallArg(Frame<SourceValue> frame, int argIdx) {
        int numOfArgs = frame.getStackSize();
        int stackPos = frame.getStackSize() - numOfArgs + argIdx;
        return frame.getStack(stackPos);
    }

    private static void partiallyEvaluateClassForNameOne(CallContext cc) {
        Optional<String> className = stringAnalyzer.inferConstant(getCallArg(cc.frame, 0));
        if (className.isEmpty()) {
            return;
        }

        MethodNode partiallyEvaluatedMethod = generatePEMethodNode(cc.methodCall, "()Ljava/lang/Class;");

        partiallyEvaluatedMethod.instructions.add(new LdcInsnNode(className.get()));
        partiallyEvaluatedMethod.instructions.add(new MethodInsnNode(cc.methodCall.getOpcode(), cc.methodCall.owner, cc.methodCall.name, cc.methodCall.desc));
        partiallyEvaluatedMethod.instructions.add(new InsnNode(Opcodes.ARETURN));
        partiallyEvaluatedMethod.maxStack = 1;
        partiallyEvaluatedMethod.maxLocals = 0;

        redirectCall(cc.methodCall, partiallyEvaluatedMethod, cc.contextClassNode);
    }

    private static void partiallyEvaluateClassForNameTwo(CallContext cc) {
        Optional<String> className = stringAnalyzer.inferConstant(getCallArg(cc.frame, 0));
        Optional<Boolean> initialize = booleanAnalyzer.inferConstant(getCallArg(cc.frame, 1));
        if (className.isEmpty() || initialize.isEmpty()) {
            return;
        }

        MethodNode partiallyEvaluatedMethod = generatePEMethodNode(cc.methodCall, "(Ljava/lang/ClassLoader;)Ljava/lang/Class;");

        partiallyEvaluatedMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        partiallyEvaluatedMethod.instructions.add(new InsnNode(initialize.get() ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        partiallyEvaluatedMethod.instructions.add(new LdcInsnNode(className.get()));
        partiallyEvaluatedMethod.instructions.add(new MethodInsnNode(cc.methodCall.getOpcode(), cc.methodCall.owner, cc.methodCall.name, cc.methodCall.desc));
        partiallyEvaluatedMethod.instructions.add(new InsnNode(Opcodes.ARETURN));
        partiallyEvaluatedMethod.maxStack = 3;
        partiallyEvaluatedMethod.maxLocals = 1;

        redirectCall(cc.methodCall, partiallyEvaluatedMethod, cc.contextClassNode);
    }

    private static MethodNode generatePEMethodNode(MethodInsnNode methodCall, String newDesc) {
        int currentCount = partialEvaluationCounts.getOrDefault(methodCall.name, 0);
        String newName = "$" + methodCall.name + currentCount;
        partialEvaluationCounts.put(methodCall.name, currentCount + 1);
        return new MethodNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC, newName, newDesc, null, null);
    }

    private static void redirectCall(MethodInsnNode methodCall, MethodNode target, ClassNode contextClassNode) {
        contextClassNode.methods.add(target);

        int originalParameterCount = Type.getArgumentTypes(methodCall.desc).length + (methodCall.getOpcode() != Opcodes.INVOKESTATIC ? 1 : 0);
        int newParameterCount = Type.getArgumentTypes(target.desc).length;

        for (int i = 0; i < originalParameterCount - newParameterCount; i++) {
            target.instructions.insertBefore(methodCall, new InsnNode(Opcodes.POP));
        }

        methodCall.owner = contextClassNode.name;
        methodCall.name = target.name;
        methodCall.desc = target.desc;
    }

    private static String encodeMethodCall(MethodInsnNode methodCall) {
        return encodeMethodCall(methodCall.owner, methodCall.name, methodCall.desc);
    }

    private static String encodeMethodCall(String owner, String name, String desc) {
        return owner + "." + name + ":" + desc;
    }
}
