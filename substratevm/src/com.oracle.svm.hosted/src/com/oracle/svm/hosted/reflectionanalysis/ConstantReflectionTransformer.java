package com.oracle.svm.hosted.reflectionanalysis;

import com.oracle.svm.hosted.reflectionanalysis.analyzers.ConstantBooleanAnalyzer;
import com.oracle.svm.hosted.reflectionanalysis.analyzers.ConstantStringAnalyzer;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.LabelNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
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
import java.util.function.Predicate;

public class ConstantReflectionTransformer implements ClassFileTransformer {

    public static ConstantReflectionRegistry callRegistry = new ConstantReflectionRegistry();

    private static final Map<String, Predicate<Frame<SourceValue>>> reflectiveCallHandlers = new HashMap<>() {
        {
            put(encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"), ConstantReflectionTransformer::canInferClassForNameOne);
            put(encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"), ConstantReflectionTransformer::canInferClassForNameTwo);
        }
    };

    private static ConstantStringAnalyzer stringAnalyzer;
    private static ConstantBooleanAnalyzer booleanAnalyzer;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classFileBuffer) {
        ClassNode classNode = new ClassNode();

        ClassReader reader = new ClassReader(classFileBuffer);
        reader.accept(classNode, 0);

        Map<MethodNode, List<LabelNode>> inferredCallLabels = analyzeClass(classNode);

        // Force label BCI resolution
        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);

        for (Map.Entry<MethodNode, List<LabelNode>> entry : inferredCallLabels.entrySet()) {
            for (LabelNode labelNode : entry.getValue()) {
                callRegistry.add(classNode, entry.getKey(), labelNode.getLabel().getOffset());
            }
        }

        return writer.toByteArray();
    }

    private static Map<MethodNode, List<LabelNode>> analyzeClass(ClassNode classNode) {
        Map<MethodNode, List<LabelNode>> inferredCallLabels = new HashMap<>();
        for (MethodNode method : classNode.methods) {
            List<LabelNode> inferredCallLabelsInMethod = analyzeMethod(method, classNode);
            if (!inferredCallLabelsInMethod.isEmpty()) {
                inferredCallLabels.put(method, inferredCallLabelsInMethod);
            }
        }
        return inferredCallLabels;
    }

    private static List<LabelNode> analyzeMethod(MethodNode methodNode, ClassNode contextClassNode) {
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

        List<LabelNode> inferredCallLabels = new ArrayList<>();

        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof MethodInsnNode methodCall) {
                Predicate<Frame<SourceValue>> handler = reflectiveCallHandlers.get(encodeMethodCall(methodCall));
                if (handler != null && handler.test(frames[i])) {
                    LabelNode label = new LabelNode();
                    methodNode.instructions.insertBefore(instructions[i], label);
                    inferredCallLabels.add(label);
                }
            }
        }

        return inferredCallLabels;
    }

    private static boolean canInferClassForNameOne(Frame<SourceValue> frame) {
        Optional<String> className = stringAnalyzer.inferConstant(getCallArg(frame, 0));
        return className.isPresent();
    }

    private static boolean canInferClassForNameTwo(Frame<SourceValue> frame) {
        Optional<String> className = stringAnalyzer.inferConstant(getCallArg(frame, 0));
        Optional<Boolean> initialize = booleanAnalyzer.inferConstant(getCallArg(frame, 1));
        return className.isPresent() && initialize.isPresent();
    }

    private static SourceValue getCallArg(Frame<SourceValue> frame, int argIdx) {
        int numOfArgs = frame.getStackSize();
        int stackPos = frame.getStackSize() - numOfArgs + argIdx;
        return frame.getStack(stackPos);
    }

    private static String encodeMethodCall(MethodInsnNode methodCall) {
        return encodeMethodCall(methodCall.owner, methodCall.name, methodCall.desc);
    }

    private static String encodeMethodCall(String owner, String name, String desc) {
        return owner + ":" + name + ":" + desc;
    }
}
