package com.oracle.svm.hosted.reflectionanalysis;

import com.oracle.svm.hosted.reflectionanalysis.analyzers.ConstantArrayAnalyzer;
import com.oracle.svm.hosted.reflectionanalysis.analyzers.ConstantBooleanAnalyzer;
import com.oracle.svm.hosted.reflectionanalysis.analyzers.ConstantClassAnalyzer;
import com.oracle.svm.hosted.reflectionanalysis.analyzers.ConstantStringAnalyzer;
import com.oracle.svm.hosted.reflectionanalysis.analyzers.ControlFlowGraphAnalyzer;
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
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class ConstantReflectionTransformer implements ClassFileTransformer {

    public static ConstantReflectionRegistry callRegistry = new ConstantReflectionRegistry();

    private static final Map<String, BiFunction<AnalyzerSuite, CallContext, List<Object>>> reflectiveCallHandlers = new HashMap<>() {
        {
            put(Utils.encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"), ConstantReflectionTransformer::canInferClassForNameOne);
            put(Utils.encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"), ConstantReflectionTransformer::canInferClassForNameTwo);
            put(Utils.encodeMethodCall("java/lang/Class", "getField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"), ConstantReflectionTransformer::canInferField);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"), ConstantReflectionTransformer::canInferField);
            put(Utils.encodeMethodCall("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"), ConstantReflectionTransformer::canInferMethod);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"), ConstantReflectionTransformer::canInferMethod);
            put(Utils.encodeMethodCall("java/lang/Class", "getConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"), ConstantReflectionTransformer::canInferConstructor);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"), ConstantReflectionTransformer::canInferConstructor);
        }
    };

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classFileBuffer) {
        ClassNode classNode = new ClassNode();

        ClassReader reader = new ClassReader(classFileBuffer);
        reader.accept(classNode, 0);

        Map<MethodNode, List<InferredCall>> inferredCalls = analyzeClass(classNode, loader);

        // Force label BCI resolution.
        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);

        for (Map.Entry<MethodNode, List<InferredCall>> entry : inferredCalls.entrySet()) {
            for (InferredCall call : entry.getValue()) {
                callRegistry.add(classNode, entry.getKey(), call.label.getLabel().getOffset(), call.arguments);
            }
        }

        return writer.toByteArray();
    }

    private static Map<MethodNode, List<InferredCall>> analyzeClass(ClassNode classNode, ClassLoader loader) {
        Map<MethodNode, List<InferredCall>> inferredCalls = new HashMap<>();
        for (MethodNode method : classNode.methods) {
            List<InferredCall> inferredCallLabelsInMethod = analyzeMethod(method, classNode, loader);
            if (!inferredCallLabelsInMethod.isEmpty()) {
                inferredCalls.put(method, inferredCallLabelsInMethod);
            }
        }
        return inferredCalls;
    }

    private static List<InferredCall> analyzeMethod(MethodNode methodNode, ClassNode contextClassNode, ClassLoader loader) {
        Analyzer<SourceValue> analyzer = new ControlFlowGraphAnalyzer<>(new SourceInterpreter());
        try {
            analyzer.analyze(contextClassNode.name, methodNode);
        } catch (AnalyzerException e) {
            throw new RuntimeException(e);
        }

        AbstractInsnNode[] instructions = methodNode.instructions.toArray();

        @SuppressWarnings("unchecked")
        ControlFlowGraphNode<SourceValue>[] frames = Arrays.stream(analyzer.getFrames())
                .map(frame -> (ControlFlowGraphNode<SourceValue>) frame)
                .toArray(ControlFlowGraphNode[]::new);

        AnalyzerSuite analyzerSuite = new AnalyzerSuite(
                new ConstantStringAnalyzer(instructions, frames),
                new ConstantBooleanAnalyzer(instructions, frames),
                new ConstantClassAnalyzer(instructions, frames, loader),
                new ConstantArrayAnalyzer<>(instructions, frames, new ConstantClassAnalyzer(instructions, frames, loader))
        );

        List<InferredCall> inferredCalls = new ArrayList<>();

        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof MethodInsnNode methodCall) {
                BiFunction<AnalyzerSuite, CallContext, List<Object>> handler = reflectiveCallHandlers.get(Utils.encodeMethodCall(methodCall));
                if (handler == null) {
                    continue;
                }
                List<Object> callArguments = handler.apply(analyzerSuite, new CallContext(frames[i], methodCall));
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

    private static List<Object> canInferClassForNameOne(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<String> className = analyzerSuite.stringAnalyzer.inferConstant(getCallArg(callContext, 0));
        return inferArguments(className);
    }

    private static List<Object> canInferClassForNameTwo(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<String> className = analyzerSuite.stringAnalyzer.inferConstant(getCallArg(callContext, 0));
        Optional<Boolean> initialize = analyzerSuite.booleanAnalyzer.inferConstant(getCallArg(callContext, 1));
        return inferArguments(className, initialize);
    }

    private static List<Object> canInferField(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<Class<?>> clazz = analyzerSuite.classAnalyzer.inferConstant(getCallArg(callContext, 0));
        Optional<String> fieldName = analyzerSuite.stringAnalyzer.inferConstant(getCallArg(callContext, 1));
        return inferArguments(clazz, fieldName);
    }

    private static List<Object> canInferMethod(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<Class<?>> clazz = analyzerSuite.classAnalyzer.inferConstant(getCallArg(callContext, 0));
        Optional<String> methodName = analyzerSuite.stringAnalyzer.inferConstant(getCallArg(callContext, 1));
        Optional<ArrayList<Class<?>>> parameterTypes = analyzerSuite.classArrayAnalyzer.inferConstant(getCallArg(callContext, 2), callContext.callSite);
        return inferArguments(clazz, methodName, parameterTypes);
    }

    private static List<Object> canInferConstructor(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<Class<?>> clazz = analyzerSuite.classAnalyzer.inferConstant(getCallArg(callContext, 0));
        Optional<ArrayList<Class<?>>> parameterTypes = analyzerSuite.classArrayAnalyzer.inferConstant(getCallArg(callContext, 1), callContext.callSite);
        return inferArguments(clazz, parameterTypes);
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    private static List<Object> inferArguments(Optional<?>... arguments) {
        if (Arrays.stream(arguments).anyMatch(Optional::isEmpty)) {
            return null;
        }
        return Arrays.stream(arguments).map(Optional::get).collect(Collectors.toUnmodifiableList());
    }

    private record InferredCall(LabelNode label, List<Object> arguments) {

    }

    private record AnalyzerSuite(ConstantStringAnalyzer stringAnalyzer, ConstantBooleanAnalyzer booleanAnalyzer,
                                 ConstantClassAnalyzer classAnalyzer, ConstantArrayAnalyzer<Class<?>> classArrayAnalyzer) {

    }

    private record CallContext(Frame<SourceValue> frame, MethodInsnNode callSite) {

    }

    private static SourceValue getCallArg(CallContext callContext, int argumentIndex) {
        return Utils.getCallArg(callContext.callSite, argumentIndex, callContext.frame);
    }
}
