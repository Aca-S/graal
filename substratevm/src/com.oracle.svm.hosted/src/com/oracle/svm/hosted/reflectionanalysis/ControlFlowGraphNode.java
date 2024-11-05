package com.oracle.svm.hosted.reflectionanalysis;

import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.Value;

import java.util.HashSet;
import java.util.Set;

public class ControlFlowGraphNode<V extends Value> extends Frame<V> {
    public Set<Integer> successors = new HashSet<>();
    public Set<Integer> predecessors = new HashSet<>();

    public ControlFlowGraphNode(int numLocals, int maxStack) {
        super(numLocals, maxStack);
    }

    public ControlFlowGraphNode(Frame<? extends V> frame) {
        super(frame);
    }
}
