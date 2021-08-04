/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.tools.insight.test.heap;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Instrument;
import org.graalvm.polyglot.Value;
import org.graalvm.tools.insight.test.InsightObjectFactory;
import org.graalvm.tools.insight.test.heap.HeapApi.At;
import org.graalvm.tools.insight.test.heap.HeapApi.Event;
import org.graalvm.tools.insight.test.heap.HeapApi.Source;
import org.graalvm.tools.insight.test.heap.HeapApi.StackElement;
import org.graalvm.tools.insight.test.heap.HeapApi.StackEvent;
import static org.graalvm.tools.insight.test.heap.HeapApi.invokeDump;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Test;

public class HeapObjectStreamTest {

    private Value heap;
    private ByteArrayOutputStream out;

    @Before
    public void prepareHeap() throws Exception {
        Context.Builder b = Context.newBuilder();
        Context ctx = InsightObjectFactory.newContext(b);

        Map<String, Instrument> instruments = ctx.getEngine().getInstruments();
        Instrument heapInstrument = instruments.get("heap");
        assertNotNull("Instrument was found", heapInstrument);

        @SuppressWarnings("unchecked")
        Consumer<OutputStream> osSetup = heapInstrument.lookup(Consumer.class);
        assertNotNull("Consumer for OutputStream", osSetup);

        osSetup.accept(out = new ByteArrayOutputStream());

        heap = InsightObjectFactory.readObject(ctx, "heap");
        assertFalse("Heap object is defined", heap.isNull());
    }

    @Test
    public void cannotSetNullStream() {
        @SuppressWarnings("unchecked")
        Consumer<OutputStream> osSetup = heap.getContext().getEngine().getInstruments().get("heap").lookup(Consumer.class);
        assertNotNull("Consumer for OutputStream", osSetup);

        try {
            osSetup.accept(null);
            fail("Stream cannot be null!");
        } catch (NullPointerException ex) {
            // OK
        }
    }

    @Test
    public void cannotSetStreamMultipleTimes() {
        @SuppressWarnings("unchecked")
        Consumer<OutputStream> osSetup = heap.getContext().getEngine().getInstruments().get("heap").lookup(Consumer.class);
        assertNotNull("Consumer for OutputStream", osSetup);

        try {
            osSetup.accept(new ByteArrayOutputStream());
            fail("Stream cannot be set twice!");
        } catch (IllegalStateException ex) {
            assertNotEquals("Right message found", -1, ex.getMessage().indexOf("Cannot set stream twice"));
        }
    }

    @Test
    public void dumpToStream() throws Exception {
        Source nullSource = new Source(null, null, null, null, null);
        invokeDump(heap, 1, new Event[]{
                        new StackEvent(new StackElement[]{new StackElement(new At(null, nullSource, 1, 0, 5), new HashMap<>())})
        });

        Source source = new Source("a.text", "application/x-test", "test", "file://a.test", "aaaaa");
        invokeDump(heap, 1, new Event[]{
                        new StackEvent(new StackElement[]{new StackElement(new At("a", source, null, null, null), new HashMap<>())})
        });

        String header = new String(out.toByteArray(), 0, 18);
        assertEquals("JAVA PROFILE 1.0.1", header);

        if (out.size() < 3000) {
            fail("Heap dump should be generated to the stream: " + out.size());
        }

    }
}
