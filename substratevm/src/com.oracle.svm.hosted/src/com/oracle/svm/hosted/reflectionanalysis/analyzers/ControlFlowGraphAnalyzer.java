package com.oracle.svm.hosted.reflectionanalysis.analyzers;

import com.oracle.svm.hosted.reflectionanalysis.ControlFlowGraphNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.Interpreter;
import jdk.internal.org.objectweb.asm.tree.analysis.Value;

public class ControlFlowGraphAnalyzer<V extends Value> extends Analyzer<V> {
    public ControlFlowGraphAnalyzer(Interpreter<V> interpreter) {
        super(interpreter);
    }

    @Override
    protected Frame<V> newFrame(int numLocals, int numStack) {
        return new ControlFlowGraphNode<>(numLocals, numStack);
    }

    @Override
    protected Frame<V> newFrame(Frame<? extends V> frame) {
        return new ControlFlowGraphNode<>(frame);
    }

    @Override
    protected void newControlFlowEdge(int instructionIndex, int successorIndex) {
        ControlFlowGraphNode<V> source = (ControlFlowGraphNode<V>) getFrames()[instructionIndex];
        ControlFlowGraphNode<V> destination = (ControlFlowGraphNode<V>) getFrames()[successorIndex];
        source.successors.add(successorIndex);
        destination.predecessors.add(instructionIndex);
    }
}
