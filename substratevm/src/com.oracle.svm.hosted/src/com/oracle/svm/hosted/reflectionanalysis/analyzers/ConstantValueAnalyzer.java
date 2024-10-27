package com.oracle.svm.hosted.reflectionanalysis.analyzers;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.util.Arrays;
import java.util.Optional;

import static jdk.internal.org.objectweb.asm.Opcodes.ALOAD;
import static jdk.internal.org.objectweb.asm.Opcodes.ASTORE;

public abstract class ConstantValueAnalyzer<T> {
    private final AbstractInsnNode[] instructions;
    private final Frame<SourceValue>[] frames;

    public ConstantValueAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames) {
        this.instructions = instructions;
        this.frames = frames;
    }

    public Optional<T> inferConstant(SourceValue value) {
        // TODO: Use memoization mapping for value -> isConstant to avoid multiple calculations for the same value.

        if (value.insns.size() != 1) {
            return Optional.empty();
        }

        AbstractInsnNode sourceInstruction = value.insns.iterator().next();
        int sourceInstructionIndex = Arrays.asList(instructions).indexOf(sourceInstruction);
        Frame<SourceValue> sourceInstructionFrame = frames[sourceInstructionIndex];

        if (sourceInstruction.getOpcode() == ALOAD) {
            SourceValue sourceValue = sourceInstructionFrame.getLocal(((VarInsnNode) sourceInstruction).var);
            return inferConstant(sourceValue);
        } else if (sourceInstruction.getOpcode() == ASTORE) {
            SourceValue sourceValue = sourceInstructionFrame.getStack(sourceInstructionFrame.getStackSize() - 1);
            return inferConstant(sourceValue);
        }

        return inferConstant(value, sourceInstruction, sourceInstructionFrame);
    }

    protected abstract Optional<T> inferConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame);
}
