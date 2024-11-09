package com.oracle.svm.hosted.reflectionanalysis.analyzers;

import com.oracle.svm.hosted.reflectionanalysis.Utils;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.util.Optional;

import static jdk.internal.org.objectweb.asm.Opcodes.GETSTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKESTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.LDC;

public class ConstantClassAnalyzer extends ConstantValueAnalyzer<Class<?>> {
    private final ClassLoader classLoader;
    private final ConstantStringAnalyzer stringAnalyzer;
    private final ConstantBooleanAnalyzer booleanAnalyzer;

    private final static String FOR_NAME_SIGNATURE = Utils.encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
    private final static String FOR_NAME_WITH_INIT_SIGNATURE = Utils.encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");

    public ConstantClassAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames, ClassLoader classLoader) {
        super(instructions, frames);
        this.classLoader = classLoader;
        this.stringAnalyzer = new ConstantStringAnalyzer(instructions, frames);
        this.booleanAnalyzer = new ConstantBooleanAnalyzer(instructions, frames);
    }

    @Override
    protected Optional<Class<?>> inferConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame) {
        return switch (sourceInstruction.getOpcode()) {
            case LDC -> {
                LdcInsnNode ldc = (LdcInsnNode) sourceInstruction;
                if (ldc.cst instanceof Type type && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) {
                    String className = type.getInternalName().replace('/', '.');
                    yield inferClassLoad(className);
                } else {
                    yield Optional.empty();
                }
            }
            case GETSTATIC -> {
                FieldInsnNode field = (FieldInsnNode) sourceInstruction;
                yield inferPrimitiveTypeClass(field);
            }
            case INVOKESTATIC -> {
                MethodInsnNode methodCall = (MethodInsnNode) sourceInstruction;
                if (Utils.encodeMethodCall(methodCall).equals(FOR_NAME_SIGNATURE)) {
                    yield forNameHandler(sourceInstructionFrame);
                } else if (Utils.encodeMethodCall(methodCall).equals(FOR_NAME_WITH_INIT_SIGNATURE)) {
                    yield forNameWithInitHandler(sourceInstructionFrame);
                } else {
                    yield Optional.empty();
                }
            }
            default -> Optional.empty();
        };
    }

    private Optional<Class<?>> inferPrimitiveTypeClass(FieldInsnNode field) {
        if (!field.name.equals("TYPE")) {
            return Optional.empty();
        }

        Class<?> clazz = switch (field.owner) {
            case "java/lang/Byte" -> byte.class;
            case "java/lang/Char" -> char.class;
            case "java/lang/Short" -> short.class;
            case "java/lang/Integer" -> int.class;
            case "java/lang/Long" -> long.class;
            case "java/lang/Float" -> float.class;
            case "java/lang/Double" -> double.class;
            default -> null;
        };

        return clazz != null ? Optional.of(clazz) : Optional.empty();
    }

    private Optional<Class<?>> inferClassLoad(String className) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            return Optional.of(clazz);
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    private Optional<Class<?>> forNameHandler(Frame<SourceValue> frame) {
        Optional<String> className = stringAnalyzer.inferConstant(Utils.getCallArg(frame, 0));
        return className.flatMap(this::inferClassLoad);
    }

    private Optional<Class<?>> forNameWithInitHandler(Frame<SourceValue> frame) {
        Optional<String> className = stringAnalyzer.inferConstant(Utils.getCallArg(frame, 0));
        Optional<Boolean> initialize = booleanAnalyzer.inferConstant(Utils.getCallArg(frame, 1));

        if (className.isEmpty() || initialize.isEmpty()) {
            return Optional.empty();
        }

        return inferClassLoad(className.get());
    }
}
