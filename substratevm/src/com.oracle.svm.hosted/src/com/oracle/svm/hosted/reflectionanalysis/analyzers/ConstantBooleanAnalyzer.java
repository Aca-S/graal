package com.oracle.svm.hosted.reflectionanalysis.analyzers;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.util.Optional;

import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_0;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_1;

public class ConstantBooleanAnalyzer extends ConstantValueAnalyzer<Boolean> {
    public ConstantBooleanAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames) {
        super(instructions, frames);
    }

    @Override
    protected Optional<Boolean> inferConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame) {
        if (sourceInstruction.getOpcode() == ICONST_0) {
            return Optional.of(false);
        } else if (sourceInstruction.getOpcode() == ICONST_1) {
            return Optional.of(true);
        } else {
            return Optional.empty();
        }
    }
}
