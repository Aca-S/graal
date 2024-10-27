package com.oracle.svm.hosted.reflectionanalysis.analyzers;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.util.Optional;

public class ConstantStringAnalyzer extends ConstantValueAnalyzer<String> {
    public ConstantStringAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames) {
        super(instructions, frames);
    }

    @Override
    protected Optional<String> inferConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame) {
        if (sourceInstruction instanceof LdcInsnNode ldc && ldc.cst instanceof String str) {
            return Optional.of(str);
        } else {
            return Optional.empty();
        }
    }
}

