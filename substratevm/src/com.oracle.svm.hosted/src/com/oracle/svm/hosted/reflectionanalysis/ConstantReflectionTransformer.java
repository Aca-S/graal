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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ConstantReflectionTransformer implements ClassFileTransformer {

    public static ConstantReflectionRegistry callRegistry = new ConstantReflectionRegistry();

    private static final Map<String, Function<Frame<SourceValue>, List<Object>>> reflectiveCallHandlers = new HashMap<>() {
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

        Map<MethodNode, List<InferredCall>> inferredCalls = analyzeClass(classNode);

        // Force label BCI resolution
        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);

        for (Map.Entry<MethodNode, List<InferredCall>> entry : inferredCalls.entrySet()) {
            for (InferredCall call : entry.getValue()) {
                callRegistry.add(classNode, entry.getKey(), call.label.getLabel().getOffset(), call.arguments);
            }
        }

        // Return the original bytes - the only transformation we're doing is inserting tracking labels in order to calculate BCIs
        return classFileBuffer;
    }

    private static Map<MethodNode, List<InferredCall>> analyzeClass(ClassNode classNode) {
        Map<MethodNode, List<InferredCall>> inferredCalls = new HashMap<>();
        for (MethodNode method : classNode.methods) {
            List<InferredCall> inferredCallLabelsInMethod = analyzeMethod(method, classNode);
            if (!inferredCallLabelsInMethod.isEmpty()) {
                inferredCalls.put(method, inferredCallLabelsInMethod);
            }
        }
        return inferredCalls;
    }

    private static List<InferredCall> analyzeMethod(MethodNode methodNode, ClassNode contextClassNode) {
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

        List<InferredCall> inferredCalls = new ArrayList<>();

        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof MethodInsnNode methodCall) {
                Function<Frame<SourceValue>, List<Object>> handler = reflectiveCallHandlers.get(encodeMethodCall(methodCall));
                if (handler == null) {
                    continue;
                }
                List<Object> callArguments = handler.apply(frames[i]);
                if (callArguments == null) {
                    continue;
                }
                LabelNode label = new LabelNode();
                methodNode.instructions.insertBefore(instructions[i], label);
                inferredCalls.add(new InferredCall(label, callArguments));
            }
        }

        return inferredCalls;
    }

    private static List<Object> canInferClassForNameOne(Frame<SourceValue> frame) {
        Optional<String> className = stringAnalyzer.inferConstant(getCallArg(frame, 0));
        return inferArguments(className);
    }

    private static List<Object> canInferClassForNameTwo(Frame<SourceValue> frame) {
        Optional<String> className = stringAnalyzer.inferConstant(getCallArg(frame, 0));
        Optional<Boolean> initialize = booleanAnalyzer.inferConstant(getCallArg(frame, 1));
        return inferArguments(className, initialize);
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    private static List<Object> inferArguments(Optional<?>... arguments) {
        if (Arrays.stream(arguments).anyMatch(Optional::isEmpty)) {
            return null;
        }
        return Arrays.stream(arguments).map(Optional::get).collect(Collectors.toUnmodifiableList());
    }

    private static SourceValue getCallArg(Frame<SourceValue> frame, int argIdx) {
        int numOfArgs = frame.getStackSize();
        int stackPos = frame.getStackSize() - numOfArgs + argIdx;
        return frame.getStack(stackPos);
    }

    private record InferredCall(LabelNode label, List<Object> arguments) {

    }

    private static String encodeMethodCall(MethodInsnNode methodCall) {
        return encodeMethodCall(methodCall.owner, methodCall.name, methodCall.desc);
    }

    private static String encodeMethodCall(String owner, String name, String desc) {
        return owner + ":" + name + ":" + desc;
    }
}
